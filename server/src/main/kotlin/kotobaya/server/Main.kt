package kotobaya.server

import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotobaya.auth.Auth
import kotobaya.auth.AuthConfig
import kotobaya.auth.AuthEvents
import kotobaya.auth.TokenValidator
import java.util.UUID
import kotobaya.blobs.BlobStore
import kotobaya.blobs.BlobsModule
import kotobaya.blobs.S3BlobStore
import kotobaya.cards.CardsModule
import kotobaya.dict.dictHandlers
import kotobaya.review.ReviewModule
import kotobaya.decks.DecksModule
import kotobaya.grammar.GrammarModule
import kotobaya.imports.ImportsModule
import kotobaya.jobs.JobsModule
import kotobaya.coverage.CoverageModule
import kotobaya.struggle.StruggleModule
import kotobaya.media.api.MediaApiModule
import kotobaya.confusables.ConfusableModule
import kotobaya.confusables.ConfusableSuggester
import kotobaya.media.publish.MediaPublishRunner
import kotobaya.parse.HttpParserClient
import kotobaya.parse.ParseModule
import kotobaya.parse.ParserClient
import kotobaya.sentences.SentencesModule
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
    val app = composeApp(db, config.databaseUrl, authConfig, bucketInit = S3BucketInit(config), blobStore = S3BlobStore(s3Client(config), config.s3Bucket),
        parser = HttpParserClient(config.parserUrl, config.parserTimeoutMs),
        ruleTester = kotobaya.grammar.HttpRuleTesterClient(config.parserUrl, config.parserTimeoutMs))
    app.jobs.start(scope)
    app.imports.start(scope)
    app.mediaPublish?.start(scope)
    app.confusables?.start(scope)
    kotobaya.push.startNtfySink(scope, app.jobs.hub, kotobaya.push.PushTopics.fromDb(db))
    Runtime.getRuntime().addShutdownHook(Thread { app.jobs.close(); scope.cancel() })
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") { kotobayaModule(app.deps) }.start(wait = false)
    if (kotobaya.grammar.GrammarSeeder.requestedByEnv()) {
        // Opt-in: idempotent (ids are derived), so leaving the flag set across restarts mints nothing new.
        val n = runCatching { runBlocking { app.seedGrammar() } }.onFailure { log.error("grammar seed failed", it) }.getOrNull()
        log.info("grammar seed: {} stubs created", n)
    }
    Thread.currentThread().join()
}

/**
 * The composed application: [deps] for `kotobayaModule`, and [jobs], [imports] and [mediaPublish] (null without a
 * blob store), which the caller must `start(scope)`.
 */
class ComposedApp(
    val deps: AppDeps,
    val jobs: JobsModule,
    val imports: ImportsModule,
    val mediaPublish: MediaPublishRunner? = null,
    val confusables: ConfusableSuggester? = null,
    /** Loads the grammar seed stubs (T507/F-20); main calls it once after startup when `GRAMMAR_SEED_ON_START` is set. */
    val seedGrammar: suspend () -> Int = { 0 },
)

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
    /** Backs putBlob/getBlob/queryBlobs; `main` always passes the S3 store (S3 config is required at startup). Null leaves them 501. */
    blobStore: BlobStore? = null,
    eventsGuardMs: Long = 5_000,
    /** Backs `parseText`; the default has no `PARSER_URL`, so it answers 503. */
    parser: ParserClient = HttpParserClient(null),
    /** Backs `testGrammarRules` and the rule check of grammar create/update (same `PARSER_URL`). */
    ruleTester: kotobaya.grammar.RuleTesterClient = kotobaya.grammar.HttpRuleTesterClient(null),
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
        NotesModule(db, sync.opApply).handlers + DecksModule(db, sync.opApply).handlers + GrammarModule(db, sync.opApply, ruleTester).handlers + SettingsModule(db, sync.opApply).handlers +
        ReviewModule(db, sync.opApply).handlers + CardsModule(db, sync.opApply).handlers + imports.handlers +
        MediaApiModule(db, sync.opApply).handlers +
        ParseModule(db, sync.opApply, parser).handlers + CoverageModule(db).handlers +
        kotobaya.tts.TtsModule(db).handlers + StruggleModule(db).handlers + ConfusableModule(db, sync.opApply).handlers +
        // listEntryExamples is provided by both dict (F-17, registered) and sentences (T408a): register it once.
        (SentencesModule(db).handlers - "listEntryExamples") +
        (blobStore?.let { BlobsModule(db, it).handlers } ?: emptyMap())
    val deps = AppDeps(
        db = db,
        authenticator = auth.authenticator,
        handlers = handlers,
        startup = startup,
        bucketInit = bucketInit,
    )
    return ComposedApp(deps, jobs, imports, blobStore?.let { MediaPublishRunner(db, sync.opApply, it) }, ConfusableSuggester(db, sync.opApply),
        seedGrammar = { kotobaya.grammar.GrammarSeeder.seed(sync.opApply) })
}

private fun s3Client(config: Config): io.minio.MinioClient = io.minio.MinioClient.builder()
    .endpoint(config.s3Endpoint)
    .credentials(config.s3AccessKey, config.s3SecretKey)
    .apply { config.s3Region?.let { region(it) } }
    .build()

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
