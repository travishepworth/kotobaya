package kotobaya.android.review

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotobaya.android.demo.DemoData
import kotobaya.android.demo.DemoStore
import kotobaya.android.nav.DictTarget
import kotobaya.contracts.dict.KanjiReadingType
import kotobaya.contracts.render.CardSide
import kotobaya.contracts.render.DictionaryData
import kotobaya.contracts.render.KanjiData
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.sync.client.apply.InMemoryLearningTables
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.apply.Row
import kotobaya.contracts.WordStatus
import kotobaya.contracts.dict.KanjiExampleCandidate
import kotobaya.contracts.sync.JsonValue as JV
import kotobaya.dict.DefaultKanjiExamplePicker
import kotobaya.contracts.dict.KanjiReadings
import kotobaya.android.sync.LearningSchema
import kotobaya.android.sync.SqlLearningTables
import kotobaya.android.sync.memoryDriver
import kotlin.random.Random
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/** AND-REVIEW-INFO: example words and deck chips from the local replica; opening the dictionary keeps the revealed card. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ReviewInfoTest {
    private fun s(v: String) = JsonString(v)
    private fun n(v: Long) = JsonNumber(v.toDouble())
    private fun row(vararg p: Pair<String, JsonValue>): Map<String, JsonValue> = mapOf<String, JsonValue>("deleted_at" to JsonNull) + p

    private val words = listOf(
        DictWord(1, "食べる", "たべる", 312),
        DictWord(2, "食事", "しょくじ", 480),
        DictWord(3, "食堂", "しょくどう", 1500),
        DictWord(4, "食品", "しょくひん", 900),
    )

    /** The per-card lookups must be indexed queries: a whole-table read stalls the tap path on a big replica. */
    private fun noScan(t: InMemoryLearningTables): kotobaya.sync.client.apply.LearningTables = object : kotobaya.sync.client.apply.LearningTables by t {
        override fun all(table: LearningTable) = error("whole-table read of $table")
    }

    @Test
    fun examplesFollowThePickerOverTheLocalReplica() {
        val t = InMemoryLearningTables()
        // the user has a note for 食堂 only: it is tier 0 and ON; 食べる is the first KUN word, 食事 fills
        t.put(LearningTable.NOTE, row("id" to s("n1"), "entry_seq" to n(3), "type" to s("vocab")))
        val note = row("id" to s("k"), "type" to s("kanji"), "kanji_literal" to s("食"))
        val kanji = KanjiData("食", listOf("eat"), listOf("ショク", "ジキ"), listOf("く.う", "た.べる"))
        val dict = DictionaryLookup { seq -> DictionaryData("", "", emptyList(), listOf("gloss $seq", "second"), emptyList()) }
        val x = BackExtrasBuilder.build(noScan(t), note, kanji, { words }, dict, BackExtrasIndex.from(t))
        assertEquals(listOf("食堂", "食べる", "食事"), x.examples.map { it.headword })
        assertEquals(listOf(KanjiReadingType.ON, KanjiReadingType.KUN, KanjiReadingType.ON), x.examples.map { it.type })
        assertEquals("gloss 3; second", x.examples.first().gloss)
        // a blacklisted word is never shown
        t.put(LearningTable.WORD_STATUS, row("entry_seq" to n(3), "status" to s("blacklisted")))
        assertEquals(listOf("食べる", "食事", "食品"), BackExtrasBuilder.build(noScan(t), note, kanji, { words }, dict, BackExtrasIndex.from(t)).examples.map { it.headword })
        // no dictionary words: no block
        assertEquals(emptyList(), BackExtrasBuilder.build(noScan(t), note, kanji, { emptyList() }, dict, BackExtrasIndex.from(t)).examples)
    }

    @Test
    fun deckChipsAreInPriorityOrderTopThreePlusMore() {
        val t = InMemoryLearningTables()
        fun deck(id: String, name: String, prio: Int, type: String = "user", deleted: Boolean = false) = t.put(
            LearningTable.DECK,
            mapOf("id" to s(id), "name" to s(name), "priority" to n(prio.toLong()), "type" to s(type), "deleted_at" to if (deleted) n(1) else JsonNull),
        )
        fun item(deckId: String, key: String, entry: Long? = null, gone: Boolean = false) = t.put(
            LearningTable.DECK_ITEM,
            mapOf("deck_id" to s(deckId), "item_key" to s(key), "entry_seq" to (entry?.let(::n) ?: JsonNull), "note_id" to (if (key.startsWith("n:")) s(key.removePrefix("n:")) else JsonNull), "deleted_at" to if (gone) n(1) else JsonNull),
        )
        deck("d-c", "Third", 3); deck("d-a", "First", 1); deck("d-b", "Second", 2); deck("d-e", "Fifth", 9)
        deck("d-s", "Smart", 0, type = "smart"); deck("d-x", "Deleted", 0, deleted = true); deck("d-r", "Removed", 0)
        item("d-c", "e:7", 7); item("d-a", "n:note1"); item("d-b", "e:7", 7); item("d-b", "n:note1") // two items, one deck
        item("d-e", "e:7", 7); item("d-s", "e:7", 7); item("d-x", "e:7", 7); item("d-r", "e:7", 7, gone = true)
        item("d-orphan", "e:7", 7) // deck row missing
        deck("d-m", "Merged", 0); item("d-m", "n:old") // an item of a note merged into this one
        t.put(LearningTable.NOTE, row("id" to s("old"), "type" to s("vocab"), "merged_into_id" to s("note1"), "deleted_at" to n(1)))
        val note = row("id" to s("note1"), "type" to s("vocab"), "entry_seq" to n(7))
        t.put(LearningTable.NOTE, note)
        val x = BackExtrasBuilder.build(noScan(t), note, null, { emptyList() }, { null }, BackExtrasIndex.from(t))
        assertEquals(listOf("Merged", "First", "Second"), x.decks)
        assertEquals(2, x.moreDecks)
        assertEquals(7L, x.entrySeq)
    }

    /** Counts the learning queries of the per-card path (each is a statement + cursor on the device). */
    private class Counting(val t: LearningTables) : LearningTables by t {
        var queries = 0
        override fun get(table: LearningTable, key: List<JV>): Row? = t.get(table, key).also { queries++ }
        override fun where(table: LearningTable, column: String, value: JV): List<Row> = t.where(table, column, value).also { queries++ }
        override fun all(table: LearningTable): List<Row> = error("whole-table read of $table")
    }

    /** A replica with notes, statuses and deck items scattered over the first [n] seqs. */
    private fun replica(n: Int, rnd: Random): InMemoryLearningTables {
        val t = InMemoryLearningTables()
        t.put(LearningTable.DECK, row("id" to s("live"), "name" to s("Live"), "priority" to n(0), "type" to s("user")))
        t.put(LearningTable.DECK, row("id" to s("gone"), "name" to s("Gone"), "priority" to n(0), "type" to s("user"), "deleted_at" to n(1)))
        for (seq in 1L..n) {
            when (rnd.nextInt(8)) {
                0 -> t.put(LearningTable.NOTE, row("id" to s("n$seq"), "type" to s("vocab"), "entry_seq" to n(seq)))
                1 -> t.put(LearningTable.NOTE, row("id" to s("n$seq"), "type" to s("vocab"), "entry_seq" to n(seq), "deleted_at" to n(1)))
                2 -> t.put(LearningTable.DECK_ITEM, row("deck_id" to s("live"), "item_key" to s("e:$seq"), "entry_seq" to n(seq), "note_id" to JsonNull))
                3 -> t.put(LearningTable.DECK_ITEM, row("deck_id" to s("gone"), "item_key" to s("e:$seq"), "entry_seq" to n(seq), "note_id" to JsonNull))
                4 -> t.put(LearningTable.DECK_ITEM, row("deck_id" to s("live"), "item_key" to s("e:$seq"), "entry_seq" to n(seq), "note_id" to JsonNull, "deleted_at" to n(1)))
                else -> {}
            }
            if (rnd.nextInt(3) == 0) t.put(LearningTable.WORD_STATUS, row("entry_seq" to n(seq), "status" to s(WordStatus.entries.random(rnd).wire)))
        }
        return t
    }

    private val shoku = KanjiData("食", listOf("eat"), listOf("ショク", "ジキ"), listOf("く.う", "た.べる"))

    private fun candidates(n: Int, rnd: Random) = (1L..n).map {
        DictWord(it, "食$it", if (rnd.nextBoolean()) "しょく$it" else "た$it", if (rnd.nextInt(4) == 0) null else rnd.nextInt(1, 20_000))
    }

    @Test
    fun theTapPathIssuesTheSameFewQueriesWhateverTheCandidateCount() {
        val rnd = Random(7)
        val t = replica(2_000, rnd)
        val index = BackExtrasIndex.from(t)
        var lookups = 0
        val dict = DictionaryLookup { lookups++; null }
        val kanjiNote = row("id" to s("k"), "type" to s("kanji"), "kanji_literal" to s("食"))
        fun queriesFor(words: List<DictWord>): Int {
            val c = Counting(t)
            lookups = 0
            val x = BackExtrasBuilder.build(c, kanjiNote, shoku, { words }, dict, index)
            assertEquals(3, x.examples.size)
            assertTrue(lookups <= 3, "glosses only for the picks: $lookups")
            return c.queries
        }
        val few = queriesFor(candidates(3, rnd))
        val many = queriesFor(candidates(2_000, rnd))
        assertEquals(few, many)
        assertEquals(0, many) // a kanji note has no deck chips: no learning query at all
        // a vocab card: deck items by note / entry, decks by id; no merged-note scan
        val c = Counting(t)
        BackExtrasBuilder.build(c, row("id" to s("v"), "type" to s("vocab"), "entry_seq" to n(9)), null, { candidates(2_000, rnd) }, dict, index)
        assertTrue(c.queries <= 4, "vocab card queries: ${c.queries}")
    }

    /** The attempt-2 per-candidate lookups, kept as the reference the per-build index must agree with. */
    private fun referenceCandidates(t: LearningTables, words: List<DictWord>): List<KanjiExampleCandidate> {
        fun live(r: Row) = (r["deleted_at"] ?: JsonNull) == JsonNull
        return words.map { w ->
            val seq = n(w.seq)
            val status = t.get(LearningTable.WORD_STATUS, listOf(seq))?.let { r -> WordStatus.entries.firstOrNull { it.wire == CardRows.str(r["status"]) } } ?: WordStatus.NEW
            val hasNote = t.where(LearningTable.NOTE, "entry_seq", seq).any(::live)
            val inDeck = t.where(LearningTable.DECK_ITEM, "entry_seq", seq).any { live(it) && CardRows.str(it["deck_id"])?.let { d -> t.get(LearningTable.DECK, listOf(s(d)))?.let(::live) } == true }
            KanjiExampleCandidate(w.seq, w.headword, w.reading, w.freqRank, status, hasNote, inDeck)
        }
    }

    @Test
    fun theIndexGivesTheSamePicksAsPerCandidateLookups() {
        repeat(25) { round ->
            val rnd = Random(round)
            val t = replica(400, rnd)
            val index = BackExtrasIndex.from(t)
            val words = candidates(400, rnd).shuffled(rnd).take(rnd.nextInt(1, 400))
            val readings = KanjiReadings("食", shoku.onyomi, shoku.kunyomi)
            val want = DefaultKanjiExamplePicker.pick(readings, referenceCandidates(t, words)).map { it.candidate.seq to it.readingType }
            val got = BackExtrasBuilder.build(noScan(t), row("id" to s("k"), "type" to s("kanji"), "kanji_literal" to s("食")), shoku, { words }, { null }, index)
                .examples.map { it.seq to it.type }
            assertEquals(want, got, "round $round")
        }
    }

    private fun kanjiDeckFixture(): InMemoryLearningTables {
        val t = InMemoryLearningTables()
        fun deck(id: String, name: String, prio: Int) = t.put(
            LearningTable.DECK, mapOf("id" to s(id), "name" to s(name), "priority" to n(prio.toLong()), "type" to s("user"), "deleted_at" to JsonNull),
        )
        fun item(deckId: String, seq: Long, gone: Boolean = false) = t.put(
            LearningTable.DECK_ITEM,
            mapOf("deck_id" to s(deckId), "item_key" to s("e:$seq"), "entry_seq" to n(seq), "note_id" to JsonNull, "deleted_at" to if (gone) n(1) else JsonNull),
        )
        deck("a", "Core 10k", 1); deck("b", "Mine", 2)
        // words 1 = 日本, 2 = 日曜, 3 = 本
        item("a", 1); item("a", 2); item("b", 1); item("b", 3); item("a", 3, gone = true)
        return t
    }

    private val kanjiWords = KanjiWordSource { lit ->
        when (lit) {
            "日" -> listOf(DictWord(1, "日本", "にほん", null), DictWord(2, "日曜", "にちよう", null))
            "本" -> listOf(DictWord(1, "日本", "にほん", null), DictWord(3, "本", "ほん", null))
            else -> emptyList()
        }
    }

    @Test
    fun aKanjiAppearsInTheDecksOfTheWordsContainingIt() {
        val index = BackExtrasIndex.from(kanjiDeckFixture())
        assertEquals(listOf(KanjiDeck("a", "Core 10k", 2), KanjiDeck("b", "Mine", 1)), BackExtrasBuilder.kanjiDecksOf("日", kanjiWords, index))
        // the removed item (a, 3) does not count
        assertEquals(listOf(KanjiDeck("a", "Core 10k", 1), KanjiDeck("b", "Mine", 2)), BackExtrasBuilder.kanjiDecksOf("本", kanjiWords, index))
        assertEquals(emptyList(), BackExtrasBuilder.kanjiDecksOf("火", kanjiWords, index))
    }

    @Test
    fun theKanjiDeckLookupIsComputedOncePerIndexBuildNotPerCard() {
        val t = kanjiDeckFixture()
        t.put(LearningTable.NOTE, row("id" to s("kn"), "type" to s("kanji"), "kanji_literal" to s("日")))
        t.put(
            LearningTable.DECK_ITEM,
            mapOf("deck_id" to s("b"), "item_key" to s("n:kn"), "entry_seq" to JsonNull, "note_id" to s("kn"), "deleted_at" to JsonNull),
        )
        val index = BackExtrasIndex.from(t)
        val c = Counting(t)
        val note = row("id" to s("kn"), "type" to s("kanji"), "kanji_literal" to s("日"))
        repeat(50) { BackExtrasBuilder.build(c, note, shoku, kanjiWords, { null }, index) }
        assertEquals(0, c.queries)
        val x = BackExtrasBuilder.build(c, note, shoku, kanjiWords, { null }, index)
        assertEquals(listOf(KanjiDeck("a", "Core 10k", 2), KanjiDeck("b", "Mine", 2)), x.kanjiDecks) // b: 日本 + the kanji's own note
    }

    @Test
    fun theDeviceIndexGivesTheSameKanjiDecks() {
        val driver = memoryDriver()
        val t = SqlLearningTables(driver, LearningSchema.read(driver))
        fun deck(id: String, prio: Int, type: String = "user") = t.put(
            LearningTable.DECK,
            mapOf("id" to s(id), "name" to s("N$id"), "type" to s(type), "priority" to n(prio.toLong()), "created_at" to n(1), "updated_at" to n(1), "deleted_at" to JsonNull),
        )
        fun item(deck: String, seq: Long, gone: Boolean = false) = t.put(
            LearningTable.DECK_ITEM,
            mapOf("deck_id" to s(deck), "item_key" to s("e:$seq"), "entry_seq" to n(seq), "note_id" to JsonNull, "added_at" to n(1), "deleted_at" to if (gone) n(2) else JsonNull),
        )
        deck("a", 1); deck("b", 2); deck("s", 0, "smart")
        item("a", 1); item("a", 2); item("b", 1); item("b", 3); item("a", 3, gone = true); item("s", 1)
        val want = BackExtrasIndex.from(t)
        val got = SqlBackExtrasIndex(driver).load()
        for (lit in listOf("日", "本", "火")) {
            assertEquals(BackExtrasBuilder.kanjiDecksOf(lit, kanjiWords, want), BackExtrasBuilder.kanjiDecksOf(lit, kanjiWords, got), lit)
        }
        assertEquals(listOf(KanjiDeck("a", "Na", 2), KanjiDeck("b", "Nb", 1)), BackExtrasBuilder.kanjiDecksOf("日", kanjiWords, got))
    }

    @Test
    fun theDeviceIndexReadsTheSameAsTheReplicaRules() {
        val driver = memoryDriver()
        val t = SqlLearningTables(driver, LearningSchema.read(driver))
        fun note(id: String, seq: Long?, deleted: Boolean = false, into: String? = null) = t.put(
            LearningTable.NOTE,
            mapOf(
                "id" to s(id), "note_type_id" to s("nt"), "type" to s("vocab"), "entry_seq" to (seq?.let(::n) ?: JsonNull),
                "created_by_device_id" to s("dev"), "created_at" to n(1), "updated_at" to n(1),
                "deleted_at" to if (deleted) n(2) else JsonNull, "merged_into_id" to (into?.let(::s) ?: JsonNull),
            ),
        )
        fun status(seq: Long, st: String) = t.put(
            LearningTable.WORD_STATUS,
            mapOf(
                "entry_seq" to n(seq), "status" to s(st), "updated_at" to n(1),
                "auto_status" to if (st in setOf("new", "learning", "known")) s(st) else JsonNull,
                "override_status" to if (st in setOf("never_forget", "blacklisted", "ignored")) s(st) else JsonNull,
                "override_hlc" to if (st in setOf("never_forget", "blacklisted", "ignored")) s("h") else JsonNull,
            ),
        )
        fun deck(id: String, deleted: Boolean = false) = t.put(
            LearningTable.DECK,
            mapOf("id" to s(id), "name" to s(id), "type" to s("user"), "created_at" to n(1), "updated_at" to n(1), "deleted_at" to if (deleted) n(2) else JsonNull),
        )
        fun item(deck: String, seq: Long, gone: Boolean = false) = t.put(
            LearningTable.DECK_ITEM,
            mapOf("deck_id" to s(deck), "item_key" to s("e:$seq"), "entry_seq" to n(seq), "note_id" to JsonNull, "added_at" to n(1), "deleted_at" to if (gone) n(2) else JsonNull),
        )
        note("a", 1); note("b", 2, deleted = true); note("c", null, deleted = true, into = "a"); note("d", 1)
        status(1, "known"); status(2, "new"); status(3, "blacklisted"); status(4, "learning")
        deck("live"); deck("gone", deleted = true)
        item("live", 5); item("gone", 6); item("live", 7, gone = true); item("missing", 8)
        val want = BackExtrasIndex.from(t)
        val got = SqlBackExtrasIndex(driver).load()
        for (seq in 0L..9L) {
            assertEquals(want.status(seq), got.status(seq), "status $seq")
            assertEquals(want.hasNote(seq), got.hasNote(seq), "note $seq")
            assertEquals(want.inDeck(seq), got.inDeck(seq), "deck $seq")
        }
        assertEquals(listOf("c"), got.mergedInto("a"))
        assertEquals(want.mergedInto("a"), got.mergedInto("a"))
        assertEquals(listOf(WordStatus.KNOWN, WordStatus.NEW, WordStatus.BLACKLISTED), listOf(1L, 2L, 3L).map(got::status))
        assertEquals(listOf(true, false), listOf(1L, 2L).map(got::hasNote))
        assertEquals(listOf(true, false, false, false), listOf(5L, 6L, 7L, 8L).map(got::inDeck))
    }

    @Test
    fun openInDictionaryKeepsTheRevealedCardAndWritesNothing() {
        val assets = ApplicationProvider.getApplicationContext<Application>().assets
        val backend = DemoStore(DemoData.load(assets)).reviewBackend()
        var jumped: DictTarget? = null
        val vm = ReviewViewModel(backend, io = Dispatchers.Unconfined, jump = { jumped = it })
        vm.enter()
        vm.flip()
        val before = assertIs<ReviewState.Showing>(vm.state.value.review)
        assertEquals(CardSide.BACK, before.side)
        val answered = vm.state.value.meta.answered
        vm.openInDictionary(BackExtras(entrySeq = 42))
        assertEquals(DictTarget.Word(42), jumped)
        vm.leave() // the review page goes under the dictionary
        vm.enter() // and comes back
        val after = assertIs<ReviewState.Showing>(vm.state.value.review)
        assertEquals(before.card.token, after.card.token)
        assertEquals(CardSide.BACK, after.side)
        assertEquals(answered, vm.state.value.meta.answered)
        vm.leave() // a real leave ends the session as before
        vm.enter()
        assertEquals(CardSide.FRONT, assertIs<ReviewState.Showing>(vm.state.value.review).side)
        vm.leave()
    }

    /** AND-KEEPGOING-FIX: a configuration change is not leaving the screen; the session (and keep-going mode) carries on. */
    @Test
    fun aConfigurationChangeKeepsTheSession() {
        val assets = ApplicationProvider.getApplicationContext<Application>().assets
        val backend = DemoStore(DemoData.load(assets)).reviewBackend()
        val vm = ReviewViewModel(backend, io = Dispatchers.Unconfined)
        vm.enter()
        vm.keepGoing()
        assertTrue(vm.state.value.meta.keepingGoing)
        vm.flip()
        val before = assertIs<ReviewState.Showing>(vm.state.value.review)
        vm.leave(changingConfigurations = true)
        vm.enter()
        val after = assertIs<ReviewState.Showing>(vm.state.value.review)
        assertEquals(before.card.token, after.card.token)
        assertEquals(CardSide.BACK, after.side)
        assertTrue(vm.state.value.meta.keepingGoing, "keep going survives")
        vm.leave() // a real leave ends the visit: the next one starts normally
        vm.enter()
        assertFalse(vm.state.value.meta.keepingGoing)
        vm.leave()
    }
}
