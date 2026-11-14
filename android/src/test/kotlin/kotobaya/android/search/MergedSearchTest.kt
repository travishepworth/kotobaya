package kotobaya.android.search

import kotlinx.coroutines.Dispatchers
import kotobaya.android.word.NoteLoad
import kotobaya.android.word.WordLoad
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AND-LABELS: one query, one list; real reading / meaning on card rows. */
class MergedSearchTest {
    private fun hit(note: String, entry: Long?, title: String, reading: String = "", meaning: String = "") =
        CardHit("c-$note", note, "meaning", "review", false, entry, title, reading, meaning)

    private class Port(val cards: List<CardHit>) : SearchPort {
        override suspend fun words(q: String, after: String?) = WordSearchOutcome.Page(
            WordPage(
                listOf(
                    WordHit(1, "猫", "ねこ", "cat", "learning"),
                    WordHit(2, "子猫", "こねこ", "kitten", "new"),
                ),
                null,
            ),
        )

        override suspend fun cards(q: String): CardSearchOutcome = CardSearchOutcome.Results(cards)

        override suspend fun word(seq: Long): WordLoad = error("unused")

        override suspend fun note(noteId: String): NoteLoad = error("unused")

        override suspend fun myCards(seqs: Collection<Long>) = mapOf(1L to MyCardInfo(listOf("Test")))
    }

    private fun vm(port: Port) = SearchViewModel(port, Dispatchers.Unconfined)

    @Test
    fun oneQueryListsDictionaryEntriesFlaggedThenNotesWithNoListedEntry() {
        val port = Port(
            listOf(
                hit("n1", 1, "猫"), // linked to a listed entry: not repeated
                hit("n2", null, "cat memo", meaning = "my own note"),
                hit("n3", 77, "犬"), // linked, but not among the dictionary results
                hit("n2", null, "cat memo"), // second card of the same note
            ),
        )
        val v = vm(port)
        v.setQuery("cat")
        v.search()
        val r = assertIs<SearchResults.Merged>(v.state.value.results)
        assertEquals(listOf(1L, 2L), r.words.map { it.hit.seq })
        assertEquals(MyCardInfo(listOf("Test")), r.words[0].mine)
        assertNull(r.words[1].mine)
        assertEquals(listOf("n2", "n3"), r.notes.map { it.noteId })
    }

    @Test
    fun aCardQuerySyntaxQueryListsCardsOnly() {
        val port = Port(listOf(hit("n1", 1, "猫", "ねこ", "cat")))
        val v = vm(port)
        v.setQuery("is:due lapses>3")
        v.search()
        val r = assertIs<SearchResults.Cards>(v.state.value.results)
        assertEquals(1, r.hits.size)
        assertTrue(isCardQuery("deck:Test cat") && isCardQuery("-is:suspended") && !isCardQuery("取る") && !isCardQuery("take"))
    }

    @Test
    fun aCardRowCarriesTheRealReadingAndMeaningNotThePlaceholderKind() {
        val labels = mapOf(5L to EntryLabel("猫", "ねこ", "cat"))
        val empty = hit("n1", 5, "").withLabels(labels)
        assertEquals("猫", empty.title)
        assertEquals("ねこ", empty.reading)
        assertEquals("cat", empty.meaning)
        val own = hit("n1", 5, "猫", "own reading", "own meaning").withLabels(labels)
        assertEquals("own reading", own.reading)
        assertEquals("own meaning", own.meaning)
    }
}
