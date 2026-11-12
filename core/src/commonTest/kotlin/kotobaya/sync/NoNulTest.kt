package kotobaya.sync

import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.Op
import kotobaya.contracts.sync.OpDecodeResult
import kotobaya.contracts.sync.OpSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Op-level rule `NO_NUL` (SYNC.md 2.1 "Storable values", C-6) in [DefaultOpCodec], against
 * `contracts/sync/examples/invalid-codec/` (embedded in [SyncExamples.invalidCodec]).
 *
 * U+0000 is always built at run time (`Char(0)`, or a JSON `\u0000` escape decoded by the parser),
 * never written as a source literal: the JS build mangles those (T202a attempt 2).
 */
class NoNulTest {
    private val c = DefaultOpCodec
    private val j = DefaultCanonicalJson
    private val nul = Char(0).toString()

    private fun tree(text: String): JsonObject = j.decode(text).value as JsonObject

    /** File name → expected pointer of the `NO_NUL` verdict (verdict: `schema_invalid`). */
    private val expectations: Map<String, String> = mapOf(
        "op-nul-in-member-name.json" to "/payload/value/a${nul}b",
        "settings-set-nul-in-open-value.json" to "/payload/value/1",
    )

    private val settings: Op by lazy {
        assertIs<OpDecodeResult.Known>(c.decode(SyncExamples.ops.getValue("settings.set.json"), OpSource.PUSHED)).op
    }

    @Test
    fun everyInvalidCodecExampleIsRejectedByNoNulOnEveryPath() {
        assertEquals(expectations.keys, SyncExamples.invalidCodec.keys, "examples/invalid-codec changed: update the expectations")
        for ((name, text) in SyncExamples.invalidCodec) {
            val file = tree(text)
            assertEquals(JsonString("nul"), file.members["rule"], name)
            val schema = (file.members.getValue("schema") as JsonString).value
            val instance = file.members.getValue("instance") as JsonObject
            // schema-valid: only the op-level rule decides
            assertNull(SyncSchemas.validate(schema, instance), "$name must be schema-valid")
            val pointer = expectations.getValue(name)

            // pushed: tree and text
            val r = assertIs<OpDecodeResult.Invalid>(c.decodeTree(instance, OpSource.PUSHED), name)
            assertEquals(pointer, r.pointer, "$name: ${r.message}")
            assertEquals(pointer, assertIs<OpDecodeResult.Invalid>(c.decode(j.encode(instance), OpSource.PUSHED), name).pointer)
            assertEquals(pointer, assertIs<OpDecodeResult.Invalid>(c.decode(text.substringAfter("\"instance\": ").trimEnd().removeSuffix("}"), OpSource.PUSHED), name).pointer)

            // pulled: same verdict, seq kept
            val pulled = JsonObject(instance.members + ("seq" to JsonNumber(7.0)) + ("caused_by_op_id" to JsonNull))
            val p = assertIs<OpDecodeResult.Invalid>(c.decodeTree(pulled, OpSource.PULLED), name)
            assertEquals(pointer, p.pointer)
            assertEquals(7L, p.seq)

            // never minted: encode / encodeTree / sizeBytes throw
            val op = settings.copy(payload = instance.members.getValue("payload") as JsonObject)
            assertFailsWith<IllegalArgumentException>(name) { c.encodeTree(op) }
            assertFailsWith<IllegalArgumentException>(name) { c.encode(op) }
            assertFailsWith<IllegalArgumentException>(name) { c.sizeBytes(op) }
            // ... but op_too_large is measured first (5.3), so the raw size is defined
            assertEquals(j.utf8Size(j.encode(instance)), c.rawSizeBytes(instance))

            // in a push body: a per-op schema_invalid, never a whole-body 400
            val body = assertIs<PushBody.Ops>(PushBodyParser.parse("""{"ops":[${j.encode(instance)}]}"""), name)
            val el = assertIs<PushElement.Parsed>(body.elements.single(), name)
            assertEquals(pointer, assertIs<OpDecodeResult.Invalid>(c.decodeTree(el.tree, OpSource.PUSHED)).pointer)
        }
    }

    @Test
    fun pushBodyWithOneNulOpKeepsTheOthers() {
        val good = SyncExamples.ops.getValue("settings.set.json")
        val bad = j.encode(
            JsonObject(
                tree(good).members + ("op_id" to JsonString("0193a1b2-00ff-7000-8000-0000000000ff")) +
                    ("payload" to JsonObject(mapOf("key" to JsonString("scheduler.new_per_day"), "value" to JsonString("a${nul}")))),
            ),
        )
        val body = assertIs<PushBody.Ops>(PushBodyParser.parse("""{"ops":[$good,$bad]}"""))
        val (first, second) = body.elements.map { assertIs<PushElement.Parsed>(it) }
        assertIs<OpDecodeResult.Known>(c.decodeTree(first.tree, OpSource.PUSHED))
        assertEquals("/payload/value", assertIs<OpDecodeResult.Invalid>(c.decodeTree(second.tree, OpSource.PUSHED)).pointer)
    }

