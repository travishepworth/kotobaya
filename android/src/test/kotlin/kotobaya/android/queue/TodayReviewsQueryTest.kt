package kotobaya.android.queue

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotobaya.db.app.AppDatabase
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * AND-PERF: todayReviews on Android's native SQLite (API 35, as the phone). 0.1.10's query walked
 * review_log_reviewed_idx backwards over the whole history for every answer of today (30-116 s per call on 78k
 * rows; all four pool connections busy). The rewrite looks the previous row up per member card through
 * review_log_replay_idx and returns the same rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TodayReviewsQueryTest {
    private val raw = AndroidSqliteDriver(AppDatabase.Schema, ApplicationProvider.getApplicationContext<Application>(), null)
    private val driver = RecordingDriver(raw)
    private val db = AppDatabase(driver)

    private fun sqliteVersion(): String =
        raw.executeQuery(null, "SELECT sqlite_version()", { c -> QueryResult.Value(if (c.next().value) c.getString(0) else null) }, 0).value.orEmpty()

    @Test
    fun eightyThousandRowsWithMergesAreCorrectFastAndIndexDriven() {
        val data = SyntheticReviews(cards = 9_500, historyRows = 78_500, todayRows = 200, dayStart = DAY_START, now = NOW)
        data.insert(db, raw)
        val expected = data.expected(DAY_START, NOW)
        assertTrue(expected.size > 150, "fixture has today's answers: ${expected.size}")
        assertTrue(expected.any { it.introducedNew } && expected.any { it.reviewType == "learning" && !it.introducedNew })

        val started = System.nanoTime()
        val actual = SyntheticReviews.newQuery(db, DAY_START, NOW)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertEquals(expected, actual)
        assertTrue(ms < 500, "todayReviews took $ms ms on SQLite ${sqliteVersion()}")

        val plan = driver.plan("AS introduced_new")
        val text = plan.joinToString("\n")
        // no full scan of review_log anywhere, the outer rows by reviewed_at, the previous row per card by replay order
        assertTrue(plan.none { it.startsWith("SCAN") && (" p" in it || " r" in it || "review_log" in it) }, text)
        assertTrue(plan.any { it.startsWith("SEARCH r USING INDEX review_log_reviewed_idx") }, text)
        assertTrue(plan.any { it.startsWith("SEARCH p USING INDEX review_log_replay_idx (card_id=?") }, text)
        assertTrue(plan.none { "review_log_reviewed_idx" in it && it.startsWith("SEARCH p") }, text)
    }

    @Test
    fun oldAndNewQueriesReturnTheSameRows() {
        val data = SyntheticReviews(cards = 300, historyRows = 2_500, todayRows = 300, dayStart = DAY_START, now = NOW, seed = 11)
        data.insert(db, raw)
        for ((from, to) in listOf(DAY_START to NOW, DAY_START - 3 * DAY to NOW, 0L to NOW, NOW + 1 to NOW + DAY)) {
            val old = SyntheticReviews.oldQuery(raw, from, to)
            assertEquals(old, SyntheticReviews.newQuery(db, from, to), "window $from..$to")
            assertEquals(data.expected(from, to), old, "oracle agrees with the old query, window $from..$to")
        }
        assertTrue(SyntheticReviews.oldQuery(raw, 0L, NOW).count { it.introducedNew } > 0)
    }

    @Test
    fun handPickedCasesMatchTheOldQuery() {
        val c = "c0000000-0000-7000-8000-000000000001"
        val loser = "c0000000-0000-7000-8000-000000000002"
        val other = "c0000000-0000-7000-8000-000000000003"
        fun card(id: String, merged: String? = null) {
            raw.execute(null, "INSERT INTO note (id, note_type_id, type, fields, created_by_device_id, created_at, updated_at) VALUES ('n$id', 't', 'vocab', '{}', 'd', 0, 0)", 0)
            raw.execute(
                null,
                "INSERT INTO card (id, note_id, kind, created_at, updated_at, deleted_at, merged_into_id) VALUES ('$id', 'n$id', 'meaning', 0, 0, " +
                    "${if (merged != null) "1, '$merged'" else "NULL, NULL"})",
                0,
            )
        }
        fun review(id: String, card: String, at: Long, hlc: String, type: String, action: String? = null, undone: Boolean = false) =
            raw.execute(
                null,
                "INSERT INTO review_log (id, card_id, device_id, hlc, reviewed_at, review_type, rating, manual_action, undone_at, undone_hlc) VALUES " +
                    "('$id', '$card', 'd', '$hlc', $at, '$type', ${if (type == "manual") "NULL" else "3"}, ${action?.let { "'$it'" } ?: "NULL"}, " +
                    "${if (undone) "$at, '$hlc'" else "NULL, NULL"})",
                0,
            )
        card(c)
        card(loser, merged = c)
        card(other)
        val t = DAY_START + 1000
        // c: history on the merge loser only, then today: learning after a set_due (not new), a reset, learning (new)
        review("r01", loser, t - DAY, "h1", "scheduled")
        review("r02", c, t, "h1", "manual", "set_due")
        review("r03", c, t + 1, "h1", "learning")
        review("r04", c, t + 2, "h1", "manual", "reset")
        review("r05", c, t + 3, "h1", "learning")
        // same reviewed_at: hlc decides, then id; an undone and a drill row in between are ignored
        review("r07", other, t + 10, "h2", "learning")
        review("r06", other, t + 10, "h2", "learning")
        review("r08", other, t + 10, "h1", "drill")
        review("r09", other, t + 11, "h1", "manual", "reset")
        review("r10", other, t + 12, "h1", "scheduled", undone = true)
        review("r11", other, t + 13, "h1", "cram")
        review("r12", other, t + 14, "h1", "learning")
        val new = SyntheticReviews.newQuery(db, DAY_START, NOW)
        assertEquals(SyntheticReviews.oldQuery(raw, DAY_START, NOW), new)
        val learning = new.filter { it.reviewType == "learning" }
        // c: r03 (after set_due) no, r05 (after reset) yes; other: r06 first yes, r07 (after r06 by id) no, r12 (after reset) yes
        assertEquals(listOf(c to false, c to true, other to false, other to true, other to true), learning.map { it.cardId to it.introducedNew }.sortedWith(compareBy({ it.first }, { it.second })))
    }

    private companion object {
        const val DAY = 86_400_000L
        const val DAY_START = 1_794_495_600_000L
        const val NOW = DAY_START + 10 * 3_600_000L
    }
}
