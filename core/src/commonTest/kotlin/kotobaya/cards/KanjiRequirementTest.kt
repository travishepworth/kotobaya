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

/** ADR-042 as amended by ADR-045 and ADR-046: every worked example of the `KanjiRequirement` KDoc, on and off, and its edge cases. */
class KanjiRequirementTest {
    private val k = DefaultKanjiRequirement

    // KANJIDIC2 facts of the examples.
    private val facts = listOf(
        KanjiFacts("寿", 8, 1500), KanjiFacts("司", 4, 1100), KanjiFacts("珈", null, null), KanjiFacts("琲", null, null),
        KanjiFacts("海", 2, 200), KanjiFacts("老", 4, 800), KanjiFacts("化", 3, 300), KanjiFacts("彼", 8, 600),
        KanjiFacts("今", 2, 49), KanjiFacts("日", 1, 1), KanjiFacts("昨", 4, 900), KanjiFacts("鱈", null, null),
        KanjiFacts("子", 1, 70), KanjiFacts("薔", null, 2356), KanjiFacts("薇", null, null),
        KanjiFacts("林", 1, 1000), KanjiFacts("檎", null, null),
        KanjiFacts("金", 1, 53), KanjiFacts("糸", 1, 1400), KanjiFacts("雀", 9, 1856), KanjiFacts("鯛", 9, 2446),
        KanjiFacts("新", 2, 51), KanjiFacts("嘉", 9, 1553), KanjiFacts("坡", null, null),
        KanjiFacts("歌", 2, 600), KanjiFacts("留", 5, 700), KanjiFacts("多", 2, 140),
    )

    private fun on(expression: String, tags: Set<String> = emptySet(), uk: Boolean = false): KanjiRequirementResult =
        k.evaluate(WordForm(expression, tags, uk), facts, true)

    private fun off(expression: String, tags: Set<String> = emptySet(), uk: Boolean = false): KanjiRequirementResult =
        k.evaluate(WordForm(expression, tags, uk), facts, false)

    private fun r(exempt: Boolean, learnable: List<String>, furigana: Boolean) = KanjiRequirementResult(exempt, learnable, furigana)

    @Test
    fun workedExamples() {
        assertEquals(r(true, listOf("海", "老"), true), on("海老", uk = true))
        assertEquals(r(false, listOf("海", "老"), false), off("海老", uk = true))
        assertEquals(r(true, listOf("寿", "司"), true), on("寿司", setOf("ateji")))
        assertEquals(r(false, listOf("寿", "司"), false), off("寿司", setOf("ateji")))
        assertEquals(r(true, emptyList(), true), on("珈琲", setOf("ateji"), uk = true))
        assertEquals(r(false, listOf("珈", "琲"), false), off("珈琲", setOf("ateji"), uk = true))
        assertEquals(r(true, listOf("林"), true), on("林檎", uk = true))
        assertEquals(r(false, listOf("林"), false), on("林檎"), "without the tag")
        // ADR-045: tags never remove a learnable kanji.
        assertEquals(r(true, listOf("化"), true), on("お化け", uk = true))
        assertEquals(r(true, listOf("彼"), true), on("彼", setOf("rK"), uk = true))
        // Irregular readings without a tag: every learnable kanji, no furigana.
        assertEquals(r(false, listOf("今", "日"), false), on("今日"))
        assertEquals(r(false, listOf("昨", "日"), false), on("昨日"))
        assertEquals(r(false, emptyList(), true), on("鱈"))
        assertEquals(r(false, listOf("鱈"), false), off("鱈"))
        assertEquals(r(false, listOf("子"), false), on("鱈子"))
        // ADR-046 (b): a tagged word requires its jōyō kanji only.
        assertEquals(r(true, emptyList(), true), on("薔薇", uk = true))
        assertEquals(r(false, listOf("薔"), false), on("薔薇"), "if it were untagged")
        assertEquals(r(false, listOf("薔", "薇"), false), off("薔薇", uk = true))
        assertEquals(r(true, listOf("金", "糸"), true), on("金糸雀", setOf("ateji"), uk = true))
        assertEquals(r(false, listOf("金", "糸", "雀"), false), off("金糸雀", setOf("ateji"), uk = true))
        assertEquals(r(true, emptyList(), true), on("鯛", uk = true))
        assertEquals(r(false, listOf("鯛"), false), on("鯛"), "without the tag")
        assertEquals(r(false, emptyList(), false), on("たべる"))
    }

