package kotobaya.sync

import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.OpDecodeResult
import kotobaya.contracts.sync.OpSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SYNC.md 5.2 "Parsing the body" (C-2): the duplicate-tolerant push-body parse. */
class PushBodyParserTest {
    private val j = DefaultCanonicalJson
    private val c = DefaultOpCodec

    /** The verdict in the shape of `examples/raw-push/<name>.expected.json`. */
    private fun verdict(body: PushBody): JsonObject = when (body) {
        is PushBody.BadRequest -> JsonObject(mapOf("http_status" to JsonNumber(400.0)))
        is PushBody.Ops -> JsonObject(
            mapOf(
                "http_status" to JsonNumber(200.0),
                "per_op" to JsonArray(
                    body.elements.map {
                        JsonString(if (it is PushElement.DuplicateKey) "schema_invalid" else "checked")
                    },
                ),
            ),
        )
    }

    @Test
    fun everyRawPushExampleGetsItsVerdict() {
        assertEquals(6, SyncExamples.rawPushBodies.size)
        for ((name, body) in SyncExamples.rawPushBodies) {
            val expected = j.decode(SyncExamples.rawPushExpected.getValue(name.replace(".body.txt", ".expected.json"))).value
            assertEquals(expected, verdict(PushBodyParser.parse(body)), name)
        }
    }

    /** Acceptance: a duplicate key in one op is a per-op error, not a whole-body failure. */
    @Test
    fun duplicateKeyInOneOpIsAPerOpErrorNotAWholeBodyFailure() {
        val body = SyncExamples.rawPushBodies.getValue("dup-key-inside-op.body.txt")
        // the strict decoder refuses the whole body ...
        assertNotNull(j.decode(body).error)
        // ... the push-body parse isolates the bad element; the others go on to the per-op checks
        val parsed = assertIs<PushBody.Ops>(PushBodyParser.parse(body))
        val dup = parsed.elements.filterIsInstance<PushElement.DuplicateKey>()
        assertEquals(1, dup.size)
        assertEquals("0193a1b2-00ff-7005-8000-000000000004", dup[0].opId)
        assertTrue(dup[0].sourceText.startsWith("{") && dup[0].sourceText.endsWith("}"))
        for (el in parsed.elements.filterIsInstance<PushElement.Parsed>()) {
            assertIs<OpDecodeResult.Known>(c.decodeTree(el.tree, OpSource.PUSHED), el.opId)
            assertTrue(c.rawSizeBytes(el.tree) > 0)
        }
    }

    /** A legal-looking op nested too deep is a per-op error, never a 400 that would block the outbox. */
    @Test
    fun tooDeepElementIsAPerOpErrorNotAWholeBodyFailure() {
        val good = SyncExamples.rawPushBodies.getValue("same-op-id-equal-contents.body.txt")
        val goodOp = (j.decode(good).value as JsonObject).members.getValue("ops").let { (it as JsonArray).items[0] }
        val goodText = j.encode(goodOp)
        val depth = DefaultCanonicalJson.MAX_DEPTH + 100
        val deepId = "0193a1b2-00ff-7005-8000-0000000000dd"
        val deep = """{"op_id":"$deepId","payload":{"x":${"[".repeat(depth)}1,{"k":"v","k":"w"}${"]".repeat(depth)}},"z":null}"""
        val r = assertIs<PushBody.Ops>(PushBodyParser.parse("""{"ops":[$goodText,$deep,$goodText]}"""))
        assertEquals(3, r.elements.size)
        assertIs<PushElement.Parsed>(r.elements[0])
        val tooDeep = assertIs<PushElement.TooDeep>(r.elements[1])
        assertEquals(deepId, tooDeep.opId)
        assertEquals(deep, tooDeep.sourceText)
        assertIs<PushElement.Parsed>(r.elements[2])
        // the same element pushed twice is a retry, not a conflict
        assertIs<PushBody.Ops>(PushBodyParser.parse("""{"ops":[$deep,$deep]}"""))
        // exactly at the limit an element still parses (element root = depth 0, as for encode)
        val max = DefaultCanonicalJson.MAX_DEPTH
        val edge = """{"op_id":"$deepId","p":${"[".repeat(max)}${"]".repeat(max)}}"""
        assertIs<PushElement.Parsed>(assertIs<PushBody.Ops>(PushBodyParser.parse("""{"ops":[$edge]}""")).elements.single())
        val over = """{"op_id":"$deepId","p":${"[".repeat(max + 1)}${"]".repeat(max + 1)}}"""
        assertIs<PushElement.TooDeep>(assertIs<PushBody.Ops>(PushBodyParser.parse("""{"ops":[$over]}""")).elements.single())
        // still a whole-body 400: bad syntax deep down, a missing or repeated op_id, an op_id that is not a uuid
        val nest = "[".repeat(depth) + "]".repeat(depth)
        for (b in listOf(
            """{"ops":[{"op_id":"$deepId","p":${"[".repeat(depth)}1,${"]".repeat(depth)}}]}""",
            """{"ops":[{"op_id":"$deepId","p":${"[".repeat(depth)}${"]".repeat(depth - 1)}}]}""",
            """{"ops":[{"p":$nest}]}""",
            """{"ops":[{"op_id":"$deepId","op_id":"$deepId","p":$nest}]}""",
            """{"ops":[{"op_id":[],"p":$nest}]}""",
            """{"ops":[{"op_id":"$deepId","p":$nest},{"op_id":"$deepId","p":[]}]}""",
        )) {
            assertIs<PushBody.BadRequest>(PushBodyParser.parse(b), b.take(80))
        }
        // and a client cannot mint it: encode refuses the tree
        assertNull(j.decode(deep).value)
    }

