package kotobaya.android.review

import androidx.test.core.app.ApplicationProvider
import kotobaya.android.notes.CandidateSource
import kotobaya.android.notes.EntryCandidate
import kotobaya.android.notes.EntryMatcher
import kotobaya.android.notes.NoteCreator
import kotobaya.android.queue.LocalQueueLoader
import kotobaya.android.sync.SqlLearningTables
import kotobaya.contracts.fsrs.Button
import kotobaya.contracts.queue.QueueItemKind
import kotobaya.contracts.render.DictionaryData
import kotobaya.contracts.sync.JsonString
import kotobaya.sync.client.store.UuidV7Source
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * AND-KEEPGOING-FIX: deck entries `e:<seq>` without a note are pending entries of the queue; the loader materializes
 * the drawn ones (note.created origin `deck` + card.created through the op path) and builds again, so every consumer
 * of the local queue gets new cards from list/media decks, and keep going draws through them without end.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DeckEntriesTest {
    private val t0 = 1_760_000_000_000L
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val d = AsyncReviewDevice.android(t0, context)
    private var reviewIds = 0
    private val deckId = "01931f3e-0000-7000-8000-00000000d001"

    private val creator = NoteCreator(
        d.store, d.stack.minter,
        EntryMatcher(object : CandidateSource {
            override fun isAvailable() = false
            override fun candidates(text: String): List<EntryCandidate> = emptyList()
        }),
        UuidV7Source(Random(11)), d.clock,
        dictionary = { seq -> if (seq == UNKNOWN_SEQ) null else DictionaryData("語$seq", "ご", emptyList(), listOf("word $seq"), emptyList()) },
    )
    private val loader = LocalQueueLoader(d.db, { d.clock.now }, materializer = creator)

    @After
    fun close() {
        d.close()
        context.deleteDatabase(kotobaya.android.db.APP_DB_FILE)
    }

    private fun setting(key: String, value: String) {
        d.driver.execute(null, "INSERT OR REPLACE INTO settings (key, value, hlc, updated_at) VALUES ('$key', '$value', 'h', 0)", 0)
    }

    /** A list deck (server-created, so not through device ops) holding `e:<seq>` for [seqs], in that order. */
    private fun listDeck(seqs: List<Long>) {
        d.driver.execute(
            null,
            "INSERT INTO deck (id, name, type, created_at, updated_at) VALUES ('$deckId', 'JLPT', 'list', 0, 0)",
            0,
        )
        seqs.forEachIndexed { i, seq ->
            d.driver.execute(
                null,
                "INSERT INTO deck_item (deck_id, item_key, entry_seq, position, added_at) VALUES ('$deckId', 'e:$seq', $seq, ${(i + 1) * 1024}, 0)",
                0,
            )
        }
    }

    private fun session(): ReviewSession<SqlLearningTables> = ReviewSession(
        d.store, d.stack.minter, loader.asQueueSource(), { d.clock.now }, { 540 },
        { "0193a1b2-0003-7000-8000-%012d".format(++reviewIds) }, { ReviewPrefs() },
        reads = d.reads,
    )

    private fun deckNotes(): List<Pair<Long, String>> = d.ops("note.created")
        .filter { d.str(it, "origin") == NoteCreator.DECK_ORIGIN }
        .map { ((d.payload(it)["entry_seq"] as kotobaya.contracts.sync.JsonNumber).value.toLong()) to checkNotNull(d.str(it, "id")) }

    @Test
    fun drawnDeckEntriesBecomeNotesAndCardsThroughOps() {
        setting("scheduler.new_per_day", "5")
        setting("scheduler.new_per_session", "10")
        // entry 1001 is known (never introduced); UNKNOWN_SEQ is not in the dictionary (skipped, not created empty)
        listDeck(listOf(1000L, 1001L, UNKNOWN_SEQ) + (1002L until 1030L))
        d.driver.execute(
            null,
            "INSERT INTO word_status (entry_seq, status, override_status, override_hlc, override_origin, updated_at) " +
                "VALUES (1001, 'known', 'known', 'h', 'manual', 0)",
            0,
        )

        val loaded = loader.load()
        val notes = deckNotes()
        assertEquals(listOf(1000L, 1002L, 1003L, 1004L), notes.map { it.first }, "the drawn entries, in deck order")
        assertTrue(notes.all { (_, id) -> d.ops("card.created").any { d.str(it, "note_id") == id } }, "each note has cards")
        val newItems = loaded.queue.items.filter { it.kind == QueueItemKind.NEW }
        assertEquals(4, newItems.size, "budget 5: four created now, the unknown entry still pending")
        assertEquals(5, loaded.counts.new)
        assertEquals(listOf(UNKNOWN_SEQ), loaded.queue.toMaterialize.map { it.entrySeq })
        d.store.read { tx ->
            for ((_, id) in notes) {
                val note = checkNotNull(tx.learning.get(kotobaya.sync.client.apply.LearningTable.NOTE, listOf(JsonString(id))))
                assertEquals(JsonString(NoteCreator.DECK_ORIGIN), note["origin"])
            }
        }

        // building again creates nothing new: the entries have live notes now
        val before = d.ops("note.created").size
        loader.load()
        assertEquals(before, d.ops("note.created").size)
    }

    @Test
    fun keepGoingDrawsThroughMoreThanFortyDeckEntries() {
        setting("scheduler.new_per_day", "20")
        setting("scheduler.new_per_session", "10")
        val seqs = (2000L until 2060L).toList()
        listDeck(seqs)
        val s = session()
        s.start()
        val normal = ArrayList<String>()
        while (s.state.value is ReviewState.Showing) {
            val card = assertIs<ReviewState.Showing>(s.state.value).card
            d.clock.now += 2_000
            assertTrue(s.answer(card.token, Button.GOOD))
            normal += card.cardId
        }
        assertIs<ReviewState.Summary>(s.state.value)
        assertTrue(s.meta.value.moreAvailable, "Keep going offered")

        s.startKeepGoing()
        val kept = ArrayList<String>()
        while (s.state.value is ReviewState.Showing) {
            val card = assertIs<ReviewState.Showing>(s.state.value).card
            d.clock.now += 2_000
            assertTrue(s.answer(card.token, Button.GOOD))
            kept += card.cardId
            check(kept.size < 2_000) { "keep going did not end" }
        }
        val created = deckNotes()
        assertEquals(seqs, created.map { it.first }.sorted(), "every entry became a note")
        val served = (normal + kept).toSet()
        val noteOfCard = d.ops("card.created").associate { checkNotNull(d.str(it, "id")) to checkNotNull(d.str(it, "note_id")) }
        val servedNotes = served.mapNotNullTo(HashSet()) { noteOfCard[it] }
        assertEquals(created.map { it.second }.toSet(), servedNotes, "a card of every entry was studied")
        assertTrue(s.meta.value.keepingGoing)
        assertFalse(s.meta.value.moreAvailable)
        val left = loader.load(keepGoing = true).queue
        assertTrue(left.items.isEmpty() && left.toMaterialize.isEmpty())
    }

    /**
     * The phone report "the same 2 cards over and over": while keeping going, a card graded Again comes back within
     * learn-ahead. It must not be served again before the next batch of new cards is loaded.
     */
    @Test
    fun aFailedCardDoesNotCrowdOutTheNextBatch() {
        setting("scheduler.new_per_day", "1")
        listDeck((3000L until 3030L).toList())
        val s = session()
        s.start()
        while (s.state.value is ReviewState.Showing) {
            val card = assertIs<ReviewState.Showing>(s.state.value).card
            d.clock.now += 2_000
            s.answer(card.token, Button.GOOD)
        }
        s.startKeepGoing()
        val shown = ArrayList<ShownCard>()
        var failed: String? = null
        while (s.state.value is ReviewState.Showing && shown.size < 300) {
            val card = assertIs<ReviewState.Showing>(s.state.value).card
            shown += card
            d.clock.now += 2_000
            // the first card is failed every time it is shown; everything else is Good
            if (failed == null) failed = card.cardId
            s.answer(card.token, if (card.cardId == failed) Button.AGAIN else Button.GOOD)
        }
        val newShown = shown.count { it.kind == QueueItemKind.NEW }
        assertTrue(newShown >= 29, "all new cards came: $newShown")
        // rule 5: learn-ahead comes after new cards. The failed card (due again within learn-ahead, never due yet at 2 s
        // per answer) waits until no new card is left, batch boundaries included; only then it is served ahead of time.
        val firstRepeat = shown.indexOfFirst { it.cardId == failed && it !== shown.first() }
        assertTrue(firstRepeat > 0, "served again once nothing else was left")
        assertTrue(shown.subList(firstRepeat, shown.size).none { it.kind == QueueItemKind.NEW }, "no new card after the first repeat")
    }

    private companion object {
        const val UNKNOWN_SEQ = 1999L
    }
}
