package kotobaya.replay

import kotobaya.contracts.fsrs.ReviewType
import kotobaya.contracts.replay.CardCache
import kotobaya.contracts.replay.ReplayDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T203 acceptance: incremental replay equals full replay (contracts/core README › T203;
 * SYNC.md 9.3 "folding one more review MUST give the same result as a full replay"). Fixed seeds;
 * runs on jvm and js.
 */
class ReplayPropertyTest {
    private val e = DefaultReplayEngine

    /** `fold(replay(S), r) == replay(S + r)` for random S and a schedule-feeding r sorting last; 1000 cases. */
    @Test
    fun foldOfLastRowEqualsFullReplay() {
        val rng = Rng(0x7203_0001L)
        var folded = 0
        repeat(1000) { case ->
            val h = genHistory(rng, case, maxRows = 12)
            val s = h.finalRows()
            val set = e.replaySet(s)
            val last = set.lastOrNull()
            val base = s.filter { last == null || it.id != last.id }
            val params = h.finalParams
            val before = e.replay(base, params)
            if (last != null) {
                val lastFolded = before.lastReviewId?.let { id -> base.first { it.id == id }.key }
                assertEquals(ReplayDecision.FOLD, e.decide(before, lastFolded, last, params), "case $case")
                assertEquals(e.replay(s, params), e.fold(before, last, params), "case $case")
                folded++
            } else {
                assertEquals(e.replay(emptyList(), params), before, "case $case")
            }
        }
        assertTrue(folded > 900, "only $folded cases had a schedule-feeding row")
    }

    /**
     * Random arrival orders of random histories (drills, crams, manual set_due/reset, undos — some
     * twice — duplicate deliveries with other content, and one params change, lazy or eager):
     * after **every** event, the table-driven replica (decide/fold/replay, ReviewLogRules) and
     * [IncrementalReplay] both equal a full replay of what that replica holds; at the end, two
     * different arrival orders and the reference all agree.
     */
    @Test
    fun incrementalEqualsFullReplayUnderRandomArrival() {
        val rng = Rng(0x7203_0002L)
        val seen = mutableMapOf<ReplayDecision, Int>()
        repeat(300) { case ->
            val h = genHistory(rng, case)
            val reference = e.replay(h.finalRows(), h.finalParams)
            val finals = (0 until 2).map { run ->
                val where = "case $case run $run"
                val table = TableReplica(initial = h.initial)
                val inc = IncrementalReplay(e, h.initial)
                for ((k, ev) in h.arrival(rng).withIndex()) {
                    when (ev) {
                        is Event.Logged -> {
                            table.logged(ev.row)
                            inc.insert(ev.row)
                        }
                        is Event.Undo -> if (table.undone(ev.id, ev.hlc, ev.wall)) inc.undo(ev.id)
                        is Event.Params -> {
                            table.paramsChanged(ev.params, ev.eager)
                            inc.setParams(ev.params)
                        }
                    }
                    val full = e.replay(table.rows(), table.params)
                    assertEquals(full, table.read(), "$where event $k $ev")
                    assertEquals(full, inc.cache, "$where event $k (incremental)")
                }
                for ((d, c) in table.decisions) seen[d] = (seen[d] ?: 0) + c
                table.read()
            }
            assertEquals(reference, finals[0], "case $case")
            assertEquals(reference, finals[1], "case $case")
        }
        // the simulation must exercise every branch of decide
        for (d in ReplayDecision.entries) assertTrue((seen[d] ?: 0) > 50, "decision $d seen ${seen[d]} times")
    }

    /** drill/cram rows never change a cache, wherever they sort and however many there are. */
    @Test
    fun drillAndCramNeverChangeACache() {
        val rng = Rng(0x7203_0003L)
        repeat(300) { case ->
            val h = genHistory(rng, case)
            val rows = h.finalRows()
            val withoutPractice = rows.filter { it.reviewType != ReviewType.DRILL && it.reviewType != ReviewType.CRAM }
            val expected: CardCache = e.replay(withoutPractice, h.finalParams)
            assertEquals(expected, e.replay(rows, h.finalParams), "case $case")
            for (r in rows.filter { !it.reviewType.feedsSchedule }) {
                assertEquals(ReplayDecision.NONE, e.decide(expected, null, r, h.finalParams), "case $case")
            }
        }
    }
}
