package kotobaya.android.review

import kotobaya.contracts.Reserved
import kotobaya.contracts.fsrs.Button
import kotobaya.contracts.queue.QueueItem
import kotobaya.contracts.queue.QueueItemKind
import kotobaya.contracts.queue.ReviewQueue
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.OpType
import kotobaya.sync.client.apply.DeviceOpApplier
import kotobaya.sync.client.apply.InMemoryLearningTables
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.mint.MintResult
import kotobaya.sync.client.mint.OpMinter
import kotobaya.sync.client.store.Clock
import kotobaya.sync.client.store.InMemoryDeviceStore
import kotobaya.sync.client.store.SyncStateKeys
import kotobaya.sync.client.store.UuidV7Source
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** RC-AND: the end-of-queue build failing, and a sync that changed the queue between two answers. */
class ReviewHardeningTest {
    private var now = 1_760_000_000_000L
    private val ids = UuidV7Source(Random(9))
    private val store = InMemoryDeviceStore(InMemoryLearningTables.seeded()) { it.copy() }
    private val minter = OpMinter(store, DeviceOpApplier<InMemoryLearningTables>(), Clock { now }, ids)

    init {
        store.transaction { it.syncState.put(SyncStateKeys.DEVICE_ID, "01931f3e-0000-7000-8000-0000000000a2") }
    }

    /** Throws instead of building while set. */
    private var failing = false
    private var loads = 0

    /** What the screen showed while the last build ran. */
    private var stateDuringLoad: ReviewState? = null
    private var session: ReviewSession<*>? = null

    /** Live cards in id order; a started card only once due (ADR-033: no learn-ahead). */
    private val queue = QueueSource {
        loads++
        stateDuringLoad = session?.state?.value
        if (failing) error("disk gone")
        val cards = store.learning().all(LearningTable.CARD)
            .filter { c ->
                val st = CardRows.str(c["state"])
                (c["suspended"] as? kotobaya.contracts.sync.JsonBool)?.value != true &&
                    ((c["buried_until"] as? JsonNumber)?.value?.toLong() ?: 0L) <= now &&
                    CardRows.live(c) &&
                    (st == "new" || ((c["due_at"] as? JsonNumber)?.value?.toLong() ?: Long.MIN_VALUE) <= now)
            }
            .sortedBy { CardRows.str(it["id"]) }
        ReviewQueue(
            cards.map { c ->
                val kind = when (CardRows.str(c["state"])) {
                    "new" -> QueueItemKind.NEW
                    "review" -> QueueItemKind.REVIEW
                    else -> QueueItemKind.LEARNING
                }
                QueueItem(checkNotNull(CardRows.str(c["id"])), kind, null)
            },
            emptyList(), null,
        )
    }

    private fun newSession() = ReviewSession(store, minter, queue, { now }, { 540 }, { ids.newOpId(it) }, { ReviewPrefs() })
        .also { session = it }

    private fun obj(vararg p: Pair<String, JsonValue>) = JsonObject(linkedMapOf(*p))

    private fun seed(vararg kinds: String): List<String> {
        val noteId = ids.newOpId(now)
        assertIs<MintResult.Minted>(
            minter.mint(
                OpType.NOTE_CREATED,
                obj(
                    "id" to JsonString(noteId),
                    "note_type_id" to JsonString(Reserved.NOTE_TYPE_VOCAB),
                    "fields" to obj("expression" to JsonString("食べる"), "reading" to JsonString("食[た]べる"), "meaning" to JsonString("to eat")),
                ),
            ),
        )
        return kinds.map { kind ->
            val id = ids.newOpId(now)
            assertIs<MintResult.Minted>(
                minter.mint(OpType.CARD_CREATED, obj("id" to JsonString(id), "note_id" to JsonString(noteId), "kind" to JsonString(kind))),
            )
            id
        }
    }

    /** [cardId] answered Good elsewhere (the op a sync pulls applies the same local effect). */
    private fun answeredElsewhere(cardId: String) {
        assertIs<MintResult.Minted>(
            minter.mint(
                OpType.REVIEW_LOGGED,
                obj(
                    "id" to JsonString(ids.newOpId(now)),
                    "card_id" to JsonString(cardId),
                    "reviewed_at" to JsonNumber(now.toDouble()),
                    "utc_offset_min" to JsonNumber(540.0),
                    "review_type" to JsonString("learning"),
                    "rating" to JsonNumber(3.0),
                    "button" to JsonString(Button.GOOD.wire),
                    "duration_ms" to JsonNumber(1000.0),
                ),
            ),
        )
    }

    @Test
    fun rebuildThrowingAfterTheLastAnswerShowsFailedAndRetryRecovers() {
        seed("meaning")
        val s = newSession()
        s.start()
        val card = assertIs<ReviewState.Showing>(s.state.value).card
        s.flip()
        now += 5_000
        failing = true
        // the answer itself is committed; only the build after it fails
        assertTrue(s.answer(card.token, Button.GOOD))
        assertEquals(ReviewState.Loading, stateDuringLoad, "a spinner while the end-of-queue build runs, not the dead card")
        assertEquals(ReviewState.Failed, s.state.value, "the end-of-queue build failed: Retry, not a frozen card")
        assertEquals(1, store.rows().count { it.opType == "review.logged" })

        // Retry (the view model starts the session again) works once storage does
        failing = false
        s.start()
        assertNotEquals(ReviewState.Failed, s.state.value)
    }

