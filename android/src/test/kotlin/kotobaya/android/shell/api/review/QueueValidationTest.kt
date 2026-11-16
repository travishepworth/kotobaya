package kotobaya.android.shell.api.review

import kotlin.test.Test
import kotlin.test.assertEquals
import kotobaya.android.shell.api.review.QueueTestSupport.device
import kotobaya.android.shell.api.review.QueueTestSupport.firstError
import kotobaya.android.shell.api.review.QueueTestSupport.json
import kotobaya.android.shell.api.review.QueueTestSupport.obj
import kotobaya.android.shell.api.review.QueueTestSupport.op
import kotobaya.android.shell.api.review.QueueTestSupport.queue
import kotobaya.android.shell.parity.ParityJson
import kotobaya.contracts.sync.JsonString
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SHELL-L2 acceptance "QueueValidationTest": `getReviewQueue` answers bad parameters with the server's status, `code`
 * and `errors[0]` (`openapi.yaml` `getReviewQueue`; SHELL.md L-3). None of these reads the dictionary or mints.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class QueueValidationTest {
    private val deck = "0193a1b2-0004-7000-8000-000000000001"
    private val d = device("queue-materialize-count")

    private fun problem(query: String): Pair<Int, String> {
        val r = d.queue(query)
        assertEquals("application/problem+json", r.headers["content-type"]?.substringBefore(';'))
        val body = r.json()
        assertEquals("validation_failed", ParityJson.str(body.members["code"]))
        return r.status to body.firstError()
    }

    @Test
    fun limitZeroAnd201AndNotANumberAreOutOfRange() {
        assertEquals(422 to "/limit out_of_range", problem("limit=0"))
        assertEquals(422 to "/limit out_of_range", problem("limit=201"))
        assertEquals(422 to "/limit out_of_range", problem("limit=ten"))
    }

    @Test
    fun anUnknownKindIsInvalidValueAtKinds() {
        assertEquals(422 to "/kinds invalid_value", problem("kinds=later"))
        assertEquals(422 to "/kinds invalid_value", problem("kinds=new,later"))
        assertEquals(422 to "/kinds invalid_value", problem("kinds="))
    }

    @Test
    fun keepGoingAcceptsOnlyTheLiteralsTrueAndFalse() {
        assertEquals(422 to "/keep_going invalid_value", problem("keep_going=yes"))
        assertEquals(422 to "/keep_going invalid_value", problem("keep_going=1"))
    }

    @Test
    fun aDeckIdThatIsNotALowercaseUuidIsInvalidValue() {
        assertEquals(422 to "/deck_id invalid_value", problem("deck_id=nope"))
        assertEquals(422 to "/deck_id invalid_value", problem("deck_id=${deck.uppercase()}"))
    }

    @Test
    fun anUnknownQueryParameterIsUnknownField() {
        assertEquals(422 to "/cursor unknown_field", problem("cursor=x"))
    }

    @Test
    fun everyBadParameterIsReportedInOneProblem() {
        val r = d.queue("limit=0&kinds=x&keep_going=yes")
        val pointers = ParityJson.arr(r.json().members["errors"]).map { ParityJson.str(ParityJson.obj(it).members["pointer"]) }.toSet()
        assertEquals(setOf("/limit", "/kinds", "/keep_going"), pointers)
        assertEquals(0, d.localChanges)
    }

    @Test
    fun anUnknownOrDeletedDeckIsNotFound() {
        val unknown = d.queue("deck_id=0193a1b2-0004-7000-8000-0000000000ff")
        assertEquals(404, unknown.status)
        assertEquals("not_found", ParityJson.str(unknown.json().members["code"]))

        assertEquals(200, d.queue("deck_id=$deck").status)
        d.pull(
            listOf(op("0193a1b2-00ff-7007-8000-0000000000f1", "deck.deleted", "deck", deck, d.clock.now + 60_000, obj("id" to JsonString(deck)), device = "01931f3e-0000-7000-8000-0000000000a1")),
            "deck deleted",
        )
        val dead = d.queue("deck_id=$deck")
        assertEquals(404, dead.status)
        assertEquals("not_found", ParityJson.str(dead.json().members["code"]))
    }
}
