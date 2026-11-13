package kotobaya.stats

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

private const val MIN_MS = 60_000L
private const val HOUR_MS = 3_600_000L

/**
 * The study day of a review (openapi `getStatsHeatmap`): `reviewed_at` shifted by the row's own `utc_offset_min`
 * (the zone's offset at that instant when NULL), minus the rollover hour; the date of the result.
 */
internal fun studyDayOfReview(reviewedAtMs: Long, utcOffsetMin: Int?, zone: ZoneId, rolloverHour: Int): LocalDate {
    val offsetMs = if (utcOffsetMin != null) utcOffsetMin * MIN_MS
    else zone.rules.getOffset(Instant.ofEpochMilli(reviewedAtMs)).totalSeconds * 1000L
    return Instant.ofEpochMilli(reviewedAtMs + offsetMs - rolloverHour * HOUR_MS).atZone(ZoneOffset.UTC).toLocalDate()
}

/** A SQL window (epoch ms) wide enough to contain every review whose study day is in `[from, to]`, whatever its offset. */
internal fun windowMs(from: LocalDate, to: LocalDate): Pair<Long, Long> =
    from.minusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() to
        to.plusDays(3).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
