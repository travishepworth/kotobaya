package kotobaya.android.widget

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotobaya.android.db.APP_DB_FILE
import kotobaya.android.db.openAppDatabase
import kotobaya.android.queue.LocalQueueLoader
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** ADR-054: the widget shows the one number of every surface, `untilStop` (learning + review + new doable now). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class WidgetDataTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext<Application>()
    private val now = 1_800_000_000_000L
    private lateinit var sql: SQLiteDatabase

    @After
    fun tearDown() = sql.close()

    private fun uuid(kind: Int, n: Int) = "0193a1b2-%04d-7000-8000-%012d".format(kind, n)

    private lateinit var loader: LocalQueueLoader

    /** 9 overdue reviews, 26 learning cards whose step ends in an hour (later today), then [newCards] new cards. */
    private fun data(newCards: Int = 0): WidgetData {
        val db = openAppDatabase(context)
        db.localQueueQueries.liveDecks().executeAsList()
        sql = SQLiteDatabase.openDatabase(context.getDatabasePath(APP_DB_FILE).path, null, SQLiteDatabase.OPEN_READWRITE)
        for (n in 1..35 + newCards) {
            sql.execSQL(
                "INSERT INTO note (id, note_type_id, type, entry_seq, fields, created_by_device_id, created_at, updated_at) VALUES " +
                    "('${uuid(1, n)}', '00000000-0000-7000-8000-000000000101', 'vocab', ${100 + n}, " +
                    "'{\"expression\":\"w$n\",\"reading\":\"r$n\",\"meaning\":\"m$n\"}', 'dev', $n, $n)",
            )
            val (state, due) = when {
                n <= 9 -> "review" to "${now - 3_600_000}"
                n <= 35 -> "learning" to "${now + 3_600_000}"
                else -> "new" to "NULL"
            }
            sql.execSQL(
                "INSERT INTO card (id, note_id, kind, state, due_at, lapses, created_at, updated_at) VALUES " +
                    "('${uuid(2, n)}', '${uuid(1, n)}', 'meaning', '$state', $due, 0, 0, 0)",
            )
        }
        loader = LocalQueueLoader(db, { now })
        return WidgetData(loader, SqliteWidgetSource(context))
    }

    @Test
    fun countIsReadyNow() {
        assertEquals(9, data().snapshot().due, "26 learning cards due later today are not ready")
    }

    /** ADR-054: new cards doable now are in the number, exactly as in Home and the review header. */
    @Test
    fun countIsUntilStop() {
        val snap = data(newCards = 5).snapshot()
        val state = loader.load().state
        assertEquals(state.untilStop, snap.due)
        assertEquals(state.untilStopParts.learning + state.untilStopParts.review + state.untilStopParts.new, snap.due)
        assertEquals(14, snap.due, "9 reviews + 5 new; the 26 later are not counted")
    }
}
