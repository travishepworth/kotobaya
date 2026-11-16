package kotobaya.android.shell.api.struggle

import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.api.actions.routes as actionRoutes
import kotobaya.android.shell.api.identity.routes as identityRoutes
import kotobaya.android.shell.api.identity.IdentityTestSupport.minted
import kotobaya.android.shell.api.review.AnswerTestSupport
import kotobaya.android.shell.api.review.AnswerTestSupport.answer
import kotobaya.android.shell.api.review.AnswerTestSupport.reviewId
import kotobaya.android.shell.api.review.routes as reviewRoutes
import kotobaya.android.shell.api.struggle.routes as struggleRoutes
import kotobaya.android.shell.parity.ParityDevice
import kotobaya.android.shell.parity.ParityJson
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonObject
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.Row

/**
 * Helpers of the SHELL-L8 tests: a [ParityDevice] over the struggle, review, identity and actions routes, seeded with the
 * scenario `answer-retry-same-id` (two vocab notes, entries 1000220 and 1000440, each with a `meaning` card [C1], [C2]).
 *
 * [struggled] builds the history the scenarios share: `scheduler.leech_threshold` 1; C1 learned to review and then
 * failed in review state (a lapse: a leech and hot), C2 failed once as a new card.
 */
internal object StruggleTestSupport {
    const val T0 = AnswerTestSupport.T0
    const val X = AnswerTestSupport.X
    const val C1 = AnswerTestSupport.C1
    const val C2 = AnswerTestSupport.C2
    const val N1 = "0193a1b2-0001-7000-8000-000000000001"
    const val N2 = "0193a1b2-0001-7000-8000-000000000002"

    /** The instant after the last answer of [struggled]. */
    const val NOW = T0 + 1_000_000L

    fun device(): ParityDevice {
        Class.forName("org.sqlite.JDBC")
        return ParityDevice(X, T0, struggleRoutes + reviewRoutes + identityRoutes + actionRoutes).also { it.pull(AnswerTestSupport.seed(), "seed") }
    }

    fun struggled(): ParityDevice = device().also { d ->
        check(call(d, "PATCH", "/api/v1/settings", """{"values":{"scheduler.leech_threshold":1}}""", T0).status == 200)
        d.answer(reviewId(1), C1, 3, at = T0)
        d.answer(reviewId(2), C1, 3, at = T0 + 70_000)
        d.answer(reviewId(3), C1, 3, at = T0 + 700_000)
        d.answer(reviewId(4), C1, 1, at = T0 + 800_000) // review state -> relearning: a lapse
        d.answer(reviewId(5), C2, 1, at = T0 + 900_000) // new -> learning
        d.clock.now = NOW
    }

    fun call(d: ParityDevice, method: String, path: String, body: String? = null, at: Long? = null, query: String = ""): ShellResponse {
        at?.let { d.clock.now = it }
        return d.request(method, path, query, body?.let { ParityJson.parse(it) })
    }

    fun ShellResponse.json(): JsonObject = ParityJson.obj(ParityJson.parse(body ?: error("no body (status $status)")))

    fun ShellResponse.items(): List<JsonObject> = (json().members["items"] as JsonArray).items.map(ParityJson::obj)

    fun ShellResponse.problem(): String = with(AnswerTestSupport) { problem() }

    fun ParityDevice.snapshot(): Map<LearningTable, List<Row>> =
        store.read { tx -> LearningTable.entries.associateWith { tx.learning.all(it) } }

    fun ParityDevice.mintedCount(): Int = minted().size
}