    @Test
    fun aStaleQueueWhoseBuildFailsShowsFailed() {
        val (a, b) = (seed("meaning") + seed("meaning")).sorted()
        val s = newSession()
        s.start()
        val first = assertIs<ReviewState.Showing>(s.state.value).card
        assertEquals(a, first.cardId)
        s.flip()
        answeredElsewhere(b) // the next card changed: a build is needed
        failing = true
        s.markStale()
        // stale: the next card waits for a build, which fails
        assertTrue(s.answer(first.token, Button.GOOD))
        assertEquals(ReviewState.Failed, s.state.value)
    }

    @Test
    fun aPeriodicRefreshFailingWithACardShownKeepsTheCardAnswerable() {
        seed("meaning")
        seed("meaning")
        seed("meaning")
        val s = newSession()
        s.start()
        val first = assertIs<ReviewState.Showing>(s.state.value).card
        s.flip()
        now += ReviewSession.REBUILD_EVERY_MS + 1
        failing = true
        assertTrue(s.answer(first.token, Button.GOOD))
        val second = assertIs<ReviewState.Showing>(s.state.value).card
        assertNotEquals(first.cardId, second.cardId)
        failing = false
        s.flip()
        assertTrue(s.answer(second.token, Button.GOOD), "the card shown is still answerable")
    }

    @Test
    fun afterSyncTheAlreadyAnsweredCardIsNotShownFromTheStaleQueue() {
        // in queue (id) order: a shown first, b next in memory, then c
        val (a, b, c) = (seed("meaning", "reading") + seed("meaning")).sorted()
        val s = newSession()
        s.start()
        val shown = assertIs<ReviewState.Showing>(s.state.value).card
        assertEquals(a, shown.cardId)
        // a sync pulls an answer of b from another device (b is now a learning card due in minutes)
        answeredElsewhere(b)
        s.markStale()
        s.flip()
        now += 5_000
        assertTrue(s.answer(shown.token, Button.EASY))
        val next = assertIs<ReviewState.Showing>(s.state.value).card
        assertEquals(c, next.cardId, "b was answered on the other device: the next card comes from a fresh build")
        assertEquals(1, store.rows().count { it.opType == "review.logged" && it.payload.contains(b) }, "b is not reviewed twice")
    }

    @Test
    fun withoutASyncTheNextCardComesFromMemory() {
        seed("meaning", "reading")
        seed("meaning")
        val s = newSession()
        s.start()
        val shown = assertIs<ReviewState.Showing>(s.state.value).card
        val builds = loads
        s.flip()
        now += 5_000
        s.answer(shown.token, Button.EASY)
        assertIs<ReviewState.Showing>(s.state.value)
        assertEquals(builds, loads, "no build between two cards in the normal case")
    }

    private fun mint(type: OpType, vararg p: Pair<String, JsonValue>) = assertIs<MintResult.Minted>(minter.mint(type, obj(*p)))

    /** a is shown, b next in memory, c after; [change] happens on b (as a sync would apply it) before a is answered. */
    private fun nextCardChangedThenAnswerA(change: (String) -> Unit) {
        val (a, b, c) = (seed("meaning") + seed("meaning") + seed("meaning")).sorted()
        val s = newSession()
        s.start()
        val shown = assertIs<ReviewState.Showing>(s.state.value).card
        assertEquals(a, shown.cardId)
        change(b)
        s.markStale()
        s.flip()
        now += 5_000
        assertTrue(s.answer(shown.token, Button.EASY))
        assertEquals(c, assertIs<ReviewState.Showing>(s.state.value).card.cardId, "b must not be shown")
    }

    @Test
    fun aCardSuspendedElsewhereIsNotShownAfterSync() =
        nextCardChangedThenAnswerA { mint(OpType.CARD_SUSPENDED, "id" to JsonString(it)) }

    @Test
    fun aCardBuriedElsewhereIsNotShownAfterSync() =
        nextCardChangedThenAnswerA { mint(OpType.CARD_BURIED, "id" to JsonString(it), "until" to JsonNumber((now + 3_600_000).toDouble())) }

    @Test
    fun aDeletedCardFollowedByAnAnsweredElsewhereCardShowsNeitherAndLogsNoDuplicate() {
        val (a, b, c, d) = (seed("meaning") + seed("meaning") + seed("meaning") + seed("meaning")).sorted()
        val s = newSession()
        s.start()
        val shown = assertIs<ReviewState.Showing>(s.state.value).card
        assertEquals(a, shown.cardId)
        mint(OpType.CARD_DELETED, "id" to JsonString(b))
        answeredElsewhere(c)
        s.markStale()
        s.flip()
        now += 5_000
        assertTrue(s.answer(shown.token, Button.EASY))
        assertEquals(d, assertIs<ReviewState.Showing>(s.state.value).card.cardId)
        assertEquals(1, store.rows().count { it.opType == "review.logged" && it.payload.contains(c) }, "c is not reviewed twice")
    }
}
