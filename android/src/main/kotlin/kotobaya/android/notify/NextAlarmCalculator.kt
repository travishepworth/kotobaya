package kotobaya.android.notify

import kotobaya.android.queue.studyDayOf
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Pure decision of the single inexact alarm (no polling). Wake-ups are batched: never sooner than [BATCH_MS] after the
 * previous alarm, and aligned up to a quarter hour of the user's wall clock so nearby due times share one wake-up.
 */
object NextAlarmCalculator {
    const val BATCH_MS: Long = 15 * 60_000L

    /**
     * @param dues sorted due instants (epoch ms); an instant `<= now` means something is due already, which re-checks
     *   in [BATCH_MS] (counts, nudge thresholds) rather than immediately.
     * @param lastAlarm when the previous alarm fired, if known.
     * @param dayRolloverHour when set, the next study-day rollover is also a wake-up (the counts change then).
     * @param eveningReminder when set, the next occurrence of that wall-clock time is also a wake-up.
     * @param extra other wake-ups (for example when the sync becomes stale).
     * @return the alarm instant, or null when nothing needs one.
     */
    fun next(
        dues: List<Long>,
        now: Long,
        zone: ZoneId,
        lastAlarm: Long?,
        dayRolloverHour: Int? = null,
        eveningReminder: LocalTime? = null,
        extra: List<Long> = emptyList(),
    ): Long? {
        val candidates = ArrayList<Long>()
        if (dues.any { it <= now }) candidates += now + BATCH_MS
        dues.firstOrNull { it > now }?.let { candidates += alignUp(it, zone) }
        if (dayRolloverHour != null) candidates += studyDayOf(now, zone, dayRolloverHour).day.endMs
        if (eveningReminder != null) candidates += nextWallClock(now, zone, eveningReminder)
        extra.filter { it > now }.forEach { candidates += alignUp(it, zone) }
        val best = candidates.minOrNull() ?: return null
        val floor = lastAlarm?.let { minOf(it, now) + BATCH_MS } ?: now
        return maxOf(best, floor, now + 1)
    }

    internal fun alignUp(t: Long, zone: ZoneId): Long {
        val off = zone.rules.getOffset(Instant.ofEpochMilli(t)).totalSeconds * 1000L
        val local = t + off
        val aligned = Math.floorDiv(local + BATCH_MS - 1, BATCH_MS) * BATCH_MS
        return aligned - off
    }

    internal fun nextWallClock(now: Long, zone: ZoneId, time: LocalTime): Long {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val first = today.atTime(time).atZone(zone).toInstant().toEpochMilli()
        return if (first > now) first else today.plusDays(1).atTime(time).atZone(zone).toInstant().toEpochMilli()
    }
}
