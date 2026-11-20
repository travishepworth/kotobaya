package kotobaya.android.shell.api.stats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotobaya.android.shell.api.stats.StatsTestSupport.C1
import kotobaya.android.shell.api.stats.StatsTestSupport.C2
import kotobaya.android.shell.api.stats.StatsTestSupport.T0
import kotobaya.android.shell.api.stats.StatsTestSupport.at
import kotobaya.android.shell.api.stats.StatsTestSupport.days
import kotobaya.android.shell.api.stats.StatsTestSupport.device
import kotobaya.android.shell.api.stats.StatsTestSupport.get
import kotobaya.android.shell.api.stats.StatsTestSupport.json
import kotobaya.android.shell.api.stats.StatsTestSupport.num
import kotobaya.android.shell.api.stats.StatsTestSupport.review
import kotobaya.android.shell.api.stats.StatsTestSupport.setting
import kotobaya.android.shell.parity.ParityDevice
import kotobaya.android.shell.parity.ParityJson
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonString
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * LF-A3, CLAUDE.md rule 9: a review's study day comes from `reviewed_at`, the row's own `utc_offset_min` (else
 * `scheduler.timezone`) and `scheduler.day_rollover_hour`, as the server's `studyDayOfReview`; "today" is the device
 * clock in the user's zone. The expected days are evaluated by hand from the openapi definitions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class StatsStudyDayTest {
    /**
     * Zone Asia/Tokyo (UTC+9), rollover 4:00. T0 = 2026-11-20T10:00Z = 19:00 JST, study day 11-20.
     * 1: C1 learning 11-19T18:59Z, offset unknown -> 03:59 JST 11-20 -> study day 11-19 (new card)
     * 2: C1 scheduled 11-19T19:01Z, offset unknown -> 04:01 JST -> 11-20
     * 3: C2 learning 11-18T08:30Z, offset -300 (the row's own, not the zone) -> 03:30 -> 11-17 (new card)
     * 4: C2 drill rated Again 11-18T20:00Z, offset 540 -> 05:00 11-19 -> 11-19 (never a new card)
     */
    private fun tokyo(): ParityDevice = device("answer-retry-same-id").also { d ->
        d.pull(
            listOf(
                setting(1, "scheduler.timezone", JsonString("Asia/Tokyo")),
                setting(2, "scheduler.day_rollover_hour", JsonNumber(4.0)),
                review(3, C1, 1_795_114_740_000L, null, type = "learning", durationMs = 1000),
                review(4, C1, 1_795_114_860_000L, null, type = "scheduled", durationMs = 2000),
                review(5, C2, 1_794_990_600_000L, -300, type = "learning", durationMs = 4000),
                review(6, C2, 1_795_032_000_000L, 540, type = "drill", rating = 1, durationMs = 8000),
            ),
            "tokyo",
        )
        d.clock.now = T0
    }

    @Test
    fun heatmapBucketsReviewsByTheUsersZoneRolloverAndRowOffset() {
        val r = tokyo().get("/api/v1/stats/heatmap", "from=2026-11-15&to=2026-11-20")
        assertEquals(200, r.status, r.body)
        val days = r.days().map { d ->
            listOf(ParityJson.str(d.members["date"]), d.members["reviews"].num(), d.members["new_cards"].num(), d.members["again"].num(), d.members["time_ms"].num())
        }
        assertEquals(
            listOf(
                listOf("2026-11-17", 1L, 1L, 0L, 4000L),
                listOf("2026-11-19", 2L, 1L, 1L, 9000L),
                listOf("2026-11-20", 1L, 0L, 0L, 2000L),
            ),
            days,
        )
    }

    @Test
    fun withoutAZoneSettingTheSameRowsFallOnUtcStudyDays() {
        val d = device("answer-retry-same-id")
        d.pull(listOf(review(3, C1, 1_795_114_740_000L, null, type = "learning"), review(4, C1, 1_795_114_860_000L, null)), "utc")
        val r = d.get("/api/v1/stats/heatmap", "from=2026-11-15&to=2026-11-20", at = T0)
        assertEquals(listOf("2026-11-19"), r.days().map { ParityJson.str(it.members["date"]) })
        assertEquals(2L, r.days().single().members["reviews"].num())
    }

    @Test
    fun summaryTodayStreakAndDayEndFollowTheZone() {
        val d = tokyo()
        val s = d.get("/api/v1/stats/summary")
        assertEquals(200, s.status, s.body)
        assertEquals(JsonString("2026-11-20"), s.at("/study_day"))
        assertEquals(JsonString("2026-11-20T19:00:00.000Z"), s.at("/day_ends_at"))
        assertEquals(1L, s.at("/done_today/reviews").num())
        assertEquals(0L, s.at("/done_today/new_cards").num())
        assertEquals(2000L, s.at("/done_today/time_ms").num())
        assertEquals(JsonNumber(1.0), s.at("/done_today/retention"))
        assertEquals(2L, s.at("/streak_days").num(), "11-20 and 11-19; 11-18 has no review")

        // 03:59 JST on 11-21 is still study day 11-20
        val before = d.get("/api/v1/stats/summary", at = 1_795_201_140_000L)
        assertEquals(JsonString("2026-11-20"), before.at("/study_day"))
        assertEquals(1L, before.at("/done_today/reviews").num())
        // 04:00 JST: a new study day, nothing done yet; the streak still ends yesterday
        val after = d.get("/api/v1/stats/summary", at = 1_795_201_200_000L)
        assertEquals(JsonString("2026-11-21"), after.at("/study_day"))
        assertEquals(0L, after.at("/done_today/reviews").num())
        assertEquals(JsonNull, after.at("/done_today/retention"))
        assertEquals(2L, after.at("/streak_days").num())
        // two study days later the streak is broken
        assertEquals(0L, d.get("/api/v1/stats/summary", at = 1_795_201_200_000L + StatsTestSupport.DAY).at("/streak_days").num())
    }

    @Test
    fun heatmapDefaultsToTheYearEndingTodayInTheUsersZone() {
        val r = tokyo().get("/api/v1/stats/heatmap", at = T0 + 9 * StatsTestSupport.HOUR) // 04:00 JST 11-21
        assertEquals(JsonString("2025-11-22"), r.at("/from"))
        assertEquals(JsonString("2026-11-21"), r.at("/to"))
    }

    @Test
    fun retentionGroupsByKindDayWeekAndMonth() {
        val d = tokyo()
        fun keys(groupBy: String) = d.get("/api/v1/stats/retention", "from=2026-11-01&to=2026-11-20&group_by=$groupBy")
            .json().let { ParityJson.arr(it.members["groups"]).map { g -> ParityJson.str(ParityJson.obj(g).members["key"]) } }
        assertEquals(listOf("vocab/meaning"), keys("card_kind"))
        assertEquals(listOf("2026-11-20"), keys("day"))
        assertEquals(listOf("2026-W47"), keys("week"))
        assertEquals(listOf("2026-11"), keys("month"))
        val r = d.get("/api/v1/stats/retention", "from=2026-11-01&to=2026-11-20")
        assertEquals(1L, r.at("/overall/total").num(), "the scheduled row only: not learning, not drill")
        assertEquals(1L, r.at("/overall/young_total").num())
        assertEquals(0L, r.at("/overall/mature_total").num())
        assertEquals(JsonString("all"), r.at("/overall/key"))
    }
}
