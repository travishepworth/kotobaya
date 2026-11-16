package kotobaya.android.shell.api.actions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotobaya.android.shell.api.actions.ActionsTestSupport.C1
import kotobaya.android.shell.api.actions.ActionsTestSupport.C2
import kotobaya.android.shell.api.actions.ActionsTestSupport.T0
import kotobaya.android.shell.api.actions.ActionsTestSupport.UNKNOWN
import kotobaya.android.shell.api.actions.ActionsTestSupport.bulk
import kotobaya.android.shell.api.actions.ActionsTestSupport.card
import kotobaya.android.shell.api.actions.ActionsTestSupport.device
import kotobaya.android.shell.api.actions.ActionsTestSupport.json
import kotobaya.android.shell.api.actions.ActionsTestSupport.mintedTypes
import kotobaya.android.shell.api.actions.ActionsTestSupport.problem
import kotobaya.android.shell.api.actions.ActionsTestSupport.rows
import kotobaya.android.shell.api.identity.IdentityTestSupport.minted
import kotobaya.android.shell.parity.ParityJson
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNumber
import kotobaya.sync.client.apply.LearningTable

/**
 * SHELL-L4 acceptance `BulkAtomicTest`: a `bulkCards` call is validated first, then minted in ONE transaction, all or
 * nothing (SHELL.md 4.3 "all-or-nothing as the server").
 *
 * Note on "one unknown id → 4xx": the contract (openapi `bulkCards`, server `BulkApi`) answers an id that is a valid
 * UUID but names no live card with `200` and counts it in `skipped`; only an id that is not an id at all is a `422`.
 * Both are tested here, the `4xx` case with zero ops.
 */
class BulkAtomicTest {
    private fun counts(body: kotobaya.contracts.sync.JsonObject): String =
        listOf("matched", "changed", "skipped", "ops_emitted").joinToString(" ") { ParityJson.long(body.members[it]).toString() } +
            " " + (body.members["dry_run"] as JsonBool).value

    @Test
    fun oneMalformedIdInABatchIs422AndMintsNothing() {
        val d = device()
        val before = d.rows(LearningTable.CARD)
        val res = d.bulk("""{"card_ids":["$C1","not-a-card","$C2"],"action":{"type":"suspend"}}""", at = T0 + 1_000)
        assertEquals("422 /card_ids/1 invalid_value", res.problem())
        assertEquals(0, d.minted().size)
        assertEquals(0, d.localChanges)
        assertEquals(before, d.rows(LearningTable.CARD))
    }

    @Test
    fun aDuplicateIdOrBothSelectorsIs422AndMintsNothing() {
        val d = device()
        assertEquals("422 /card_ids invalid_value", d.bulk("""{"card_ids":["$C1","$C1"],"action":{"type":"suspend"}}""").problem())
        assertEquals("422  invalid_value", d.bulk("""{"card_ids":["$C1"],"query":"kind:meaning","action":{"type":"suspend"}}""").problem())
        assertEquals("422 /action/flags out_of_range", d.bulk("""{"card_ids":["$C1"],"action":{"type":"flag","flags":8}}""").problem())
        assertEquals("422 /action/until unknown_field", d.bulk("""{"card_ids":["$C1"],"action":{"type":"suspend","until":"x"}}""").problem())
        assertEquals("422 /action/type invalid_value", d.bulk("""{"card_ids":["$C1"],"action":{"type":"reset"}}""").problem())
        assertEquals("422 /extra unknown_field", d.bulk("""{"card_ids":["$C1"],"action":{"type":"suspend"},"extra":1}""").problem())
        assertEquals(0, d.minted().size)
    }

    /** As the server: an unknown (well-formed) id is skipped, the batch still applies to the live cards, in id order. */
    @Test
    fun anUnknownWellFormedIdIsSkippedAndTheRestApplied() {
        val d = device()
        val res = d.bulk("""{"card_ids":["$C2","$UNKNOWN","$C1"],"action":{"type":"suspend"}}""", at = T0 + 1_000)
        assertEquals(200, res.status)
        assertEquals("2 2 1 2 false", counts(res.json()))
        assertEquals(listOf("card.suspended", "card.suspended"), d.mintedTypes())
        assertEquals(listOf(C1, C2), d.minted().map { ParityJson.str(it.members["entity_id"]) }, "card-id order")
        assertEquals(JsonBool(true), d.card(C1)["suspended"])
        assertEquals(JsonBool(true), d.card(C2)["suspended"])
        assertEquals(1, d.localChanges, "one transaction, one sync trigger")
    }

    /** A refusal of the minter (here: an `until` past year 9999, C-6) rolls back every op of the batch. */
    @Test
    fun aRefusedOpMintsNoneOfTheBatch() {
        val d = device()
        val before = d.rows(LearningTable.CARD)
        val res = d.bulk("""{"card_ids":["$C1","$C2"],"action":{"type":"bury","until":"+10000-01-01T00:00:00Z"}}""", at = T0 + 1_000)
        assertEquals("422 /action/until invalid_value", res.problem())
        assertEquals(0, d.minted().size)
        assertEquals(before, d.rows(LearningTable.CARD))
    }

