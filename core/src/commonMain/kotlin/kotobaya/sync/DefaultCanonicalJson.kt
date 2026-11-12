package kotobaya.sync

import kotobaya.contracts.sync.CanonicalJson
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonDecodeResult
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonSyntaxError
import kotobaya.contracts.sync.JsonValue

/**
 * The [CanonicalJson] of the shared core (contracts/sync/Json.kt; SYNC.md 5.3/5.4, C-1e).
 *
 * Implementation limit: [decode] rejects nesting deeper than [MAX_DEPTH] arrays/objects (an error,
 * never a stack overflow). No op schema comes near it.
 */
public object DefaultCanonicalJson : CanonicalJson {
    /** Deepest accepted nesting of arrays and objects. */
    public const val MAX_DEPTH: Int = 512

    override fun decode(text: String): JsonDecodeResult {
        val p = JsonParser(text, allowDuplicateKeys = false)
        return try {
            p.skipWs()
            val v = p.parseValue(0)
            p.skipWs()
            if (!p.atEnd()) p.fail("trailing content after the JSON value")
            JsonDecodeResult(v, null)
        } catch (e: JsonParseException) {
            JsonDecodeResult(null, JsonSyntaxError(e.message ?: "syntax error", e.offset))
        }
    }

    override fun encode(value: JsonValue): String {
        val sb = StringBuilder()
        write(sb, value)
        return sb.toString()
    }

    override fun sizeOf(value: JsonValue): Int = utf8Size(encode(value))

    override fun utf8Size(text: String): Int {
        var n = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.code < 0x80 -> n += 1
                c.code < 0x800 -> n += 2
                c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> {
                    n += 4
                    i++
                }
                else -> n += 3 // BMP, or a lone surrogate (written as U+FFFD)
            }
            i++
        }
        return n
    }

    private fun write(sb: StringBuilder, v: JsonValue) {
        when (v) {
            JsonNull -> sb.append("null")
            is JsonBool -> sb.append(if (v.value) "true" else "false")
            is JsonNumber -> sb.append(JsNumberFormat.format(v.value))
            is JsonString -> writeString(sb, v.value)
            is JsonArray -> {
                sb.append('[')
                v.items.forEachIndexed { i, item ->
                    if (i > 0) sb.append(',')
                    write(sb, item)
                }
                sb.append(']')
            }
            is JsonObject -> {
                sb.append('{')
                // String.compareTo is UTF-16 code unit order on every platform (RFC 8785 member order)
                v.members.keys.sorted().forEachIndexed { i, k ->
                    if (i > 0) sb.append(',')
                    writeString(sb, k)
                    sb.append(':')
                    write(sb, v.members.getValue(k))
                }
                sb.append('}')
            }
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\b' -> sb.append("\\b")
                c == '\u000c' -> sb.append("\\f")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c.code < 0x20 -> sb.append("\\u00").append(HEX[c.code shr 4]).append(HEX[c.code and 0xf])
                c.isHighSurrogate() -> {
                    require(i + 1 < s.length && s[i + 1].isLowSurrogate()) { "unpaired surrogate at $i" }
                    sb.append(c).append(s[i + 1])
                    i++
                }
                c.isLowSurrogate() -> throw IllegalArgumentException("unpaired surrogate at $i")
                else -> sb.append(c)
            }
            i++
        }
        sb.append('"')
    }

    private const val HEX = "0123456789abcdef"
}

/** A syntax error at [offset] (UTF-16 index). */
internal class JsonParseException(message: String, val offset: Int) : Exception(message)

/**
 * RFC 8259 recursive-descent parser shared by [DefaultCanonicalJson.decode] (duplicates rejected) and
 * [PushBodyParser] (duplicates tolerated and recorded: the later member wins in the tree, as in
 * Python's `json.loads`, and [sawDuplicate] is set).
 */
internal class JsonParser(val text: String, private val allowDuplicateKeys: Boolean) {
    var pos: Int = 0

    /** Set when a duplicate key was met since the last reset (tolerant mode only). */
    var sawDuplicate: Boolean = false

    /** `(depth of the object, key)` of every duplicate met since the last clear (tolerant mode only). */
    val duplicateKeys: MutableList<Pair<Int, String>> = ArrayList()

    fun atEnd(): Boolean = pos >= text.length

    fun fail(message: String, at: Int = pos): Nothing = throw JsonParseException(message, at)

    fun skipWs() {
        while (pos < text.length) {
            when (text[pos]) {
                ' ', '\t', '\n', '\r' -> pos++
                else -> return
            }
        }
    }

    fun peek(): Char = if (pos < text.length) text[pos] else fail("unexpected end of input")

    fun expect(c: Char) {
        if (pos >= text.length || text[pos] != c) fail("expected '$c'")
        pos++
    }

