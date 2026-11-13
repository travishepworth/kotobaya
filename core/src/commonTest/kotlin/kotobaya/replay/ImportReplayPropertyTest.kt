package kotobaya.replay

import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.replay.ReplayDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BUG-6: the fold = replay and arrival-order properties of [ReplayPropertyTest] with imported jpdb rows
 * mixed into the random histories (SYNC.md 9.3, 9.5). Fixed seeds; runs on jvm and js.
 */
class ImportReplayPropertyTest {
    private val e = DefaultReplayEngine

    @Test
    fun foldOfLastRowEqualsFullReplayWithImportedRows() {
        val rng = Rng(0x0b06_0001L)
        var folded = 0
        var changed = 0
        repeat(1000) { case ->
            val h = genHistory(rng, case, maxRows = 12, importPercent = 40)
            val s = h.finalRows()
            val set = e.replaySet(s)
            val last = set.lastOrNull() ?: return@repeat
            val base = s.filter { it.id != last.id }
            val params = h.finalParams
            val before = e.replay(base, params)
            val lastFolded = before.lastReviewId?.let { id -> base.first { it.id == id }.key }
            assertEquals(ReplayDecision.FOLD, e.decide(before, lastFolded, last, params), "case $case")
            assertEquals(e.replay(s, params), e.fold(before, last, params), "case $case")
            folded++
            if (e.replay(s, params) != e.replay(s.map { it.copy(importSource = null) }, params)) changed++
        }
        assertTrue(folded > 900, "only $folded cases had a schedule-feeding row")
        assertTrue(changed > 25, "the import rule fired in only $changed cases")
    }

    @Test
    fun incrementalEqualsFullReplayUnderRandomArrivalWithImportedRows() {
        val rng = Rng(0x0b06_0002L)
        repeat(300) { case ->
            val h = genHistory(rng, case, importPercent = 40)
            val reference = e.replay(h.finalRows(), h.finalParams)
            repeat(2) { run ->
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
                assertEquals(reference, table.read(), where)
            }
        }
    }

    /** A history made only of imported passes and lapses spread over days never ends stuck in (re)learning on a pass. */
    @Test
    fun importedHistoriesEndingInAPassAfterADayAreInReview() {
        val rng = Rng(0x0b06_0003L)
        var checked = 0
        repeat(500) { case ->
            val n = 2 + rng.int(12)
            var t = T0
            val rows = (0 until n).map { i ->
                t += rng.long(DAY, 30 * DAY)
                imported(uuid(case, rng.nextLong()), t, rng.pick(kotobaya.contracts.fsrs.Rating.entries), counter = i)
            }
            val c = e.replay(rows, P0).schedule
            if (rows.last().rating != kotobaya.contracts.fsrs.Rating.AGAIN) {
                assertEquals(CardState.REVIEW, c.state, "case $case")
                checked++
            }
        }
        assertTrue(checked > 300)
    }
}
