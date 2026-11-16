package kotobaya.android.shell.parity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotobaya.android.shell.api.LocalRoute
import kotobaya.android.shell.api.LocalRoutes

/**
 * SHELL-P2: the shell's [kotobaya.android.shell.api.LocalApi] must reproduce the server's goldens (SHELL.md 8). Every
 * scenario whose operations all have a registered route runs; the others are `pending` and listed (the L tickets turn
 * scenarios on by registering routes).
 */
class LocalParityTest {
    @Test
    fun everyScenarioWithAllOperationsRegisteredMatchesItsGolden() {
        assertParity(LocalRoutes.all)
    }

    @Test
    fun withZeroRegisteredRoutesEveryScenarioIsPendingAndTheGatePasses() {
        val outcomes = ParityScenario.all().map { ParityRunner.run(it, emptyList()) }
        assertTrue(outcomes.isNotEmpty())
        outcomes.forEach { o ->
            val p = o as? ParityOutcome.Pending ?: fail("${o.id} ran with no routes")
            assertEquals(ParityScenario.load(o.id).operations, p.missing, o.id)
        }
        assertParity(emptyList())
    }

    @Test
    fun goldensAndScenariosAreOneToOne() {
        val ids = ParityScenario.files().map { it.name }.toSet()
        val goldens = ParityScenario.goldenDir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().map { it.name }.toSet()
        assertEquals(ids, goldens, "goldens must match scenario files one to one")
    }

    companion object {
        /** The gate: prints the report (pending list included); fails when a scenario that ran differs from its golden. */
        fun assertParity(routes: List<LocalRoute>) {
            val outcomes = ParityScenario.all().map { ParityRunner.run(it, routes) }
            val report = ParityRunner.report(outcomes)
            println(report)
            if (outcomes.any { it is ParityOutcome.Ran && !it.matches }) fail(report)
        }
    }
}
