package kotobaya.android.shell.api.review

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotobaya.android.shell.api.LocalRoutes
import kotobaya.android.shell.api.review.QueueTestSupport.device
import kotobaya.android.shell.api.review.QueueTestSupport.items
import kotobaya.android.shell.api.review.QueueTestSupport.json
import kotobaya.android.shell.api.review.QueueTestSupport.long
import kotobaya.android.shell.api.review.QueueTestSupport.queue
import kotobaya.android.shell.api.review.QueueTestSupport.str
import kotobaya.android.shell.parity.ParityDevice
import kotobaya.android.shell.parity.ParityJson
import kotobaya.android.shell.parity.ParityOutcome
import kotobaya.android.shell.parity.ParityRunner
import kotobaya.android.shell.parity.ParityScenario
import kotobaya.android.shell.parity.RawResponse
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SHELL-L2 parity beyond the scenarios `LocalParityTest` runs whole. The queue scenarios that only need
 * `getReviewQueue` / `updateSettings` (counts with and without keep going, learning wait, session cap, kinds filter,
 * reviews unlimited, C-27 count) run there. Scenarios that also need operations of later tickets (`answerReview`,
 * `createNote`, `undoReview`) are pending there; here every step before the first such operation must already equal
 * its golden step, and the parts of "reading re-enabled" and "non-UTC day rollover" that need no other operation are
 * checked directly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class QueueParityTest {
    private val routes = LocalRoutes.all

    private fun registered(method: String, path: String): Boolean = routes.any { r ->
        r.method == method && Regex("^" + r.pathTemplate.replace(Regex("\\{[^}]+}"), "[^/]+") + "$").matches(path)
    }

    @Test
    fun theQueueScenariosThatOnlyNeedThisTicketMatchTheirGoldensWhole() {
        val ids = listOf(
            "queue-counts-keep-going", "queue-learning-wait", "queue-session-cap-more-available", "queue-kinds-filter-counts",
            "queue-reviews-unlimited", "queue-materialize-count",
        )
        for (id in ids) {
            val o = ParityRunner.run(ParityScenario.load(id), routes)
            val ran = o as? ParityOutcome.Ran ?: fail("$id did not run: $o")
            assertTrue(ran.matches, "$id:\n" + ran.problems.joinToString("\n"))
        }
    }

    @Test
    fun pendingScenariosMatchTheirGoldensUpToTheFirstUnregisteredOperation() {
        val checked = ArrayList<String>()
        val problems = ArrayList<String>()
        for (s in ParityScenario.all()) {
            val ops = routes.map { it.operationId }.toSet()
            // only scenarios LocalParityTest leaves pending (the others run whole there)
            if ("getReviewQueue" !in s.operations || s.operations.all { it in ops }) continue
            val d = ParityDevice(s.deviceId, s.clockMs, routes)
            d.pull(s.seedLog, "seed")
            val raw = ArrayList<RawResponse?>()
            for (i in s.steps.indices) {
                d.clock.now = s.atMs(i)
                if (s.isApply(i)) {
                    d.pull(s.applyOps(i), "step $i")
                    raw += null
                    continue
                }
                val r = s.request(i)
                if (!registered(r.method, r.path)) break
                val res = d.request(r.method, r.path, r.query, r.body)
                raw += RawResponse(res.status, res.headers.mapKeys { it.key.lowercase() }, res.body?.ifEmpty { null })
            }
            if (raw.none { it != null }) continue
            checked += s.id
            val actual = ParityRunner.normalise(s, raw)
            val golden = ParityScenario.golden(s.id) ?: fail("no golden ${s.id}")
            val goldenSteps = ParityJson.arr(golden.members["steps"]).take(raw.size)
            val expected = JsonObject(linkedMapOf("id" to JsonString(s.id), "steps" to JsonArray(goldenSteps)))
            problems += ParityJson.diff(expected, actual).map { "${s.id}: $it" }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
        assertTrue(
            // SHELL-L3: `empty-replica` and `queue-day-rollover-timezone` are no longer pending (they run whole, AnswerParityTest)
            checked.containsAll(listOf("dict-entry-missing", "reading-retired-reenabled")),
            "checked: $checked",
        )
    }

    /** `queue-day-rollover-timezone` without its answer: the study day of Asia/Tokyo changes at 19:00:00.000Z. */
    @Test
    fun theStudyDayRollsOverAtFourOClockTokyoTime() {
        val d = device("queue-day-rollover-timezone")
        val before = d.queue(at = 1_795_201_199_999L).json()
        assertEquals("2026-11-20", str(before.members["study_day"]))
        assertEquals("2026-11-20T19:00:00.000Z", str(before.members["day_ends_at"]))
        val after = d.queue(at = 1_795_201_200_000L).json()
        assertEquals("2026-11-21", str(after.members["study_day"]))
        assertEquals("2026-11-21T19:00:00.000Z", str(after.members["day_ends_at"]))
        assertEquals(1L, after.long("/counts/new"))
    }

    /** `reading-retired-reenabled` without `createNote`: the seed note's reading card is served only while enabled. */
    @Test
    fun aRetiredReadingCardComesBackWhenReadingIsEnabled() {
        val d = device("reading-retired-reenabled")
        fun kinds(): List<String> = d.queue().json().items().map { str(ParityJson.at(it, "/card/kind")) }
        fun set(reading: Boolean) {
            val body = """{"values":{"cards.reading_enabled":$reading,"scheduler.bury_siblings":false}}"""
            assertEquals(200, d.request("PATCH", "/api/v1/settings", "", ParityJson.parse(body)).status)
        }
        d.clock.now += 1_000
        set(false)
        assertEquals(listOf("meaning"), kinds())
        d.clock.now += 1_000
        set(true)
        assertEquals(listOf("meaning", "reading"), kinds().sorted())
        assertEquals(2L, d.queue().json().long("/counts/new"))
        d.clock.now += 1_000
        set(false)
        assertEquals(listOf("meaning"), kinds())
        assertEquals(1L, d.queue().json().long("/counts/new"))
    }
}
