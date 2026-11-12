package kotobaya.sync

import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.OpDecodeResult
import kotobaya.contracts.sync.OpSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Schema `pattern`s must give the same verdict on jvm and js (ECMA-262 semantics). Runs on both.
 * Instances are written with `{XXXX}` placeholders for code points, expanded at runtime (no
 * non-ASCII source literals reach the JS build).
 */
class PortableRegexTest {
    /** `{XXXX}` → the UTF-16 unit U+XXXX. */
    private fun x(spec: String): String = Regex("\\{([0-9a-fA-F]{4})\\}").replace(spec) { Char(it.groupValues[1].toInt(16)).toString() }

    private val uuid = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
    private val tag = "^\\S+$"

    /**
     * pattern, instance spec, does `new RegExp(pattern).test(instance)` hold. Every row was checked
     * with node: `new RegExp(p).test(s)` and `new RegExp(p, "u").test(s)` both give the expected value.
     */
    private val table: List<Triple<String, String, Boolean>> = listOf(
        Triple(uuid, "0193a1b2-0001-7000-8000-000000000001", true),
        Triple(uuid, "0193a1b2-0001-7000-8000-000000000001{000a}", false),
        Triple(uuid, "0193a1b2-0001-7000-8000-000000000001{000d}{000a}", false),
        Triple(uuid, "0193a1b2-0001-7000-8000-000000000001{2028}", false),
        Triple(uuid, "0193a1b2-0001-7000-8000-000000000001{0085}", false),
        Triple("^[0-9a-f]{4}$", "abcd{000a}", false),
        Triple("^[0-9a-f]{4}$", "abcd", true),
        Triple(tag, "anime", true),
        Triple(tag, "a{3000}b", false),
        Triple(tag, "a{00a0}b", false),
        Triple(tag, "a{1680}b", false),
        Triple(tag, "a{2007}b", false),
        Triple(tag, "a{202f}b", false),
        Triple(tag, "a{205f}b", false),
        Triple(tag, "a{feff}b", false),
        Triple(tag, "a{000b}b", false),
        Triple(tag, "a{2028}b", false),
        Triple(tag, "a{0085}b", true), // NEL is not ECMA whitespace
        Triple(tag, "a{200b}b", true), // zero-width space is not whitespace either
        Triple(tag, "{65e5}{672c}{8a9e}", true),
        Triple(tag, "front{000a}", false),
        Triple("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$", "note.created", true),
        Triple("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$", "note.created{000a}", false),
        Triple("^(e:[1-9][0-9]{0,15}|n:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$", "e:1358280{000a}", false),
        Triple("^(e:[1-9][0-9]{0,15}|n:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$", "e:1358280", true),
        Triple("^[0-9a-f]{64}$", "${"a".repeat(64)}{000a}", false),
        Triple("[\\s]", "{3000}", true),
        Triple("b", "abc", true),
    )

    @Test
    fun patternsBehaveAsEcmaScriptOnEveryPlatform() {
        for ((pattern, spec, expected) in table) {
            assertEquals(expected, PortableRegex.compile(pattern).containsMatchIn(x(spec)), "/$pattern/ on $spec")
        }
    }

    @Test
    fun unportableConstructsAreRefused() {
        for (p in listOf("\\d", "\\w", "\\b", "\\p{L}", "[\\S]", "a.b", "\\1", "\\v", "[]a]")) {
            assertFailsWith<IllegalArgumentException>(p) { PortableRegex.compile(p) }
        }
    }

    @Test
    fun everyEmbeddedPatternTranslates() {
        fun walk(v: JsonValue) {
            when (v) {
                is JsonObject -> v.members.forEach { (k, c) ->
                    if (k == "pattern" && c is JsonString) PortableRegex.compile(c.value)
                    walk(c)
                }
                is JsonArray -> v.items.forEach(::walk)
                else -> Unit
            }
        }
        for (f in SyncSchemas.files) walk(SyncSchemas.schema(f)!!)
    }

    private fun tree(text: String): JsonObject = DefaultCanonicalJson.decode(text).value as JsonObject

    private fun withPayload(op: JsonObject, name: String, value: JsonValue): JsonObject {
        val payload = LinkedHashMap((op.members.getValue("payload") as JsonObject).members)
        payload[name] = value
        return JsonObject(op.members + ("payload" to JsonObject(payload)))
    }

    /** The reviewer's scenarios: values a JVM regex would accept and JavaScript would not. */
    @Test
    fun opsWithNonEcmaValuesAreRejectedEverywhere() {
        val note = tree(SyncExamples.ops.getValue("note.created.json"))
        val codec = DefaultOpCodec
        for (badTag in listOf(x("{65e5}{672c}{3000}{8a9e}"), x("a{00a0}b"), "front\n")) {
            val op = withPayload(note, "tags", JsonArray(listOf(JsonString(badTag))))
            val r = codec.decodeTree(op, OpSource.PUSHED)
            assertIs<OpDecodeResult.Invalid>(r, badTag)
            assertEquals("/payload/tags/0", r.pointer)
        }
        val badId = withPayload(note, "source_media_id", JsonString("0193a1b2-0007-7000-8000-000000000001\n"))
        assertIs<OpDecodeResult.Invalid>(codec.decodeTree(badId, OpSource.PUSHED))
        val badOpId = JsonObject(note.members + ("op_id" to JsonString("0193a1b2-00ff-7000-8000-000000000008\n")))
        assertEquals("/op_id", (codec.decodeTree(badOpId, OpSource.PUSHED) as OpDecodeResult.Invalid).pointer)
        assertNotNull(SyncSchemas.validate("common.schema.json#/\$defs/hlc", JsonString("0193a1b2c008-0000-01931f3e-0000-7000-8000-0000000000a1\n")))
    }
}
