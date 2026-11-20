package kotobaya.android.shell.api.stats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotobaya.android.shell.api.LocalRoutes
import kotobaya.android.shell.parity.ParityJson
import kotobaya.android.shell.parity.ParityRunner
import kotobaya.android.shell.parity.ParityScenario
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonValue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * LF-A3: the C-31 parity scenarios of the three local stats operations (SHELL.md 8.4 `stats.summary_local`,
 * `stats.heatmap_local`, `stats.retention_local`) run on the shell with every route registered: each request step meets
 * its `expect` (status, problem code, body pointers, lengths), the invariants hold (summary `due` = queue `counts`
 * before and after a local answer) and nothing is forwarded. The goldens are recorded by LF-GOLD; [LocalParityTest]
 * compares with them once they exist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class StatsParityTest {
    @Test
    fun theThreeStatsOperationsAreRegisteredLocally() {
        val ids = LocalRoutes.all.map { it.operationId }
        for (op in listOf("getStatsSummary", "getStatsHeatmap", "getStatsRetention")) assertTrue(op in ids, op)
    }

    @Test
    fun statsSummaryLocalMeetsItsExpectationsAndInvariants() = check("stats-summary-local")

    @Test
    fun statsHeatmapRetentionLocalMeetsItsExpectations() = check("stats-heatmap-retention-local")

    private fun check(id: String) {
        Class.forName("org.sqlite.JDBC")
        val scenario = ParityScenario.load(id)
        val run = ParityRunner.execute(scenario, LocalRoutes.all)
        assertEquals(emptyList(), run.forwarded, "a scenario never goes online")
        val problems = ArrayList<String>()
        scenario.steps.indices.forEach { i ->
            val expect = (scenario.steps[i].members["expect"] as? JsonObject)?.members ?: return@forEach
            val raw = run.raw[i] ?: return@forEach
            val body: JsonValue? = raw.body?.let { runCatching { ParityJson.parse(it) }.getOrNull() }
            fun bad(what: String) = problems.add("$id step $i: $what (body ${raw.body})")
            if (ParityJson.long(expect["status"]) != raw.status.toLong()) bad("status expected ${ParityJson.show(expect["status"])}, actual ${raw.status}")
            expect["problem_code"]?.let { want ->
                val got = ParityJson.at(body, "/code")
                if (got != want) bad("problem code expected ${ParityJson.show(want)}, actual ${ParityJson.show(got)}")
            }
            (expect["body"] as? JsonObject)?.members?.forEach { (pointer, want) ->
                val got = ParityJson.at(body, pointer)
                if (got != want) bad("$pointer expected ${ParityJson.show(want)}, actual ${ParityJson.show(got)}")
            }
            (expect["lengths"] as? JsonObject)?.members?.forEach { (pointer, want) ->
                val got = (ParityJson.at(body, pointer) as? JsonArray)?.items?.size
                if (got?.toLong() != ParityJson.long(want)) bad("$pointer length expected ${ParityJson.show(want)}, actual $got")
            }
        }
        problems += ParityRunner.invariantProblems(scenario, run.normalised)
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }
}
