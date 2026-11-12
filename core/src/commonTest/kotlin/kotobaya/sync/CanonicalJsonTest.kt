package kotobaya.sync

import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `CanonicalJson` (contracts/sync/Json.kt). Runs on jvm and js: every golden here is the exact
 * JavaScript `JSON.stringify` output, so both platforms are held to the same strings.
 */
class CanonicalJsonTest {
    // Lone surrogates are built at runtime: as source literals the JS build turns them into '?'.
    private val HI = Char(0xD800).toString()
    private val HI2 = Char(0xD83D).toString()
    private val LO = Char(0xDC00).toString()

    private val j = DefaultCanonicalJson

    private fun canon(text: String): String {
        val r = j.decode(text)
        assertNull(r.error, "decode($text): ${r.error}")
        return j.encode(r.value!!)
    }

    @Test
    fun kdocExamples() {
        assertEquals("""{"a":[1,0.9,"食べる\n"],"b":null}""", canon("""{"a":[1,0.9,"食べる\n"],"b":null}"""))
        assertEquals("""{"a":[1],"b":null}""", canon("""{"b":null, "a": [1.0]}"""))
        assertEquals(
            """{"device_id":"d","op_id":"o"}""",
            canon("""{"op_id":"o","device_id":"d"}"""),
        )
    }

    @Test
    fun stringEscapingGoldens() {
        val s = JsonString("q\" b\\ \b\u000c\n\r\t \u0001\u001f / \u007f é 食 \ud83d\ude00 \u2028")
        assertEquals(
            "\"q\\\" b\\\\ \\b\\f\\n\\r\\t \\u0001\\u001f / \u007f é 食 \ud83d\ude00 \u2028\"",
            j.encode(s),
        )
        // escapes in the input are decoded and re-encoded raw
        assertEquals("\"/é食😀\"", canon("\"\\/\\u00e9\\u98df\\ud83d\\ude00\""))
        assertEquals("\"\\u001b\"", canon("\"\\u001B\""))
    }

    @Test
    fun nestedMembersSortedAtEveryDepthArraysKeepOrder() {
        assertEquals(
            """{"a":{"x":[3,{"m":1,"n":2},1],"y":true},"b":false,"c":{}}""",
            canon(""" { "c" : { } , "b":false, "a":{"y":true,"x":[3,{"n":2,"m":1},1]} } """),
        )
        // UTF-16 code unit order: "Z" < "a" < "é" < "😀"(d83d) < "ｱ"(ff71)
        assertEquals(
            """{"Z":1,"a":2,"é":3,"😀":5,"ｱ":4}""",
            canon("""{"ｱ":4,"é":3,"a":2,"😀":5,"Z":1}"""),
        )
    }

    @Test
    fun numbersMatchJavaScriptVectors() {
        for ((bits, expected) in JS_NUMBER_VECTORS) {
            val d = Double.fromBits(bits.toULong(16).toLong())
            assertEquals(expected, j.encode(JsonNumber(d)), "bits $bits")
            // and decoding the text gives the same double back (except -0 → 0)
            val back = j.decode(expected).value
            assertEquals(if (d == 0.0) 0.0 else d, (back as JsonNumber).value, "bits $bits")
        }
    }

    @Test
    fun numberGoldensFromTheContract() {
        assertEquals("1", canon("1.0"))
        assertEquals("0.9", canon("0.9"))
        assertEquals("100000000000000000000", canon("1e20"))
        assertEquals("1e+21", canon("1e21"))
        assertEquals("1e-7", canon("1e-7"))
        assertEquals("0.000001", canon("1e-6"))
        assertEquals("0", j.encode(JsonNumber(-0.0)))
        assertEquals("-5", canon("-5E0"))
        assertEquals("123", canon("1.23e2"))
        assertEquals("[1,2.5,-0.001]", canon("[1 , 2.50 , -1e-3]"))
        // decode(encode(v)) == v, including integral doubles >= 2^53
        for (d in listOf(1e20, 9007199254740992.0, 9007199254740994.0, 1e21, 0.1, 5e-324, 1.7976931348623157e308)) {
            assertEquals(JsonNumber(d), j.decode(j.encode(JsonNumber(d))).value)
        }
    }

