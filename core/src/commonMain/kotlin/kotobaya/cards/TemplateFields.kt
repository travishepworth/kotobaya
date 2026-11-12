package kotobaya.cards

/** Result of [TemplateFields.substitute]. */
public data class TemplateResult(
    /** Expanded text; field values are HTML-escaped, `{{FrontSide}}` is inserted as given. */
    public val html: String,
    /** Non-fatal problems as codes: `unknown_placeholder`, `unbalanced_section`. */
    public val warnings: List<String>,
)

/**
 * Basic `{{...}}` template substitution (the base of the T213a renderer).
 *
 * Supported: `{{key}}` (field value, HTML-escaped; missing = empty), `{{#key}}...{{/key}}` (only if the
 * field is non-empty after trimming), `{{^key}}...{{/key}}` (only if empty), nested sections, and
 * `{{FrontSide}}` (the already rendered front, inserted unescaped; empty when none is given).
 * Any other placeholder (including prefixed forms such as `{{furigana:key}}`) renders as `""` with
 * warning `unknown_placeholder`; T213a layers those on top. Never throws.
 */
public object TemplateFields {
    /** Name of the front-side placeholder. */
    public const val FRONT_SIDE: String = "FrontSide"

    private val placeholder = Regex("""\{\{\s*([#^/]?)\s*([^{}]*?)\s*\}\}""")
    private val keyPattern = Regex("[a-z][a-z0-9_]*")

    private class Frame(val key: String, val active: Boolean)

    /** Escapes `& < > " '` for use in HTML text and attribute values. */
    public fun escapeHtml(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&#39;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** Expands [template] against [fields] (key to raw value); [frontSide] feeds `{{FrontSide}}`. */
    public fun substitute(template: String, fields: Map<String, String>, frontSide: String? = null): TemplateResult {
        val warnings = LinkedHashSet<String>()
        val stack = ArrayList<Frame>()
        val sb = StringBuilder()
        var pos = 0
        for (m in placeholder.findAll(template)) {
            if (stack.all { it.active }) sb.append(template, pos, m.range.first)
            pos = m.range.last + 1
            val sigil = m.groupValues[1]
            val name = m.groupValues[2]
            when (sigil) {
                "#", "^" -> {
                    val nonEmpty = (fields[name] ?: "").trim().isNotEmpty()
                    stack.add(Frame(name, if (sigil == "#") nonEmpty else !nonEmpty))
                }
                "/" ->
                    if (stack.isNotEmpty() && stack.last().key == name) stack.removeAt(stack.size - 1)
                    else warnings.add("unbalanced_section")
                else -> if (stack.all { it.active }) {
                    when {
                        name == FRONT_SIDE -> sb.append(frontSide ?: "")
                        keyPattern.matches(name) -> sb.append(escapeHtml(fields[name] ?: ""))
                        else -> warnings.add("unknown_placeholder")
                    }
                }
            }
        }
        if (stack.all { it.active }) sb.append(template, pos, template.length)
        if (stack.isNotEmpty()) warnings.add("unbalanced_section")
        return TemplateResult(sb.toString(), warnings.toList())
    }
}
