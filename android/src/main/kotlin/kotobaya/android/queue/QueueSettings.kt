package kotobaya.android.queue

import kotobaya.contracts.queue.AddKanjiMode
import kotobaya.contracts.queue.StudyDay
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.sync.DefaultCanonicalJson
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The `scheduler.*` settings the queue reads, by the validity test of SCHEMA.md 6.1: an invalid value, a missing row
 * and JSON null alike mean the default; nothing is clamped or rounded. Same rules as the server's ReviewSettings.
 */
internal data class QueueSettings(
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
        /** [raw]: `settings.key` to the stored JSON text. */
        fun from(raw: Map<String, String>): QueueSettings {
            val values = raw.mapValues { (_, text) -> DefaultCanonicalJson.decode(text).value }
            val d = QueueSettings()
            fun int(key: String, default: Int, min: Int = Int.MIN_VALUE, max: Int = Int.MAX_VALUE) =
                intValue(values["scheduler.$key"], min, max) ?: default
            fun bool(key: String, default: Boolean) = (values["scheduler.$key"] as? JsonBool)?.value ?: default
            return QueueSettings(
                timezone = (values["scheduler.timezone"] as? JsonString)?.let { runCatching { ZoneId.of(it.value) }.getOrNull() } ?: d.timezone,
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

        /** SCHEMA.md 6.1 `int`: a JSON number whose binary64 value is an integer in `[min, max]`; otherwise null. */
        fun intValue(node: JsonValue?, min: Int, max: Int): Int? {
            val d = (node as? JsonNumber)?.value ?: return null
            if (!d.isFinite() || d != Math.floor(d) || d < min || d > max) return null
            return d.toInt()
        }
    }
}

/** The study day containing [nowMs] with the local date it is named after. */
internal data class LocalStudyDay(val date: LocalDate, val day: StudyDay)

/**
 * The study day containing [nowMs] (StudyDay KDoc): starts at the latest instant `<= now` at which the wall clock in
 * [zone] reads `hour:00`, ends at the next such instant (23 h or 25 h across DST). A rollover time inside a DST gap
 * uses the first instant after the gap; inside an overlap, the earlier instant.
 */
internal fun studyDayOf(nowMs: Long, zone: ZoneId, hour: Int): LocalStudyDay {
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val date = if (rolloverMs(zone, hour, today) <= nowMs) today else today.minusDays(1)
    return LocalStudyDay(date, StudyDay(rolloverMs(zone, hour, date), rolloverMs(zone, hour, date.plusDays(1))))
}

/** The instant the study day named [date] starts. */
internal fun rolloverMs(zone: ZoneId, hour: Int, date: LocalDate): Long {
    val local = date.atTime(hour, 0)
    val t = zone.rules.getTransition(local)
    val at = if (t != null && t.isGap) t.dateTimeAfter.atZone(zone) else local.atZone(zone)
    return at.toInstant().toEpochMilli()
}
