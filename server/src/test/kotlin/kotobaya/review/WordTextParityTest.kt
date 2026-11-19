package kotobaya.review

import kotlin.test.Test
import kotlin.test.assertEquals
import org.jooq.SQLDialect
import org.jooq.impl.DSL

/** KANJI-TEXT-SHARED: the expression / reading the kanji requirement is judged on. Same literals as android `WordTextParityTest`. */
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
        val needs = KanjiNeeds(DSL.using(SQLDialect.POSTGRES), enabled = false)
        for ((i, c) in cases.withIndex()) {
            val w = needs.resolve(listOf(WordRef("k$i", c.first, c.first, null))).getValue("k$i")
            assertEquals(c.second, w.expression, "expression of ${c.first}")
            assertEquals(c.third, w.reading, "reading of ${c.first}")
        }
    }
}
