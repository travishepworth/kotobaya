package kotobaya.android.db

import android.os.Looper
import android.util.Log
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import java.util.concurrent.CopyOnWriteArrayList

/**
 * BUG-8 (I-4, I-7): no database statement may run on the main thread. A sync chunk holds the write
 * connection for a while; a main-thread statement that needs it (or any disk read) freezes the UI and
 * ends in an ANR. StrictMode catches SQLite on a device (debug builds, [kotobaya.android.app.KotobayaApp]),
 * but not under Robolectric, so the app driver is wrapped in [GuardedDriver], which reports every
 * statement issued on the main thread here: logged with its stack, and kept in [violations] (the
 * first [MAX_KEPT]) for the tests that walk the screens.
 */
class DbThreadGuard(private val isMainThread: () -> Boolean = { Looper.getMainLooper().isCurrentThread }) {
    private val kept = CopyOnWriteArrayList<String>()

    /** Main-thread statements seen so far (SQL and the caller), oldest first. */
    val violations: List<String> get() = kept.toList()

    /** Forgets [violations]. */
    fun reset() = kept.clear()

    /** Reports [sql] if the current thread is the main thread. */
    fun check(sql: String) {
        if (!isMainThread()) return
        val where = Throwable("database access on the main thread: $sql")
        Log.w(TAG, where.message, where)
        if (kept.size < MAX_KEPT) kept += "$sql\n" + where.stackTrace.take(STACK_LINES).joinToString("\n") { "  at $it" }
    }

    companion object {
        const val MAX_KEPT: Int = 50
        private const val STACK_LINES = 25
        private const val TAG = "KotobayaDb"
    }
}

/** [delegate] with every statement and transaction start reported to [guard] ([DbThreadGuard]). */
class GuardedDriver(private val delegate: SqlDriver, private val guard: DbThreadGuard) : SqlDriver by delegate {
    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        guard.check(sql)
        return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
    }

    override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> {
        guard.check(sql)
        return delegate.execute(identifier, sql, parameters, binders)
    }

    override fun newTransaction(): QueryResult<Transacter.Transaction> {
        guard.check("BEGIN")
        return delegate.newTransaction()
    }
}
