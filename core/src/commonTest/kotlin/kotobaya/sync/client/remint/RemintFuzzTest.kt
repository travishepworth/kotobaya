package kotobaya.sync.client.remint

import kotobaya.contracts.Reserved
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.Op
import kotobaya.contracts.sync.OpType
import kotobaya.sync.DefaultHlcRules
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.diffRows
import kotobaya.sync.client.apply.projection
import kotobaya.sync.client.mint.MintResult
import kotobaya.sync.client.protocol.CycleResult
import kotobaya.sync.client.rejected.RejectedActionResult
import kotobaya.sync.client.rejected.RejectionCodes
import kotobaya.sync.client.rejected.rejectionCode
import kotobaya.sync.client.store.PendingOpRow
import kotobaya.sync.client.store.PendingState
import kotobaya.sync.client.store.SyncStateKeys
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Device-side fuzz of the rejected-op actions (SYNC.md 10, 11): two phones whose clocks jump ahead
 * (by days, caught by the server) or back to 2000 (a client without G1/G2), adding words, reviewing,
 * undoing, creating / updating presets, `card.preset_set`, the server publishing presets and merging
 * duplicates; the user picks Re-mint, Discard or Retry at random. Properties at every Re-mint: the
 * outbox never holds an hlc twice, minted hlcs strictly increase and lie above every kept row and
 * `hlc_acked`; at every clock Discard `hlc_last` is the repaired value. At the end every replica
 * equals the server's (SYNC.md 11 projection) and every review not explicitly discarded (or undone
 * and dropped by Re-mint) is on the server.
 */
class RemintFuzzTest {
    @Test
    fun remintDiscardRetryConvergeAndLoseNoReview() {
        for (seed in 0 until SEEDS) Run(seed).go()
    }

    private class Dev(val r: RemintDevice) {
        var mode = Mode.OK
        var skew = 0L
    }

    private enum class Mode { OK, AHEAD, RESET }

    private class Run(val seed: Int) {
        val rnd = Random(seed)
        var real = T0
        val js = JudgingServer(real, seed)
        val devs = listOf(Dev(RemintDevice(A, js.server, real, seed * 2 + 1)), Dev(RemintDevice(B, js.server, real, seed * 2 + 2)))
        val ids = RowIds(seed + 1000)
        val expected = LinkedHashSet<String>()
        val ctx = "seed $seed"

        fun clockOf(d: Dev) = when (d.mode) {
            Mode.OK -> real
            Mode.AHEAD -> real + d.skew
            Mode.RESET -> 946_684_800_000L + (real - T0)
        }

        fun tick(d: Dev) {
            js.now = real
            d.r.d.clock.now = clockOf(d)
        }

        fun mint(d: Dev, type: OpType, payload: kotobaya.contracts.sync.JsonObject): Op? {
            tick(d)
            if (d.r.d.state(SyncStateKeys.REBUILD_PENDING) != null) return null
            if (d.mode == Mode.RESET) return d.r.mintUnguarded(type, payload)
            return (d.r.d.minter.mint(type, payload) as? MintResult.Minted)?.ops?.single()
        }

        fun liveCards(d: Dev) = d.r.d.tables().all(LearningTable.CARD)
            .filter { (it["deleted_at"] ?: JsonNull) == JsonNull && (it["merged_into_id"] ?: JsonNull) == JsonNull }
            .map { (it["id"] as JsonString).value }

        fun presets(d: Dev) = d.r.d.tables().all(LearningTable.FSRS_PARAMS)
            .filter { (it["deleted_at"] ?: JsonNull) == JsonNull }.map { (it["id"] as JsonString).value }

        fun reviewIdOf(row: PendingOpRow): String? =
            if (row.opType == OpType.REVIEW_LOGGED.wire) (kotobaya.sync.DefaultCanonicalJson.decode(row.payload).value as kotobaya.contracts.sync.JsonObject).members["id"]?.let { (it as JsonString).value } else null

