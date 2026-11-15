package kotobaya.android.review

import androidx.test.core.app.ApplicationProvider
import kotobaya.android.notes.CandidateSource
import kotobaya.android.notes.EntryCandidate
import kotobaya.android.notes.EntryMatcher
import kotobaya.android.notes.NoteCreator
import kotobaya.android.queue.EntryMaterializer
import kotobaya.android.queue.EntryRef
import kotobaya.android.queue.LocalQueueLoader
import kotobaya.android.queue.materializeAllowed
import kotobaya.android.sync.SqlLearningTables
import kotobaya.contracts.fsrs.Button
import kotobaya.contracts.queue.QueueItemKind
import kotobaya.contracts.render.DictionaryData
import kotobaya.contracts.sync.JsonString
import kotobaya.sync.client.store.UuidV7Source
import kotobaya.sync.client.store.bookkeeping
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
        dictionary = { seq ->
            if (seq == UNKNOWN_SEQ || seq in UNKNOWN_RANGE) null
            else DictionaryData(expressions[seq] ?: "語$seq", "ご", emptyList(), listOf("word $seq"), emptyList())
        },
    )
    private val expressions = HashMap<Long, String>()

    /** The materializer as production gates it ([syncReady] stands in for the sync coordinator's state). */
    private var syncReady = true
    private val gated = object : EntryMaterializer {
        override fun materialize(entries: List<kotobaya.contracts.queue.PendingEntry>) = creator.materialize(entries)
        override fun plan(entries: List<EntryRef>, burySiblings: Boolean) = creator.plan(entries, burySiblings)
        override fun ready() = creator.ready() && syncReady
    }

    /** As production: a deck note (fields {}) takes its expression kanji from the dictionary headword. */
    private val loader = LocalQueueLoader(
        d.db, { d.clock.now },
        headwordOf = { seq -> if (seq == UNKNOWN_SEQ || seq in UNKNOWN_RANGE) null else expressions[seq] ?: "語$seq" },
        materializer = gated,
    )

    @After
    fun close() {
        d.close()
        context.deleteDatabase(kotobaya.android.db.APP_DB_FILE)
    }

    private fun setting(key: String, value: String) {
        d.driver.execute(null, "INSERT OR REPLACE INTO settings (key, value, hlc, updated_at) VALUES ('$key', '$value', 'h', 0)", 0)
    }

    /** A list deck (server-created, so not through device ops) holding `e:<seq>` for [seqs], in that order. */
    private fun listDeck(seqs: List<Long>, settings: String = "{}") {
        d.driver.execute(
            null,
            "INSERT INTO deck (id, name, type, settings, created_at, updated_at) VALUES ('$deckId', 'JLPT', 'list', '$settings', 0, 0)",
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
        // entry 1001 is known (never introduced); UNKNOWN_SEQ is not in the dictionary (never pending: uses no budget)
        listDeck(listOf(1000L, 1001L, UNKNOWN_SEQ) + (1002L until 1030L))
        d.driver.execute(
            null,
            "INSERT INTO word_status (entry_seq, status, override_status, override_hlc, override_origin, updated_at) " +
                "VALUES (1001, 'known', 'known', 'h', 'manual', 0)",
            0,
        )

        val loaded = loader.load()
        val notes = deckNotes()
        assertEquals(listOf(1000L, 1002L, 1003L, 1004L, 1005L), notes.map { it.first }, "the drawn entries, in deck order")
        assertTrue(notes.all { (_, id) -> d.ops("card.created").any { d.str(it, "note_id") == id } }, "each note has cards")
        val newItems = loaded.queue.items.filter { it.kind == QueueItemKind.NEW }
        assertEquals(5, newItems.size, "budget 5; the unknown entry used none of it")
        assertEquals(5, loaded.counts.new)
        assertTrue(loaded.queue.toMaterialize.isEmpty())
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

    /** Review finding 1: entries the dictionary lacks never use the new budget, so the deck's later entries come. */
    @Test
    fun entriesMissingFromTheDictionaryDoNotStarveTheRest() {
        setting("scheduler.new_per_day", "5")
        listDeck(UNKNOWN_RANGE.toList() + (4100L until 4110L))
        val loaded = loader.load()
        assertEquals((4100L until 4105L).toList(), deckNotes().map { it.first })
        assertEquals(5, loaded.queue.items.count { it.kind == QueueItemKind.NEW })
        assertEquals(5, loaded.counts.new)
        assertTrue(loaded.queue.toMaterialize.isEmpty())
    }

    /** Review finding 2: nothing is materialized while a pull is incomplete; the queue still builds, without entries. */
    @Test
    fun nothingIsMaterializedUntilAPullHasCompleted() {
        setting("scheduler.new_per_day", "5")
        listDeck((4200L until 4210L).toList())
        syncReady = false
        val waiting = loader.load()
        assertTrue(deckNotes().isEmpty(), "no notes minted mid-pull")
        assertTrue(waiting.queue.toMaterialize.isEmpty() && waiting.queue.items.isEmpty())
        assertEquals(0, waiting.counts.new)
        syncReady = true
        assertEquals(5, loader.load().queue.items.count { it.kind == QueueItemKind.NEW })

        // the production gate: a paired device only after a complete pull, nothing running, no failure, no rebuild
        assertTrue(materializeAllowed(paired = false, syncRunning = true, failures = 3, lastSyncAt = null, rebuildPending = true))
        assertTrue(materializeAllowed(paired = true, syncRunning = false, failures = 0, lastSyncAt = 1, rebuildPending = false))
        assertFalse(materializeAllowed(paired = true, syncRunning = false, failures = 0, lastSyncAt = null, rebuildPending = false), "never pulled")
        assertFalse(materializeAllowed(paired = true, syncRunning = true, failures = 0, lastSyncAt = 1, rebuildPending = false), "pulling")
        assertFalse(materializeAllowed(paired = true, syncRunning = false, failures = 1, lastSyncAt = 1, rebuildPending = false), "cut off")
        assertFalse(materializeAllowed(paired = true, syncRunning = false, failures = 0, lastSyncAt = 1, rebuildPending = true), "rebuild")
    }

    /**
     * C-27: the ops are the normative ones (fields {}, origin deck, every kind of kindsForNewNote), and a user-deleted
     * note drops its entry for good while a merge loser does not count as deleted.
     */
    @Test
    fun opsFollowC27AndAUserDeletedNoteIsNotRecreated() {
        setting("scheduler.new_per_day", "1")
        listDeck((4500L until 4510L).toList())
        loader.load()
        val (seq, noteId) = deckNotes().single()
        assertEquals(4500L, seq)
        val created = d.ops("note.created").single { d.str(it, "id") == noteId }
        assertEquals(kotobaya.contracts.sync.JsonObject(emptyMap()), d.payload(created)["fields"])
        assertEquals(defaultVocabKinds(4500L), kindsOf(noteId))

        // the user deletes the word's note: the entry is gone for good, the next one is drawn instead
        assertIs<kotobaya.sync.client.mint.MintResult.Minted>(
            d.stack.minter.mint(
                kotobaya.contracts.sync.OpType.NOTE_DELETED,
                kotobaya.contracts.sync.JsonObject(linkedMapOf("id" to JsonString(noteId))),
            ),
        )
        d.clock.now += 86_400_000
        loader.load()
        assertEquals(listOf(4500L, 4501L), deckNotes().map { it.first })
    }

    /** A refused mint (rebuild pending) creates nothing; the load still returns the queue. */
    @Test
    fun aRefusedMintStillReturnsTheQueue() {
        setting("scheduler.new_per_day", "3")
        listDeck((4300L until 4310L).toList())
        d.seed(1)
        d.store.transaction { it.bookkeeping.rebuildPending = true }
        val loaded = loader.load()
        assertTrue(deckNotes().isEmpty())
        assertTrue(loaded.queue.toMaterialize.isNotEmpty(), "drawn, not created")
        assertEquals(3, loaded.queue.toMaterialize.size + loaded.queue.items.count { it.kind == QueueItemKind.NEW })
        assertEquals(3, loaded.counts.new)
    }

    /** add_kanji: two words sharing a kanji in one mint get one kanji note for it. */
    @Test
    fun addKanjiCreatesEachKanjiOnceWithinOneMint() {
        setting("scheduler.new_per_day", "10")
        expressions[4400L] = "日本"
        expressions[4401L] = "日曜"
        listDeck(listOf(4400L, 4401L), settings = """{"add_kanji":"before"}""")
        loader.load()
        assertEquals(listOf("日", "本", "曜"), d.ops("note.created").mapNotNull { d.str(it, "kanji_literal") })
        assertEquals(2, deckNotes().size)
    }

    /** C-27 (final): with add_kanji, an entry is drawn as its whole group; one that does not fit is never created. */
    @Test
    fun anAddKanjiGroupThatDoesNotFitIsNeverMaterialized() {
        setting("scheduler.new_per_day", "1")
        expressions[5000L] = "日本"
        expressions[5001L] = "すし"
        listDeck(listOf(5000L, 5001L, 5002L), settings = """{"add_kanji":"before"}""")
        repeat(3) { round ->
            val loaded = loader.load()
            val noteOf = loaded.input.candidates.associate { it.cardId to it.noteId }
            val shownNotes = loaded.queue.items.mapNotNullTo(HashSet()) { noteOf[it.cardId] }
            assertTrue(deckNotes().all { (_, id) -> id in shownNotes }, "round $round: every created note is in the queue")
            assertTrue(loaded.queue.toMaterialize.isEmpty())
            d.clock.now += 1_000
        }
        assertEquals(listOf(5001L), deckNotes().map { it.first }, "日本 (1 + 2 kanji cards) never fits an allowance of 1")
        assertTrue(d.ops("note.created").none { d.str(it, "kanji_literal") != null }, "no kanji note created for it")
    }

    /** C-27: a kanji note the user deleted is never re-created by add_kanji. */
    @Test
    fun aUserDeletedKanjiNoteIsNotRecreated() {
        setting("scheduler.new_per_day", "10")
        val kanjiNote = "01931f3e-0000-7000-8000-00000000c001"
        assertIs<kotobaya.sync.client.mint.MintResult.Minted>(
            d.stack.minter.mint(
                kotobaya.contracts.sync.OpType.NOTE_CREATED,
                kotobaya.contracts.sync.JsonObject(
                    linkedMapOf(
                        "id" to JsonString(kanjiNote),
                        "note_type_id" to JsonString(NoteCreator.KANJI_NOTE_TYPE_ID),
                        "kanji_literal" to JsonString("日"),
                    ),
                ),
            ),
        )
        assertIs<kotobaya.sync.client.mint.MintResult.Minted>(
            d.stack.minter.mint(kotobaya.contracts.sync.OpType.NOTE_DELETED, kotobaya.contracts.sync.JsonObject(linkedMapOf("id" to JsonString(kanjiNote)))),
        )
        expressions[5100L] = "日本"
        listDeck(listOf(5100L), settings = """{"add_kanji":"before"}""")
        loader.load()
        assertEquals(listOf(5100L), deckNotes().map { it.first })
        assertEquals(listOf("日", "本"), d.ops("note.created").mapNotNull { d.str(it, "kanji_literal") }, "only 本 is new")
    }

    /** C-27: deck card_kinds [] (or no valid kind) falls back to the vocab type's default kinds. */
    @Test
    fun emptyCardKindsFallBackToTheDefaultKinds() {
        setting("scheduler.new_per_day", "10")
        listDeck(listOf(5200L), settings = """{"card_kinds":[]}""")
        loader.load()
        val (_, noteId) = deckNotes().single()
        assertEquals(defaultVocabKinds(5200L), kindsOf(noteId))
        assertTrue(kindsOf(noteId).isNotEmpty())
    }

    private fun kindsOf(noteId: String): List<String> =
        d.ops("card.created").filter { d.str(it, "note_id") == noteId }.mapNotNull { d.str(it, "kind") }.sorted()

    /** The vocab type's default kinds minus those whose front renders empty for the entry (C-27 step 2). */
    private fun defaultVocabKinds(seq: Long): List<String> {
        val type = kotobaya.cards.BuiltInNoteTypes.VOCAB.info
        val dict = DictionaryData(expressions[seq] ?: "語$seq", "ご", emptyList(), listOf("word $seq"), emptyList())
        return kotobaya.cards.DefaultCardGenerator.kindsForNewNote(type, null)
            .filterNot { kotobaya.render.DefaultCardRenderer.frontIsEmpty(type, it, emptyMap(), dictionary = dict) }.sorted()
    }

    private companion object {
        const val UNKNOWN_SEQ = 1999L
        val UNKNOWN_RANGE = 4000L until 4030L
    }
}
