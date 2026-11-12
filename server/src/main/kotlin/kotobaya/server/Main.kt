package kotobaya.server

import io.ktor.server.engine.embeddedServer
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
    val deps = AppDeps(
        db = db,
        startup = StartupSequence(db),
        bucketInit = S3BucketInit(config),
    )
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") { kotobayaModule(deps) }.start(wait = true)
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
