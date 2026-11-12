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
