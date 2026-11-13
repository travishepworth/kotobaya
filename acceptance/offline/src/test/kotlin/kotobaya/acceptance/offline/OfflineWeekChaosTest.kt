package kotobaya.acceptance.offline

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T313 acceptance: randomised offline weeks (default 100 seeds; `-Pseeds=`), two or three phones on the
 * real client stack against the real server, with reviews (all buttons, undo), Takoboto-style adds of
 * the same words on several phones, edits, deletes, suspends/buries, word statuses, deck items (`e:`
 * and `n:`), presets (update, server publish, `card.preset_set`), settings, a server-originated import,
 * clock skew ±10 min with drift, clock jumps ahead / behind followed by Re-mint, Discard or Retry,
 * a server restore from the nightly backup, kills mid-push and at commits, lost requests and responses,
 * small pull pages; then final syncs in random orders. Oracle: [Sim.check].
 *
 * Failures print the seed and a shrunk trace. The report (per-run counts, skew-ordering anomalies,
 * Re-mint outcomes) goes to stdout and `build/reports/offline-chaos/report.txt`.
 */
class OfflineWeekChaosTest {
    @Test
    fun offlineWeeksConvergeWithZeroLostReviews() {
        val seeds = Chaos.seeds()
        val total = Stats()
        val lines = ArrayList<String>()
        val failures = ArrayList<String>()
        var anomalies = 0L
        var kind = "?"
        val started = System.currentTimeMillis()
        for (seed in seeds) {
            val r = Chaos.execute(Chaos.plan(seed))
            kind = r.serverKind
            total.plus(r.stats)
            anomalies += maxOf(r.anomalies, 0)
            lines += runLine(r)
            if (r.violations.isNotEmpty()) {
                println("seed $seed FAILED; shrinking …")
                val shrunk = Chaos.shrink(r)
                val text = Chaos.failureReport(r, shrunk)
                println(text)
                failures += text
                if (failures.size >= 3) break
            }
        }
        val report = buildString {
            appendLine("T313 offline chaos report — ${seeds.size} seeds, server: $kind${if (kind == "memory") " (Docker unavailable or -Pserver=memory: in-memory reference server, NOT the real server)" else " (real :server sync code on Postgres)"}")
            appendLine("elapsed: ${(System.currentTimeMillis() - started) / 1000} s; failures: ${failures.size}")
            appendLine("skew-ordering anomalies (adjacent replay-set reviews whose true answer times are out of order; T203 follow-up): $anomalies")
            appendLine()
            appendLine("totals:")
            total.counts.forEach { (k, v) -> appendLine("  %-48s %d".format(k, v)) }
            appendLine()
            appendLine("per run:")
            lines.forEach { appendLine("  $it") }
            if (failures.isNotEmpty()) {
                appendLine()
                failures.forEach { appendLine(it) }
            }
        }
        File(Chaos.reportDir(), "report.txt").writeText(report)
        println(report.lineSequence().take(4).joinToString("\n"))
        println(total.counts.entries.joinToString(", ") { "${it.key}=${it.value}" })
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
        if (seeds.size >= 50) {
            // the generator really reaches what the ticket names (else the run proves little)
            for (k in COVERAGE) assertTrue(total[k] > 0, "never exercised: $k (totals: ${total.counts})")
        }
    }

    private fun runLine(r: RunResult): String {
        val s = r.stats
        fun g(k: String) = s[k]
        val reviews = s.counts.filterKeys { it == "minted.review.logged" }.values.sum()
        return "seed %3d %s dev=%d steps=%d ops=%d reviews=%d undos=%d adds=%d refused=%d rejected=%d remint=%d(ops %d, reviews %d, discarded %d) discardAll=%d restore=%d resets=%d kills=%d merges=%d anomalies=%d".format(
            r.plan.seed, if (r.violations.isEmpty()) "ok  " else "FAIL", r.plan.devices.size, r.plan.steps.size, g("log.ops"), reviews,
            g("minted.review.undone"), g("minted.note.created"), s.counts.filterKeys { it.startsWith("refused.") }.values.sum(),
            s.counts.filterKeys { it.startsWith("rejected.") }.values.sum(), g("remint.done"), g("remint.minted_ops"), g("remint.reviews"),
            g("remint.discarded"), g("action.discard_all"), g("server.restore"), g("device.log_reset"), g("sync.killed"),
            g("log.server.note.merged") + g("log.server.card.merged"), r.anomalies,
        )
    }

    private companion object {
        val COVERAGE = listOf(
            "server.restore", "device.log_reset", "remint.done", "remint.reviews", "action.discard_all", "sync.killed",
            "log.server.note.merged", "log.server.card.merged", "server.import.vocab", "server.import.kanji", "server.publish",
            "minted.review.undone", "minted.card.preset_set", "minted.fsrs_params.updated", "minted.deck.item_put",
            "rejected.clock_ahead", "refused.clock_guard_g2_behind_last", "review.manual", "add.duplicate_refused",
        )
    }
}
