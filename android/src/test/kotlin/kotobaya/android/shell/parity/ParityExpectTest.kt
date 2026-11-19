package kotobaya.android.shell.parity

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotobaya.android.shell.api.LocalRoutes
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonValue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the contract already fixes (`expect` of each request step: status, problem code, body pointers, array lengths,
 * absent members) holds on the shell's own answers. The goldens carry the same facts once the server has recorded
 * them; this check does not need them, so a scenario that was edited in the contract (C-28 kanji-first, until_stop,
 * kanji parts, furigana) is tested against its hand-evaluated expectations the moment it lands.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class ParityExpectTest {
    @Test
    fun everyScenarioMeetsItsOwnExpectations() {
        val problems = ArrayList<String>()
        for (scenario in ParityScenario.all()) {
            if (scenario.operations.any { op -> LocalRoutes.all.none { it.operationId == op } }) continue
            val run = ParityRunner.execute(scenario, LocalRoutes.all)
            scenario.steps.indices.forEach { i ->
                val expect = (scenario.steps[i].members["expect"] as? JsonObject)?.members ?: return@forEach
                val raw = run.raw[i] ?: return@forEach
                val body: JsonValue? = raw.body?.let { runCatching { ParityJson.parse(it) }.getOrNull() }
                fun bad(what: String) = problems.add("${scenario.id} step $i: $what")
                if (ParityJson.long(expect["status"]) != raw.status.toLong()) bad("status expected ${ParityJson.show(expect["status"])}, actual ${raw.status}")
                (expect["body"] as? JsonObject)?.members?.forEach { (pointer, want) ->
                    val got = ParityJson.at(body, pointer)
                    if (got != want) bad("$pointer expected ${ParityJson.show(want)}, actual ${ParityJson.show(got)}")
                }
                (expect["lengths"] as? JsonObject)?.members?.forEach { (pointer, want) ->
                    val got = (ParityJson.at(body, pointer) as? JsonArray)?.items?.size
                    if (got?.toLong() != ParityJson.long(want)) bad("$pointer length expected ${ParityJson.show(want)}, actual $got")
                }
                (expect["absent"] as? JsonArray)?.items?.forEach { p ->
                    val pointer = ParityJson.str(p)
                    if (ParityJson.at(body, pointer) != null) bad("$pointer should be absent")
                }
            }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
        assertTrue(ParityScenario.all().isNotEmpty())
    }
}
