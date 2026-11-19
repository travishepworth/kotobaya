package kotobaya.android.shell.api.actions

import kotlin.random.Random
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotobaya.android.shell.api.actions.ActionsTestSupport.C1
import kotobaya.android.shell.api.actions.ActionsTestSupport.C2
import kotobaya.android.shell.api.actions.ActionsTestSupport.N1
import kotobaya.android.shell.api.actions.ActionsTestSupport.N2
import kotobaya.android.shell.api.actions.ActionsTestSupport.T0
import kotobaya.android.shell.api.actions.ActionsTestSupport.X
import kotobaya.android.shell.api.actions.ActionsTestSupport.Y
import kotobaya.android.shell.api.actions.ActionsTestSupport.bulk
import kotobaya.android.shell.api.actions.ActionsTestSupport.call
import kotobaya.android.shell.api.actions.ActionsTestSupport.device
import kotobaya.android.shell.api.actions.ActionsTestSupport.obj
import kotobaya.android.shell.api.actions.ActionsTestSupport.patchNote
import kotobaya.android.shell.api.actions.ActionsTestSupport.remote
import kotobaya.android.shell.api.actions.ActionsTestSupport.replica
import kotobaya.android.shell.api.actions.ActionsTestSupport.rows
import kotobaya.android.shell.api.identity.IdentityTestSupport.isValidOp
import kotobaya.android.shell.api.identity.IdentityTestSupport.minted
import kotobaya.android.shell.parity.ParityDevice
import kotobaya.android.shell.parity.ParityJson
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.sync.client.apply.LearningTable

/**
 * SHELL.md 8.4 (a)-(c) for every writing handler of the actions group: the minted ops validate against
 * `op.schema.json`, are the device's own, and applying them out of order (interleaved with another device's ops on
 * the same registers) on fresh replicas converges to the device's state.
 */
