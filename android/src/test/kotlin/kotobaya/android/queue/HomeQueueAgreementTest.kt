package kotobaya.android.queue

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.TimeZone
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotobaya.android.db.createAppDatabase
import kotobaya.android.decks.DeckRepository
import kotobaya.android.decks.DecksViewModel
import kotobaya.android.home.ui.TodayHero
import kotobaya.android.home.ui.todayHero
import kotobaya.android.home.ui.todayIsEmpty
import kotobaya.android.review.ReviewPrefs
import kotobaya.android.review.ReviewSession
import kotobaya.android.review.ReviewState
import kotobaya.android.review.asQueueSource
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.contracts.queue.QueueItemKind
import kotobaya.db.app.AppDatabase
import kotobaya.sync.client.apply.DeviceOpApplier
import kotobaya.sync.client.mint.OpMinter
import kotobaya.sync.client.store.Clock
import kotobaya.sync.client.store.UuidV7Source

/**
 * AND-QUEUE regression: live 0.1.11 showed "You are all caught up" on Home with 299 reviews due. The local queue was
 * right (183 due / 10 new / 1 learning, as the server's GET /review/queue); Home's hero summed the per-deck counts of
 * the user decks, and every due card of that collection is in no deck (mined and imported jpdb history), so the sum
 * was 0. Synthetic, anonymized fixture of the same shape as the phone's database; plain JVM like PostSyncLoadTest.
 */
class HomeQueueAgreementTest {
    private val savedZone: TimeZone = TimeZone.getDefault()

    @AfterTest
    fun restoreZone() = TimeZone.setDefault(savedZone)

    @Test
    fun homeShowsTheDecklessReviewsThatTheReviewTabStartsWith() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo")) // the phone's zone; the queue must not depend on it
        val f = Fixture()
        val loader = LocalQueueLoader(f.db, { NOW })

        // The Review tab's queue (ReviewBackend.newSession(null) -> asQueueSource().load): limits applied.
        val review = loader.asQueueSource().load(0)
        val dueItems = review.items.count { it.kind == QueueItemKind.REVIEW }
        assertEquals(REVIEWS_PER_DAY - SCHEDULED_TODAY, dueItems, "200/day default minus today's scheduled answers")
        assertEquals(10, review.items.count { it.kind == QueueItemKind.NEW }, "new_per_session default")
        assertEquals(1, review.items.count { it.kind == QueueItemKind.LEARNING })
        assertTrue(review.items.all { it.deckId == null }, "every due card is deckless")

        // Home: the Decks ViewModel state Today reads.
        val vm = DecksViewModel(DeckRepository(f.driver, loader), null, Dispatchers.Unconfined)
        val state = vm.state.value
        val decks = assertNotNull(state.decks, "decks still loading")
        val queue = assertNotNull(state.queue, "queue counts still loading")
        assertEquals(review.items.size, queue.total, "Home's count == the Review queue size")
        assertEquals(QueueCounts(dueItems, 10, 1), queue)
        assertEquals(TodayHero.Waiting(review.items.size), todayHero(queue))

        // The pre-fix hero: a sum over enabled user decks. Zero here, which is the "caught up" of the bug report.
        val oldSum = decks.filter { it.type == "user" && it.enabled }.sumOf { it.counts.total }
        assertEquals(0, oldSum)

