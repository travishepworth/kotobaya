package kotobaya.android.shell.parity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotobaya.android.shell.api.ApiOperations
import kotobaya.android.shell.api.Fallthrough
import kotobaya.android.shell.api.LocalRoute
import kotobaya.android.shell.api.ShellResponse
import kotobaya.contracts.sync.JsonObject

/** The harness catches what it must: fake handlers, right and wrong, on a scenario that only calls `getReviewQueue`. */
class ParityHarnessSelfTest {
    private val scenario = ParityScenario.load("queue-learning-wait")
    private val op = ApiOperations.byId.getValue("getReviewQueue")

    private fun route(handler: (Int) -> ShellResponse): LocalRoute {
        var calls = 0
        return LocalRoute(op.operationId, op.method, op.pathTemplate) { handler(calls++) }
    }

    private val wrong = route {
        ShellResponse(
            200,
            mapOf("content-type" to "application/json; charset=utf-8"),
            """{"items":[],"counts":{"new":0,"learning":0,"review":0},"more_available":false,"generated_at":"x"}""",
        )
    }

    @Test
    fun aDeliberatelyWrongHandlerIsAMismatchWithAJsonPointerDiff() {
        assertEquals(listOf("getReviewQueue"), scenario.operations, "the self-test needs a getReviewQueue-only scenario")
        val r = assertIs<ParityOutcome.Ran>(ParityRunner.run(scenario, listOf(wrong)))
        assertTrue(!r.matches)
        assertTrue("/steps/0/body/counts/learning: expected 1, actual 0" in r.problems, r.problems.joinToString("\n"))
        assertTrue("/steps/0/body/next_learning_due_at: expected \"2026-11-20T10:00:55.000Z\", actual absent" in r.problems, r.problems.joinToString("\n"))
        assertTrue(r.problems.any { it.startsWith("/steps/1/body/items/0: expected {") && it.endsWith("actual absent") }, r.problems.joinToString("\n"))
        // generated_at was normalised away and the content-type parameter dropped: not reported
        assertTrue(r.problems.none { "generated_at" in it || "/headers" in it }, r.problems.joinToString("\n"))
    }

    @Test
    fun theGateFailsWhenARegisteredScenarioDiffersAndNamesThePointer() {
        val e = assertFailsWith<AssertionError> { LocalParityTest.assertParity(listOf(wrong)) }
        val msg = e.message.orEmpty()
        assertTrue("DIFFERS queue-learning-wait" in msg, msg)
        assertTrue("/steps/0/body/counts/learning" in msg, msg)
        assertTrue("pending empty-replica: needs answerReview, undoReview" in msg, msg)
    }

    @Test
    fun aHandlerAnsweringTheGoldenMatches() {
        val golden = ParityScenario.golden(scenario.id)!!
        val steps = ParityJson.arr(golden.members["steps"]).map { ParityJson.obj(it) }
        val replay = route { i ->
            val s = steps[i].members
            val headers = ParityJson.obj(s["headers"]).members.mapValues { ParityJson.str(it.value) }
            ShellResponse(ParityJson.long(s["status"]).toInt(), headers, ParityJson.encode(s.getValue("body")))
        }
        val r = assertIs<ParityOutcome.Ran>(ParityRunner.run(scenario, listOf(replay)))
        assertEquals(emptyList(), r.problems)
    }

    @Test
    fun aForwardedRequestIsAFinding() {
        val r = assertIs<ParityOutcome.Ran>(ParityRunner.run(scenario, listOf(route { Fallthrough })))
        assertTrue(r.problems.any { it.startsWith("forwarded upstream") && "getReviewQueue GET /api/v1/review/queue" in it }, r.problems.joinToString("\n"))
        // the offline answer itself differs too
        assertTrue(r.problems.any { it.startsWith("/steps/0/status: expected 200, actual 503") }, r.problems.joinToString("\n"))
    }

    @Test
    fun invariantsAreCheckedOnTheShellsAnswers() {
        val s = ParityScenario.load("queue-counts-keep-going")
        assertEquals(1, s.invariants.size)
        fun answers(body: (Int) -> String) =
            s.steps.indices.map { i -> RawResponse(200, mapOf("content-type" to "application/json"), body(i)) }
        val differ = ParityRunner.invariantProblems(s, ParityRunner.normalise(s, answers { """{"counts":{"new":$it}}""" }))
        assertEquals(1, differ.size, differ.joinToString("\n"))
        assertTrue(differ.single().startsWith("invariant 0: not equal: step 0 /counts={\"new\":0}"), differ.single())
        val same = ParityRunner.normalise(s, answers { """{"counts":{"new":1,"learning":0,"review":0}}""" })
        assertEquals(emptyList(), ParityRunner.invariantProblems(s, same))
        val missing = ParityRunner.invariantProblems(s, ParityRunner.normalise(s, answers { if (it == 1) "{}" else """{"counts":{}}""" }))
        assertEquals(listOf("invariant 0: unresolved step 1 /counts"), missing)
    }

    @Test
    fun diffReportsReadablePointers() {
        val a = ParityJson.parse("""{"a":{"b/c":[1,2]},"x":"s"}""") as JsonObject
        val b = ParityJson.parse("""{"a":{"b/c":[1,3,4]},"y":true}""") as JsonObject
        assertEquals(
            listOf(
                "/a/b~1c/1: expected 2, actual 3",
                "/a/b~1c/2: expected absent, actual 4",
                "/x: expected \"s\", actual absent",
                "/y: expected absent, actual true",
            ),
            ParityJson.diff(a, b),
        )
        assertEquals(listOf("\"\": expected 1, actual \"1\""), ParityJson.diff(ParityJson.parse("1"), ParityJson.parse("\"1\"")))
    }
}
