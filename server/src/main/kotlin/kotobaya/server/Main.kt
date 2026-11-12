package kotobaya.server

import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotobaya.auth.Auth
import kotobaya.auth.AuthConfig
import kotobaya.auth.AuthEvents
import kotobaya.auth.TokenValidator
import java.util.UUID
import kotobaya.cards.CardsModule
import kotobaya.dict.dictHandlers
import kotobaya.review.ReviewModule
import kotobaya.decks.DecksModule
import kotobaya.imports.ImportsModule
import kotobaya.jobs.JobsModule
import kotobaya.notes.NotesModule
import kotobaya.settings.SettingsModule
import kotobaya.sync.SyncEvents
import kotobaya.sync.SyncModule
import kotobaya.sync.restore.SyncWiring
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

/**
 * `server` / `java -jar server-all.jar`: run the API.
 * `server migrate`: apply `contracts/db/V1__init.sql` via Flyway and exit (non-zero on failure); T111's Helm hook uses this.
 */
fun main(args: Array<String>) {
    val log = LoggerFactory.getLogger("kotobaya.main")
    if (args.firstOrNull() == "migrate") exitProcess(migrateCommand())

    val config = try {
        Config.fromEnv()
    } catch (e: ConfigException) {
        log.error("configuration error: {}", e.message)
        exitProcess(2)
    }
    val db = Database.connect(config.databaseUrl)
    if (config.migrateOnStart) {
        val n = Migrations.migrate(db.dataSource)
        log.info("migrations applied: {}", n)
    }
    // Fail fast: no AUTH_PASSWORD_HASH means no way to log in, and we never fall back to AllowAll.
    val authConfig = try {
        AuthConfig.fromEnv()
    } catch (e: ConfigException) {
        log.error("configuration error: {}", e.message)
        exitProcess(2)
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val app = composeApp(db, config.databaseUrl, authConfig, bucketInit = S3BucketInit(config))
    app.jobs.start(scope)
    app.imports.start(scope)
    Runtime.getRuntime().addShutdownHook(Thread { app.jobs.close(); scope.cancel() })
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") { kotobayaModule(app.deps) }.start(wait = true)
}

/** The composed application: [deps] for `kotobayaModule`, and [jobs], and [imports], which the caller must `start(scope)`. */
class ComposedApp(val deps: AppDeps, val jobs: JobsModule, val imports: ImportsModule)

/**
 * The one place the modules meet: auth (login, token authenticator), dictionary and jobs/events handlers.
 * `Auth.handlers` is taken whole, so handlers other tickets add there (devices, pairing, tokens) come along.
 *
 * Jobs is built first so auth can publish `device.*` events on its bus and close revoked tokens' `/events` streams
 * on its hub; every open stream also re-checks its credential every [eventsGuardMs] through the shared validator
 * (revoked, expired, or its device revoked: the stream ends).
 */
fun composeApp(
    db: Database,
    databaseUrl: String,
    authConfig: AuthConfig,
    startup: StartupSequence? = SyncWiring.startup(db),
    bucketInit: BucketInit = BucketInit.Disabled,
    eventsGuardMs: Long = 5_000,
): ComposedApp {
    val validator = TokenValidator(db)
    val jobs = JobsModule(
        db, databaseUrl,
        credentialValid = { token -> token?.id?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let { validator.isLive(it) } ?: false },
        guardIntervalMs = eventsGuardMs,
    )
    val auth = Auth(db, authConfig, validator = validator, events = AuthEvents.of(jobs.bus, jobs.hub))
    val sync = SyncModule(db, SyncWiring.config(events = SyncEvents { dsl, hw, _ -> jobs.bus.syncChangedInTx(dsl, hw) }))
    // One OpApply for every writer: they share its advisory lock, HLC and card cache.
    val imports = ImportsModule(db, sync.opApply)
    val handlers = auth.handlers + dictHandlers(db) + jobs.handlers + sync.handlers +
        NotesModule(db, sync.opApply).handlers + DecksModule(db, sync.opApply).handlers + SettingsModule(db, sync.opApply).handlers +
        ReviewModule(db, sync.opApply).handlers + CardsModule(db, sync.opApply).handlers + imports.handlers
    val deps = AppDeps(
        db = db,
        authenticator = auth.authenticator,
        handlers = handlers,
        startup = startup,
        bucketInit = bucketInit,
    )
    return ComposedApp(deps, jobs, imports)
}

fun migrateCommand(env: Map<String, String> = System.getenv()): Int {
    val log = LoggerFactory.getLogger("kotobaya.migrate")
    return try {
        Database.connect(Config.databaseUrlFromEnv(env), connectionTimeoutMs = 30_000).use { db ->
            val n = Migrations.migrate(db.dataSource)
            log.info("migrate ok: {} migration(s) applied", n)
        }
        0
    } catch (e: Exception) {
        log.error("migrate failed", e)
        1
    }
}
