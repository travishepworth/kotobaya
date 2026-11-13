package kotobaya.confusables

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfusableScoringTest {
    private val comps = mapOf(
        "待" to setOf("彳", "土", "寸"),
        "持" to setOf("扌", "土", "寸"),
        "特" to setOf("牛", "土", "寸"),
        "日" to setOf("日"),
        "月" to setOf("月"),
        "土" to setOf("土"),
        "士" to setOf("士"),
    )

    private fun v(id: String, seq: Long, head: String, reading: String, vararg glosses: String) =
        VocabNote(id, seq, head, reading, listOf(glosses.toList()))

    @Test
    fun homophonesShareAReadingAcrossEntriesOnly() {
        val a = v("a", 1, "待つ", "まつ", "to wait")
        val b = v("b", 2, "松", "マツ", "pine")
        val c = v("c", 3, "持つ", "もつ", "to hold")
        val same = v("d", 1, "待つ", "まつ", "to wait")
        assertEquals(setOf(Pair3("a", "b", Reason.HOMOPHONE)), ConfusableScoring.homophones(listOf(a, b, c)))
        assertTrue(ConfusableScoring.homophones(listOf(a, same)).isEmpty(), "the same entry is not its own homophone")
    }

    @Test
    fun lookAlikeKanjiNeedsEnoughSharedComponents() {
        val k = listOf(KanjiNote("1", "待"), KanjiNote("2", "持"), KanjiNote("3", "特"), KanjiNote("4", "日"), KanjiNote("5", "月"))
        val got = ConfusableScoring.lookAlikeKanji(k, comps)
        assertEquals(
            setOf(Pair3("1", "2", Reason.LOOK_ALIKE), Pair3("1", "3", Reason.LOOK_ALIKE), Pair3("2", "3", Reason.LOOK_ALIKE)),
            got,
        )
        // One shared component is not enough (土 / 士 share nothing anyway; 待 vs 日 share nothing).
        assertTrue(ConfusableScoring.lookAlikeKanji(listOf(KanjiNote("6", "土"), KanjiNote("7", "士")), comps).isEmpty())
    }

    @Test
    fun lookAlikeVocabDiffersInExactlyOneLookAlikeKanji() {
        val machi = v("a", 1, "待つ", "まつ")
        val motsu = v("b", 2, "持つ", "もつ")
        val hi = v("c", 3, "日つ", "ひつ")
        val two = v("d", 4, "特待", "とくたい")
        val got = ConfusableScoring.lookAlikeVocab(listOf(machi, motsu, hi, two), comps)
        assertEquals(setOf(Pair3("a", "b", Reason.LOOK_ALIKE)), got)
    }

    @Test
    fun closeMeaningsShareANormalisedGloss() {
        val a = v("a", 1, "待つ", "まつ", "to wait", "to await")
        val b = v("b", 2, "控える", "ひかえる", "to wait (e.g. for an order)", "to hold back")
        val c = v("c", 3, "持つ", "もつ", "to hold")
        assertEquals(setOf(Pair3("a", "b", Reason.MEANING)), ConfusableScoring.meanings(listOf(a, b, c)))
    }

    @Test
    fun aTooGenericGlossIsNotASuggestion() {
        val many = (1..7).map { v("n$it", it.toLong(), "語$it", "ご$it", "thing") }
        assertTrue(ConfusableScoring.meanings(many).isEmpty())
    }

    @Test
    fun pairsAreOrderedSmallerIdFirst() {
        assertEquals("a", Pair3.of("b", "a", Reason.MEANING).a)
    }
}