    @Test
    fun kanaWordsAndTheirKanjiFormsInTheField() {
        // ADR-046 (a): the stored headword is the reading, so the form reaching the predicate is kana.
        for (e in listOf("シンガポール", "かるた")) {
            assertEquals(r(false, emptyList(), false), on(e, uk = true), e)
            assertEquals(r(false, emptyList(), false), off(e, uk = true), e)
        }
        // The note's own field holds the kanji form: evaluated as a tagged word.
        assertEquals(r(true, listOf("新"), true), on("新嘉坡", setOf("ateji", "rK"), uk = true))
        assertEquals(r(false, listOf("新", "嘉", "坡"), false), off("新嘉坡", setOf("ateji", "rK"), uk = true))
        assertEquals(r(true, listOf("歌", "留", "多"), true), on("歌留多", setOf("ateji", "rK"), uk = true))
    }

    @Test
    fun singaporeEntryShape() {
        // CORE-28d acceptance: every kanji form ateji + rK, sense uk, 嘉 grade 9 rank 1553; headword シンガポール.
        val singaporeFacts = listOf(
            KanjiFacts("新", 2, 51), KanjiFacts("嘉", 9, 1553), KanjiFacts("坡", null, null),
            KanjiFacts("加", 4, 300), KanjiFacts("星", 2, 900),
        )
        for (enabled in listOf(true, false)) {
            val res = k.evaluate(WordForm("シンガポール", emptySet(), true), singaporeFacts, enabled)
            assertEquals(r(false, emptyList(), false), res, "enabled=$enabled")
        }
    }

    @Test
    fun aTagNeverRemovesAJoyoKanji() {
        assertEquals(listOf("化"), on("化学").learnableKanji, "an untagged word with the same kanji requires it alike")
        for (e in listOf("寿司", "海老", "珈琲", "林檎", "今日", "鱈", "鱈子", "薔薇", "彼", "金糸雀", "鯛", "新嘉坡", "歌留多")) {
            val untagged = on(e)
            val taggedForms = listOf(setOf("ateji"), setOf("rK", "iK"), setOf("oK"), setOf("sK")).map { on(e, it) } + on(e, uk = true)
            for (tagged in taggedForms) {
                assertTrue(tagged.wordExempt && tagged.frontFurigana, e)
                assertEquals(untagged.learnableKanji.filter { it in tagged.learnableKanji }, tagged.learnableKanji, "$e sub-list")
                for (l in untagged.learnableKanji) {
                    val f = facts.first { it.literal == l }
                    assertEquals(KanjiRequirement.isJoyo(f.grade), l in tagged.learnableKanji, "$e $l")
                }
            }
            // Untagged: furigana iff no learnable kanji.
            assertEquals(untagged.learnableKanji.isEmpty(), untagged.frontFurigana, e)
        }
    }

    @Test
    fun randomTaggedIsJoyoSubListOfUntagged() {
        val rnd = kotlin.random.Random(46)
        val pool = "一二三四五六七八九十百千万円日月火水木金土"
        val grades = listOf(null, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        val ranks = listOf(null, 0, 1, 1200, 2500, 2501, 3000)
        repeat(500) {
            val literals = (1..rnd.nextInt(1, 6)).map { pool[rnd.nextInt(pool.length)].toString() }
            val expr = literals.joinToString("")
            val fs = literals.distinct().map { KanjiFacts(it, grades[rnd.nextInt(grades.size)], ranks[rnd.nextInt(ranks.size)]) }
            val untagged = k.evaluate(WordForm(expr, emptySet(), false), fs, true)
            val tagged = k.evaluate(WordForm(expr, setOf("ateji"), rnd.nextBoolean()), fs, true)
            assertEquals(untagged.learnableKanji.filter { it in tagged.learnableKanji }, tagged.learnableKanji, expr)
            for (l in untagged.learnableKanji - tagged.learnableKanji.toSet()) {
                val f = fs.first { it.literal == l }
                assertFalse(KanjiRequirement.isJoyo(f.grade), "$expr $l")
                val rank = f.freqRank
                assertTrue(rank != null && rank in 1..KanjiRequirement.FREQ_TOP, "$expr $l")
            }
            for (f in fs) {
                if (KanjiRequirement.isJoyo(f.grade)) assertTrue(f.literal in tagged.learnableKanji && f.literal in untagged.learnableKanji, expr)
            }
        }
    }

    @Test
    fun joyoGrades() {
        for (g in listOf(null, 0, 7, 9, 10)) assertFalse(KanjiRequirement.isJoyo(g), "grade $g")
        for (g in listOf(1, 6, 8)) assertTrue(KanjiRequirement.isJoyo(g), "grade $g")
        for (g in listOf(null, 0, 1, 6, 7, 8, 9, 10)) for (rank in listOf(null, 1, 2500, 2501)) {
            if (KanjiRequirement.isJoyo(g)) assertTrue(k.isLearnable(g, rank), "$g $rank")
        }
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
        assertEquals(r(true, listOf("今", "日"), true), on("今日", uk = true))
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
