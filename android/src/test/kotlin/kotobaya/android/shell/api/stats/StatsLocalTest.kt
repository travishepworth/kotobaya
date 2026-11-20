package kotobaya.android.shell.api.stats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotobaya.android.shell.api.LocalRoutes
import kotobaya.android.shell.api.identity.IdentityTestSupport.minted
import kotobaya.android.shell.api.stats.StatsTestSupport.C2
import kotobaya.android.shell.api.stats.StatsTestSupport.DAY
import kotobaya.android.shell.api.stats.StatsTestSupport.T0
import kotobaya.android.shell.api.stats.StatsTestSupport.at
import kotobaya.android.shell.api.stats.StatsTestSupport.days
import kotobaya.android.shell.api.stats.StatsTestSupport.device
import kotobaya.android.shell.api.stats.StatsTestSupport.get
import kotobaya.android.shell.api.stats.StatsTestSupport.json
import kotobaya.android.shell.api.stats.StatsTestSupport.num
import kotobaya.android.shell.api.stats.StatsTestSupport.problem
import kotobaya.android.shell.api.stats.StatsTestSupport.send
import kotobaya.android.shell.parity.ParityDevice
import kotobaya.android.shell.parity.ParityJson
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonNull
import kotobaya.sync.client.apply.LearningTable
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * LF-A3: the three stats operations answer from the replica (SHELL.md 4.3): a local answer that has not been pushed
 * counts at once, `due` / `more_available` come from the same loop evaluation as `getReviewQueue`, the summary never
 * materializes, and validation is the server's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class StatsLocalTest {
    private fun answer(d: ParityDevice, n: Int, card: String, rating: Int, at: Long) =
        d.send("POST", "/api/v1/review/answer", """{"id":"${StatsTestSupport.reviewId(100 + n)}","card_id":"$card","rating":$rating,"duration_ms":4200,"utc_offset_min":0}""", at)

    @Test
    fun aLocalAnswerNotYetPushedCountsInTodaysSummaryAndHeatmapCell() {
        val d = device("stats-summary-local")
        val before = d.get("/api/v1/stats/summary", at = T0 + 1000)
        assertEquals(0L, before.at("/done_today/reviews").num())
        val today = ParityJson.str(before.at("/study_day"))
        assertTrue(d.get("/api/v1/stats/heatmap").days().none { ParityJson.str(it.members["date"]) == today })

        assertEquals(200, answer(d, 1, C2, 4, T0 + 2000).status)
        assertTrue(d.minted().any { ParityJson.str(it.members["op_type"]) == "review.logged" }, "the answer waits in the outbox")

        val after = d.get("/api/v1/stats/summary", at = T0 + 3000)
        assertEquals(1L, after.at("/done_today/reviews").num())
        assertEquals(1L, after.at("/done_today/new_cards").num())
        assertEquals(4200L, after.at("/done_today/time_ms").num())
        assertEquals(1L, after.at("/streak_days").num())
        val cell = d.get("/api/v1/stats/heatmap").days().last()
        assertEquals(today, ParityJson.str(cell.members["date"]))
        assertEquals(1L, cell.members["reviews"].num())
        assertEquals(1L, cell.members["new_cards"].num())

        // undone locally: no longer counted
        val reviewId = StatsTestSupport.reviewId(101)
        assertEquals(200, d.send("POST", "/api/v1/review/undo", """{"review_id":"$reviewId"}""", T0 + 4000).status)
        assertEquals(0L, d.get("/api/v1/stats/summary").at("/done_today/reviews").num())
        assertTrue(d.get("/api/v1/stats/heatmap").days().none { ParityJson.str(it.members["date"]) == today })
    }

    @Test
    fun summaryDueEqualsTheQueueAtTheSameInstant() {
        val d = device("stats-summary-local")
        fun same(at: Long, what: String) {
            d.clock.now = at
            val s = d.get("/api/v1/stats/summary").json().members
            val q = d.get("/api/v1/review/queue", "limit=20").json().members
            assertEquals(q["counts"], s["due"], "$what: due = counts")
            assertEquals(q["more_available"], s["more_available"], "$what: more_available")
            assertEquals(q["study_day"], s["study_day"], what)
            assertEquals(q["day_ends_at"], s["day_ends_at"], what)
        }
        same(T0 + 1000, "before the answer")
        assertEquals(200, answer(d, 1, C2, 4, T0 + 2000).status)
        same(T0 + 3000, "after the answer (done for today, keep going has card 3)")
        assertEquals(true, (d.get("/api/v1/stats/summary").at("/more_available") as kotobaya.contracts.sync.JsonBool).value)
        same(T0 + DAY, "the next study day")
        same(T0 + 30 * DAY, "a month later")
    }

    @Test
    fun theSummaryNeverMaterializesDeckEntries() {
        val d = device("queue-materialize-count")
        val minted = d.minted().size
        val cards = d.store.read { it.learning.all(LearningTable.CARD) }.size
        val s = d.get("/api/v1/stats/summary", at = d.clock.now)
        assertEquals(200, s.status, s.body)
        assertEquals(2L, s.at("/due/new").num(), "drawn deck entries without notes are counted")
        assertEquals(minted, d.minted().size, "nothing minted")
        assertEquals(cards, d.store.read { it.learning.all(LearningTable.CARD) }.size, "no card created")
        val q = d.get("/api/v1/review/queue", "limit=20").json().members
        assertEquals(q["counts"], d.get("/api/v1/stats/summary").json().members["due"])
    }

    @Test
    fun anEmptyReplicaAnswersZerosAndEmptyLists() {
        Class.forName("org.sqlite.JDBC")
        val d = ParityDevice(kotobaya.android.shell.api.review.AnswerTestSupport.X, T0, LocalRoutes.all)
        val s = d.get("/api/v1/stats/summary")
        assertEquals(200, s.status, s.body)
        for (p in listOf("/due/new", "/due/learning", "/due/review", "/done_today/reviews", "/totals/notes", "/totals/cards", "/streak_days")) {
            assertEquals(0L, s.at(p).num(), p)
        }
        assertEquals(JsonNull, s.at("/next_due_at"))
        assertEquals(JsonArray(emptyList()), d.get("/api/v1/stats/heatmap").at("/days"))
        val r = d.get("/api/v1/stats/retention")
        assertEquals(JsonArray(emptyList()), r.at("/groups"))
        assertEquals(0L, r.at("/overall/total").num())
        assertEquals(JsonNull, r.at("/overall/retention"))
    }

    @Test
    fun totalsAndNextDueComeFromTheReplica() {
        val d = device("stats-summary-local")
        val s = d.get("/api/v1/stats/summary", at = T0 + 1000)
        assertEquals(3L, s.at("/totals/notes").num())
        assertEquals(3L, s.at("/totals/cards").num())
        assertEquals(0L, s.at("/totals/suspended").num())
        assertEquals(0L, s.at("/totals/leeches").num())
        // card 1 graduated (Easy) two days ago: its due is the only future one
        val due = d.store.read { it.learning.all(LearningTable.CARD) }
            .single { ParityJson.str(it["id"]) == StatsTestSupport.C1 }["due_at"].num()
        assertEquals(kotobaya.android.shell.api.identity.timestamp(due), ParityJson.str(s.at("/next_due_at")))
    }

    @Test
    fun validationIsTheServers() {
        val d = device("stats-heatmap-retention-local")
        d.clock.now = T0
        assertEquals("422 /from out_of_range", d.get("/api/v1/stats/heatmap", "from=2026-11-20&to=2026-11-01").problem())
        assertEquals("422 /from out_of_range", d.get("/api/v1/stats/retention", "from=2016-11-12&to=2026-11-20").problem())
        assertEquals(200, d.get("/api/v1/stats/heatmap", "from=2016-11-13&to=2026-11-20").status, "exactly 3660 days")
        assertEquals("422 /from invalid_value", d.get("/api/v1/stats/heatmap", "from=2026-13-01").problem())
        assertEquals("422 /to invalid_value", d.get("/api/v1/stats/retention", "to=yesterday").problem())
        assertEquals("422 /group_by invalid_value", d.get("/api/v1/stats/retention", "group_by=year").problem())
        assertEquals("422 /x unknown_field", d.get("/api/v1/stats/heatmap", "x=1").problem())
        assertEquals("422 /from unknown_field", d.get("/api/v1/stats/summary", "from=2026-11-01").problem())
        assertEquals("422 /group_by unknown_field", d.get("/api/v1/stats/heatmap", "group_by=day").problem())
    }
}
