package kotobaya.server

import org.testcontainers.containers.PostgreSQLContainer
import java.util.concurrent.atomic.AtomicInteger

/** One Postgres 17 container (C.UTF-8 ctype, as V1 requires) per test JVM; every test gets a fresh database. */
object TestPg {
    private val container: PostgreSQLContainer<*> by lazy {
        PostgreSQLContainer("postgres:17")
            .withEnv("POSTGRES_INITDB_ARGS", "--locale=C.UTF-8")
            .withTmpFs(mapOf("/var/lib/postgresql/data" to "rw"))
            .also { it.start() }
    }
    private val counter = AtomicInteger()

    /** JDBC URL of a new empty database (credentials in the query string, like the deployment's secret). */
    fun freshDatabaseUrl(): String {
        val name = "t${counter.incrementAndGet()}_${System.nanoTime()}"
        container.createConnection("").use { c -> c.createStatement().use { it.execute("create database $name") } }
        val base = container.jdbcUrl.substringBeforeLast('/')
        return "$base/$name?user=${container.username}&password=${container.password}"
    }

    /** A migrated database wrapped in a [Database]. */
    fun migratedDb(): Database {
        val db = Database.connect(freshDatabaseUrl())
        Migrations.migrate(db.dataSource)
        return db
    }

    /** Nothing listens here, so connections are refused at once. */
    fun deadDb(): Database = Database.connect("jdbc:postgresql://127.0.0.1:1/none?user=a&password=b", connectionTimeoutMs = 500)
}
