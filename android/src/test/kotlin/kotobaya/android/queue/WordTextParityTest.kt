package kotobaya.android.queue

import kotlin.test.Test
import kotlin.test.assertEquals

/** KANJI-TEXT-SHARED: the expression / reading the kanji requirement is judged on. Same literals as server `WordTextParityTest`. */
class WordTextParityTest {
    private val cases = listOf(
        // field, expression (KANJI), reading (KANA)
        Triple("食[た]べる", "食べる", "たべる"),
        Triple("a&amp;b", "a&b", "a&b"),
        Triple("x<br>y", "x\ny", "x\ny"),
        Triple("<ruby>漢<rt>かん</rt></ruby>字", "漢字", "漢字"),
    )

    @Test
    fun expressionAndReadingGoThroughCoreFieldPlainText() {
        for (c in cases) {
            assertEquals(c.second, WordText.expression(c.first, null), "expression of ${c.first}")
            assertEquals(c.third, WordText.reading(c.first, null), "reading of ${c.first}")
        }
        assertEquals("headword", WordText.expression("", "headword"))
        assertEquals("entry", WordText.reading(null, "entry"))
    }
}
