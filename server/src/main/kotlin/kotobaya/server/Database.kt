package kotobaya.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import javax.sql.DataSource

/** Hikari pool plus a jOOQ [DSLContext] (plain SQL; no generated classes in the skeleton). */
class Database(val dataSource: HikariDataSource) : AutoCloseable {
    private val dsl: DSLContext = DSL.using(dataSource, SQLDialect.POSTGRES)

    /** Run [block] in one transaction on the IO dispatcher. */
    suspend fun <T> tx(block: (DSLContext) -> T): T = withContext(Dispatchers.IO) { txBlocking(block) }

    fun <T> txBlocking(block: (DSLContext) -> T): T = dsl.transactionResult { cfg -> block(DSL.using(cfg)) }

    /** True when `select 1` succeeds. */
    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        runCatching { dsl.fetchValue("select 1") == 1 }.getOrDefault(false)
    }

    override fun close() = dataSource.close()

    companion object {
        fun connect(jdbcUrl: String, connectionTimeoutMs: Long = 3_000): Database {
            // Times are UTC in storage (CLAUDE.md rule 9); also avoids pgjdbc sending legacy zone ids Postgres rejects.
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
            val cfg = HikariConfig().apply {
                this.jdbcUrl = jdbcUrl
                maximumPoolSize = 10
                this.connectionTimeout = connectionTimeoutMs
                // Do not fail pool creation when Postgres is not up yet; /readyz reports it.
                initializationFailTimeout = -1
                poolName = "kotobaya"
            }
            return Database(HikariDataSource(cfg))
        }
    }
}

object Migrations {
    /** Applies `contracts/db/V1__init.sql` (copied onto the classpath at build time, never forked). Throws on failure. */
    fun migrate(dataSource: DataSource): Int =
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .placeholderReplacement(false)
            .load()
            .migrate()
            .migrationsExecuted
}
