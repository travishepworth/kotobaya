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
                        JsonString(if (it is PushElement.Parsed) "checked" else "schema_invalid")
                    },
                ),
            ),
        )
    }

    @Test
    fun everyRawPushExampleGetsItsVerdict() {
        assertEquals(8, SyncExamples.rawPushBodies.size)
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

    /**
     * SYNC.md 2.1/5.2 (C-5): an element of depth 501–510 is a per-op error (never a 400 that would block
     * the outbox); depth 500 is fine; a body deeper than 512 (element > 510) is a 400.
     */
    @Test
    fun tooDeepElementIsAPerOpErrorNotAWholeBodyFailure() {
        val good = SyncExamples.rawPushBodies.getValue("same-op-id-equal-contents.body.txt")
        val goodOp = (j.decode(good).value as JsonObject).members.getValue("ops").let { (it as JsonArray).items[0] }
        val goodText = j.encode(goodOp)
        val deepId = "0193a1b2-00ff-7005-8000-0000000000dd"
        // element {1} > payload {2} > 505 arrays: depth 507
        val k = 505
        val deep = """{"op_id":"$deepId","payload":{"x":${"[".repeat(k)}1,{"k":"v","k":"w"}${"]".repeat(k)}},"z":null}"""
        val r = assertIs<PushBody.Ops>(PushBodyParser.parse("""{"ops":[$goodText,$deep,$goodText]}"""))
        assertEquals(3, r.elements.size)
        assertIs<PushElement.Parsed>(r.elements[0])
        val tooDeep = assertIs<PushElement.TooDeep>(r.elements[1])
        assertEquals(deepId, tooDeep.opId)
        assertEquals(deep, tooDeep.sourceText)
        assertIs<PushElement.Parsed>(r.elements[2])
        // the same element pushed twice is a retry, not a conflict
        assertIs<PushBody.Ops>(PushBodyParser.parse("""{"ops":[$deep,$deep]}"""))

        // element {"op_id":…,"p":m arrays} has depth 1 + m
        fun element(m: Int) = """{"op_id":"$deepId","p":${"[".repeat(m)}${"]".repeat(m)}}"""
        fun single(m: Int) = PushBodyParser.parse("""{"ops":[${element(m)}]}""")
        val max = DefaultCanonicalJson.MAX_OP_DEPTH
        assertIs<PushElement.Parsed>(assertIs<PushBody.Ops>(single(max - 1)).elements.single()) // depth 500
        assertIs<PushElement.TooDeep>(assertIs<PushBody.Ops>(single(max)).elements.single()) // 501
        assertIs<PushElement.TooDeep>(assertIs<PushBody.Ops>(single(509)).elements.single()) // 510: body 512
        assertIs<PushBody.BadRequest>(single(510)) // element 511: body 513

        // still a whole-body 400: bad syntax deep down, a missing or repeated op_id, an op_id that is not a uuid
        val nest = "[".repeat(k) + "]".repeat(k)
        for (b in listOf(
            """{"ops":[{"op_id":"$deepId","p":${"[".repeat(k)}1,${"]".repeat(k)}}]}""",
            """{"ops":[{"op_id":"$deepId","p":${"[".repeat(k)}${"]".repeat(k - 1)}}]}""",
            """{"ops":[{"p":$nest}]}""",
            """{"ops":[{"op_id":"$deepId","op_id":"$deepId","p":$nest}]}""",
            """{"ops":[{"op_id":[],"p":$nest}]}""",
            """{"ops":[{"op_id":"$deepId","p":$nest},{"op_id":"$deepId","p":[]}]}""",
        )) {
            assertIs<PushBody.BadRequest>(PushBodyParser.parse(b), b.take(80))
        }
        // as a document the element is fine (≤ 512), but as an op it is Invalid
        val plain = j.decode(element(max)).value
        assertNotNull(plain)
        assertIs<OpDecodeResult.Invalid>(c.decodeTree(plain, OpSource.PUSHED))
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
