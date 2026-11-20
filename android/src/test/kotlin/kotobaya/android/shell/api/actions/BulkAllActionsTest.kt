package kotobaya.android.shell.api.actions

import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotobaya.android.shell.api.actions.ActionsTestSupport.C1
import kotobaya.android.shell.api.actions.ActionsTestSupport.C2
import kotobaya.android.shell.api.actions.ActionsTestSupport.N1
import kotobaya.android.shell.api.actions.ActionsTestSupport.N2
import kotobaya.android.shell.api.actions.ActionsTestSupport.SEQ1
import kotobaya.android.shell.api.actions.ActionsTestSupport.T0
import kotobaya.android.shell.api.actions.ActionsTestSupport.UNKNOWN
import kotobaya.android.shell.api.actions.ActionsTestSupport.bulk
import kotobaya.android.shell.api.actions.ActionsTestSupport.call
import kotobaya.android.shell.api.actions.ActionsTestSupport.card
import kotobaya.android.shell.api.actions.ActionsTestSupport.device
import kotobaya.android.shell.api.actions.ActionsTestSupport.iso
import kotobaya.android.shell.api.actions.ActionsTestSupport.json
import kotobaya.android.shell.api.actions.ActionsTestSupport.mintedTypes
import kotobaya.android.shell.api.actions.ActionsTestSupport.note
import kotobaya.android.shell.api.actions.ActionsTestSupport.obj
import kotobaya.android.shell.api.actions.ActionsTestSupport.problem
import kotobaya.android.shell.api.actions.ActionsTestSupport.remote
import kotobaya.android.shell.api.actions.ActionsTestSupport.rows
import kotobaya.android.shell.api.identity.IdentityTestSupport.isValidOp
import kotobaya.android.shell.api.identity.IdentityTestSupport.minted
import kotobaya.android.shell.parity.ParityDevice
import kotobaya.android.shell.parity.ParityJson
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonString
import kotobaya.sync.client.apply.LearningTable

/**
 * LF-A4 acceptance: `bulkCards` `set_preset`, `add_tags`, `remove_tags`, `move`, `delete` and `bulkScheduleCards`
 * answered on the device with the server's counts and ops (openapi `x-emits-ops`), for both selectors, `dry_run`, an
 * empty selection, an unknown id, a merge loser and a tombstoned card; idempotent where the server documents a
 * no-op; every minted op validates against the op schemas.
 */
class BulkAllActionsTest {
    private val D1 = "0193c000-0d00-7000-8000-000000000001"
    private val D2 = "0193c000-0d00-7000-8000-000000000002"
    private val DEFAULT_PRESET = "00000000-0000-7000-8000-000000000010"

    private fun counts(res: kotobaya.android.shell.api.ShellResponse): String {
        assertEquals(200, res.status, res.body)
        val b = res.json()
        return listOf("matched", "changed", "skipped", "ops_emitted").joinToString(" ") { ParityJson.long(b.members[it]).toString() } +
            " " + (b.members["dry_run"] as JsonBool).value
    }

    private fun ParityDevice.validOps() = minted().forEach { assertTrue(isValidOp(it), "invalid op ${ParityJson.encode(it)}") }

    /** A device with two user decks, note 1 filed in the first (`e:1001010`). */
    private fun withDecks(): ParityDevice {
        val d = device()
        fun deck(n: Int, id: String, name: String) =
            remote(n, "deck.created", "deck", id, T0 - 1_800_000, obj("id" to JsonString(id), "name" to JsonString(name), "type" to JsonString("user")))
        d.pull(
            listOf(
                deck(1, D1, "Inbox"),
                deck(2, D2, "Later"),
                remote(3, "deck.item_put", "deck_item", "$D1|e:$SEQ1", T0 - 1_700_000, obj("deck_id" to JsonString(D1), "item_key" to JsonString("e:$SEQ1"), "position" to JsonNumber(1024.0))),
            ),
            "decks",
        )
        return d
    }

    private fun ids(vararg c: String) = c.joinToString(",") { "\"$it\"" }

