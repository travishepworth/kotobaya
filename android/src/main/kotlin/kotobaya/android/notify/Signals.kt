package kotobaya.android.notify

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotobaya.android.db.APP_DB_FILE
import java.io.File

/** What the notifications need to know that the queue loader does not tell. */
interface NotifySignals {
    /** Epoch ms of the last successful sync (`sync_state.last_sync_at`), null if never. */
    fun lastSyncAtMs(): Long?

    /** True if a rated or drill review was logged at or after [sinceMs]. */
    fun reviewedSince(sinceMs: Long): Boolean
}

/** Two tiny read-only lookups straight from the learning database file; failure reads as "unknown". */
class SqliteSignals(private val context: Context) : NotifySignals {
    private fun <T> read(block: (SQLiteDatabase) -> T): T? {
        val f: File = context.getDatabasePath(APP_DB_FILE)
        if (!f.exists()) return null
        return runCatching {
            SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use(block)
        }.getOrNull()
    }

    override fun lastSyncAtMs(): Long? = read { db ->
        db.rawQuery("SELECT value FROM sync_state WHERE key = 'last_sync_at'", null).use {
            if (it.moveToFirst()) it.getString(0).toLongOrNull() else null
        }
    }

    override fun reviewedSince(sinceMs: Long): Boolean = read { db ->
        db.rawQuery(
            "SELECT 1 FROM review_log WHERE reviewed_at >= ? AND review_type <> 'manual' LIMIT 1",
            arrayOf(sinceMs.toString()),
        ).use { it.moveToFirst() }
    } ?: false
}
