package kotobaya.android.review

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotobaya.android.queue.LocalQueueLoader
import kotobaya.android.sync.SqlLearningTables
import kotobaya.contracts.fsrs.Button
import kotobaya.contracts.queue.QueueItemKind
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.OpType
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.mint.MintResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AND-DECKEND: a deck session at the end of its deck. Phone log (0.1.14): with nothing else due in the deck, every
 * answer of its last learning card ran a "rebuild ahead" holding only that card, which was shown again at once
 * (learn-ahead), and each tap wrote one more review. Production driver and queue loader (Robolectric).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DeckEndTest {
    private val t0 = 1_760_000_000_000L
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val d = AsyncReviewDevice.android(t0, context)
    private val deck = "0193a1b2-0000-7000-8000-00000000d001"
    private var reviewIds = 0

    @After
    fun close() {
        d.close()
        context.deleteDatabase(kotobaya.android.db.APP_DB_FILE)
    }

    private fun obj(vararg p: Pair<String, kotobaya.contracts.sync.JsonValue>) = JsonObject(linkedMapOf(*p))

    /** A deck holding the notes of [cards]. */
    private fun deckOf(vararg cards: String) {
        assertIs<MintResult.Minted>(d.stack.minter.mint(OpType.DECK_CREATED, obj("id" to JsonString(deck), "name" to JsonString("D"), "type" to JsonString("user"))))
        cards.forEachIndexed { i, card ->
            val note = d.store.read { tx -> CardRows.str(CardRows.get(tx.learning, LearningTable.CARD, card)?.get("note_id")) }.orEmpty()
            assertIs<MintResult.Minted>(
                d.stack.minter.mint(
                    OpType.DECK_ITEM_PUT,
                    obj("deck_id" to JsonString(deck), "item_key" to JsonString("n:$note"), "position" to JsonNumber(i + 1.0)),
                ),
            )
        }
    }

    /** Production wiring of ReviewBackend.newSession: a deck session gets the all-decks queue too ([deckId] null: all decks). */
    private fun session(deckId: String? = deck): ReviewSession<SqlLearningTables> {
        val all = LocalQueueLoader(d.db, { d.clock.now }).asQueueSource()
        return ReviewSession(
            d.store, d.stack.minter, if (deckId == null) all else all.forDeck(deckId), { d.clock.now }, { 540 },
            { "0193a1b2-0003-7000-8000-%012d".format(++reviewIds) }, { ReviewPrefs() },
            reads = d.reads, allDue = if (deckId == null) null else all,
        )
    }

    private fun showing(s: ReviewSession<*>): ShownCard = assertIs<ReviewState.Showing>(s.state.value).card

    private fun dueAt(card: String): Long =
        d.store.read { tx -> (CardRows.get(tx.learning, LearningTable.CARD, card)?.get("due_at") as JsonNumber).value.toLong() }

    private fun logged(): List<String?> = d.ops("review.logged").map { d.str(it, "card_id") }

    private fun answer(s: ReviewSession<*>, button: Button): ShownCard {
        val card = showing(s)
        d.clock.now += 2_000
        assertTrue(s.answer(card.token, button), "answer of ${card.cardId} committed")
        return card
    }

    @Test
    fun theDecksLastCardIsNotShownAgainInsideItsStepWhenOtherDecksHaveCardsDue() {
        val (inDeck, elsewhere) = d.seed(2)
        deckOf(inDeck)
        val s = session()
        s.start()
        assertEquals(inDeck, showing(s).cardId)
        answer(s, Button.AGAIN) // learning, due in 1 min: within learn-ahead
        val done = assertIs<ReviewState.DeckDone>(s.state.value, "the deck is done; the card is not served ahead of its step")
        assertTrue(done.othersDue, "another deck has a card due")
        assertEquals(dueAt(inDeck), done.nextLearningDueAt)
        assertEquals(1, done.stats?.answered)
        assertEquals(listOf<String?>(inDeck), logged(), "exactly one review op")
        assertNotEquals(elsewhere, inDeck)
    }

    @Test
    fun theDecksOnlyLearningCardIsNotReservedWhenNothingElseIsDueAnywhere() {
        val (inDeck) = d.seed(1)
        deckOf(inDeck)
        val s = session()
        s.start()
        answer(s, Button.GOOD) // learning step 2, due in 10 min
        val done = assertIs<ReviewState.DeckDone>(s.state.value)
        assertTrue(!done.othersDue)
        assertEquals(dueAt(inDeck), done.nextLearningDueAt)
        assertEquals(listOf<String?>(inDeck), logged())
    }

    @Test
    fun learnAheadInADeckOnlyWhenNothingIsDueAnywhereAndNeverTheCardJustAnswered() {
        val (a, b) = d.seed(2)
        deckOf(a, b)
        val s = session()
        s.start()
        val shown = ArrayList<Pair<String, QueueItemKind>>()
        repeat(6) {
            val st = s.state.value as? ReviewState.Showing ?: return@repeat
            shown += st.card.cardId to st.card.kind
            answer(s, Button.AGAIN)
        }
        // a, b new; then each learning card ahead of time in turn, never the one just answered
        assertEquals(listOf(a to QueueItemKind.NEW, b to QueueItemKind.NEW), shown.take(2))
        assertTrue(shown.zipWithNext().none { (x, y) -> x.first == y.first }, "no card twice in a row: $shown")
        assertEquals(shown.map { it.first }, logged(), "one op per answer")
    }

    @Test
    fun aLearningCardThatFallsDueIsShownAgainAndAnsweredOnce() {
        val (inDeck, _) = d.seed(2)
        deckOf(inDeck)
        val s = session()
        s.start()
        answer(s, Button.AGAIN)
        val done = assertIs<ReviewState.DeckDone>(s.state.value)
        d.clock.now = requireNotNull(done.nextLearningDueAt)
        s.start() // the screen's refresh at the due time
        assertEquals(inDeck to QueueItemKind.LEARNING, showing(s).let { it.cardId to it.kind })
        answer(s, Button.GOOD)
        assertEquals(listOf<String?>(inDeck, inDeck), logged())
    }

    @Test
    fun anEmptyDeckIsDeckDoneAndContinueSwitchesToTheAllDecksQueue() {
        val (inDeck, elsewhere) = d.seed(2)
        deckOf(inDeck)
        val s = session()
        s.start()
        answer(s, Button.EASY) // graduates: nothing of the deck comes back today
        val done = assertIs<ReviewState.DeckDone>(s.state.value)
        assertTrue(done.othersDue)
        assertNull(done.nextLearningDueAt)

        s.continueWithAll()
        assertEquals(elsewhere, showing(s).cardId)
        answer(s, Button.EASY)
        val summary = assertIs<ReviewState.Summary>(s.state.value, "all decks: the ordinary end of a session")
        assertEquals(2, summary.stats.answered)
        assertEquals(listOf<String?>(inDeck, elsewhere), logged())
    }

    @Test
    fun aDeckWithNothingDueStartsAsDeckDone() {
        val (inDeck, _) = d.seed(2)
        deckOf(inDeck)
        val s = session()
        s.start()
        answer(s, Button.EASY)
        val again = session()
        again.start()
        val done = assertIs<ReviewState.DeckDone>(again.state.value)
        assertNull(done.stats)
        assertTrue(done.othersDue)
    }

    @Test
    fun theAllDecksSessionKeepsTheBuilderLearnAheadRule() {
        // QueueBuilder rule 5: "With only L1 -> L1 (served ahead of time)"; unchanged outside deck sessions
        val (only) = d.seed(1)
        val s = session(deckId = null)
        s.start()
        answer(s, Button.AGAIN)
        assertEquals(only to QueueItemKind.LEARNING, showing(s).let { it.cardId to it.kind })
        assertEquals(listOf<String?>(only), logged())
    }
}
