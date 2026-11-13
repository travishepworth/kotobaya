package kotobaya.android.sync

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import kotobaya.android.db.APP_DB_FILE
import kotobaya.db.app.AppDatabase

/**
 * Opening the app database so the outbox write path is durable (SYNC.md 4: a committed mint is never
 * lost): WAL journal and `synchronous=FULL` (each commit is fsynced, so neither a process kill nor a
 * power cut loses it).
 */
object DurableDriver {
    /**
     * The Android driver for [APP_DB_FILE] (same file and schema as `db/openAppDatabase`), WAL + FULL.
     * Creating it does no I/O (BUG-8: it is built while Koin starts, on the main thread): the file is
     * opened on its first statement, on whatever thread issues it ([OpenOnFirstUse]).
     */
    fun open(context: Context): SqlDriver = OpenOnFirstUse(create(context))

    /**
     * AndroidSqliteDriver opens (and creates/migrates the schema) lazily on first use; before the first
     * statement or transaction through this wrapper, one query opens it outside any caller transaction,
     * so a first-use `store.transaction` never meets a missing schema (F-6).
     */
    private class OpenOnFirstUse(private val delegate: SqlDriver) : SqlDriver by delegate {
        @Volatile
        private var opened = false

        private fun ensureOpen() {
            if (opened) return
            synchronized(this) {
                if (opened) return
                delegate.executeQuery(null, "SELECT count(*) FROM sqlite_master", { c -> c.next(); QueryResult.Value(c.getLong(0)) }, 0).value
                opened = true
            }
        }

        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> {
            ensureOpen()
            return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
        }

        override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> {
            ensureOpen()
            return delegate.execute(identifier, sql, parameters, binders)
        }

        override fun newTransaction(): QueryResult<Transacter.Transaction> {
            ensureOpen()
            return delegate.newTransaction()
        }
    }

    private fun create(context: Context): SqlDriver = AndroidSqliteDriver(
        schema = AppDatabase.Schema,
        context = context,
        name = APP_DB_FILE,
        callback = object : AndroidSqliteDriver.Callback(AppDatabase.Schema) {
            override fun onConfigure(db: SupportSQLiteDatabase) {
                super.onConfigure(db)
                db.enableWriteAheadLogging()
                // after WAL is enabled (enabling it resets the connection's sync mode to the platform default)
                db.execSQL("PRAGMA synchronous = FULL")
            }
        },
    )

    /** `PRAGMA synchronous` of [driver]'s connection: 2 = FULL. */
    fun synchronousLevel(driver: SqlDriver): Long =
        driver.executeQuery(null, "PRAGMA synchronous", { c -> c.next(); QueryResult.Value(c.getLong(0) ?: -1L) }, 0).value

    /** `PRAGMA journal_mode` of [driver], lowercase (e.g. `wal`). */
    fun journalMode(driver: SqlDriver): String =
        driver.executeQuery(null, "PRAGMA journal_mode", { c -> c.next(); QueryResult.Value(c.getString(0).orEmpty().lowercase()) }, 0).value
}
