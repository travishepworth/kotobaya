package kotobaya.render

import kotobaya.contracts.render.CardClasses
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * An independent, strict reader of renderer output: every `<` must open a tag of the contract's
 * allowlist with only allowlisted attributes in canonical form (`name="value"`), and every value
 * must have its allowed shape. Anything else fails the test.
 */
object HtmlChecker {
    private val ELEMENTS = setOf(
        "div", "span", "p", "br", "hr", "b", "i", "u", "em", "strong", "small", "sub", "sup",
        "ruby", "rb", "rt", "rp", "ul", "ol", "li", "img", "button",
    )
    private val TAG = Regex("""<(/?)([a-z]+)((?: [a-z-]+="[^"<>]*")*)>""")
    private val ATTR = Regex(""" ([a-z-]+)="([^"<>]*)"""")
    private val SHA = Regex("[0-9a-f]{64}")
    private val GENERATED_CLASS = Regex("kb-field-[a-z][a-z0-9_]*|kb-kind-[a-z][a-z0-9_]*|kb-(vocab|kanji|phrase|grammar|sentence|custom)")
    private val REGION = Regex("""<span class="kb-field-([a-z][a-z0-9_]*)" data-field="\1" data-field-source="(note|dictionary|kanji)">""")
    private val USER_CLASS = Regex("user-[A-Za-z0-9_-]+")

    fun assertSafe(html: String, mediaTemplate: String, context: String = html) {
        val lower = html.lowercase()
        for (bad in listOf("<script", "<svg", "<style", "<iframe", "<object", "<embed", "<math", "<template", "<a ", "<!--")) {
            assertTrue(bad !in lower, "$bad in output for: $context\n$html")
        }
        var i = 0
        while (true) {
            val lt = html.indexOf('<', i)
            if (lt < 0) break
            assertTrue('>' !in html.substring(i, lt), "raw > in text for: $context\n$html")
            val m = TAG.matchAt(html, lt) ?: fail("not a canonical tag at $lt for: $context\n$html")
            val name = m.groupValues[2]
            assertTrue(name in ELEMENTS, "element $name for: $context")
            if (m.groupValues[1] == "/") assertTrue(m.groupValues[3].isEmpty(), "attributes on end tag: $context")
            for (a in ATTR.findAll(m.groupValues[3])) {
                val (attr, value) = a.destructured
                val vl = value.lowercase()
                assertTrue("javascript:" !in vl && "data:" !in vl && "vbscript:" !in vl, "url scheme in $attr=$value: $context")
                when (attr) {
                    "class" -> for (c in value.split(' ')) {
                        assertTrue(
                            c in CardClasses.ALL || USER_CLASS.matches(c) || GENERATED_CLASS.matches(c),
                            "class $c for: $context",
                        )
                    }
                    "lang" -> assertTrue(Regex("[A-Za-z0-9-]+").matches(value), "lang=$value: $context")
                    "src" -> {
                        assertTrue(name == "img", "src on $name: $context")
                        val (pre, post) = mediaTemplate.split("{sha256}")
                        assertTrue(
                            value.startsWith(pre) && value.endsWith(post) &&
                                SHA.matches(value.substring(pre.length, value.length - post.length)),
                            "src=$value: $context",
                        )
                    }
                    "alt" -> assertTrue(name == "img", "alt on $name: $context")
                    "data-kb-audio" -> assertTrue(name == "button" && SHA.matches(value), "data-kb-audio=$value: $context")
                    // C-19 regions: only in the renderer's own wrapper start tag, in its exact form
                    "data-field", "data-field-source" -> assertTrue(REGION.matches(m.value), "forged region ${m.value}: $context")
                    else -> fail("attribute $attr for: $context\n$html")
                }
            }
            if (name == "img") assertTrue(" src=\"" in m.value, "img without src: $context")
            i = m.range.last + 1
        }
        assertTrue('>' !in html.substring(i), "raw > in text for: $context\n$html")
        assertTrue('' !in html && '' !in html, "sentinel leaked: $context")
    }
}
