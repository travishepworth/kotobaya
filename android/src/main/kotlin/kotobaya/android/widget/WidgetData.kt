package kotobaya.android.widget

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotobaya.android.db.APP_DB_FILE
import kotobaya.android.queue.LocalQueueLoader
import org.json.JSONObject

/** A word picked for the 4x3 widget. [entrySeq] forms the `kotobaya://word/<id>` link. */
data class HotWord(val entrySeq: Long, val expression: String, val reading: String, val meaning: String)

/** Everything the widget shows, read from the local database only. */
data class WidgetSnapshot(
    /** Due reviews plus learning cards (the same "due" the notification shows). */
    val due: Int,
    val new: Int,
    /** Cards due in each of the next 24 hours; hour 0 includes overdue. */
    val forecast: List<Int>,
    val doneToday: Int,
    val hotWord: HotWord?,
) {
    val isEmpty: Boolean get() = due == 0 && new == 0

    /** 0.0..1.0 share of today's work finished; 1.0 when there was nothing to do. */
    val progress: Float
        get() {
            val total = doneToday + due + new
            return if (total == 0) 1f else doneToday.toFloat() / total
        }

    companion object {
        val EMPTY = WidgetSnapshot(0, 0, List(24) { 0 }, 0, null)
    }
}

/** Where the widget's extra reads come from (kept apart from the queue loader so tests can use the real DB file). */
interface WidgetSource {
    /** Highest-lapse live vocab words, most lapsed first (the stand-in for core's HotScorer; see HANDOFF). */
    fun hotCandidates(): List<HotWord>

    /** Non-undone scheduled or learning answers logged at or after [sinceMs]. */
    fun doneSince(sinceMs: Long): Int
}

/** Read-only raw SQLite reads of the app database file; a failure reads as "nothing". */
class SqliteWidgetSource(private val context: Context) : WidgetSource {
    private fun <T> read(block: (SQLiteDatabase) -> T): T? {
        val f = context.getDatabasePath(APP_DB_FILE)
        if (!f.exists()) return null
        return runCatching {
            SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use(block)
        }.getOrNull()
    }

    override fun hotCandidates(): List<HotWord> = read { db ->
        db.rawQuery(
            "SELECT n.entry_seq, n.fields, SUM(c.lapses) AS l FROM note n " +
                "JOIN card c ON c.note_id = n.id AND c.deleted_at IS NULL AND c.merged_into_id IS NULL " +
                "WHERE n.type = 'vocab' AND n.entry_seq IS NOT NULL AND n.deleted_at IS NULL " +
                "GROUP BY n.id HAVING l > 0 ORDER BY l DESC, n.id LIMIT $HOT_LIMIT",
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val fields = runCatching { JSONObject(c.getString(1)) }.getOrNull() ?: continue
                    val expression = fields.optString("expression")
                    if (expression.isEmpty()) continue
                    add(HotWord(c.getLong(0), expression, fields.optString("reading"), fields.optString("meaning")))
                }
            }
        }
    }.orEmpty()

    override fun doneSince(sinceMs: Long): Int = read { db ->
        db.rawQuery(
            "SELECT COUNT(*) FROM review_log WHERE undone_at IS NULL AND review_type IN ('scheduled', 'learning') AND reviewed_at >= ?",
            arrayOf(sinceMs.toString()),
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    } ?: 0

    private companion object {
        const val HOT_LIMIT = 10
    }
}

/** Pure data layer of the widget and tile; no Glance, no network. Queue rules stay in [LocalQueueLoader]. */
class WidgetData(private val loader: LocalQueueLoader, private val source: WidgetSource) {
    fun snapshot(): WidgetSnapshot {
        val loaded = loader.load()
        val counts = loaded.counts
        val day = loaded.input.day
        return WidgetSnapshot(
            due = counts.due + counts.learning,
            new = counts.new,
            forecast = loader.forecast24h(),
            doneToday = source.doneSince(day.startMs),
            hotWord = pickHotWord(source.hotCandidates(), day.startMs),
        )
    }

    companion object {
        private const val DAY_MS = 86_400_000L

        /** Same word for the whole study day, the next one tomorrow; order of [candidates] decides, so it is stable. */
        fun pickHotWord(candidates: List<HotWord>, dayStartMs: Long): HotWord? {
            if (candidates.isEmpty()) return null
            val dayNumber = Math.floorDiv(dayStartMs + DAY_MS / 2, DAY_MS)
            return candidates[Math.floorMod(dayNumber, candidates.size.toLong()).toInt()]
        }
    }
}
