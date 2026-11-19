package kotobaya.cards

import kotobaya.contracts.cards.KanjiFacts
import kotobaya.contracts.cards.KanjiRequirement
import kotobaya.contracts.cards.KanjiRequirementResult
import kotobaya.contracts.cards.WordForm
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ADR-042: every worked example of the `KanjiRequirement` KDoc, on and off, and its edge cases. */
class KanjiRequirementTest {
    private val k = DefaultKanjiRequirement

    // KANJIDIC2 facts of the examples.
    private val facts = listOf(
        KanjiFacts("寿", 8, 1500), KanjiFacts("司", 4, 1100), KanjiFacts("珈", null, null), KanjiFacts("琲", null, null),
        KanjiFacts("海", 2, 200), KanjiFacts("老", 4, 800), KanjiFacts("化", 3, 300), KanjiFacts("彼", 8, 600),
        KanjiFacts("今", 2, 49), KanjiFacts("日", 1, 1), KanjiFacts("昨", 4, 900), KanjiFacts("鱈", null, null),
        KanjiFacts("子", 1, 70), KanjiFacts("薔", null, 2356), KanjiFacts("薇", null, null),
        KanjiFacts("林", 1, 1000), KanjiFacts("檎", null, null),
    )

    private fun on(expression: String, tags: Set<String> = emptySet(), uk: Boolean = false): KanjiRequirementResult =
        k.evaluate(WordForm(expression, tags, uk), facts, true)

    private fun off(expression: String, tags: Set<String> = emptySet(), uk: Boolean = false): KanjiRequirementResult =
        k.evaluate(WordForm(expression, tags, uk), facts, false)

    private fun r(exempt: Boolean, learnable: List<String>, furigana: Boolean) = KanjiRequirementResult(exempt, learnable, furigana)

    @Test
    fun workedExamples() {
        assertEquals(r(true, emptyList(), true), on("寿司", setOf("ateji")))
        assertEquals(r(false, listOf("寿", "司"), false), off("寿司", setOf("ateji")))
        assertEquals(r(true, emptyList(), true), on("珈琲", setOf("ateji"), uk = true))
        assertEquals(r(true, emptyList(), true), on("海老", uk = true))
        // The word-level exemption beats learnable kanji.
        assertEquals(r(true, emptyList(), true), on("お化け", uk = true))
        assertEquals(r(true, emptyList(), true), on("彼", setOf("rK"), uk = true))
        assertEquals(r(false, listOf("化"), false), on("化学"), "an untagged word with the same kanji still requires it")
        // Irregular readings without a tag: every learnable kanji, no furigana.
        assertEquals(r(false, listOf("今", "日"), false), on("今日"))
        assertEquals(r(false, listOf("昨", "日"), false), on("昨日"))
        assertEquals(r(false, emptyList(), true), on("鱈"))
        assertEquals(r(false, listOf("鱈"), false), off("鱈"))
        assertEquals(r(false, listOf("子"), false), on("鱈子"))
        assertEquals(r(false, listOf("薔"), false), on("薔薇"), "if it were untagged")
        assertEquals(r(true, emptyList(), true), on("薔薇", uk = true))
        // Ticket: 林檎 needs 林 only and has no furigana.
        assertEquals(r(false, listOf("林"), false), on("林檎"))
        assertEquals(r(false, emptyList(), false), on("たべる"))
    }

    @Test
    fun offIsEveryKanjiAndNoFurigana() {
        for (e in listOf("寿司", "珈琲", "海老", "今日", "鱈", "鱈子", "薔薇")) {
            val all = DefaultCardGenerator.kanjiOf(e)
            assertEquals(r(false, all, false), off(e, setOf("ateji", "rK"), uk = true), e)
        }
    }

    @Test
    fun eachExemptTagAlone() {
        for (tag in KanjiRequirement.EXEMPT_FORM_TAGS) assertTrue(on("今日", setOf(tag)).wordExempt, tag)
        assertEquals(setOf("ateji", "rK", "oK", "iK", "sK"), KanjiRequirement.EXEMPT_FORM_TAGS)
        // Exact and case-sensitive; unknown tags are ignored.
        assertFalse(on("今日", setOf("rk", "ATEJI", "io", "uk")).wordExempt)
    }

    @Test
    fun usuallyKanaOnlyWhenTheSenseUsedSaysSo() {
        // The host passes usuallyKana = false when `uk` sits on a later sense only, true for the first sense that applies.
        assertEquals(r(false, listOf("今", "日"), false), on("今日", uk = false))
        assertEquals(r(true, emptyList(), true), on("今日", uk = true))
    }

    @Test
    fun learnableGradesAndRanks() {
        assertTrue(k.isLearnable(8, null))
        assertFalse(k.isLearnable(9, null))
        assertFalse(k.isLearnable(10, null))
        for (g in 1..6) assertTrue(k.isLearnable(g, null), "grade $g")
        assertFalse(k.isLearnable(7, null))
        assertTrue(k.isLearnable(null, 2500))
        assertFalse(k.isLearnable(null, 2501))
        assertTrue(k.isLearnable(9, 2500))
        assertFalse(k.isLearnable(null, null))
        assertFalse(k.isLearnable(null, 0))
    }

    @Test
    fun aKanjiWithoutFactsIsNotLearnable() {
        assertEquals(r(false, emptyList(), true), k.evaluate(WordForm("学校", emptySet(), false), emptyList(), true))
        // Duplicates: the first counts.
        val dup = listOf(KanjiFacts("学", null, null), KanjiFacts("学", 1, 1))
        assertEquals(r(false, emptyList(), true), k.evaluate(WordForm("学", emptySet(), false), dup, true))
    }

    @Test
    fun kanaOnlyAndEmptyExpressions() {
        for (e in listOf("", "たべる", "コーヒー", "々")) {
            assertEquals(r(false, emptyList(), false), on(e, setOf("ateji"), uk = true), e)
            assertEquals(r(false, emptyList(), false), off(e, setOf("ateji"), uk = true), e)
        }
        // A repeated kanji counts once; a kanji outside the BMP is one literal.
        assertEquals(listOf("日", "曜"), off("日曜日").learnableKanji)
        assertEquals(listOf("𠮷"), off("𠮷").learnableKanji)
    }

    @Test
    fun exemptionsEnabledSetting() {
        assertTrue(KanjiRequirement.exemptionsEnabled(null))
        assertTrue(KanjiRequirement.exemptionsEnabled(JsonNull))
        assertTrue(KanjiRequirement.exemptionsEnabled(JsonBool(true)))
        assertFalse(KanjiRequirement.exemptionsEnabled(JsonBool(false)))
        assertTrue(KanjiRequirement.exemptionsEnabled(JsonNumber(0.0)))
        assertTrue(KanjiRequirement.exemptionsEnabled(JsonString("false")))
        assertEquals("cards.kanji_exemptions", KanjiRequirement.SETTING_KANJI_EXEMPTIONS)
        assertTrue(KanjiRequirement.KANJI_EXEMPTIONS_DEFAULT)
    }
}