    /** A device clock before 2020 (guard G1) refuses the whole batch with the L-3 code. */
    @Test
    fun aWrongDeviceClockRefusesTheWholeBatch() {
        val d = device()
        val res = d.bulk("""{"card_ids":["$C1","$C2"],"action":{"type":"suspend"}}""", at = 1_500_000_000_000L)
        assertEquals("422 /reviewed_at before_floor", res.problem())
        assertEquals(0, d.minted().size)
    }

    @Test
    fun dryRunCountsAndMintsNothing() {
        val d = device()
        val res = d.bulk("""{"card_ids":["$C1","$C2","$UNKNOWN"],"action":{"type":"flag","flags":3},"dry_run":true}""", at = T0 + 1_000)
        assertEquals("2 2 1 0 true", counts(res.json()))
        assertEquals(0, d.minted().size)
        assertEquals(JsonNumber(0.0), d.card(C1)["flags"])
    }

    /** Register actions converge: a retried call mints again (it bumps the clock) and leaves the same state. */
    @Test
    fun aRetriedCallMintsAgainAndConverges() {
        val d = device()
        d.bulk("""{"card_ids":["$C1"],"action":{"type":"flag","flags":5}}""", at = T0 + 1_000)
        val after = d.card(C1)
        d.bulk("""{"card_ids":["$C1"],"action":{"type":"flag","flags":5}}""", at = T0 + 2_000)
        assertEquals(listOf("card.flag_set", "card.flag_set"), d.mintedTypes())
        assertEquals(after["flags"], d.card(C1)["flags"])
        assertEquals(JsonNumber(5.0), d.card(C1)["flags"])
    }

    @Test
    fun buryDefaultsToTheEndOfTheStudyDayAndRefusesThePast() {
        val d = device()
        assertEquals("422 /action/until out_of_range", d.bulk("""{"card_ids":["$C1"],"action":{"type":"bury","until":"2020-01-01T00:00:00Z"}}""", at = T0 + 1_000).problem())
        assertEquals(0, d.minted().size)
        assertEquals(200, d.bulk("""{"card_ids":["$C1"],"action":{"type":"bury"}}""", at = T0 + 1_000).status)
        val until = ParityJson.long(d.card(C1)["buried_until"])
        // default settings: UTC, rollover 04:00 — the next 04:00 UTC after the device's now
        val expected = java.time.Instant.ofEpochMilli(T0 + 1_000).atZone(java.time.ZoneOffset.UTC).let { z ->
            val today4 = z.toLocalDate().atTime(4, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
            if (today4 > T0 + 1_000) today4 else today4 + 86_400_000L
        }
        assertEquals(expected, until)
        assertEquals(200, d.bulk("""{"card_ids":["$C1"],"action":{"type":"unbury"}}""", at = T0 + 2_000).status)
        assertEquals(kotobaya.contracts.sync.JsonNull, d.card(C1)["buried_until"] ?: kotobaya.contracts.sync.JsonNull)
    }

    /** The `query` selector is the card search over the replica; a query that does not parse is `422 invalid_query`. */
    @Test
    fun aQuerySelectsCardsAndAnInvalidQueryIs422() {
        val d = device()
        val res = d.bulk("""{"query":"kind:meaning","action":{"type":"suspend"}}""", at = T0 + 1_000)
        assertEquals(200, res.status, res.body)
        assertEquals("2 2 0 2 false", counts(res.json()))
        val bad = d.bulk("""{"query":"dek:x","action":{"type":"unsuspend"}}""", at = T0 + 2_000)
        assertEquals(422, bad.status)
        assertEquals("invalid_query", ParityJson.str(bad.json().members["code"]))
        assertTrue(ParityJson.at(bad.json(), "/errors/0/position") is JsonNumber)
        assertEquals(2, d.minted().size)
    }

    /** Other actions are validated locally, then forwarded (offline: `503 shell_offline`), never answered locally. */
    @Test
    fun otherActionsAreForwardedAfterLocalValidation() {
        val d = device()
        assertEquals("422 /action/tags required", d.bulk("""{"card_ids":["$C1"],"action":{"type":"add_tags"}}""").problem())
        assertEquals(emptyList(), d.forwarded)
        assertEquals("503 shell_offline", d.bulk("""{"card_ids":["$C1"],"action":{"type":"add_tags","tags":["x"]}}""").problem())
        assertEquals("503 shell_offline", d.bulk("""{"card_ids":["$C1"],"action":{"type":"delete"}}""").problem())
        assertEquals(listOf("bulkCards POST /api/v1/cards/bulk", "bulkCards POST /api/v1/cards/bulk"), d.forwarded)
        assertEquals(0, d.minted().size)
    }
}
