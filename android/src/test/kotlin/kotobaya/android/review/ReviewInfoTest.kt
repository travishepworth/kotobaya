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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
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
        val x = BackExtrasBuilder.build(noScan(t), note, kanji, { words }, dict)
        assertEquals(listOf("食堂", "食べる", "食事"), x.examples.map { it.headword })
        assertEquals(listOf(KanjiReadingType.ON, KanjiReadingType.KUN, KanjiReadingType.ON), x.examples.map { it.type })
        assertEquals("gloss 3; second", x.examples.first().gloss)
        // a blacklisted word is never shown
        t.put(LearningTable.WORD_STATUS, row("entry_seq" to n(3), "status" to s("blacklisted")))
        assertEquals(listOf("食べる", "食事", "食品"), BackExtrasBuilder.build(noScan(t), note, kanji, { words }, dict).examples.map { it.headword })
        // no dictionary words: no block
        assertEquals(emptyList(), BackExtrasBuilder.build(noScan(t), note, kanji, { emptyList() }, dict).examples)
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
        val note = row("id" to s("note1"), "type" to s("vocab"), "entry_seq" to n(7))
        t.put(LearningTable.NOTE, note)
        val x = BackExtrasBuilder.build(noScan(t), note, null, { emptyList() }, { null })
        assertEquals(listOf("First", "Second", "Third"), x.decks)
        assertEquals(1, x.moreDecks)
        assertEquals(7L, x.entrySeq)
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
}