    @Test
    fun envelopeAndPayloadStringsAndNamesAreAllChecked() {
        val base = c.encodeTree(settings)
        // a NUL in a typed envelope string fails (lenient shape or schema): schema_invalid either way
        for (key in listOf("op_id", "entity_id", "device_id", "hlc", "entity_type")) {
            val t = JsonObject(base.members + (key to JsonString((base.members.getValue(key) as JsonString).value + nul)))
            assertIs<OpDecodeResult.Invalid>(c.decodeTree(t, OpSource.PUSHED), key)
        }
        // an unknown top-level member name with NUL: NO_NUL decides before additionalProperties
        val extra = JsonObject(base.members + ("x${nul}" to JsonNumber(1.0)))
        assertEquals("/x$nul", assertIs<OpDecodeResult.Invalid>(c.decodeTree(extra, OpSource.PUSHED)).pointer)
        // RFC 6901 escaping of '~' and '/' in the pointer
        val esc = settings.copy(
            payload = JsonObject(settings.payload.members + ("value" to JsonObject(mapOf("a/b~c" to JsonArray(listOf(JsonString(nul))))))),
        )
        val r = assertIs<OpDecodeResult.Invalid>(c.decodeTree(JsonObject(base.members + ("payload" to esc.payload)), OpSource.PUSHED))
        assertEquals("/payload/value/a~1b~0c/0", r.pointer)
        // a NUL-free op of every shape still round-trips (no false positive on "\\u0000" spelled out)
        val spelled = settings.copy(payload = JsonObject(settings.payload.members + ("value" to JsonString("\\u0000"))))
        assertEquals(OpDecodeResult.Known(spelled), c.decode(c.encode(spelled), OpSource.PUSHED))
    }

    @Test
    fun unknownTypeWithNulStaysUnsupported() {
        // 5.3: unsupported_op is checked before the schema_invalid step that holds NO_NUL
        val base = c.encodeTree(settings)
        val t = JsonObject(
            base.members + ("op_type" to JsonString("foo.bar")) +
                ("payload" to JsonObject(mapOf("k" to JsonString("v$nul")))),
        )
        val r = assertIs<OpDecodeResult.Unsupported>(c.decodeTree(t, OpSource.PUSHED))
        assertEquals("foo.bar", r.opType)
        // a newer version of a known type, too
        val v2 = JsonObject(base.members + ("op_version" to JsonNumber(99.0)) + ("payload" to JsonObject(mapOf("k${nul}" to JsonNumber(1.0)))))
        assertIs<OpDecodeResult.Unsupported>(c.decodeTree(v2, OpSource.PUSHED))
    }

    /** The walk is iterative: a NUL at the deepest legal level (op depth 500) is found, no stack overflow. */
    @Test
    fun nulAtMaxOpDepthIsFoundAndDeeperIsRefusedForDepth() {
        val max = DefaultCanonicalJson.MAX_OP_DEPTH
        // op {1} > payload {2} > n arrays in value: op depth 2 + n; the string sits inside the innermost array
        fun withNulAtOpDepth(opDepth: Int): Op {
            var v: JsonValue = JsonArray(listOf(JsonString("x${nul}y")))
            repeat(opDepth - 3) { v = JsonArray(listOf(v)) }
            return settings.copy(payload = JsonObject(settings.payload.members + ("value" to v)))
        }
        val base = c.encodeTree(settings)
        val edge = withNulAtOpDepth(max)
        val edgeTree = JsonObject(base.members + ("payload" to edge.payload))
        assertEquals(false, DefaultCanonicalJson.exceedsDepth(edgeTree, max))
        val r = assertIs<OpDecodeResult.Invalid>(c.decodeTree(edgeTree, OpSource.PUSHED))
        assertEquals("/payload/value" + "/0".repeat(max - 2), r.pointer)
        assertEquals(r.pointer, assertIs<OpDecodeResult.Invalid>(c.decode(j.encode(edgeTree), OpSource.PUSHED)).pointer)
        assertFailsWith<IllegalArgumentException> { c.encodeTree(edge) }
        // depth 501: the depth rule decides first (pointer ""), still schema_invalid
        val overTree = JsonObject(base.members + ("payload" to withNulAtOpDepth(max + 1).payload))
        assertEquals("", assertIs<OpDecodeResult.Invalid>(c.decodeTree(overTree, OpSource.PUSHED)).pointer)
        // the walk itself never recurses: far past any stack, on every platform
        var deep: JsonValue = JsonString(nul)
        repeat(100_000) { deep = JsonArray(listOf(deep)) }
        assertEquals("/0".repeat(100_000), c.nulPointer(deep))
        var clean: JsonValue = JsonString("ok")
        repeat(100_000) { clean = JsonObject(mapOf("k" to clean)) }
        assertNull(c.nulPointer(clean))
    }

    @Test
    fun everyExampleOpIsNulFree() {
        for ((name, text) in SyncExamples.ops) assertNull(c.nulPointer(tree(text)), name)
    }
}