        fun discard(d: Dev, rows: List<PendingOpRow>) {
            val bk = d.r.d.store.syncState()
            val res = d.r.service.discard(rows.map { it.opId })
            if (res !is RejectedActionResult.Done) return
            rows.mapNotNull { reviewIdOf(it) }.forEach { expected -= it }
            if (rows.any { it.rejectionCode == RejectionCodes.CLOCK_AHEAD }) {
                // clock repair: hlc_last = max(hlc_acked, every kept row)
                val kept = d.r.d.store.rows().map { it.hlc }
                val want = (kept + listOfNotNull(bk[SyncStateKeys.HLC_ACKED])).maxOrNull()
                assertEquals(want, d.r.d.state(SyncStateKeys.HLC_LAST), "$ctx: clock repair")
            }
        }

        fun remint(d: Dev): Boolean {
            val p = d.r.service.previewRemint()
            if (p.blocked != null) return false
            val before = d.r.d.store.rows()
            val acked = d.r.d.state(SyncStateKeys.HLC_ACKED)
            val out = d.r.service.remint(p) as? RemintOutcome.Reminted ?: error("$ctx: remint with a current preview: ${d.r.service.remint(p)}")
            assertEquals(p.discards.map { it.row.opId }, out.discarded, "$ctx: discard list = rows not re-minted")
            val kept = before.filter { it.opId !in out.deleted }.map { it.hlc }
            val floor = (kept + listOfNotNull(acked)).maxOrNull()
            val hlcs = out.minted.map { it.hlc }
            assertEquals(hlcs.sorted(), hlcs, "$ctx: minted order monotone")
            assertEquals(hlcs.size, hlcs.toSet().size, "$ctx: no repeat")
            if (floor != null) assertTrue(hlcs.all { it > floor }, "$ctx: minted above every kept hlc")
            val all = d.r.d.store.rows().map { it.hlc }
            assertEquals(all.size, all.toSet().size, "$ctx: outbox hlcs unique")
            for (e in p.discards) if (e.reason == DiscardReason.UNDONE_REVIEW) reviewIdOf(e.row)?.let { expected -= it }
            for ((old, new) in out.reviewIds) if (expected.remove(old)) expected += new
            return true
        }

        fun resolve(d: Dev) {
            val rejected = d.r.d.store.rows().filter { it.state == PendingState.REJECTED }
            if (rejected.isEmpty()) return
            when (rnd.nextInt(5)) {
                0, 1 -> {
                    d.mode = Mode.OK
                    tick(d)
                    d.r.cycle()
                    remint(d)
                }
                2 -> {
                    d.mode = Mode.OK
                    tick(d)
                    d.r.cycle()
                    discard(d, listOf(rejected[rnd.nextInt(rejected.size)]))
                }
                3 -> d.r.service.list().filter { it.retryable }.randomOrNull(rnd)?.let { d.r.service.retry(listOf(it.row.opId)) }
                else -> {
                    d.mode = Mode.OK
                    tick(d)
                    d.r.cycle()
                    discard(d, d.r.d.store.rows().filter { it.state == PendingState.REJECTED })
                }
            }
        }

