package kotobaya.search.sql

/**
 * Minimal JSON reader for the residual predicates on the device (`tags` array, `fields` object).
 * Tolerant: malformed input yields what was read so far (empty on garbage); never throws.
 */
internal class MiniJson(private val s: String) {
    private var i = 0

    private fun ws() {
        while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
    }

    /** Elements of a JSON array that are strings (other elements skipped). */
    fun stringArray(): List<String> {
        val out = ArrayList<String>()
        ws()
        if (i >= s.length || s[i] != '[') return out
        i++
        while (true) {
            ws()
            if (i >= s.length || s[i] == ']') return out
            if (s[i] == '"') {
                out.add(str() ?: return out)
            } else if (!skip()) {
                return out
            }
            ws()
            if (i < s.length && s[i] == ',') i++
        }
    }

    /** Members of a JSON object as text, as `jsonb_each_text` renders them (JSON null skipped). */
    fun objectText(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ws()
        if (i >= s.length || s[i] != '{') return out
        i++
        while (true) {
            ws()
            if (i >= s.length || s[i] == '}') return out
            if (s[i] != '"') return out
            val key = str() ?: return out
            ws()
            if (i >= s.length || s[i] != ':') return out
            i++
            ws()
            if (i >= s.length) return out
            if (s[i] == '"') {
                out[key] = str() ?: return out
            } else {
                val st = i
                if (!skip()) return out
                val raw = s.substring(st, i)
                if (raw != "null") out[key] = raw
            }
            ws()
            if (i < s.length && s[i] == ',') i++
        }
    }

    private fun str(): String? {
        i++ // opening quote
        val sb = StringBuilder()
        while (i < s.length) {
            val ch = s[i++]
            when (ch) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (i >= s.length) return null
                    when (val e = s[i++]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> {
                            if (i + 4 > s.length) return null
                            val v = s.substring(i, i + 4).toIntOrNull(16) ?: return null
                            sb.append(v.toChar())
                            i += 4
                        }
                        else -> sb.append(e)
                    }
                }
                else -> sb.append(ch)
            }
        }
        return null
    }

    /** Skips one value (string, number, literal, array, object). */
    private fun skip(): Boolean {
        ws()
        if (i >= s.length) return false
        if (s[i] == '"') return str() != null
        if (s[i] == '[' || s[i] == '{') {
            var depth = 0
            while (i < s.length) {
                val ch = s[i]
                if (ch == '"') {
                    if (str() == null) return false
                    continue
                }
                if (ch == '[' || ch == '{') depth++
                if (ch == ']' || ch == '}') depth--
                i++
                if (depth == 0) return true
            }
            return false
        }
        val st = i
        while (i < s.length && s[i] != ',' && s[i] != ']' && s[i] != '}' && s[i] != ' ') i++
        return i > st
    }
}