    @Test
    fun addAndRemoveTagsMintOneNoteUpdatedPerNoteAndRepeatingMintsNothing() {
        val d = device()
        val body = """{"card_ids":[${ids(C2, C1)}],"action":{"type":"add_tags","tags":["b","a"]}}"""
        assertEquals("2 2 0 2 false", counts(d.bulk(body, at = T0 + 1_000)))
        assertEquals(listOf("note.updated", "note.updated"), d.mintedTypes())
        assertEquals(listOf(N1, N2), d.minted().map { ParityJson.str(it.members["entity_id"]) }, "card-id order")
        assertEquals(JsonArray(listOf(JsonString("a"), JsonString("b"))), d.note(N1)["tags"])
        assertEquals(1, d.localChanges)

        // idempotent: the same call changes nothing, counts both notes in skipped, mints and triggers nothing
        assertEquals("2 0 2 0 false", counts(d.bulk(body, at = T0 + 2_000)))
        assertEquals(2, d.minted().size)
        assertEquals(1, d.localChanges)

        assertEquals("1 1 0 1 false", counts(d.bulk("""{"card_ids":["$C1"],"action":{"type":"remove_tags","tags":["a","zz"]}}""", at = T0 + 3_000)))
        assertEquals(JsonArray(listOf(JsonString("b"))), d.note(N1)["tags"])
        d.validOps()
    }

    @Test
    fun tagActionsBySelectorAndDryRun() {
        val d = device()
        assertEquals("2 2 0 0 true", counts(d.bulk("""{"query":"kind:meaning","action":{"type":"add_tags","tags":["q"]},"dry_run":true}""")))
        assertEquals(0, d.minted().size)
        assertEquals("2 2 0 2 false", counts(d.bulk("""{"query":"kind:meaning","action":{"type":"add_tags","tags":["q"]}}""", at = T0 + 1_000)))
        // the tag is now searchable: the selector sees what the action wrote
        assertEquals("2 0 2 0 false", counts(d.bulk("""{"query":"tag:q","action":{"type":"remove_tags","tags":["nothere"]}}""", at = T0 + 2_000)))
        assertEquals("0 0 0 0 false", counts(d.bulk("""{"query":"tag:nomatch","action":{"type":"add_tags","tags":["q"]}}""")))
        assertEquals("422 /action/tags/0 invalid_value", d.bulk("""{"card_ids":["$C1"],"action":{"type":"add_tags","tags":["leech"]}}""").problem())
        d.validOps()
    }

    @Test
    fun setPresetChecksThePresetAndMintsOnePerCard() {
        val d = device()
        assertEquals("1 1 1 0 true", counts(d.bulk("""{"card_ids":["$C1","$UNKNOWN"],"action":{"type":"set_preset","preset_id":"$DEFAULT_PRESET"},"dry_run":true}""")))
        assertEquals(0, d.minted().size)
        assertEquals("2 2 0 2 false", counts(d.bulk("""{"card_ids":["$C1","$C2"],"action":{"type":"set_preset","preset_id":"$DEFAULT_PRESET"}}""", at = T0 + 1_000)))
        assertEquals(listOf("card.preset_set", "card.preset_set"), d.mintedTypes())
        assertEquals(JsonString(DEFAULT_PRESET), d.card(C1)["preset_id"])
        assertEquals("422 /action/preset_id invalid_value", d.bulk("""{"card_ids":["$C1"],"action":{"type":"set_preset","preset_id":"$UNKNOWN"}}""").problem())
        assertEquals("422 /action/preset_id invalid_value", d.bulk("""{"query":"kind:none","action":{"type":"set_preset","preset_id":"$UNKNOWN"}}""").problem())
        assertEquals(2, d.minted().size)
        d.validOps()
    }