        fun step() {
            val d = devs[rnd.nextInt(devs.size)]
            when (rnd.nextInt(20)) {
                in 0..2 -> {
                    val note = ids.next(real)
                    val card = ids.next(real)
                    if (mint(d, OpType.NOTE_CREATED, vocabNote(note, 3_000_000L + rnd.nextInt(40))) != null) {
                        mint(d, OpType.CARD_CREATED, obj("id" to card, "note_id" to note, "kind" to "meaning"))
                    }
                }
                in 3..6 -> liveCards(d).randomOrNull(rnd)?.let { card ->
                    val id = ids.next(real)
                    tick(d)
                    if (mint(d, OpType.REVIEW_LOGGED, review(id, card, d.r.d.clock.now, 1 + rnd.nextInt(4))) != null) expected += id
                }
                7 -> d.r.d.tables().all(LearningTable.REVIEW_LOG)
                    .filter { it["device_id"] == JsonString(d.r.d.deviceId) && (it["undone_hlc"] ?: JsonNull) == JsonNull }
                    .randomOrNull(rnd)?.let { mint(d, OpType.REVIEW_UNDONE, obj("review_id" to it["id"])) }
                8 -> mint(d, OpType.FSRS_PARAMS_CREATED, obj("id" to ids.next(real), "name" to "P${rnd.nextInt(100)}", "desired_retention" to 0.8 + rnd.nextInt(15) / 100.0))
                9 -> presets(d).filter { it != Reserved.DEFAULT_PRESET_ID }.randomOrNull(rnd)?.let {
                    mint(d, OpType.FSRS_PARAMS_UPDATED, obj("id" to it, "set" to obj("desired_retention" to 0.8 + rnd.nextInt(15) / 100.0)))
                }
                10, 11 -> {
                    val card = liveCards(d).randomOrNull(rnd)
                    val preset = presets(d).randomOrNull(rnd)
                    if (card != null && preset != null) mint(d, OpType.CARD_PRESET_SET, obj("id" to card, "preset_id" to preset))
                }
                12 -> js.tables.all(LearningTable.FSRS_PARAMS).map { (it["id"] as JsonString).value }.randomOrNull(rnd)?.let {
                    js.now = real
                    js.publish(it)
                }
                13 -> {
                    when (rnd.nextInt(4)) {
                        0 -> {
                            d.mode = Mode.AHEAD
                            d.skew = (2 + rnd.nextInt(14)) * DAY + rnd.nextInt(1000)
                        }
                        1 -> d.mode = Mode.RESET
                        else -> d.mode = Mode.OK
                    }
                }
                in 14..16 -> {
                    tick(d)
                    d.r.cycle()
                }
                17 -> real += MINUTE + rnd.nextLong(6 * HOUR)
                else -> resolve(d)
            }
        }

        fun go() {
            repeat(STEPS) { step() }
            // final: a month later every clock is right; Re-mint where possible, else Discard; then sync
            real += 30 * DAY
            for (d in devs) d.mode = Mode.OK
            var rounds = 0
            while (true) {
                check(rounds++ < 20) { "$ctx: does not settle" }
                var dirty = false
                for (d in devs) {
                    tick(d)
                    d.r.cycle()
                    if (d.r.d.store.rows().none { it.state == PendingState.REJECTED }) continue
                    dirty = true
                    if (!remint(d)) discard(d, d.r.d.store.rows().filter { it.state == PendingState.REJECTED })
                }
                if (!dirty) break
            }
            repeat(2) { for (d in devs) assertTrue(d.r.cycle() is CycleResult.Completed, ctx) }
            // SYNC.md 11 item 3: caches are compared after all pending replays (a card created after a
            // fsrs_params.published keeps weights_version 0 until it is replayed, OPS.md)
            fun settled(t: kotobaya.sync.client.apply.InMemoryLearningTables) =
                projection(t.copy().also { kotobaya.sync.client.apply.DeviceCardCache().replayAll(it) })
            val want = settled(js.tables)
            for (d in devs) {
                assertEquals(emptyList(), diffRows(want, settled(d.r.d.tables())), "$ctx: ${d.r.d.deviceId} diverged")
                assertNull(d.r.d.state(SyncStateKeys.REBUILD_PENDING), ctx)
                assertNull(d.r.d.state(SyncStateKeys.REMINT_OFFSET), ctx)
            }
            val onServer = js.tables.all(LearningTable.REVIEW_LOG).map { (it["id"] as JsonString).value }.toSet()
            val lost = expected - onServer
            assertEquals(emptySet(), lost, "$ctx: reviews lost")
            val hlcs = js.server.loggedOps().map { it.hlc }
            assertEquals(hlcs.size, hlcs.toSet().size, "$ctx: an hlc repeats in the log")
            for (op in js.server.loggedOps()) assertTrue(DefaultHlcRules.wall(op.hlc) >= Reserved.CLOCK_FLOOR_MS, ctx)
        }
    }

    private companion object {
        const val SEEDS = 110
        const val STEPS = 160
        const val T0 = 1_733_584_666_624L
        const val A = "01931f3e-0000-7000-8000-0000000000a1"
        const val B = "01931f3e-0000-7000-8000-0000000000b2"
    }
}
