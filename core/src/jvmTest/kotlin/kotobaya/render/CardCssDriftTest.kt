package kotobaya.render

import kotobaya.contracts.render.CardClasses
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** T006 follow-up: `assets/card/card.css` styles every class the renderer may emit. */
class CardCssDriftTest {
    private val css = File("../assets/card/card.css").readText()

    /** Class names used in selectors, with their rule bodies (comments removed). */
    private fun styledClasses(): Map<String, List<String>> {
        val plain = css.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        val out = HashMap<String, MutableList<String>>()
        for (rule in Regex("([^{}]+)\\{([^{}]*)\\}").findAll(plain)) {
            val body = rule.groupValues[2].trim()
            for (c in Regex("\\.(-?[_a-zA-Z][_a-zA-Z0-9-]*)").findAll(rule.groupValues[1])) {
                out.getOrPut(c.groupValues[1]) { ArrayList() }.add(body)
            }
        }
        return out
    }

    @Test
    fun everyCardClassIsStyledInCardCss() {
        val styled = styledClasses()
        val missing = CardClasses.ALL.filter { c -> styled[c].orEmpty().none { it.isNotEmpty() } }
        assertEquals(emptyList(), missing, "classes of CardClasses.ALL without a non-empty rule in assets/card/card.css")
    }

    @Test
    fun cardCssHasLightAndDarkVariablesAndAJapaneseFontStack() {
        assertTrue("prefers-color-scheme: dark" in css)
        assertTrue("--kb-font:" in css && "Noto Sans JP" in css)
    }
}