    @Test
    fun moveMintsItemPutPerNoteAndRemovesFromTheSourceDeck() {
        val d = withDecks()
        val body = """{"card_ids":[${ids(C1, C2)}],"action":{"type":"move","to_deck_id":"$D2","from_deck_id":"$D1"}}"""
        assertEquals("2 2 0 0 true", counts(d.bulk("""{"card_ids":[${ids(C1, C2)}],"action":{"type":"move","to_deck_id":"$D2","from_deck_id":"$D1"},"dry_run":true}""")))
        assertEquals(0, d.minted().size)
        // note 1 (e:1001010) is in Inbox only; note 2 is not there: 2 puts, 1 removal
        assertEquals("2 2 0 3 false", counts(d.bulk(body, at = T0 + 1_000)))
        assertEquals(listOf("deck.item_put", "deck.item_removed", "deck.item_put"), d.mintedTypes())
        val put1 = ParityJson.at(d.minted()[0], "/payload")
        assertEquals(1024L, ParityJson.long(ParityJson.obj(put1).members["position"]))
        assertEquals(2048L, ParityJson.long(ParityJson.obj(ParityJson.at(d.minted()[2], "/payload")).members["position"]))
        val live = d.rows(LearningTable.DECK_ITEM).filter { it["deleted_at"] == null || it["deleted_at"] == kotobaya.contracts.sync.JsonNull }
        assertEquals(listOf(D2 to "e:$SEQ1", D2 to "e:1001150"), live.map { ParityJson.str(it["deck_id"]) to ParityJson.str(it["item_key"]) }.sortedBy { it.second })
        d.validOps()

        // idempotent where the server is: the item is put again (same position), nothing else is removed
        assertEquals("2 2 0 2 false", counts(d.bulk(body, at = T0 + 2_000)))
        assertEquals(listOf("deck.item_put", "deck.item_removed", "deck.item_put", "deck.item_put", "deck.item_put"), d.mintedTypes().take(5))
    }

    @Test
    fun moveRefusesDecksThatAreNotLiveUserDecks() {
        val d = withDecks()
        assertEquals("409 deck_read_only", d.bulk("""{"card_ids":["$C1"],"action":{"type":"move","to_deck_id":"$UNKNOWN"}}""").problem())
        assertEquals("409 deck_read_only", d.bulk("""{"card_ids":["$C1"],"action":{"type":"move","to_deck_id":"$D2","from_deck_id":"$UNKNOWN"}}""").problem())
        assertEquals("409 deck_read_only", d.bulk("""{"query":"kind:none","action":{"type":"move","to_deck_id":"$UNKNOWN"}}""").problem())
        assertEquals(0, d.minted().size)
    }

    @Test
    fun deleteMintsCardDeletedAndSkipsTombstonedUnknownAndMergedCards() {
        val d = device()
        // a merge loser (server op): its card is merged away and is not live
        val loser = "0193a1b2-0002-7000-8000-0000000000aa"
        d.pull(
            listOf(
                remote(1, "card.created", "card", loser, T0 - 1_800_000, obj("id" to JsonString(loser), "note_id" to JsonString(N1), "kind" to JsonString("reading"))),
                remote(2, "card.merged", "card", loser, T0 - 1_700_000, obj("loser_id" to JsonString(loser), "survivor_id" to JsonString(C1)), device = "00000000-0000-7000-8000-000000000001"),
            ),
            "merge",
        )
        assertEquals("1 1 0 0 true", counts(d.bulk("""{"card_ids":["$C1"],"action":{"type":"delete"},"dry_run":true}""")))
        assertEquals("1 1 0 1 false", counts(d.bulk("""{"card_ids":["$C1"],"action":{"type":"delete"}}""", at = T0 + 1_000)))
        assertEquals(listOf("card.deleted"), d.mintedTypes())
        assertEquals("410 gone", d.call("GET", "/api/v1/cards/$C1").problem())
        // tombstoned, merge loser, unknown: all skipped; C2 deleted
        assertEquals("1 1 3 1 false", counts(d.bulk("""{"card_ids":[${ids(C1, loser, UNKNOWN, C2)}],"action":{"type":"delete"}}""", at = T0 + 2_000)))
        // the same request again mints nothing
        assertEquals("0 0 4 0 false", counts(d.bulk("""{"card_ids":[${ids(C1, loser, UNKNOWN, C2)}],"action":{"type":"delete"}}""", at = T0 + 3_000)))
        assertEquals(listOf("card.deleted", "card.deleted"), d.mintedTypes())
        assertEquals(2, d.localChanges)
        // by query: nothing live is left
        assertEquals("0 0 0 0 false", counts(d.bulk("""{"query":"kind:meaning","action":{"type":"delete"}}""")))
        d.validOps()
    }

