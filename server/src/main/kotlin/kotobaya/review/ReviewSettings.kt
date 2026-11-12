package kotobaya.review

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import kotobaya.contracts.queue.AddKanjiMode
import kotobaya.contracts.queue.StudyDay
import kotobaya.server.json
import org.jooq.DSLContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * The `scheduler.*` settings the review module reads, by the validity test of SCHEMA.md 6.1: an invalid value, a
 * missing row and JSON null alike mean the default; nothing is clamped or rounded.
 */
internal data class ReviewSettings(
    val timezone: ZoneId = ZoneOffset.UTC,
    val dayRolloverHour: Int = 4,
    val newPerDay: Int = 20,
    val reviewsPerDay: Int = 200,
    val newPerSession: Int = 10,
    val burySiblings: Boolean = true,
    val kanjiBeforeVocab: Boolean = false,
    val minedPriority: Int = 0,
    val leechThreshold: Int = 8,
) {
    /** `decklessAddKanji` of the queue input (SCHEMA.md 6.2). */
    val decklessAddKanji: AddKanjiMode get() = if (kanjiBeforeVocab) AddKanjiMode.BEFORE else AddKanjiMode.OFF

    companion object {
        private val KEYS = listOf(
            "timezone", "day_rollover_hour", "new_per_day", "reviews_per_day", "new_per_session", "bury_siblings",
            "kanji_before_vocab", "mined_priority", "leech_threshold",
        ).map { "scheduler.$it" }
        private val bigDecimals = json.readerFor(JsonNode::class.java).with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

        fun load(dsl: DSLContext): ReviewSettings {
            val values = dsl.fetch("select key, value::text from app.settings where key = any(?::text[])", KEYS.toTypedArray())
                .associate { it.get(0, String::class.java) to bigDecimals.readTree(it.get(1, String::class.java)) }
            val d = ReviewSettings()
            fun int(key: String, default: Int, min: Int = Int.MIN_VALUE, max: Int = Int.MAX_VALUE) =
                intValue(values["scheduler.$key"], min, max) ?: default
            fun bool(key: String, default: Boolean) = values["scheduler.$key"]?.takeIf { it.isBoolean }?.asBoolean() ?: default
            return ReviewSettings(
                timezone = values["scheduler.timezone"]?.takeIf { it.isTextual }?.let { runCatching { ZoneId.of(it.asText()) }.getOrNull() } ?: d.timezone,
                dayRolloverHour = int("day_rollover_hour", d.dayRolloverHour, 0, 23),
                newPerDay = int("new_per_day", d.newPerDay),
                reviewsPerDay = int("reviews_per_day", d.reviewsPerDay),
                newPerSession = int("new_per_session", d.newPerSession),
                burySiblings = bool("bury_siblings", d.burySiblings),
                kanjiBeforeVocab = bool("kanji_before_vocab", d.kanjiBeforeVocab),
                minedPriority = int("mined_priority", d.minedPriority),
                leechThreshold = int("leech_threshold", d.leechThreshold),
            )
        }

        /**
         * SCHEMA.md 6.1 `int`: a JSON number whose binary64 value is an integer in `[min, max]`
         * (`1.0` and `1e2` are valid, `3.5`, `1e400`, `"3"` and `true` are not); otherwise null.
         */
        fun intValue(node: JsonNode?, min: Int, max: Int): Int? {
            if (node == null || !node.isNumber) return null
            val d = node.decimalValue().toDouble()
            if (!d.isFinite() || d != Math.floor(d) || d < min || d > max) return null
            return d.toInt()
        }
    }
}

/** A study day with the local date it is named after (the response's `study_day`). */
internal data class LocalStudyDay(val date: LocalDate, val day: StudyDay)

/**
 * The study day containing [nowMs] (QueueBuilder.kt `StudyDay`): it starts at the latest instant `<= now` at which the
 * wall clock in [zone] reads `hour:00`, and ends at the next such instant (23 h or 25 h across a DST change). When the
 * rollover time falls in a DST gap the first instant after the gap is used; when it falls in an overlap the earlier
 * of the two instants is.
 */
internal fun studyDayOf(nowMs: Long, zone: ZoneId, hour: Int): LocalStudyDay {
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val date = if (rolloverMs(zone, hour, today) <= nowMs) today else today.minusDays(1)
    return LocalStudyDay(date, StudyDay(rolloverMs(zone, hour, date), rolloverMs(zone, hour, date.plusDays(1))))
}

/** The instant the study day named [date] starts: `hour:00` local, or the first instant after a DST gap that contains it. */
internal fun rolloverMs(zone: ZoneId, hour: Int, date: LocalDate): Long {
    val local = date.atTime(hour, 0)
    val t = zone.rules.getTransition(local)
    val at: ZonedDateTime = if (t != null && t.isGap) t.dateTimeAfter.atZone(zone) else local.atZone(zone)
    return at.toInstant().toEpochMilli()
}
