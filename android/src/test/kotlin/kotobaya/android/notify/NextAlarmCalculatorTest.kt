package kotobaya.android.notify

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NextAlarmCalculatorTest {
    private val utc = ZoneOffset.UTC
    private val min = 60_000L
    private val now = LocalDateTime.of(2026, 3, 10, 10, 7).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun next(dues: List<Long>, zone: ZoneId = utc, last: Long? = null, rollover: Int? = null) =
        NextAlarmCalculator.next(dues, now, zone, last, rollover)

    @Test
    fun dueInsideBatchWindowAlignsToQuarterHour() {
        // 10:07 now, due 10:09 -> 10:15
        assertEquals(now - 7 * min + 15 * min, next(listOf(now + 2 * min)))
    }

    @Test
    fun farFutureDueFiresAtItsQuarterHour() {
        val due = now + 30 * 60 * 60_000L + 4 * min
        val at = next(listOf(due))!!
        assertTrue(at >= due && at - due < 15 * min)
        assertEquals(0L, (at % (15 * min)))
    }

    @Test
    fun noneDueMeansNoAlarm() {
        assertNull(next(emptyList()))
    }

    @Test
    fun manyDueInThePastFireAtMostFifteenMinutesOut() {
        assertEquals(now + 15 * min, next(List(500) { now - 1000L * it - 1 }.sorted()))
        // a recent alarm cannot push it past now + 15 min
        assertEquals(now + 15 * min, next(listOf(now - 5), last = now - 1))
    }

    @Test
    fun lastAlarmBatchesAFutureDue() {
        val last = now - 2 * min
        // due in 1 minute would align to 10:15, but last + 15 = 10:20 wins
        assertEquals(last + 15 * min, next(listOf(now + min), last = last))
    }

    @Test
    fun rolloverIsAWakeUpAndFollowsTheTimeZone() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        val inUtc = next(emptyList(), utc, rollover = 4)!!
        val inTokyo = next(emptyList(), tokyo, rollover = 4)!!
        assertEquals(LocalDateTime.of(2026, 3, 11, 4, 0).toInstant(ZoneOffset.UTC).toEpochMilli(), inUtc)
        assertEquals(LocalDateTime.of(2026, 3, 11, 4, 0).atZone(tokyo).toInstant().toEpochMilli(), inTokyo)
        assertTrue(inUtc != inTokyo)
    }

    @Test
    fun dstDayRolloverIsTheRealWallClockInstant() {
        val ny = ZoneId.of("America/New_York")
        // 2026-03-08 is the spring-forward day; at 2026-03-07 12:00 local the next 04:00 is 23 h later in UTC terms +...
        val t = LocalDateTime.of(2026, 3, 8, 1, 0).atZone(ny).toInstant().toEpochMilli()
        val at = NextAlarmCalculator.next(emptyList(), t, ny, null, 4)!!
        assertEquals(LocalDateTime.of(2026, 3, 8, 4, 0).atZone(ny).toInstant().toEpochMilli(), at)
        assertEquals(2 * 60 * min, at - t) // 01:00 EST -> 04:00 EDT is only 2 elapsed hours
    }

    @Test
    fun eveningReminderRollsToTomorrowOncePassed() {
        val t = NextAlarmCalculator.next(emptyList(), now, utc, null, null, java.time.LocalTime.of(8, 0))!!
        assertEquals(LocalDateTime.of(2026, 3, 11, 8, 0).toInstant(ZoneOffset.UTC).toEpochMilli(), t)
    }

    @Test
    fun deterministic() {
        val dues = listOf(now + 5 * min, now + 90 * min)
        assertEquals(next(dues, last = now - min), next(dues, last = now - min))
    }
}