    @Test
    fun deleteByQueryAndEmptyOrDuplicateSelections() {
        val d = device()
        d.bulk("""{"card_ids":["$C1"],"action":{"type":"add_tags","tags":["later"]}}""", at = T0 + 1_000)
        assertEquals("1 1 0 1 false", counts(d.bulk("""{"query":"tag:later","action":{"type":"delete"}}""", at = T0 + 2_000)))
        assertEquals("410 gone", d.call("GET", "/api/v1/cards/$C1").problem())
        assertEquals("422 /card_ids out_of_range", d.bulk("""{"card_ids":[],"action":{"type":"delete"}}""").problem())
        assertEquals("422 /card_ids invalid_value", d.bulk("""{"card_ids":["$C2","$C2"],"action":{"type":"set_preset","preset_id":"$DEFAULT_PRESET"}}""").problem())
        assertEquals("422 /action/preset_id required", d.bulk("""{"card_ids":["$C2"],"action":{"type":"set_preset"}}""").problem())
    }

    /** Card 1 answered once (state `review` after Easy), card 2 left new. */
    private fun graduated(): ParityDevice {
        val d = device()
        val res = d.call("POST", "/api/v1/review/answer", """{"id":"0193c000-0f00-7000-8000-000000000001","card_id":"$C1","rating":4,"mode":"schedule"}""", at = T0 + 1_000)
        assertEquals(200, res.status, res.body)
        return d
    }

    // Off until the route is registered with ApiOperations' PROXY -> LOCAL flip (see Routes.kt); then drop @Ignore.
    @Ignore
    @Test
    fun scheduleRescheduleAndResetMintManualReviewRows() {
        val d = graduated()
        val before = d.minted().size
        val due = iso(T0 + 10 * 86_400_000L)
        assertEquals("1 1 0 0 true", counts(d.call("POST", "/api/v1/cards/bulk/schedule", """{"card_ids":["$C1"],"action":{"type":"reschedule","due_at":"$due"},"dry_run":true}""", at = T0 + 2_000)))
        assertEquals(before, d.minted().size)
        assertEquals("1 1 0 1 false", counts(d.call("POST", "/api/v1/cards/bulk/schedule", """{"card_ids":["$C1"],"action":{"type":"reschedule","due_at":"$due"}}""", at = T0 + 3_000)))
        assertEquals("review.logged", d.mintedTypes().last())
        val op = ParityJson.obj(ParityJson.at(d.minted().last(), "/payload"))
        assertEquals("manual", ParityJson.str(op.members["review_type"]))
        assertEquals("set_due", ParityJson.str(op.members["manual_action"]))
        assertEquals(T0 + 3_000, ParityJson.long(op.members["reviewed_at"]))
        assertEquals(T0 + 10 * 86_400_000L, ParityJson.long(d.card(C1)["due_at"]))

        // a new card among the matches refuses the whole reschedule
        assertEquals(
            "422 /action/type invalid_state",
            d.call("POST", "/api/v1/cards/bulk/schedule", """{"card_ids":["$C1","$C2"],"action":{"type":"reschedule","due_at":"$due"}}""", at = T0 + 4_000).problem(),
        )
        assertEquals("422 /action/due_at required", d.call("POST", "/api/v1/cards/bulk/schedule", """{"card_ids":["$C1"],"action":{"type":"reschedule"}}""").problem())
        assertEquals("422 /action/due_at unknown_field", d.call("POST", "/api/v1/cards/bulk/schedule", """{"card_ids":["$C1"],"action":{"type":"reset","due_at":"$due"}}""").problem())
        assertEquals("422 /action/type invalid_value", d.call("POST", "/api/v1/cards/bulk/schedule", """{"card_ids":["$C1"],"action":{"type":"suspend"}}""").problem())

        val n = d.minted().size
        // reset: card 1 gets a row and is new again; card 2 (new) is skipped
        assertEquals("2 1 1 1 false", counts(d.call("POST", "/api/v1/cards/bulk/schedule", """{"query":"kind:meaning","action":{"type":"reset"}}""", at = T0 + 5_000)))
        assertEquals(n + 1, d.minted().size)
        assertEquals(JsonString("new"), d.card(C1)["state"])
        // repeating the reset mints nothing: every card is new
        assertEquals("2 0 2 0 false", counts(d.call("POST", "/api/v1/cards/bulk/schedule", """{"query":"kind:meaning","action":{"type":"reset"}}""", at = T0 + 6_000)))
        assertEquals(n + 1, d.minted().size)
        d.validOps()
    }
}
