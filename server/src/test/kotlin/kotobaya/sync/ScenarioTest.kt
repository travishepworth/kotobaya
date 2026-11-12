package kotobaya.sync

import com.fasterxml.jackson.databind.JsonNode
import kotobaya.contracts.sync.OpType
import kotobaya.server.json
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The worked examples of SYNC.md 12.1–12.4 as server tests (SYNC.md 13, "Server"): each device's
 * ops are pushed in log order with the server clock at `wall(op.hlc)`; ops the server minted
 * without a cause are applied as the server's own; then the tables must hold `expected`, and every
 * `rejected[].op` must get its code.
 *
 * Scenarios whose log contains server-emitted merges (`caused_by_op_id` set: 12.3
 * `same-word-added-twice`, `merge-two-level*`, `duplicate-*`, `rematch-arrival-order-a`,
 * `survivor-deleted-concurrently`) need the duplicate detection of SYNC.md 8.1, which is T202c's;
 * [mergeScenariosAreLeftToT202c] pins that list so none is silently skipped.
 */
class ScenarioTest : SyncFixture() {
    private fun scenario(id: String): JsonNode = json.readTree(File(contracts, "examples/scenarios/$id.json"))

    private val pk = mapOf(
        "settings" to listOf("key"), "word_status" to listOf("entry_seq"), "deck_item" to listOf("deck_id", "item_key"),
        "confusable" to listOf("note_a_id", "note_b_id", "reason"),
        "sentence_grammar_span" to listOf("sentence_id", "grammar_point_id", "start_offset", "end_offset", "origin"),
    )

    private fun keyOf(table: String, partial: JsonNode): Array<Pair<String, Any?>> =
        (pk[table] ?: listOf("id")).map { c ->
            val v = partial[c] ?: if (c == "origin") json.valueToTree("manual") else error("$table key $c missing")
            c to (if (v.isNumber) v.longValue() else v.asText())
        }.toTypedArray()

    private fun run(id: String) {
        val s = scenario(id)
        for (op in s["log"]) {
            if (op["device_id"].asText() == SERVER) {
                check(op["caused_by_op_id"].isNull) { "$id: server-emitted merges are T202c" }
                syncNow.set(java.time.Instant.ofEpochMilli(wall(op["hlc"].asText())))
                val type = OpType.entries.first { it.wire == op["op_type"].asText() }
                emit(type, json.writeValueAsString(op["payload"]))
            } else {
                assertAccepted(pushAsOwner(op))
            }
        }
        val lastWall = wall(s["log"].last()["hlc"].asText())
        for (r in s.path("rejected")) {
            val res = pushAsOwner(r["op"], at = r["received_at"]?.asLong() ?: lastWall)
            assertRejected(r["code"].asText(), res)
        }
        for ((table, rows) in s["expected"].path("rows").fields()) {
            for (exp in rows) {
                val actual = row(table, *keyOf(table, exp))
                assertNotNull(actual, "$id: $table $exp missing")
                for ((col, v) in exp.fields()) assertJsonEquals(v, actual[col], "$id: $table.$col of ${keyOf(table, exp).toList()}")
            }
        }
        for ((table, keys) in s["expected"].path("absent").fields()) {
            for (k in keys) assertNull(row(table, *keyOf(table, k)), "$id: $table $k must not exist")
        }
        for ((card, ids) in s["expected"].path("replay_sets").fields()) {
            val actual = db.txBlocking { ReplaySets.ids(it, UUID.fromString(card)) }.map(UUID::toString)
            assertEquals(ids.map { it.asText() }, actual, "$id: replay set of $card")
        }
        // Replaying the whole log onto the up-to-date server changes nothing (7.8): every op is a duplicate.
        for (op in s["log"]) {
            if (op["device_id"].asText() != SERVER) assertEquals(PushResult.DUPLICATE, pushAsOwner(op).status)
        }
    }

    // 12.1
    @Test fun workedExample12_1_reviewsSameCardOffline() = run("reviews-same-card-offline")

    // 12.2
    @Test fun workedExample12_2_editVersusDelete() = run("edit-vs-delete")

    // 12.4 rows that need no server-emitted merge
    @Test fun workedExample12_4_noteFieldsLww() = run("note-fields-lww")

    @Test fun workedExample12_4_lwwSettingsSuspendBuryWordStatus() = run("lww-settings-suspend-bury-word-status")

    @Test fun workedExample12_4_undoOnTwoDevices() = run("undo-on-two-devices")

    @Test fun workedExample12_4_deckItemPutRemove() = run("deck-item-put-remove")

    @Test fun workedExample12_4_rejectedOps() = run("rejected-ops")

    @Test fun workedExample12_4_rematchArrivalOrderB() = run("rematch-arrival-order-b")

    @Test fun workedExample12_4_clockBehindRemint() = run("clock-behind-remint")
}
