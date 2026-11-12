package kotobaya.cards

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TemplateFieldsTest {
    private fun sub(t: String, f: Map<String, String>, front: String? = null) = TemplateFields.substitute(t, f, front)

    @Test
    fun escapesHtmlInValues() {
        val r = sub("<b>{{meaning}}</b>", mapOf("meaning" to "<script>alert(1)</script><img onerror=\"x\" a='b'>&"))
        assertEquals(
            "<b>&lt;script&gt;alert(1)&lt;/script&gt;&lt;img onerror=&quot;x&quot; a=&#39;b&#39;&gt;&amp;</b>",
            r.html,
        )
        assertTrue(r.warnings.isEmpty())
    }

    @Test
    fun missingFieldsAndSections() {
        val f = mapOf("a" to "x", "blank" to "  ")
        assertEquals("[]", sub("[{{missing}}]", f).html)
        assertEquals("<x>", sub("{{#a}}<{{a}}>{{/a}}{{#missing}}no{{/missing}}{{#blank}}no{{/blank}}", f).html)
        assertEquals("yes", sub("{{^missing}}yes{{/missing}}{{^a}}no{{/a}}", f).html)
        assertEquals("ok", sub("{{#a}}{{#missing}}no{{/missing}}ok{{/a}}", f).html)
    }

    @Test
    fun frontSideAndWarnings() {
        assertEquals("<p>Q</p>|A", sub("{{FrontSide}}|{{a}}", mapOf("a" to "A"), "<p>Q</p>").html)
        assertEquals("|", sub("{{FrontSide}}|", emptyMap()).html)
        assertEquals(listOf("unknown_placeholder"), sub("{{furigana:a}}", mapOf("a" to "x")).warnings)
        assertEquals(listOf("unbalanced_section"), sub("{{#a}}x", mapOf("a" to "x")).warnings)
        assertEquals(listOf("unbalanced_section"), sub("x{{/a}}", emptyMap()).warnings)
    }
}
