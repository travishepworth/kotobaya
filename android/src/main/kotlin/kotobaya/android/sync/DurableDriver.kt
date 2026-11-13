package kotobaya.android.sync

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.QueryResult
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
    /** The Android driver for [APP_DB_FILE] (same file and schema as `db/openAppDatabase`), WAL + FULL. */
    fun open(context: Context): SqlDriver = AndroidSqliteDriver(
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