    fun parseValue(depth: Int): JsonValue {
        if (depth > DefaultCanonicalJson.MAX_DEPTH) fail("nesting deeper than ${DefaultCanonicalJson.MAX_DEPTH}")
        return when (val c = peek()) {
            '{' -> parseObject(depth)
            '[' -> parseArray(depth)
            '"' -> JsonString(parseString())
            't' -> literal("true", JsonBool(true))
            'f' -> literal("false", JsonBool(false))
            'n' -> literal("null", JsonNull)
            else -> if (c == '-' || c in '0'..'9') parseNumber() else fail("unexpected character")
        }
    }

    private fun literal(word: String, v: JsonValue): JsonValue {
        if (!text.startsWith(word, pos)) fail("invalid literal")
        pos += word.length
        return v
    }

    fun parseObject(depth: Int): JsonObject {
        expect('{')
        val members = LinkedHashMap<String, JsonValue>()
        skipWs()
        if (peek() == '}') {
            pos++
            return JsonObject(members)
        }
        while (true) {
            skipWs()
            if (peek() != '"') fail("expected a member name")
            val keyAt = pos
            val key = parseString()
            skipWs()
            expect(':')
            skipWs()
            val v = parseValue(depth + 1)
            if (key in members) {
                if (!allowDuplicateKeys) fail("duplicate key \"$key\"", keyAt)
                sawDuplicate = true
                duplicateKeys.add(depth to key)
                members.remove(key) // the later member wins, at its own position
            }
            members[key] = v
            skipWs()
            when (peek()) {
                ',' -> pos++
                '}' -> {
                    pos++
                    return JsonObject(members)
                }
                else -> fail("expected ',' or '}'")
            }
        }
    }

    fun parseArray(depth: Int): JsonArray {
        expect('[')
        val items = ArrayList<JsonValue>()
        skipWs()
        if (peek() == ']') {
            pos++
            return JsonArray(items)
        }
        while (true) {
            skipWs()
            items.add(parseValue(depth + 1))
            skipWs()
            when (peek()) {
                ',' -> pos++
                ']' -> {
                    pos++
                    return JsonArray(items)
                }
                else -> fail("expected ',' or ']'")
            }
        }
    }

    fun parseString(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            if (pos >= text.length) fail("unterminated string")
            val c = text[pos]
            when {
                c == '"' -> {
                    pos++
                    return sb.toString()
                }
                c == '\\' -> {
                    pos++
                    if (pos >= text.length) fail("unterminated escape")
                    when (text[pos]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000c')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            val at = pos - 1
                            val unit = hex4()
                            if (unit.isHighSurrogate()) {
                                if (!text.startsWith("\\u", pos + 1)) fail("unpaired surrogate", at)
                                pos += 2
                                val low = hex4()
                                if (!low.isLowSurrogate()) fail("unpaired surrogate", at)
                                sb.append(unit).append(low)
                            } else if (unit.isLowSurrogate()) {
                                fail("unpaired surrogate", at)
                            } else {
                                sb.append(unit)
                            }
                        }
                        else -> fail("invalid escape")
                    }
                    pos++
                }
                c.code < 0x20 -> fail("control character in string")
                c.isHighSurrogate() -> {
                    if (pos + 1 >= text.length || !text[pos + 1].isLowSurrogate()) fail("unpaired surrogate")
                    sb.append(c).append(text[pos + 1])
                    pos += 2
                }
                c.isLowSurrogate() -> fail("unpaired surrogate")
                else -> {
                    sb.append(c)
                    pos++
                }
            }
        }
    }

    /** Reads the 4 hex digits after `\u` (pos is on the `u`); leaves pos on the last digit. */
    private fun hex4(): Char {
        if (pos + 4 >= text.length) fail("truncated \\u escape")
        var v = 0
        for (i in 1..4) {
            val d = text[pos + i].digitToIntOrNull(16) ?: fail("invalid \\u escape", pos + i)
            v = v * 16 + d
        }
        pos += 4
        return v.toChar()
    }

    private fun parseNumber(): JsonNumber {
        val start = pos
        if (text[pos] == '-') pos++
        if (pos >= text.length) fail("truncated number")
        when (text[pos]) {
            '0' -> pos++
            in '1'..'9' -> digits()
            else -> fail("invalid number")
        }
        if (pos < text.length && text[pos] == '.') {
            pos++
            if (pos >= text.length || text[pos] !in '0'..'9') fail("digit expected after '.'")
            digits()
        }
        if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
            pos++
            if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
            if (pos >= text.length || text[pos] !in '0'..'9') fail("digit expected in exponent")
            digits()
        }
        val d = text.substring(start, pos).toDouble()
        if (!d.isFinite()) fail("number overflows a double", start)
        return JsonNumber(d)
    }

    private fun digits() {
        while (pos < text.length && text[pos] in '0'..'9') pos++
    }
}