    @Test
    fun wholeBodyFailures() {
        val op = """{"op_id":"0193a1b2-00ff-7005-8000-000000000004","x":1}"""
        val bad = listOf(
            "",
            "[]",
            "{}",
            """{"ops":[]}""",
            """{"ops":[$op],"ops":[$op]}""",
            """{"ops":[1]}""",
            """{"ops":[{"op_id":"0193A1B2-00FF-7005-8000-000000000004"}]}""",
            """{"ops":[{"x":1}]}""",
            """{"ops":[$op]} x""",
            """{"ops":[$op,]}""",
            """{"ops":[{"op_id":"0193a1b2-00ff-7005-8000-000000000004","op_id":"0193a1b2-00ff-7005-8000-000000000004"}]}""",
            """{"ops":[$op,{"op_id":"0193a1b2-00ff-7005-8000-000000000004","x":2}]}""",
            """{"ops":[${List(501) { op }.joinToString(",")}]}""",
        )
        for (b in bad) assertIs<PushBody.BadRequest>(PushBodyParser.parse(b), b)
    }

    @Test
    fun sameOpIdWithEqualContentsIsNotAnError() {
        val a = """{"op_id":"0193a1b2-00ff-7005-8000-000000000004","x":1.0,"y":[1]}"""
        val b = """{ "y" : [ 1 ], "x":1, "op_id":"0193a1b2-00ff-7005-8000-000000000004" }"""
        val r = assertIs<PushBody.Ops>(PushBodyParser.parse("""{"ops":[$a,$b]}"""))
        assertEquals(2, r.elements.size)
        // byte-identical elements with a duplicate key are equal too
        val d = """{"op_id":"0193a1b2-00ff-7005-8000-000000000004","p":{"k":1,"k":2}}"""
        val r2 = assertIs<PushBody.Ops>(PushBodyParser.parse("""{"ops":[$d,$d]}"""))
        assertTrue(r2.elements.all { it is PushElement.DuplicateKey })
        // a duplicate inside an unknown top-level member is not the body's business (validate.py)
        assertIs<PushBody.Ops>(PushBodyParser.parse("""{"ops":[$a],"meta":{"z":1,"z":2}}"""))
    }

    @Test
    fun duplicateAtAnyDepthOfAnElementIsDetected() {
        val id = "0193a1b2-00ff-7005-8000-000000000004"
        for (inner in listOf(""""a":1,"a":1""", """"a":[{"b":{"c":1,"c":2}}]""", """"payload":{"x":[[{"q":0,"q":0}]]}""")) {
            val r = assertIs<PushBody.Ops>(PushBodyParser.parse("""{"ops":[{"op_id":"$id",$inner}]}"""), inner)
            assertIs<PushElement.DuplicateKey>(r.elements.single(), inner)
        }
    }
}
