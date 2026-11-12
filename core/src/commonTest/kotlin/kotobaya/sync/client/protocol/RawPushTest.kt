package kotobaya.sync.client.protocol

import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.OpDecodeResult
import kotobaya.contracts.sync.OpSource
import kotobaya.sync.DefaultCanonicalJson
import kotobaya.sync.DefaultOpCodec
import kotobaya.sync.JsonParser
import kotobaya.sync.PushBody
import kotobaya.sync.PushBodyParser
import kotobaya.sync.PushElement
import kotobaya.sync.SyncExamples
import kotobaya.sync.client.store.PendingOpRow
import kotobaya.sync.client.store.PendingState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Every `contracts/sync/examples/raw-push` case, seen from the client: the test server receives the
 * raw body (as if a buggy client or proxy had sent it for the device's outbox) and the client records
 * the answer. Expected: HTTP 400 → every row stays `pending` with `attempts + 1`; per op `checked` →
 * whatever the rest of SYNC.md 5.3 decides for that op (`acked` if valid); `schema_invalid` → the
 * row is `rejected` with that code. A correct client never sends these bodies (it pushes
 * `OpCodec.encode` output), so this checks the response handling.
 */
class RawPushTest {
    @Test
    fun everyRawPushVerdictIsReflectedInTheRowStates() {
        val names = SyncExamples.rawPushBodies.keys.sorted()
        assertEquals(8, names.size)
        for (name in names) {
            val body = SyncExamples.rawPushBodies.getValue(name)
            val expected = DefaultCanonicalJson.decode(SyncExamples.rawPushExpected.getValue(name.removeSuffix(".body.txt") + ".expected.json")).value as JsonObject
            val status = (expected.members.getValue("http_status") as JsonNumber).value.toInt()
            val server = TestServer(NOW)
            val dev = TestDevice(DEV_B, server, NOW)
            val parsed = PushBodyParser.parse(body)
            if (status == 400) {
                assertIs<PushBody.BadRequest>(parsed, name)
                dev.setting("k.one", 1)
                dev.setting("k.two", 2)
                dev.transport.substituteNextPushBody = body
                val r = assertIs<CycleResult.Failed>(dev.push(), name)
                assertEquals(FailureKind.BAD_REQUEST, r.kind, name)
                assertTrue(dev.rows().all { it.state == PendingState.PENDING && it.attempts == 1 }, name)
                assertEquals(0, server.log.size, name)
                continue
            }
            val elements = assertIs<PushBody.Ops>(parsed, name).elements
            val perOp = (expected.members.getValue("per_op") as JsonArray).items.map { (it as JsonString).value }
            assertEquals(perOp.size, elements.size, name)
            seed(dev, elements)
            dev.transport.substituteNextPushBody = body
            assertIs<CycleResult.Completed>(dev.push(), name)
            assertEquals(1, dev.transport.pushedBodies.size, name)
            val rows = dev.rows().associateBy { it.opId }
            for ((i, verdict) in perOp.withIndex()) {
                val row = rows.getValue(elements[i].opId)
                // `checked` passed the body parse; the op then meets the rest of 5.3 (here: its schema)
                val code = if (verdict == "checked") codeAfterParse(elements[i]) else verdict
                if (code == null) {
                    assertEquals(PendingState.ACKED, row.state, "$name[$i]")
                } else {
                    assertEquals(PendingState.REJECTED, row.state, "$name[$i]")
                    assertTrue(row.lastError!!.startsWith("$code: "), "$name[$i]: ${row.lastError}")
                }
            }
        }
    }

    /** The 5.3 verdict of a parse-clean element: null = accepted (the test server checks no references). */
    private fun codeAfterParse(el: PushElement): String? {
        val tree = assertIs<PushElement.Parsed>(el).tree
        return when (DefaultOpCodec.decodeTree(tree, OpSource.PUSHED)) {
            is OpDecodeResult.Known -> null
            is OpDecodeResult.Unsupported -> "unsupported_op"
            is OpDecodeResult.Invalid -> "schema_invalid"
        }
    }

    /** One outbox row per distinct op id of the body, taken from the element itself. */
    private fun seed(dev: TestDevice, elements: List<PushElement>) {
        val seen = HashSet<String>()
        for (el in elements) {
            if (!seen.add(el.opId)) continue
            val tree = when (el) {
                is PushElement.Parsed -> el.tree
                is PushElement.DuplicateKey -> JsonParser(el.sourceText, allowDuplicateKeys = true).parseValue(0) as JsonObject
                is PushElement.TooDeep -> JsonParser(el.sourceText, allowDuplicateKeys = true).parseValue(0) as JsonObject
            }
            val m = tree.members
            dev.store.transaction { tx ->
                tx.outbox.insert(
                    PendingOpRow(
                        opId = el.opId,
                        opType = (m.getValue("op_type") as JsonString).value,
                        opVersion = (m.getValue("op_version") as JsonNumber).value.toInt(),
                        entityType = (m.getValue("entity_type") as JsonString).value,
                        entityId = (m.getValue("entity_id") as JsonString).value,
                        hlc = (m.getValue("hlc") as JsonString).value,
                        payload = DefaultCanonicalJson.encode(m.getValue("payload")),
                        createdAt = NOW,
                    ),
                )
            }
        }
    }
}