    @Test
    fun randomDoublesRoundTrip() {
        val rnd = Random(53)
        repeat(3000) {
            val d = Double.fromBits(rnd.nextLong())
            if (!d.isFinite()) return@repeat
            val text = j.encode(JsonNumber(d))
            assertEquals(d, text.toDouble(), text)
            val back = j.decode(text).value as JsonNumber
            assertEquals(if (d == 0.0) 0.0 else d, back.value)
        }
    }

    @Test
    fun encodeRejectsUnpairedSurrogatesAndNonFinite() {
        assertFailsWith<IllegalArgumentException> { j.encode(JsonString("a${HI}b")) }
        assertFailsWith<IllegalArgumentException> { j.encode(JsonString(LO)) }
        assertFailsWith<IllegalArgumentException> { j.encode(JsonString("x$HI2")) }
        assertFailsWith<IllegalArgumentException> { j.encode(JsonNumber(Double.NaN)) }
        assertFailsWith<IllegalArgumentException> { j.encode(JsonNumber(Double.POSITIVE_INFINITY)) }
        assertFailsWith<IllegalArgumentException> { j.sizeOf(JsonObject(mapOf("k" to JsonString(HI)))) }
    }

    @Test
    fun decodeRejections() {
        val bad = listOf(
            "", " ", "{", "}", "[1,]", "{\"a\":1,}", "{\"a\" 1}", "{a:1}", "'a'", "01", "-", "1.", ".5", "1e",
            "+1", "0x10", "NaN", "Infinity", "-Infinity", "1e400", "-1e400", "tru", "nul", "true false",
            "{\"a\":1}x", "[1] [2]", "\"\\ud800\"", "\"\\udc00x\"", "\"a$HI\"", "\"\\x\"", "\"\\u12\"",
            "\"\u0001\"", "\"tab\there\"", "\"line\nbreak\"", "\"unterminated", "\ufeff{}",
            "{\"a\":1,\"a\":2}", "{\"x\":{\"b\":1,\"b\":1}}", "[{\"k\":1,\"k\":1}]", "\u00a01",
        )
        for (t in bad) {
            val r = j.decode(t)
            assertNull(r.value, "should reject: $t")
            assertNotNull(r.error, "should reject: $t")
            assertTrue(r.error!!.offset in 0..t.length, "offset of $t")
        }
    }

    @Test
    fun decodeAcceptsAnyRfc8259Text() {
        assertEquals(JsonNull, j.decode(" null ").value)
        assertEquals(JsonBool(true), j.decode("\ttrue\r\n").value)
        assertEquals(JsonNumber(-0.0), j.decode("-0").value)
        assertEquals(JsonNumber(0.0), j.decode("1e-400").value) // underflow rounds to 0, as JSON.parse
        assertEquals(JsonString("é\u0000"), j.decode("\"\\u00E9\\u0000\"").value)
        assertEquals(JsonArray(emptyList()), j.decode("[ ]").value)
        assertEquals(
            JsonObject(mapOf("b" to JsonArray(listOf(JsonNumber(1.0))), "a" to JsonObject(emptyMap()))),
            j.decode("{\"b\":[1],\"a\":{}}").value,
        )
    }

    @Test
    fun deepNestingIsAnErrorNotACrash() {
        val deep = "[".repeat(100_000) + "]".repeat(100_000)
        val r = j.decode(deep)
        assertNull(r.value)
        assertNotNull(r.error)
        val ok = "[".repeat(200) + "]".repeat(200)
        assertNotNull(j.decode(ok).value)
    }

    /** One limit on every path: decode, encode and sizeOf agree at the boundary (root = depth 0). */
    @Test
    fun depthLimitIsTheSameForDecodeAndEncode() {
        val max = DefaultCanonicalJson.MAX_DEPTH
        fun nested(n: Int): JsonValue {
            var v: JsonValue = JsonArray(emptyList())
            repeat(n - 1) { v = JsonArray(listOf(v)) }
            return v
        }
        // max + 1 nested arrays: the innermost sits at depth max
        val edge = "[".repeat(max + 1) + "]".repeat(max + 1)
        assertEquals(nested(max + 1), j.decode(edge).value)
        assertEquals(edge, j.encode(nested(max + 1)))
        assertEquals(edge.length, j.sizeOf(nested(max + 1)))
        val over = "[".repeat(max + 2) + "]".repeat(max + 2)
        assertNull(j.decode(over).value)
        assertFailsWith<IllegalArgumentException> { j.encode(nested(max + 2)) }
        assertFailsWith<IllegalArgumentException> { j.sizeOf(JsonObject(mapOf("k" to nested(max + 1)))) }
    }

