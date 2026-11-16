package kotobaya.android.shell.api.review

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotobaya.android.shell.api.LocalRoutes
import kotobaya.android.shell.api.identity.IdentityTestSupport.minted
import kotobaya.android.shell.parity.ParityOutcome
import kotobaya.android.shell.parity.ParityRunner
import kotobaya.android.shell.parity.ParityScenario
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SHELL-L3 acceptance "parity scenarios green": every scenario that needs only the queue, `answerReview` and
 * `undoReview` runs whole (none is pending any more) and equals the server's golden — answer retry with the same id,
 * suspended `409`, deleted `410`, the `reviewed_at` bounds, `button` not matching `rating`, undo twice, unknown
 * `review_id`, the empty replica, a parent deleted by pull, and the queue after an answer across a day rollover.
 * Robolectric: the scenarios that fetch the queue read dictionary entries.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class AnswerParityTest {
    init {
        Class.forName("org.sqlite.JDBC") // DriverManager may not have loaded it yet inside Robolectric's class loader
    }

    private val scenarios = listOf(
        "answer-retry-same-id", "answer-suspended-deleted", "answer-reviewed-at-bounds", "answer-button-rating-mismatch",
        "undo-twice-unknown", "empty-replica", "parent-deleted-by-pull", "queue-day-rollover-timezone",
    )

    @Test
    fun theAnswerAndUndoScenariosMatchTheirGoldensWhole() {
        for (id in scenarios) {
            val o = ParityRunner.run(ParityScenario.load(id), LocalRoutes.all)
            val ran = o as? ParityOutcome.Ran ?: fail("$id did not run: $o")
            assertTrue(ran.matches, "$id:\n" + ran.problems.joinToString("\n"))
        }
    }

    /** What the goldens cannot show: how many ops each scenario left in the outbox (a refused step mints nothing). */
    @Test
    fun eachScenarioMintsExactlyTheOpsOfItsAcceptedSteps() {
        val expected = mapOf(
            "answer-retry-same-id" to listOf("review.logged"),
            "answer-suspended-deleted" to listOf("review.logged"),
            "answer-reviewed-at-bounds" to listOf("review.logged"),
            "answer-button-rating-mismatch" to listOf("review.logged", "review.logged"),
            "undo-twice-unknown" to listOf("review.logged", "review.undone", "review.undone"),
            "empty-replica" to emptyList(),
            "parent-deleted-by-pull" to listOf("review.logged"),
        )
        for ((id, ops) in expected) {
            val x = ParityRunner.execute(ParityScenario.load(id), LocalRoutes.all)
            assertEquals(ops, x.device.minted().map { (it.members["op_type"] as kotobaya.contracts.sync.JsonString).value }, id)
            assertEquals(emptyList(), x.forwarded, id)
        }
    }
}