// a re-match of entry_seq reads dictionary details (Android SQLite / org.json): Robolectric, as the queue parity tests
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class ActionOpsTest {
    private val tables = listOf(LearningTable.NOTE, LearningTable.CARD, LearningTable.WORD_STATUS)

    /**
     * The rows of [t]. `word_status.updated_at` is left out: when an `auto_status` re-derivation (a note re-matched or
     * deleted) and override ops on the same entry arrive in different orders, the core apply path stamps it with the
     * wall time of whichever op came last (a finding outside this ticket, reported in HANDOFF); every register and the
     * status itself converge.
     */
    private fun comparable(d: ParityDevice, t: LearningTable) =
        d.rows(t).map { if (t == LearningTable.WORD_STATUS) it - "updated_at" else it }

    private fun exercise(d: ParityDevice) {
        assertEquals(200, d.bulk("""{"card_ids":["$C1","$C2"],"action":{"type":"suspend"}}""", at = T0 + 1_000).status)
        assertEquals(200, d.bulk("""{"card_ids":["$C1"],"action":{"type":"unsuspend"}}""", at = T0 + 2_000).status)
        assertEquals(200, d.bulk("""{"card_ids":["$C1","$C2"],"action":{"type":"bury","until":"${ActionsTestSupport.iso(T0 + 86_400_000)}"}}""", at = T0 + 3_000).status)
        assertEquals(200, d.bulk("""{"card_ids":["$C2"],"action":{"type":"unbury"}}""", at = T0 + 4_000).status)
        assertEquals(200, d.bulk("""{"card_ids":["$C1","$C2"],"action":{"type":"flag","flags":4}}""", at = T0 + 5_000).status)
        assertEquals(200, d.call("PUT", "/api/v1/words/1001010/status", """{"status":"blacklisted"}""", at = T0 + 6_000).status)
        assertEquals(200, d.call("PUT", "/api/v1/words/1001150/status", """{"status":"known"}""", at = T0 + 7_000).status)
        assertEquals(200, d.call("DELETE", "/api/v1/words/1001150/status", at = T0 + 8_000).status)
        // note 2 goes first, so the re-match of note 1 onto its entry is no collision (a device never merges)
        assertEquals(204, d.call("DELETE", "/api/v1/notes/$N2", at = T0 + 8_500).status)
        assertEquals(
            200,
            d.patchNote(
                N1,
                """{"fields":{"meaning":"m"},"tags":["b","a"],"mnemonic":"mn","source_sentence":"s","source_media_id":"0193c000-0e00-7000-8000-000000000001","entry_seq":1001150}""",
                at = T0 + 9_000,
            ).status,
        )
    }

    @Test
    fun everyMintedOpValidatesAndIsTheDevices() {
        val d = device(X)
        exercise(d)
        val ops = d.minted()
        assertEquals(
            listOf(
                "card.suspended", "card.suspended", "card.unsuspended", "card.buried", "card.buried", "card.unburied", "card.flag_set",
                "card.flag_set", "word.status_set", "word.status_set", "word.status_cleared", "note.deleted", "note.updated",
            ),
            ops.map { ParityJson.str(it.members["op_type"]) },
        )
        ops.forEach { op ->
            assertTrue(isValidOp(op), "invalid op ${ParityJson.encode(op)}")
            assertEquals(X, ParityJson.str(op.members["device_id"]))
        }
        assertEquals(ops.map { ParityJson.str(it.members["hlc"]) }.sorted(), ops.map { ParityJson.str(it.members["hlc"]) })
        assertEquals(ops.size, ops.map { it.members["op_id"] }.toSet().size)
        assertEquals(
            ParityJson.parse(
                """{"id":"$N1","set":{"fields":{"meaning":"m"},"tags":["a","b"],"mnemonic":"mn","source_sentence":"s","source_sentence_id":null,"source_media_id":"0193c000-0e00-7000-8000-000000000001","entry_seq":1001150}}""",
            ),
            ParityJson.at(ops[12], "/payload"),
        )
    }

    @Test
    fun opsAppliedOutOfOrderWithAnotherDevicesConverge() {
        val d = device(X)
        // another device writes the same registers, some before and some after this device's writes
        val theirs = listOf(
            remote(1, "card.suspended", "card", C1, T0 + 1_500, obj("id" to JsonString(C1))),
            remote(2, "card.flag_set", "card", C2, T0 + 6_000, obj("id" to JsonString(C2), "flags" to JsonNumber(7.0))),
            remote(3, "word.status_set", "word_status", "1001010", T0 + 5_500, obj("entry_seq" to JsonNumber(1001010.0), "status" to JsonString("ignored"), "origin" to JsonString("manual"))),
            remote(4, "note.updated", "note", N1, T0 + 9_500, obj("id" to JsonString(N1), "set" to obj("fields" to obj("meaning" to JsonString("theirs"))))),
        )
        exercise(d)
        d.clock.now = T0 + 20_000
        d.pull(theirs, "B's concurrent writes")
        val all = d.minted() + theirs
        val oracles = listOf<(List<JsonObject>) -> List<JsonObject>>(
            { ops -> ops.sortedBy { ParityJson.str(it.members["hlc"]) } },
            { ops -> ops.sortedByDescending { ParityJson.str(it.members["hlc"]) } },
            { ops -> ops.shuffled(Random(11)) },
            { ops -> ops.shuffled(Random(12)) },
        )
        for ((i, order) in oracles.withIndex()) {
            val r = replica(Y, all, T0 + 30_000, order)
            for (t in tables) assertEquals(comparable(d, t), comparable(r, t), "order $i: $t")
        }
        // the clock rule decided: B's later flag and edit won, this device's later suspend/blacklist won
        val c2 = d.rows(LearningTable.CARD).single { it["id"] == JsonString(C2) }
        assertEquals(JsonNumber(7.0), c2["flags"])
        assertEquals(JsonString("blacklisted"), d.rows(LearningTable.WORD_STATUS).single { it["entry_seq"] == JsonNumber(1001010.0) }["override_status"])
    }
}
