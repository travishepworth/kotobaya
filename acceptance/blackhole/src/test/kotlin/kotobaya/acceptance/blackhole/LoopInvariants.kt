package kotobaya.acceptance.blackhole

import kotobaya.android.shell.parity.ParityJson
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonObject

/**
 * I1 … I4 of the `ReviewLoop` KDoc (ADR-051), checked on one `getReviewQueue` response. Core's own checker
 * (`ReviewLoopInvariantFuzzTest`) works on its internal `QueueInput` / `LoopResult` and is private to core's tests, so
 * this is the same four rules on the wire shape the SPA sees (`ReviewQueue` + `loop`):
 *
 * - I1 cards ready (learning + review) => phase `reviewing` with items; phase `reviewing` <=> something is served.
 * - I2 a terminal phase (`goal_reached`, `done_for_today`, `waiting`, `empty`) => nothing ready, nothing later, nothing
 *   until stop... except `waiting`, whose point is the learning cards that are later today (rule C4).
 * - I3 `until_stop_parts` are non-negative, agree with `ready` / `later_today`, and sum to `until_stop`.
 * - I4 `empty` => no Keep going; `reviewing` => no Keep going; every other phase => `keep_going_available` is exactly
 *   "pressing it serves a card" (checked by the caller with the probe result).
 *
 * On a phone `to_materialize` must be 0 (SHELL.md 4.3): the device materializes itself.
 */
object LoopInvariants {
    private val TERMINAL = setOf("goal_reached", "done_for_today", "waiting", "empty")

    fun check(body: JsonObject): List<String> {
        val out = ArrayList<String>()
        fun long(pointer: String): Long = ParityJson.long(ParityJson.at(body, pointer))
        fun must(inv: String, ok: Boolean, msg: () -> String) {
            if (!ok) out += "$inv: ${msg()}"
        }
        val phase = ParityJson.str(ParityJson.at(body, "/loop/phase"))
        val served = ParityJson.arr(ParityJson.at(body, "/items")).size
        val readyL = long("/loop/ready/learning")
        val readyR = long("/loop/ready/review")
        val later = long("/loop/later_today/count")
        val untilStop = long("/loop/until_stop")
        val parts = listOf("learning", "later", "review", "new").associateWith { long("/loop/until_stop_parts/$it") }
        val available = (ParityJson.at(body, "/loop/keep_going_available") as? JsonBool)?.value ?: false
        val toMaterialize = ParityJson.at(body, "/to_materialize")?.let { ParityJson.long(it) } ?: 0L

        // I1
        must("I1", readyL + readyR == 0L || (phase == "reviewing" && served > 0)) { "ready ${readyL + readyR} but phase $phase with $served items" }
        must("I1", (phase == "reviewing") == (served > 0)) { "phase $phase with $served items" }
        must("I1", toMaterialize == 0L) { "to_materialize $toMaterialize on a phone" }
        // I2
        if (phase in TERMINAL) {
            must("I2", readyL == 0L && readyR == 0L) { "$phase with ready $readyL/$readyR" }
            if (phase != "waiting") must("I2", later == 0L && untilStop == 0L) { "$phase with later $later, until_stop $untilStop" }
        }
        // I3
        must("I3", parts.values.sum() == untilStop) { "parts $parts do not sum to until_stop $untilStop" }
        must("I3", parts.values.all { it >= 0 }) { "negative part $parts" }
        must("I3", parts.getValue("learning") == readyL && parts.getValue("later") == later) { "parts $parts vs ready $readyL, later $later" }
        // I4 (the "exactly" half is the caller's probe)
        when (phase) {
            "empty", "reviewing" -> must("I4", !available) { "$phase with keep_going_available" }
        }
        return out
    }
}
