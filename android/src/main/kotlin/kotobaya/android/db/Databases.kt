package kotobaya.android.db

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import kotobaya.db.app.AppDatabase
import kotobaya.db.ref.RefDatabase

const val APP_DB_FILE = "kotobaya-app.db"

/** Opens (creating tables on first run) the learning database in app-private storage. */
fun openAppDatabase(context: Context): AppDatabase =
    AppDatabase(AndroidSqliteDriver(AppDatabase.Schema, context, APP_DB_FILE))

/** For tests and tooling: creates the schema on any driver and wraps it. */
fun createAppDatabase(driver: SqlDriver): AppDatabase {
    AppDatabase.Schema.create(driver)
    return AppDatabase(driver)
}

/** Seam for the read-only dictionary file; T302 supplies the real implementation. */
interface DictionaryProvider {
    /** Null until a dictionary release is installed. */
    fun open(): RefDatabase?
}

class EmptyDictionaryProvider : DictionaryProvider {
    override fun open(): RefDatabase? = null
}
