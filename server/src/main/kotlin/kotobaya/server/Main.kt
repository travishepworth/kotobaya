package kotobaya.server

import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotobaya.auth.Auth
import kotobaya.auth.AuthConfig
import kotobaya.dict.dictHandlers
import kotobaya.jobs.JobsModule
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
    val app = composeApp(db, config.databaseUrl, authConfig, startup = StartupSequence(db), bucketInit = S3BucketInit(config))
    app.jobs.start(scope)
    Runtime.getRuntime().addShutdownHook(Thread { app.jobs.close(); scope.cancel() })
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") { kotobayaModule(app.deps) }.start(wait = true)
}

/** The composed application: [deps] for `kotobayaModule`, and [jobs], which the caller must `start(scope)`. */
class ComposedApp(val deps: AppDeps, val jobs: JobsModule)

/**
 * The one place the modules meet: auth (login, token authenticator), dictionary and jobs/events handlers.
 * `Auth.handlers` is taken whole, so handlers other tickets add there (devices, pairing, tokens) come along.
 */
fun composeApp(
    db: Database,
    databaseUrl: String,
    authConfig: AuthConfig,
    startup: StartupSequence? = null,
    bucketInit: BucketInit = BucketInit.Disabled,
): ComposedApp {
    val auth = Auth(db, authConfig)
    val jobs = JobsModule(db, databaseUrl)
    val deps = AppDeps(
        db = db,
        authenticator = auth.authenticator,
        handlers = auth.handlers + dictHandlers(db) + jobs.handlers,
        startup = startup,
        bucketInit = bucketInit,
    )
    return ComposedApp(deps, jobs)
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
