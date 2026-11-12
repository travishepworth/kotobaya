package kotobaya.sync.merge

import com.fasterxml.jackson.databind.JsonNode
import kotobaya.contracts.sync.OpType
import kotobaya.server.json
import kotobaya.sync.PushResult
import kotobaya.sync.ReplaySets
import kotobaya.sync.wall
import kotobaya.sync.fuzz.RealSyncFixture
import kotobaya.sync.fuzz.RefReplica
import kotobaya.sync.fuzz.ServerProjection
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every scenario file of `contracts/sync/examples/scenarios` (SYNC.md 13, "Server") through the real
 * push path with the T202c merge policy and core card cache — including the seven whose logs hold
 * server-emitted merges, which T202b had to leave out (`ScenarioTest.mergeScenariosAreLeftToT202c`).
 *
 * Each device op is pushed in log order. The server clock is set to `wall(op.hlc)`, or, when the op
 * triggers server-emitted ops, to `wall` of the first of them, so the merge HLCs the server mints are
 * exactly the log's (and with them the losers' `deleted_at`); the ids of server-created cards are
 * injected from the log. Then:
 * 1. the server's log equals the scenario's (type, device, hlc, payload, cause; server op ids differ);
 * 2. the tables hold `expected` rows, `absent` keys and `replay_sets`;
 * 3. every `rejected[].op` gets its code;
 * 4. for scenarios in the note/card/review subset: the server's SYNC.md 11 projection, card caches
 *    included, equals an independent replica that applied the log in seq order (the caches the server
 *    folded/replayed op by op equal a full replay);
 * 5. pushing every device op again gives `duplicate` and changes nothing.
 */
class MergeScenarioTest : RealSyncFixture() {
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
        val log = s["log"].toList()
        for (op in log) {
            if (op["device_id"].asText() == SERVER) {
                if (!op["caused_by_op_id"].isNull) continue // derived by the server below
                syncNow.set(Instant.ofEpochMilli(wall(op["hlc"].asText())))
                emit(OpType.entries.first { it.wire == op["op_type"].asText() }, json.writeValueAsString(op["payload"]))
                continue
            }
            val consequences = log.filter { it["caused_by_op_id"].asText(null) == op["op_id"].asText() }
            consequences.filter { it["op_type"].asText() == "card.created" }.forEach { serverCardIds += it["payload"]["id"].asText() }
            val at = consequences.firstOrNull()?.let { wall(it["hlc"].asText()) }
            assertAccepted(pushAsOwner(op, at))
        }
        assertTrue(serverCardIds.isEmpty(), "$id: server-created cards not used: $serverCardIds")

        // 1. The log the server wrote is the scenario's.
        val actual = db.txBlocking { dsl ->
            dsl.fetch("select op_id::text, op_type, device_id::text, hlc::text, payload::text, caused_by_op_id::text from app.change order by seq")
                .map { r -> List(6) { r.get(it, String::class.java) } }
        }
        assertEquals(log.size, actual.size, "$id: number of log entries")
        for ((want, got) in log.zip(actual)) {
            val label = "$id seq ${want["seq"]}"
            assertEquals(want["op_type"].asText(), got[1], label)
            assertEquals(want["device_id"].asText(), got[2], label)
            assertEquals(want["hlc"].asText(), got[3], "$label hlc")
            assertTrue(json.readTree(got[4]) == want["payload"], "$label payload: expected ${want["payload"]}, was ${got[4]}")
            assertEquals(want["caused_by_op_id"].asText(null), got[5], "$label caused_by_op_id")
            if (want["device_id"].asText() != SERVER) assertEquals(want["op_id"].asText(), got[0], label)
        }