        // A session started now shows a card (not ReviewState.Empty).
        val store = SqlDelightDeviceStore(f.driver, f.db)
        val minter = OpMinter(store, DeviceOpApplier(), Clock { NOW }, UuidV7Source(Random(1)))
        val session = ReviewSession(store, minter, loader.asQueueSource(), { NOW }, { 540 }, { "unused" }, { ReviewPrefs() })
        session.start()
        assertIs<ReviewState.Showing>(session.state.value)
    }

    @Test
    fun homeIsCaughtUpOnlyWhenTheWholeQueueIsEmpty() {
        assertEquals(TodayHero.Loading, todayHero(null))
        assertEquals(TodayHero.Clear, todayHero(QueueCounts.ZERO))
        assertEquals(TodayHero.Waiting(1), todayHero(QueueCounts(0, 0, 1)))
        // No deck at all but deckless cards due: the hero, not the "add your first words" page.
        assertEquals(false, todayIsEmpty(emptyList(), QueueCounts(5, 0, 0)))
        assertEquals(true, todayIsEmpty(emptyList(), QueueCounts.ZERO))
        assertEquals(false, todayIsEmpty(null, QueueCounts.ZERO))
    }

    /** The phone's shape, scaled down: no personal data, ids and values generated. */
    private class Fixture {
        val driver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val db: AppDatabase = createAppDatabase(driver)
        private val rnd = Random(11)

        private fun exec(sql: String) {
            driver.execute(null, sql, 0)
        }

        private fun id(kind: Int, n: Int) = "0193a1b2-%04d-7000-8000-%012d".format(kind, n)
        fun note(n: Int) = id(1, n)
        fun card(n: Int) = id(2, n)
        fun deck(n: Int) = id(3, n)

        init {
            db.transaction {
                // Only the two settings the phone had: no limits, no time zone.
                exec("INSERT INTO settings (key, value, hlc, updated_at) VALUES ('scheduler.kanji_before_vocab', 'true', 'h', 0)")
                exec("INSERT INTO settings (key, value, hlc, updated_at) VALUES ('scheduler.grading_buttons', '4', 'h', 0)")

                // Decks: a live media deck whose items were never downloaded, a deleted media deck, a user deck with
                // one item (a card that is not due), deleted smoke decks.
                deckRow(1, "media", priority = 5, contentVersion = 1, installed = 0)
                deckRow(2, "media", priority = 5, contentVersion = 1, installed = 1, deleted = true)
                deckRow(3, "user")
                deckRow(4, "user", deleted = true)
                deckRow(5, "list", deleted = true)
                exec("INSERT INTO deck_item (deck_id, item_key, note_id, position, added_at) VALUES ('${deck(3)}', 'n:${note(LAST_REVIEW)}', '${note(LAST_REVIEW)}', 0, 0)")

                // Review-state cards, one per vocab note: DUE of them overdue, the rest due in the coming months.
                for (n in 1..LAST_REVIEW) {
                    val due = if (n <= DUE) NOW - n * 60_000L else DAY_END + n * 3_600_000L
                    noteRow(n)
                    cardRow(n, "review", due)
                }
                // One relearning card due now, suspended learning cards (real data, never queued), new cards.
                noteRow(RELEARNING); cardRow(RELEARNING, "relearning", NOW - 60_000L)
                for (n in SUSPENDED_FROM until SUSPENDED_FROM + 124) { noteRow(n); cardRow(n, "learning", NOW - 3_600_000L, suspended = true) }
                for (n in NEW_FROM until NEW_FROM + 23) { noteRow(n); cardRow(n, "new", null) }

                // History: many old rows, a few since the JST rollover before the UTC one (yesterday by the UTC day
                // the loader uses without a time zone setting), and today's answers.
                var r = 0
                repeat(20_000) { reviewRow(++r, 1 + rnd.nextInt(LAST_REVIEW), DAY_START - 200 * DAY + rnd.nextLong(199 * DAY), "scheduled") }
                repeat(5) { reviewRow(++r, DUE + 1 + it, DAY_START - 9 * 3_600_000L + it * 60_000L, "scheduled") }
                repeat(SCHEDULED_TODAY) { reviewRow(++r, DUE + 100 + it, DAY_START + 3_600_000L + it * 60_000L, "scheduled") }
                reviewRow(++r, RELEARNING, NOW - 50 * DAY, "scheduled")
                repeat(3) { reviewRow(++r, RELEARNING, NOW - 600_000L + it * 60_000L, "learning") }
            }
        }

        private fun deckRow(n: Int, type: String, priority: Int = 0, contentVersion: Int = 0, installed: Int = 0, deleted: Boolean = false) =
            exec(
                "INSERT INTO deck (id, name, type, priority, enabled, settings, content_version, installed_content_version, created_at, updated_at, deleted_at) " +
                    "VALUES ('${deck(n)}', 'deck $n', '$type', $priority, 1, '{}', $contentVersion, $installed, 0, 0, ${if (deleted) "1" else "NULL"})",
            )

        private fun noteRow(n: Int) = exec(
            "INSERT INTO note (id, note_type_id, type, fields, created_by_device_id, created_at, updated_at) VALUES " +
                "('${note(n)}', '00000000-0000-7000-8000-000000000101', 'vocab', '{\"expression\":\"w$n\",\"reading\":\"r\",\"meaning\":\"m\"}', 'dev', $n, $n)",
        )

        /** Started cards carry an FSRS cache (the session previews the shown card's intervals). */
        private fun cardRow(n: Int, state: String, due: Long?, suspended: Boolean = false) {
            val fsrs = if (state == "new") "NULL, NULL, NULL, NULL, 0" else "10.0, 5.0, 10, ${NOW - 10 * DAY}, 3"
            exec(
                "INSERT INTO card (id, note_id, kind, state, due_at, suspended, stability, difficulty, interval_days, last_review_at, reps, " +
                    "created_at, updated_at) VALUES " +
                    "('${card(n)}', '${note(n)}', 'meaning', '$state', ${due ?: "NULL"}, ${if (suspended) 1 else 0}, $fsrs, 0, 0)",
            )
        }

        private fun reviewRow(n: Int, cardN: Int, at: Long, type: String) = exec(
            "INSERT INTO review_log (id, card_id, device_id, hlc, reviewed_at, review_type, rating, button) VALUES " +
                "('${id(9, n)}', '${card(cardN)}', 'dev', '${"%012x".format(at)}-0000-dev', $at, '$type', 3, 'good')",
        )
    }

    private companion object {
        const val DAY = 86_400_000L

        /** 2026-11-14T06:10Z: 15:10 in Tokyo, after both the 04:00 JST and the 04:00 UTC rollover. */
        const val NOW = 1_794_636_600_000L

        /** 2026-11-14T04:00Z: the study day start without a `scheduler.timezone` setting (UTC, rollover 4). */
        const val DAY_START = 1_794_628_800_000L
        const val DAY_END = DAY_START + DAY
        const val REVIEWS_PER_DAY = 200
        const val SCHEDULED_TODAY = 17
        const val DUE = 300
        const val LAST_REVIEW = 4_000
        const val RELEARNING = 4_001
        const val SUSPENDED_FROM = 5_000
        const val NEW_FROM = 6_000
    }
}
