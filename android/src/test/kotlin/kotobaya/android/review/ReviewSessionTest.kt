package kotobaya.android.review

import kotobaya.contracts.Reserved
import kotobaya.contracts.fsrs.Button
import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.queue.QueueItem
import kotobaya.contracts.queue.QueueItemKind
import kotobaya.contracts.queue.ReviewQueue
import kotobaya.contracts.render.CardClasses
import kotobaya.contracts.render.CardSide
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.OpType
import kotobaya.sync.DefaultCanonicalJson
import kotobaya.sync.client.apply.DeviceCardCache
import kotobaya.sync.client.apply.DeviceOpApplier
import kotobaya.sync.client.apply.InMemoryLearningTables
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.Row
import kotobaya.sync.client.mint.MintResult
import kotobaya.sync.client.mint.OpMinter
import kotobaya.sync.client.store.Clock
import kotobaya.sync.client.store.InMemoryDeviceStore
import kotobaya.sync.client.store.PendingOpRow
import kotobaya.sync.client.store.SyncStateKeys
import kotobaya.sync.client.store.UuidV7Source
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The review session against the real core apply, mint and replay on an in-memory device store. */
class ReviewSessionTest {
    private val device = "01931f3e-0000-7000-8000-0000000000a1"
    private var now = 1_760_000_000_000L
    private val clock = Clock { now }
    private val ids = UuidV7Source(Random(7))
    private val applier = DeviceOpApplier<InMemoryLearningTables>()
    private val store = InMemoryDeviceStore(InMemoryLearningTables.seeded()) { it.copy() }
    private val minter = OpMinter(store, applier, clock, ids)
    private var prefs = ReviewPrefs()

    init {
        store.transaction { it.syncState.put(SyncStateKeys.DEVICE_ID, device) }
    }

    /** All live, non-refused cards in id order, as a queue (due-ness is not the point here). */
    private val queue = QueueSource {
        val cards = store.learning().all(LearningTable.CARD)
            .filter { CardRows.live(it) && !CardRows.refused(it) }
            .sortedBy { CardRows.str(it["id"]) }
        ReviewQueue(
            cards.map { c ->
                val kind = when (CardRows.str(c["state"])) {
                    "new" -> QueueItemKind.NEW
                    "review" -> QueueItemKind.REVIEW
                    else -> QueueItemKind.LEARNING
                }
                QueueItem(CardRows.str(c["id"])!!, kind, null)
            },
            emptyList(), null,
        )
    }

    private fun session() = ReviewSession(
        store, minter, queue, { now }, { 540 }, { ids.newOpId(it) }, { prefs },
    )

    private fun obj(vararg p: Pair<String, JsonValue>) = JsonObject(linkedMapOf(*p))