        // 2. Expected rows, absent keys, replay sets.
        for ((table, rows) in s["expected"].path("rows").fields()) {
            for (exp in rows) {
                val row = row(table, *keyOf(table, exp))
                assertNotNull(row, "$id: $table $exp missing")
                for ((col, v) in exp.fields()) assertJsonEquals(v, row[col], "$id: $table.$col of ${keyOf(table, exp).toList()}")
            }
        }
        for ((table, keys) in s["expected"].path("absent").fields()) {
            for (k in keys) assertNull(row(table, *keyOf(table, k)), "$id: $table $k must not exist")
        }
        for ((card, ids) in s["expected"].path("replay_sets").fields()) {
            val got = db.txBlocking { ReplaySets.ids(it, UUID.fromString(card)) }.map(UUID::toString)
            assertEquals(ids.map { it.asText() }, got, "$id: replay set of $card")
        }

        // 3. Rejections.
        val lastWall = wall(log.last()["hlc"].asText())
        for (r in s.path("rejected")) assertRejected(r["code"].asText(), pushAsOwner(r["op"], at = r["received_at"]?.asLong() ?: lastWall))

        // 4. Convergence with an independent replica, caches included.
        if (log.all { it["op_type"].asText() in REF_TYPES }) {
            val ref = RefReplica()
            log.forEach(ref::apply)
            assertEquals(emptyList(), ServerProjection.diff(ServerProjection.of(db), ref.projection()), "$id: server vs replica")
        }

        // 5. Idempotency (7.8): every device op again is a duplicate; nothing new is logged.
        val before = count("select count(*) from app.change")
        for (op in log) if (op["device_id"].asText() != SERVER) assertEquals(PushResult.DUPLICATE, pushAsOwner(op).status)
        assertEquals(before, count("select count(*) from app.change"))
    }

    // SYNC.md 12.3
    @Test fun workedExample12_3_sameWordAddedTwice() = run("same-word-added-twice")

    // 12.4 merge rows
    @Test fun mergeTwoLevel() = run("merge-two-level")

    @Test fun mergeTwoLevelCards() = run("merge-two-level-cards")

    @Test fun duplicateDeletedBeforePush() = run("duplicate-deleted-before-push")

    @Test fun duplicateKanjiDeckItemConfusable() = run("duplicate-kanji-deck-item-confusable")

    @Test fun survivorDeletedConcurrently() = run("survivor-deleted-concurrently")

    @Test fun rematchArrivalOrderA() = run("rematch-arrival-order-a")

    // The scenarios without merges, again with the production hooks (and the replay-set / cache checks).
    @Test fun rematchArrivalOrderB() = run("rematch-arrival-order-b")

    @Test fun undoOnTwoDevices() = run("undo-on-two-devices")

    @Test fun clockBehindRemint() = run("clock-behind-remint")

    @Test fun reviewsSameCardOffline() = run("reviews-same-card-offline")

    @Test fun editVersusDelete() = run("edit-vs-delete")

    @Test fun noteFieldsLww() = run("note-fields-lww")

    @Test fun lwwSettingsSuspendBuryWordStatus() = run("lww-settings-suspend-bury-word-status")

    @Test fun deckItemPutRemove() = run("deck-item-put-remove")

    @Test fun rejectedOps() = run("rejected-ops")

    @Test
    fun everyScenarioFileIsCoveredHere() {
        val files = File(contracts, "examples/scenarios").listFiles()!!.map { it.name.removeSuffix(".json") }.toSet()
        val tests = MergeScenarioTest::class.java.declaredMethods.filter { it.isAnnotationPresent(Test::class.java) }.size - 1
        assertEquals(16, files.size, "a new scenario file must get a test here")
        assertEquals(files.size, tests)
    }

    companion object {
        /** Op types [RefReplica] implements. */
        val REF_TYPES = setOf(
            "note.created", "note.updated", "note.deleted", "note.merged", "card.created", "card.suspended", "card.unsuspended",
            "card.buried", "card.unburied", "card.flag_set", "card.deleted", "card.merged", "review.logged", "review.undone",
        )
    }
}
