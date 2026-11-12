package kotobaya.sync

import kotobaya.auth.Principal
import kotobaya.auth.TokenKind
import kotobaya.server.StartupSequence
import kotobaya.server.TestPg
import kotobaya.server.json
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Every `contracts/sync/examples/raw-push` body gets its verdict (SYNC.md 5.2 "Parsing the body",
 * C-2, C-5): `http_status` 400 = [PushOutcome.BadRequest] (the handler's `400 invalid_request`),
 * otherwise one result per op whose parse-stage `schema_invalid` (duplicate key, deeper than 500) is
 * exactly the expected per-op verdict ("checked" = passed those checks; it may still fail later). The examples
 * reuse op ids, so each runs against its own fresh database.
 */
class RawPushExamplesTest {
    @Test
    fun everyRawPushExampleGetsItsVerdict() {
        val dir = File(SyncFixture.contracts, "examples/raw-push")
        val cases = dir.listFiles { f -> f.name.endsWith(".body.txt") }!!.sortedBy { it.name }
        assertEquals(8, cases.size)
        for (case in cases) {
            val name = case.name.removeSuffix(".body.txt")
            val expected = json.readTree(File(dir, "$name.expected.json"))
            TestPg.migratedDb().use { db ->
                StartupSequence(db).runOnce()
                db.txBlocking { it.execute("insert into app.device (id, name, kind) values (?::uuid, 'b', 'android')", SyncFixture.B) }
                val module = SyncModule(db, SyncConfig(clock = { Instant.ofEpochMilli(0x0193a1b2c100L) }))
                val who = Principal(UUID.randomUUID(), "b", TokenKind.DEVICE, listOf("read", "write", "review"), UUID.fromString(SyncFixture.B), null)
                when (val o = module.push.push(case.readText(), who)) {
                    is PushOutcome.BadRequest -> assertEquals(400, expected["http_status"].asInt(), "$name: ${o.message}")
                    is PushOutcome.Ok -> {
                        if (expected["http_status"].asInt() != 200) fail("$name: expected 400, got ${o.results}")
                        assertEquals(expected["per_op"].size(), o.results.size, name)
                        for ((i, verdict) in expected["per_op"].withIndex()) {
                            val r = o.results[i]
                            // The parse-stage verdict: a duplicate key or a too-deep element (the op may still fail its schema later).
                            val parseStage = r.status == PushResult.REJECTED && r.error?.code == RejectCode.SCHEMA_INVALID &&
                                (r.error.message.startsWith("the op repeats a key") || r.error.message.startsWith("the op is nested deeper"))
                            assertEquals(verdict.asText() == "schema_invalid", parseStage, "$name[$i]: $r")
                        }
                    }
                }
            }
        }
    }
}