    @Test
    fun unicodeEscapesTakeOnlyAsciiHexDigits() {
        assertEquals(JsonString("\u00e9"), j.decode("\"\\u00E9\"").value)
        val fullwidthA = Char(0xFF21).toString()
        val arabicIndicOne = Char(0x0661).toString()
        for (bad in listOf("\"\\u00e$fullwidthA\"", "\"\\u00e$arabicIndicOne\"", "\"\\u00eg\"")) {
            assertNull(j.decode(bad).value, bad)
        }
    }

    @Test
    fun encodingIsAFunctionOfTheValue() {
        val a = JsonObject(linkedMapOf("x" to JsonNumber(1.0), "y" to JsonArray(listOf(JsonString("s"), JsonNull))))
        val b = JsonObject(linkedMapOf("y" to JsonArray(listOf(JsonString("s"), JsonNull)), "x" to JsonNumber(1.0)))
        assertEquals(a, b)
        assertEquals(j.encode(a), j.encode(b))
        assertEquals("""{"x":1,"y":["s",null]}""", j.encode(a))
    }

    @Test
    fun sizesAreRawUtf8OfCompactCanonicalJson() {
        assertEquals(0, j.utf8Size(""))
        assertEquals(1, j.utf8Size("a"))
        assertEquals(2, j.utf8Size("é"))
        assertEquals(3, j.utf8Size("食"))
        assertEquals(4, j.utf8Size("😀"))
        assertEquals(3, j.utf8Size(HI)) // lone surrogate: as U+FFFD
        val v = j.decode(""" { "b" : "パンを食べる" , "a" : [ 1.0 , "\u00e9" ] } """).value!!
        // counted on the canonical text, never on the input: 19 ASCII bytes + é (2) + 6 kana/kanji (18)
        val canonical = j.encode(v)
        assertEquals("""{"a":[1,"é"],"b":"パンを食べる"}""", canonical)
        assertEquals(j.utf8Size(canonical), j.sizeOf(v))
        assertEquals(19 + 2 + 18, j.sizeOf(v))
        // a \u-escaped non-ASCII character counts as its raw UTF-8 bytes, not 6 escape bytes
        assertEquals(j.sizeOf(JsonString("食")), j.sizeOf(j.decode("\"\\u98df\"").value!!))
        assertEquals(5, j.sizeOf(JsonString("食")))
    }

    @Test
    fun randomTreesRoundTripAndAreOrderInsensitive() {
        val rnd = Random(7)
        repeat(300) {
            val v = randomValue(rnd, 0)
            val text = j.encode(v)
            val back = j.decode(text).value!!
            assertEquals(text, j.encode(back))
            assertEquals(j.utf8Size(text), j.sizeOf(v))
            assertEquals(text, j.encode(shuffle(v, rnd)))
        }
    }

    private fun shuffle(v: JsonValue, rnd: Random): JsonValue = when (v) {
        is JsonObject -> JsonObject(LinkedHashMap(v.members.entries.shuffled(rnd).associate { it.key to shuffle(it.value, rnd) }))
        is JsonArray -> JsonArray(v.items.map { shuffle(it, rnd) })
        else -> v
    }

    private fun randomValue(rnd: Random, depth: Int): JsonValue = when (if (depth > 3) rnd.nextInt(4) else rnd.nextInt(6)) {
        0 -> JsonNull
        1 -> JsonBool(rnd.nextBoolean())
        2 -> JsonNumber(if (rnd.nextBoolean()) rnd.nextLong(-1_000_000, 1_000_000).toDouble() else rnd.nextDouble() * 1e6)
        3 -> JsonString(randomString(rnd))
        4 -> JsonArray(List(rnd.nextInt(4)) { randomValue(rnd, depth + 1) })
        else -> JsonObject(LinkedHashMap((0 until rnd.nextInt(4)).associate { randomString(rnd) to randomValue(rnd, depth + 1) }))
    }

    private fun randomString(rnd: Random): String {
        val pool = listOf("a", "b", "Z", "\"", "\\", "\n", "\u0001", "é", "食", "😀", "/", "\u007f", " ")
        return (0 until rnd.nextInt(5)).joinToString("") { pool[rnd.nextInt(pool.size)] }
    }
}
