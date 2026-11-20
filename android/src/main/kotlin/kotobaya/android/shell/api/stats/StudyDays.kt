package kotobaya.android.shell.api.stats

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import kotobaya.android.shell.api.FieldErrors
import kotobaya.android.shell.api.QueryParams

private const val MIN_MS = 60_000L
private const val HOUR_MS = 3_600_000L

/** `from` / `to` of a stats request may span at most this many study days (openapi `getStatsHeatmap`). */
internal const val MAX_RANGE_DAYS: Long = 3660L

/**
 * The study day of a review (openapi `getStatsHeatmap`, the server's `studyDayOfReview`): `reviewed_at` shifted by the
 * row's own `utc_offset_min` (the user's zone's offset at that instant when NULL), minus the rollover hour; the date of
 * the result. CLAUDE.md rule 9: the stored instant is UTC, the zone only decides the day.
 */
internal fun studyDayOfReview(reviewedAtMs: Long, utcOffsetMin: Int?, zone: ZoneId, rolloverHour: Int): LocalDate {
    val offsetMs = if (utcOffsetMin != null) {
        utcOffsetMin * MIN_MS
    } else {
        zone.rules.getOffset(Instant.ofEpochMilli(reviewedAtMs)).totalSeconds * 1000L
    }
    return Instant.ofEpochMilli(reviewedAtMs + offsetMs - rolloverHour * HOUR_MS).atZone(ZoneOffset.UTC).toLocalDate()
}

/** A `reviewed_at` window `[lo, hi)` wide enough to contain every review whose study day is in `[from, to]`, whatever its offset. */
internal fun windowMs(from: LocalDate, to: LocalDate): Pair<Long, Long> =
    from.minusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() to
        to.plusDays(3).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/** A validated `[from, to]` of study days. */
internal data class DayRange(val from: LocalDate, val to: LocalDate)

/**
 * `from` / `to` exactly as the server's `StatsModule.range`: each a `YYYY-MM-DD` date (`invalid_value`); with any error
 * so far (including ones the caller added before) nothing else is checked; defaults `to` = [today], `from` = `to` − 364
 * days; `from` after `to`, or a range over [MAX_RANGE_DAYS] days, is `out_of_range` at `/from`. Null when [errors] has
 * any error.
 */
internal fun dayRange(q: QueryParams, today: LocalDate, errors: FieldErrors): DayRange? {
    fun date(name: String): LocalDate? = q.first(name)?.let { v ->
        try {
            LocalDate.parse(v)
        } catch (e: DateTimeParseException) {
            errors.add("/$name", "invalid_value", "Must be a date YYYY-MM-DD.")
            null
        }
    }
    val to = date("to")
    val from = date("from")
    if (!errors.isEmpty) return null
    val t = to ?: today
    val f = from ?: t.minusDays(364)
    if (f.isAfter(t)) {
        errors.add("/from", "out_of_range", "from is after to.")
    } else if (ChronoUnit.DAYS.between(f, t) + 1 > MAX_RANGE_DAYS) {
        errors.add("/from", "out_of_range", "The range may span at most $MAX_RANGE_DAYS days.")
    }
    return if (errors.isEmpty) DayRange(f, t) else null
}
