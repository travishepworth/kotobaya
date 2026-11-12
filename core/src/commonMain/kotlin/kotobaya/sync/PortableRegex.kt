package kotobaya.sync

/**
 * Compiles a JSON Schema `pattern` (ECMA-262 syntax) so that it behaves identically on jvm
 * (java.util.regex) and js (RegExp). Otherwise a value could be valid on Android and invalid on
 * the web, and a device that pulls such an op stops pulling for good (SYNC.md 6.1, C-1d).
 *
 * The constructs that behave differently are rewritten before compiling:
 * - `$` outside a character class becomes `(?![\s\S])` (true end of input). On the JVM, `$` also
 *   matches before a final line terminator, so `^[0-9a-f]{4}$` would accept `"abcd\n"`.
 * - `\s` / `\S` outside a class become an explicit class of the ECMA-262 WhiteSpace and
 *   LineTerminator code points ([ECMA_WS]). The JVM's `\s` is ASCII-only: U+3000 and U+00A0 count
 *   as `\S` there.
 * - `\s` inside a class is expanded the same way. `\S` inside a class has no simple expansion and
 *   is refused (IllegalArgumentException), as is any other backslash class letter (`\d`, `\w`,
 *   `\b`, `\p`, …) that no schema uses. A new pattern using one fails the schema keyword test.
 *
 * A bare `.` is refused too (the JVM's excludes U+0085, JavaScript's does not).
 *
 * `^` is left alone: without MULTILINE it means start of input on both engines.
 */
internal object PortableRegex {
    /** ECMA-262 `\s`: WhiteSpace + LineTerminator (Unicode 15 Zs), as regex class content. */
    const val ECMA_WS: String =
        "\\u0009-\\u000d\\u0020\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff"

    // identity escapes plus \n \t \r \f \uXXXX; not \v (a class on the JVM) nor digits (backreferences)
    private const val ALLOWED_ESCAPES = "\\^$.|?*+()[]{}/-ntrfu"

    fun compile(pattern: String): Regex = Regex(translate(pattern))

    fun translate(pattern: String): String {
        val sb = StringBuilder()
        var inClass = false
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' -> {
                    require(i + 1 < pattern.length) { "trailing backslash in /$pattern/" }
                    val e = pattern[i + 1]
                    when {
                        e == 's' -> sb.append(if (inClass) ECMA_WS else "[$ECMA_WS]")
                        e == 'S' && !inClass -> sb.append("[^$ECMA_WS]")
                        e in ALLOWED_ESCAPES -> sb.append(c).append(e)
                        else -> throw IllegalArgumentException("unsupported escape \\$e in /$pattern/")
                    }
                    i += 2
                    continue
                }
                inClass -> {
                    if (c == ']') inClass = false
                    sb.append(c)
                }
                c == '[' -> {
                    inClass = true
                    sb.append(c)
                    // a ']' right after '[' or '[^' is a literal in Java but closes an empty class in JS
                    val next = i + 1 + (if (pattern.getOrNull(i + 1) == '^') 1 else 0)
                    require(pattern.getOrNull(next) != ']') { "ambiguous '[]' in /$pattern/" }
                }
                c == '$' -> sb.append("(?![\\s\\S])")
                c == '.' -> throw IllegalArgumentException("bare '.' differs between engines (U+0085) in /$pattern/")
                else -> sb.append(c)
            }
            i++
        }
        require(!inClass) { "unterminated class in /$pattern/" }
        return sb.toString()
    }
}
