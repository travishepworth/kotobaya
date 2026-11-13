package kotobaya.cards.grammar

import kotobaya.cards.BuiltInNoteTypes
import kotobaya.contracts.render.CardSide
import kotobaya.contracts.render.RenderInput
import kotobaya.contracts.render.RenderOptions
import kotobaya.render.DefaultCardRenderer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GrammarCardSentencesTest {
    private val opts = RenderOptions(furigana = true, pitch = false, showMnemonic = false, mediaUrlTemplate = "/b/{sha256}")

    private fun c(id: String, unknown: Int, text: String = "食べざるを得ない", s: Int = 2, e: Int = 7) =
        GrammarCandidate(id, text, s, e, unknown)

    @Test
    fun sentenceRotatesAcrossConsecutiveReviews() {
        val cands = listOf(c("a", 0), c("b", 0), c("c", 0))
        var last: String? = null
        repeat(12) { n ->
            val p = GrammarCardSentences.pick(cands, last, n)!!
            assertTrue(p.sentenceId != last, "review $n repeated $last")
            last = p.sentenceId
        }
        // Two candidates: strictly alternate regardless of rotation.
        val two = listOf(c("a", 0), c("b", 3))
        assertEquals("b", GrammarCardSentences.pick(two, "a", 0)!!.sentenceId)
        assertEquals("a", GrammarCardSentences.pick(two, "b", 0)!!.sentenceId)
        // A single candidate is shown even if it was the last.
        assertEquals("a", GrammarCardSentences.pick(listOf(c("a", 0)), "a", 0)!!.sentenceId)
    }

    @Test
    fun iPlusZeroIsPreferredOverUnknownWords() {
        val cands = listOf(c("a", 2), c("b", 0), c("c", 1), c("d", 0))
        repeat(6) { n -> assertEquals(0, GrammarCardSentences.pick(cands, null, n)!!.unknownCount) }
        assertEquals("d", GrammarCardSentences.pick(cands, "b", 0)!!.sentenceId)
        // Only when the i+0 sentence was just shown does the next best appear.
        assertEquals("c", GrammarCardSentences.pick(listOf(c("b", 0), c("c", 1), c("a", 2)), "b", 0)!!.sentenceId)
    }

    @Test
    fun clozeBlanksExactlyTheSpanInUtf16WithNonBmpText() {
        val text = "𠮷野家で食べざるを得ない"
        val start = text.indexOf("ざる")
        val end = text.indexOf("ない") + 2
        val cand = GrammarCandidate("s1", text, start, end, 0, "I have to eat.")
        val cloze = GrammarCardSentences.toCloze(GrammarCardSentences.pick(listOf(cand), null)!!)
        val front = DefaultCardRenderer.render(
            RenderInput(BuiltInNoteTypes.GRAMMAR.info, "cloze", mapOf("pattern" to "〜ざるを得ない"), "", null, cloze, CardSide.FRONT, opts),
        )
        assertEquals(emptyList(), front.warnings)
        assertTrue("𠮷野家で食べ<span class=\"kb-cloze-blank\">＿＿＿</span></div>" in front.html, front.html)
        assertFalse("ざる" in front.html)
        val back = DefaultCardRenderer.render(
            RenderInput(BuiltInNoteTypes.GRAMMAR.info, "cloze", mapOf(), "", null, cloze, CardSide.BACK, opts),
        )
        assertTrue("<span class=\"kb-cloze-answer\">ざるを得ない</span>" in back.html, back.html)
    }

    @Test
    fun invalidSpansAreNeverPicked() {
        val t = "𠮷あ"
        assertFalse(GrammarCardSentences.isValidSpan(t, 1, 3))
        assertFalse(GrammarCardSentences.isValidSpan(t, 0, 1))
        assertTrue(GrammarCardSentences.isValidSpan(t, 0, 2))
        assertFalse(GrammarCardSentences.isValidSpan(t, 2, 2))
        assertFalse(GrammarCardSentences.isValidSpan(t, 0, 9))
        assertNull(GrammarCardSentences.pick(listOf(GrammarCandidate("x", t, 1, 3, 0)), null))
    }
}
