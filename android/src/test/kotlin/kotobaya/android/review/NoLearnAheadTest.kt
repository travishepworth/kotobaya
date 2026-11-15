package kotobaya.android.review

import androidx.test.core.app.ApplicationProvider
import kotobaya.android.queue.LocalQueueLoader
import kotobaya.android.sync.SqlLearningTables
import kotobaya.contracts.fsrs.Button
import kotobaya.contracts.queue.QueueItemKind
import kotobaya.contracts.sync.JsonNumber
import kotobaya.sync.client.apply.LearningTable
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * ADR-033 on the device: no learn-ahead. A learning or relearning card is shown only once `due_at <= now`; when only
 * not-yet-due learning cards are left, the all-decks session waits ([ReviewState.Waiting]) and resumes at the next due
 * instant. Phone log 2026-11-15 (0.1.22): three cards answered Again came back every 2-8 s at the end of the queue, and
 * new cards on their 10-minute step came back after ~1 min. Production store and loader (Robolectric).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class NoLearnAheadTest {
    private val t0 = 1_760_000_000_000L
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val d = AsyncReviewDevice.android(t0, context)
    private var reviewIds = 0

    @After
    fun close() {
        d.close()
        context.deleteDatabase(kotobaya.android.db.APP_DB_FILE)
    }

    private fun setting(key: String, value: String) {
        d.driver.execute(null, "INSERT OR REPLACE INTO settings (key, value, hlc, updated_at) VALUES ('$key', '$value', 'h', 0)", 0)
    }

    private fun loader() = LocalQueueLoader(d.db, { d.clock.now })

    private fun session(): ReviewSession<SqlLearningTables> = ReviewSession(
        d.store, d.stack.minter, loader().asQueueSource(), { d.clock.now }, { 540 },
        { "0193a1b2-0003-7000-8000-%012d".format(++reviewIds) }, { ReviewPrefs() },
        rebuildEveryMs = 20_000,
        reads = d.reads,
    )

    /** Turns [ids] into review cards due an hour ago. */
    private fun makeDueReviews(ids: List<String>) {
        val day = 86_400_000L
        for (id in ids) {
            d.driver.execute(
                null,
                "UPDATE card SET state = 'review', stability = 12.0, difficulty = 5.0, interval_days = 12, reps = 6, lapses = 0, " +
                    "last_review_at = ?, due_at = ? WHERE id = ?",
                3,
            ) {
                bindLong(0, d.clock.now - 13 * day)
                bindLong(1, d.clock.now - 3_600_000)
                bindString(2, id)
            }
        }
    }

    private fun showing(s: ReviewSession<*>): ShownCard = assertIs<ReviewState.Showing>(s.state.value).card

    private fun row(card: String) = d.store.read { tx -> CardRows.get(tx.learning, LearningTable.CARD, card) }

    private fun dueAt(card: String): Long = (row(card)?.get("due_at") as JsonNumber).value.toLong()

    private fun state(card: String): String? = CardRows.str(row(card)?.get("state"))

    private fun logged(): List<String?> = d.ops("review.logged").map { d.str(it, "card_id") }

    /**
     * Watches every card shown: a card in learning or relearning must be due ([dueAt] <= now), and no card may be
     * shown before the due instant its last answer gave it while it was in learning.
     */
    private inner class Watch {
        val earliest = HashMap<String, Long>()
        val shown = ArrayList<Pair<String, Long>>()

        fun check(card: ShownCard) {
            val now = d.clock.now
            shown += card.cardId to now
            val st = state(card.cardId)
            if (st == "learning" || st == "relearning") {
                assertTrue(dueAt(card.cardId) <= now, "${card.cardId} ($st) shown ${dueAt(card.cardId) - now} ms before due")
            }
            earliest[card.cardId]?.let { assertTrue(now >= it, "${card.cardId} shown ${it - now} ms inside its step") }
        }

        fun answered(card: ShownCard) {
            val st = state(card.cardId)
            if (st == "learning" || st == "relearning") earliest[card.cardId] = dueAt(card.cardId) else earliest.remove(card.cardId)
        }
    }

    private fun answer(s: ReviewSession<*>, w: Watch, button: (ShownCard) -> Button): ShownCard {
        val card = showing(s)
        w.check(card)
        d.clock.now += 2_000
        assertTrue(s.answer(card.token, button(card)), "answer of ${card.cardId} committed")
        w.answered(card)
        return card
    }

    /** The screen's resume at the Waiting instant: the clock reaches it and the session restarts (refreshIfEmpty). */
    private fun resumeAt(s: ReviewSession<*>, waiting: ReviewState.Waiting) {
        assertTrue(waiting.nextDueAt > d.clock.now, "waiting for an instant in the future")
        d.clock.now = waiting.nextDueAt
        s.start()
    }

    @Test
    fun aCardAnsweredAgainAsTheLastOneLeftWaitsAndResumesWhenDue() {
        val (only) = d.seed(1)
        val s = session()
        s.start()
        assertEquals(only, showing(s).cardId)
        d.clock.now += 2_000
        assertTrue(s.answer(showing(s).token, Button.AGAIN))
        val waiting = assertIs<ReviewState.Waiting>(s.state.value, "not the card again, not the summary")
        assertEquals(dueAt(only), waiting.nextDueAt)
        assertEquals(1, waiting.stats?.answered)

        // a resume before it is due changes nothing
        d.clock.now = waiting.nextDueAt - 1
        s.start()
        assertEquals(waiting.nextDueAt, assertIs<ReviewState.Waiting>(s.state.value).nextDueAt)

        d.clock.now = waiting.nextDueAt
        s.start()
        assertEquals(only to QueueItemKind.LEARNING, showing(s).let { it.cardId to it.kind })
        assertEquals(listOf<String?>(only), logged(), "one review op")
        assertTrue(s.meta.value.canUndo, "the session carries on: its answers are still there")
    }

    @Test
    fun aNewCardOnItsTenMinuteStepDoesNotComeBackBeforeTheStepEvenWhenEverythingElseIsAnswered() {
        val cards = d.seed(5)
        val s = session()
        s.start()
        val w = Watch()
        val first = answer(s, w) { Button.GOOD }
        val step = dueAt(first.cardId)
        assertTrue(step - d.clock.now >= 9 * 60_000, "10-minute step: ${step - d.clock.now} ms")
        while (s.state.value is ReviewState.Showing) {
            val c = answer(s, w) { Button.EASY }
            assertTrue(c.cardId != first.cardId, "${first.cardId} came back ${step - d.clock.now} ms before its step")
        }
        val waiting = assertIs<ReviewState.Waiting>(s.state.value)
        assertEquals(step, waiting.nextDueAt)
        assertEquals(cards.size, waiting.stats?.answered)
        resumeAt(s, waiting)
        assertEquals(first.cardId, showing(s).cardId)
    }

    @Test
    fun threeLapsedCardsAreNeverShownTwiceWithinTheirStep() {
        // the 11:57 scenario: three reviews answered Again, nothing else left
        val lapsed = d.seed(3)
        makeDueReviews(lapsed)
        val s = session()
        s.start()
        val w = Watch()
        repeat(3) { assertEquals(QueueItemKind.REVIEW, answer(s, w) { Button.AGAIN }.kind) }
        assertTrue(lapsed.all { state(it) == "relearning" })
        val grades = ArrayDeque(listOf(Button.HARD, Button.HARD, Button.GOOD))
        val byCard = HashMap<String, ArrayDeque<Button>>()
        var waits = 0
        var guard = 0
        while (true) {
            when (val st = s.state.value) {
                is ReviewState.Showing -> answer(s, w) { c -> byCard.getOrPut(c.cardId) { ArrayDeque(grades) }.removeFirstOrNull() ?: Button.GOOD }
                is ReviewState.Waiting -> { waits++; resumeAt(s, st) }
                else -> break
            }
            check(++guard < 200) { "did not end: ${w.shown}" }
        }
        assertIs<ReviewState.Summary>(s.state.value)
        assertTrue(waits > 0, "the session waited for steps")
        assertTrue(lapsed.none { state(it) == "relearning" }, "all graduated")
        // no card twice within a few seconds
        for ((id, times) in w.shown.groupBy({ it.first }, { it.second })) {
            assertTrue(times.zipWithNext().all { (a, b) -> b - a >= 60_000 }, "$id shown at $times")
        }
        assertEquals(w.shown.map { it.first }, logged(), "one op per answer")
    }

    @Test
    fun aLongKeepGoingStreamNeverShowsACardBeforeItsDueAndNeverEndsWhileNewCardsRemain() {
        setting("scheduler.new_per_day", "5")
        setting("scheduler.new_per_session", "5")
        val reviews = d.seed(50)
        makeDueReviews(reviews)
        val news = d.seed(30)
        val s = session()
        s.start()
        val w = Watch()
        var n = 0
        var guard = 0
        var waitsWhileKeepingGoing = 0
        while (true) {
            val st = s.state.value
            when {
                st is ReviewState.Showing -> answer(s, w) { if (++n % 7 == 0) Button.AGAIN else Button.GOOD }
                s.meta.value.moreAvailable && !s.meta.value.keepingGoing -> s.startKeepGoing()
                st is ReviewState.Waiting -> {
                    if (s.meta.value.keepingGoing) {
                        waitsWhileKeepingGoing++
                        // the keep-going build has nothing but not-yet-due learning cards
                        val kg = loader().load(keepGoing = true).queue
                        assertTrue(kg.items.isEmpty() && kg.toMaterialize.isEmpty(), "waiting with cards left: ${kg.items}")
                    }
                    resumeAt(s, st)
                }
                else -> break
            }
            check(++guard < 2_000) { "did not end" }
        }
        assertIs<ReviewState.Summary>(s.state.value)
        assertTrue(s.meta.value.keepingGoing)
        val served = w.shown.mapTo(HashSet()) { it.first }
        assertEquals(news.toSet(), served.filterTo(HashSet()) { it in news }, "every new card served")
        assertEquals(reviews.toSet(), served.filterTo(HashSet()) { it in reviews }, "every review served")
        assertFalse((reviews + news).any { state(it) == "learning" || state(it) == "relearning" }, "nothing left in learning")
        val left = loader().load(keepGoing = true).queue
        assertTrue(left.items.isEmpty() && left.toMaterialize.isEmpty() && left.nextLearningDueAt == null)
    }

    @Test
    fun keepGoingServesNewCardsWhileALearningCardWaits() {
        setting("scheduler.new_per_day", "1")
        val (a, b, c) = d.seed(3)
        val s = session()
        s.start()
        val w = Watch()
        assertEquals(a, answer(s, w) { Button.AGAIN }.cardId)
        // a waits (1 min); the daily new limit is reached: waiting, with Keep going offered
        assertIs<ReviewState.Waiting>(s.state.value)
        assertTrue(s.meta.value.moreAvailable)
        s.startKeepGoing()
        assertEquals(setOf(b, c), setOf(answer(s, w) { Button.EASY }.cardId, answer(s, w) { Button.EASY }.cardId))
        val waiting = assertIs<ReviewState.Waiting>(s.state.value)
        assertEquals(dueAt(a), waiting.nextDueAt)
        resumeAt(s, waiting)
        assertEquals(a, answer(s, w) { Button.EASY }.cardId)
        assertIs<ReviewState.Summary>(s.state.value)
    }

    @Test
    fun theTodayCountsIncludeLearningCardsDueLaterToday() {
        val (a, _) = d.seed(2)
        val s = session()
        s.start()
        val w = Watch()
        assertEquals(a, answer(s, w) { Button.AGAIN }.cardId)
        val counts = loader().counts()
        assertEquals(1, counts.new)
        assertEquals(1, counts.learning, "a learning card due later today is still to do today (as the server's counts)")
        assertEquals(0, loader().load().queue.items.count { it.kind == QueueItemKind.LEARNING }, "not in the queue before due")
    }
}
