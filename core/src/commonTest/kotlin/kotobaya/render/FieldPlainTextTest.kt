package kotobaya.render

import kotobaya.contracts.NoteBaseType
import kotlin.test.Test
import kotlin.test.assertEquals

/** CORE-28b: [FieldPlainText] is the renderers' "text of a field" (the furigana front's E and R). */
class FieldPlainTextTest {
    private fun rendererKanji(value: String) =
        DefaultCardTextRenderer.fieldText(mapOf("expression" to value), null, null, NoteBaseType.VOCAB, "expression", TextMode.KANJI)

    private fun rendererKana(value: String) =
        DefaultCardTextRenderer.fieldText(mapOf("reading" to value), null, null, NoteBaseType.VOCAB, "reading", TextMode.KANA)

    private val cases = listOf(
        "寿司",
        "a&amp;b",
        "a&b",
        "<img src=x onerror=alert(1)>寿司",
        "<b>寿</b>司",
        "上<br>下",
        "<div>一</div><div>二</div>",
        "<ruby>寿司<rt>すし</rt></ruby>",
        "<ruby>寿<rp>(</rp><rt>す</rt><rp>)</rp></ruby>司",
        "食[た]べる",
        "食[た]べ 物[もの]",
        "  寿&nbsp;&nbsp;司  ",
        "&lt;script&gt;",
        "<script>alert(1)</script>寿司",
    )

    @Test
    fun matchesTheRendererInBothModes() {
        for (v in cases) {
            assertEquals(rendererKanji(v), FieldPlainText.of(v), v)
            assertEquals(rendererKana(v), FieldPlainText.of(v, PlainTextMode.KANA), v)
        }
    }

    @Test
    fun examples() {
        assertEquals("", FieldPlainText.of(null))
        assertEquals("", FieldPlainText.of("  "))
        assertEquals("a&b", FieldPlainText.of("a&amp;b"))
        assertEquals("寿司", FieldPlainText.of("<img src=x onerror=alert(1)>寿司"))
        assertEquals("上\n下", FieldPlainText.of("上<br>下"))
        assertEquals("寿司", FieldPlainText.of("<ruby>寿司<rt>すし</rt></ruby>"))
        assertEquals("食べる", FieldPlainText.of("食[た]べる"))
        assertEquals("たべる", FieldPlainText.of("食[た]べる", PlainTextMode.KANA))
        assertEquals("食べ物", FieldPlainText.of("食[た]べ 物[もの]"))
        assertEquals("<script>", FieldPlainText.of("&lt;script&gt;"))
        assertEquals("寿司", FieldPlainText.of("<script>alert(1)</script>寿司"))
    }
}