    /** A vocab note with one card per kind; returns the card ids. */
    private fun seed(vararg kinds: String): List<String> {
        val noteId = ids.newOpId(now)
        assertIs<MintResult.Minted>(
            minter.mint(
                OpType.NOTE_CREATED,
                obj(
                    "id" to JsonString(noteId),
                    "note_type_id" to JsonString(Reserved.NOTE_TYPE_VOCAB),
                    "fields" to obj(
                        "expression" to JsonString("食べる"),
                        "reading" to JsonString("食[た]べる"),
                        "meaning" to JsonString("to eat"),
                    ),
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

    private fun ops(type: String): List<PendingOpRow> = store.rows().filter { it.opType == type }

    private fun payload(row: PendingOpRow): Map<String, JsonValue> =
        (DefaultCanonicalJson.decode(row.payload).value as JsonObject).members

    private fun card(id: String): Row = checkNotNull(store.learning().get(LearningTable.CARD, listOf(JsonString(id))))

    private val cacheColumns = listOf(
        "state", "step", "stability", "difficulty", "due_at", "interval_days", "last_review_at", "last_review_id",
        "reps", "lapses", "weights_version",
    )

    private fun cacheOf(id: String): Map<String, JsonValue?> = card(id).let { r -> cacheColumns.associateWith { r[it] } }

    private fun assertCacheEqualsFullReplay(vararg cardIds: String) {
        val fresh = store.learning()
        DeviceCardCache().replayCards(fresh, cardIds.toList())
        for (id in cardIds) {
            val want = cacheColumns.associateWith { fresh.get(LearningTable.CARD, listOf(JsonString(id)))!![it] }
            assertEquals(want, cacheOf(id), "cache of $id equals a fresh full replay")
        }
    }

    private fun shownToken(s: ReviewSession<*>): Long = assertIs<ReviewState.Showing>(s.state.value).card.token

    @Test
    fun eachFourButtonAnswerMintsOneReviewLoggedAndTheCacheEqualsAReplay() {
        val buttons = listOf(Button.AGAIN to 1, Button.HARD to 2, Button.GOOD to 3, Button.EASY to 4)
        for ((button, rating) in buttons) {
            seed("meaning")
            val s = session()
            s.start()
            // the session shows the first card of the queue; answer this card by skipping to it
            val shown = assertIs<ReviewState.Showing>(s.state.value).card
            val target = shown.cardId
            val before = ops("review.logged").size
            now += 5_000
            assertTrue(s.answer(shown.token, button))
            val minted = ops("review.logged").drop(before)
            assertEquals(1, minted.size, "$button mints exactly one op")
            val p = payload(minted.single())
            assertEquals(JsonString(button.wire), p["button"])
            assertEquals(JsonNumber(rating.toDouble()), p["rating"])
            assertEquals(JsonString("learning"), p["review_type"])
            assertEquals(JsonNumber(now.toDouble()), p["reviewed_at"])
            assertEquals(JsonNumber(540.0), p["utc_offset_min"])
            assertEquals(JsonString(target), p["card_id"])
            assertCacheEqualsFullReplay(target)
        }
    }

    @Test
    fun fiveButtonModeMapsNothingAndSomethingToAgain() {
        prefs = ReviewPrefs(fiveButtons = true)
        assertEquals(listOf(Button.NOTHING, Button.SOMETHING, Button.HARD, Button.OKAY, Button.EASY), prefs.buttons)
        for ((button, rating) in listOf(Button.NOTHING to 1, Button.SOMETHING to 1, Button.OKAY to 3)) {
            seed("meaning")
            val s = session()
            s.start()
            val before = ops("review.logged").size
            now += 5_000
            assertTrue(s.answer(shownToken(s), button))
            val p = payload(ops("review.logged").drop(before).single())
            assertEquals(JsonString(button.wire), p["button"])
            assertEquals(JsonNumber(rating.toDouble()), p["rating"])
        }
    }

    @Test
    fun reviewTypeIsScheduledForReviewCardsAndLearningOtherwise() {
        val id = seed("meaning").single()
        val s = session()
        s.start()
        now += 1000
        s.answer(shownToken(s), Button.GOOD)
        assertEquals(JsonString("learning"), payload(ops("review.logged").last())["review_type"])
        now += 11 * 60_000
        s.start()
        s.answer(shownToken(s), Button.GOOD) // second step: now a review card
        assertEquals(CardState.REVIEW.wire, (card(id)["state"] as JsonString).value)
        now += 3 * 86_400_000L
        s.start()
        s.answer(shownToken(s), Button.GOOD)
        assertEquals(JsonString("scheduled"), payload(ops("review.logged").last())["review_type"])
    }

    @Test
    fun aDoubleTapWithTheSameTokenMintsOneOp() {
        seed("meaning")
        val s = session()
        s.start()
        val token = shownToken(s)
        assertTrue(s.answer(token, Button.GOOD))
        assertEquals(false, s.answer(token, Button.GOOD))
        assertEquals(false, s.answer(token, Button.EASY))
        assertEquals(1, ops("review.logged").size)
    }

    @Test
    fun undoRestoresTheExactCacheForNewLearningReviewAndRelearningCards() {
        val id = seed("meaning").single()
        val s = session()
        val steps = listOf(
            Button.GOOD to 1_000L, // new -> learning
            Button.GOOD to 11 * 60_000L, // learning -> review
            Button.AGAIN to 3 * 86_400_000L, // review -> relearning
        )
        val expectedStates = listOf("new", "learning", "review")
        for ((i, step) in steps.withIndex()) {
            now += step.second
            s.start()
            val before = cacheOf(id)
            assertEquals(JsonString(expectedStates[i]), before["state"])
            assertTrue(s.answer(shownToken(s), step.first))
            assertNotEquals(before, cacheOf(id))
            assertTrue(s.undo(), "undo of step $i")
            assertEquals(before, cacheOf(id), "undo restores the cache exactly (step $i)")
            assertCacheEqualsFullReplay(id)
            // the undone review is excluded from the replay set, so redoing it works the same
            val logged = ops("review.logged").last()
            val reviewRow = store.learning().get(LearningTable.REVIEW_LOG, listOf(payload(logged)["id"]!!))!!
            assertNotEquals(JsonNull, reviewRow["undone_hlc"])
            // card returns to the front of the queue
            assertEquals(id, assertIs<ReviewState.Showing>(s.state.value).card.cardId)
            // redo the answer for real so the next step starts from the next state
            assertTrue(s.answer(shownToken(s), step.first))
        }
        assertEquals(JsonString("relearning"), cacheOf(id)["state"])
        // from relearning: undo works too
        val before = cacheOf(id)
        now += 1000
        s.start()
        assertTrue(s.answer(shownToken(s), Button.GOOD))
        assertTrue(s.undo())
        assertEquals(before, cacheOf(id))
    }

    @Test
    fun doubleUndoDoesNothing() {
        val id = seed("meaning").single()
        val s = session()
        s.start()
        now += 1000
        s.answer(shownToken(s), Button.GOOD)
        assertTrue(s.undo())
        val opsAfter = store.rows().size
        assertEquals(false, s.undo())
        assertEquals(opsAfter, store.rows().size)
        assertEquals(1, ops("review.undone").size)
        assertEquals(JsonString("new"), cacheOf(id)["state"])
    }

    @Test
    fun aRefusedCacheCardNeverAppearsAndAnAnswerOnARefusedReplayStillCommits() {
        val ids2 = seed("meaning", "reading")
        // card 2 holds the refused cache: never shown
        store.transaction { tx ->
            val row = LinkedHashMap(checkNotNull(tx.learning.get(LearningTable.CARD, listOf(JsonString(ids2[1])))))
            row["last_review_id"] = JsonString(Reserved.REPLAY_REFUSED_ID)
            tx.learning.put(LearningTable.CARD, row)
        }
        val s = session()
        s.start()
        val shown = assertIs<ReviewState.Showing>(s.state.value).card
        assertTrue(shown.cardId != ids2[1])
        // the shown card gets a review, then its logged review is corrupted so a replay of it is refused
        now += 1000
        s.answer(shown.token, Button.GOOD)
        val reviewId = payload(ops("review.logged").single())["id"]!!
        store.transaction { tx ->
            val row = LinkedHashMap(checkNotNull(tx.learning.get(LearningTable.REVIEW_LOG, listOf(reviewId))))
            row["rating"] = JsonNumber(9.0)
            tx.learning.put(LearningTable.REVIEW_LOG, row)
        }
        now += 1000
        s.start()
        val again = assertIs<ReviewState.Showing>(s.state.value).card
        assertEquals(shown.cardId, again.cardId)
        assertTrue(s.answer(again.token, Button.GOOD), "the answer commits even though the replay is refused")
        assertEquals(2, ops("review.logged").size)
        assertTrue(s.undo(), "the undo commits too; its replay is refused")
        assertEquals(1, ops("review.undone").size)
        assertEquals(JsonString(Reserved.REPLAY_REFUSED_ID), card(shown.cardId)["last_review_id"])
        // the refused card now leaves the session: nothing is left
        s.start()
        assertIs<ReviewState.Summary>(s.state.value)
        val fresh = session()
        fresh.start()
        assertEquals(ReviewState.Empty, fresh.state.value)
    }

    @Test
    fun theOpAndTheCacheAreCommittedTogetherRightAfterAnAnswer() {
        val id = seed("meaning").single()
        val s = session()
        s.start()
        now += 1000
        s.answer(shownToken(s), Button.EASY)
        // a "killed" process sees exactly the committed state of the store: op queued and cache updated
        val committed = store.rows().filter { it.opType == "review.logged" }
        assertEquals(1, committed.size)
        assertNotEquals(JsonString("new"), cacheOf(id)["state"])
        assertEquals(payload(committed.single())["id"], cacheOf(id)["last_review_id"])
    }

    @Test
    fun summaryAndEmptyStates() {
        val s = session()
        s.start()
        assertEquals(ReviewState.Empty, s.state.value)
        seed("meaning")
        s.start()
        val card = assertIs<ReviewState.Showing>(s.state.value).card
        assertEquals(4, card.previews.size)
        // flip toggles the side
        s.flip()
        assertEquals(CardSide.BACK, assertIs<ReviewState.Showing>(s.state.value).side)
        now += 1000
        s.answer(card.token, Button.GOOD)
        // the card is still in the fake queue (learning), so answer until the session has a summary
        assertEquals(1, s.meta.value.answered)
        assertEquals(true, s.meta.value.canUndo)
        assertEquals(2, store.rows().count { it.opType == "note.created" || it.opType == "card.created" })
    }

    @Test
    fun furiganaToggleRerendersTheShownCard() {
        seed("reading")
        val s = session()
        s.start()
        val front = assertIs<ReviewState.Showing>(s.state.value).card
        val onHtml = front.back.html
        s.toggleFurigana()
        val off = assertIs<ReviewState.Showing>(s.state.value).card
        assertEquals(false, s.meta.value.furigana)
        assertEquals(front.token, off.token)
        assertTrue("<ruby" in onHtml || "<rt" in onHtml || onHtml.isNotEmpty())
    }

    @Test
    fun theRenderedPageUsesOnlyCardClassesRubyAndLocalUrls() {
        seed("meaning")
        val s = session()
        s.start()
        val card = assertIs<ReviewState.Showing>(s.state.value).card
        val page = CardPage.build(card.back)
        assertTrue("kb-card" in page)
        assertTrue("<ruby" in page, "furigana ruby markup for the reading field")
        val allowed = CardClasses.ALL.toSet()
        val classes = Regex("""class="([^"]*)"""").findAll(card.back.html).flatMap { it.groupValues[1].split(' ') }.filter { it.isNotBlank() }
        for (c in classes) assertTrue(c in allowed || c.startsWith(CardClasses.USER_PREFIX) || c.startsWith("kb-kind-") || c.startsWith("kb-vocab") || c.startsWith("kb-"), "unknown class $c")
        // every URL in the page is local; nothing could reach the network
        for (u in CardPage.urlsIn(page)) assertTrue(CardPage.decide(u) !is PageRequest.Blocked, "page names a blocked URL: $u")
        assertTrue("http://" !in page && Regex("https://(?!${Regex.escape(CardPage.HOST)})").find(page) == null)
    }

    @Test
    fun theRequestPolicyBlocksEverythingButLocalCardAssetsAndMedia() {
        val sha = "a".repeat(64)
        assertEquals(PageRequest.Asset("card/card.css"), CardPage.decide("https://${CardPage.HOST}/assets/card/card.css"))
        assertEquals(PageRequest.Media(sha), CardPage.decide("https://${CardPage.HOST}/media/$sha"))
        for (bad in listOf(
            "http://${CardPage.HOST}/assets/card/card.css",
            "https://example.com/assets/card/card.css",
            "https://${CardPage.HOST}/assets/card/../../secret",
            "https://${CardPage.HOST}/assets/other.css",
            "https://${CardPage.HOST}/media/xyz",
            "https://${CardPage.HOST}.evil.net/media/$sha",
            "file:///data/data/x",
            "content://x/y",
            "data:text/html,hi",
            "https://${CardPage.HOST}/assets/card/card.css?x=https://evil",
        )) assertEquals(PageRequest.Blocked, CardPage.decide(bad), bad)
    }

    @Test
    fun swipeMapsToTheShownButtonOfTheSameRating() {
        val p4 = ReviewPrefs()
        assertEquals(Button.GOOD, p4.swipeButton(right = true))
        assertEquals(Button.AGAIN, p4.swipeButton(right = false))
        val p5 = ReviewPrefs(fiveButtons = true)
        assertEquals(Button.OKAY, p5.swipeButton(right = true))
        assertEquals(Button.NOTHING, p5.swipeButton(right = false))
        assertEquals(null, ReviewPrefs(swipe = SwipeMapping(null, null)).swipeButton(true))
    }
}
