package kotobaya.stats

import kotobaya.auth.AuthFixture
import org.jooq.DSLContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BUG-1: the rewritten stats queries give the same numbers as the 0.1.4 ones. A hand-computed fixture (merged card, reset,
 * drill, undone row, same-instant tie broken by hlc, previous review outside the window) plus an oracle comparison against
 * [LegacyStatsQueries] on generated data.
 */
class StatsEquivalenceTest : AuthFixture() {
    private val utc: ZoneId = ZoneOffset.UTC

    private fun note(dsl: DSLContext, device: UUID, seq: Long): UUID = UUID.randomUUID().also {
        dsl.execute(
            "insert into app.note (id, note_type_id, type, entry_seq, created_by_device_id, created_at, updated_at) " +
                "values (?, ?::uuid, 'vocab', ?, ?, 'epoch', 'epoch')",
            it, StatsSeed.VOCAB, seq, device,
        )
    }

    private fun card(dsl: DSLContext, note: UUID, kind: String, mergedInto: UUID? = null): UUID = UUID.randomUUID().also {
        dsl.execute(
            "insert into app.card (id, note_id, kind, created_at, updated_at, deleted_at, merged_into_id) " +
                "values (?, ?, ?, 'epoch', 'epoch', ?::timestamptz, ?)",
            it, note, kind, if (mergedInto == null) null else java.sql.Timestamp(0), mergedInto,
        )
    }

    private fun review(
        dsl: DSLContext, device: UUID, card: UUID, at: String, type: String, rating: Int?,
        counter: Int = 0, undone: Boolean = false, duration: Int = 1000,
    ) {
        val ms = Instant.parse(at).toEpochMilli()
        val hlc = "%012x-%04x-%s".format(ms, counter, device)
        dsl.execute(
            "insert into app.review_log (id, card_id, device_id, hlc, reviewed_at, utc_offset_min, review_type, rating, duration_ms, " +
                "manual_action, undone_at, undone_hlc) values (?, ?, ?, ?, ?::timestamptz, null, ?, ?, ?, ?, ?::timestamptz, ?)",
            UUID.randomUUID(), card, device, hlc, java.sql.Timestamp(ms), type, rating, duration,
            if (type == "manual") "reset" else null,
            if (undone) java.sql.Timestamp(ms + 1000) else null,
            if (undone) "%012x-0000-%s".format(ms + 1000, device) else null,
        )
    }

    private fun handFixture() {
        val dev = insertDevice()
        db.txBlocking { dsl ->
            val n1 = note(dsl, dev, 1)
            val n2 = note(dsl, dev, 2)
            val a = card(dsl, n1, "meaning")
            val b = card(dsl, n1, "meaning", mergedInto = a)
            val c = card(dsl, n2, "reading")
            // Inserted out of replay order on purpose.
            review(dsl, dev, a, "2024-03-02T10:00:00Z", "scheduled", 3, duration = 2000) // prev = B's learning row: not introduced
            review(dsl, dev, b, "2024-03-01T10:00:00Z", "learning", 3, duration = 500) // first row of root A: introduced
            review(dsl, dev, a, "2024-03-02T12:00:00Z", "scheduled", 1, duration = 3000) // prev = reset: introduced, again
            review(dsl, dev, a, "2024-03-02T11:00:00Z", "manual", null) // reset: not counted
            review(dsl, dev, a, "2024-03-02T13:00:00Z", "drill", 3, duration = 700) // drill: counted, never introduced
            review(dsl, dev, c, "2024-03-03T09:00:00Z", "scheduled", 4, undone = true) // undone: ignored everywhere
            review(dsl, dev, c, "2024-03-03T12:00:00Z", "scheduled", 3, counter = 2) // same instant, later hlc: not introduced
            review(dsl, dev, c, "2024-03-03T12:00:00Z", "learning", 2, counter = 1) // earlier hlc: introduced
        }
    }

    private class Day(val reviews: Int, val newCards: Int, val again: Int, val timeMs: Long) {
        override fun equals(other: Any?) = other is Day && other.toString() == toString()
        override fun hashCode() = toString().hashCode()
        override fun toString() = "Day($reviews, $newCards, $again, $timeMs)"
    }

    private fun heat(rows: List<DayRow>) = rows.groupBy { it.day.toString() }.toSortedMap()
        .mapValues { (_, v) -> Day(v.size, v.count { it.introduced }, v.count { it.rating == 1 }, v.sumOf { it.durationMs }) }

    private fun dayKey(rows: List<DayRow>) = rows.map { "${it.day} ${it.type} ${it.rating} ${it.durationMs} ${it.introduced}" }.sorted()
    private fun schedKey(rows: List<ScheduledReview>) =
        rows.map { "${it.day} ${it.passed} ${it.intervalBefore} ${it.groupKey} ${it.predicted}" }.sorted()

    @Test
    fun heatmapRowsMatchHandComputedValues() {
        handFixture()
        db.txBlocking { dsl ->
            val all = dayRows(dsl, LocalDate.parse("2024-03-01"), LocalDate.parse("2024-03-03"), utc, 0)
            assertEquals(
                mapOf(
                    "2024-03-01" to Day(1, 1, 0, 500),
                    "2024-03-02" to Day(3, 1, 1, 5700),
                    "2024-03-03" to Day(2, 1, 0, 2000),
                ),
                heat(all),
            )
            // The previous review of A's first in-window row is outside the window and on the merge loser.
            val late = dayRows(dsl, LocalDate.parse("2024-03-02"), LocalDate.parse("2024-03-02"), utc, 0)
            assertEquals(mapOf("2024-03-02" to Day(3, 1, 1, 5700)), heat(late))
            assertEquals(emptyList(), dayRows(dsl, LocalDate.parse("2025-01-01"), LocalDate.parse("2025-01-31"), utc, 0))
            assertEquals(dayKey(LegacyStatsQueries.dayRows(dsl, LocalDate.parse("2024-03-01"), LocalDate.parse("2024-03-03"), utc, 0)), dayKey(all))
        }
    }

    @Test
    fun retentionRowsMatchHandComputedValues() {
        handFixture()
        db.txBlocking { dsl ->
            val from = LocalDate.parse("2024-03-01")
            val to = LocalDate.parse("2024-03-03")
            val rows = scheduledReviews(dsl, from, to, utc, 0, null)
            assertEquals(3, rows.size)
            assertEquals(2, rows.count { it.passed })
            assertEquals(mapOf("vocab/meaning" to 2, "vocab/reading" to 1), rows.groupingBy { it.groupKey }.eachCount().toSortedMap())
            assertEquals(listOf(LocalDate.parse("2024-03-02"), LocalDate.parse("2024-03-02"), LocalDate.parse("2024-03-03")), rows.map { it.day }.sorted())
            assertEquals(schedKey(LegacyStatsQueries.scheduledReviews(dsl, from, to, utc, 0, null)), schedKey(rows))
            assertEquals(emptyList(), scheduledReviews(dsl, from, to, utc, 0, "00000000-0000-7000-8000-0000000000ff"))
        }
    }

    @Test
    fun newQueriesAgreeWithTheLegacyQueriesOnGeneratedData() {
        val dev = insertDevice()
        val end = Instant.parse("2026-11-12T09:30:00Z").toEpochMilli()
        db.txBlocking { StatsSeed.seed(it, dev.toString(), notes = 40, perCard = 30, spanDays = 120, endMs = end, loserEvery = 4) }
        val tokyo = ZoneId.of("Asia/Tokyo")
        db.txBlocking { dsl ->
            assertTrue((dsl.fetchValue("select count(*) from app.review_log") as Number).toInt() > 2000)
            val endDay = LocalDate.parse("2026-11-12")
            for ((from, to) in listOf(endDay.minusDays(60) to endDay, endDay.minusDays(200) to endDay, endDay.minusDays(30) to endDay.minusDays(29))) {
                for ((zone, hour) in listOf(utc to 0, tokyo to 4)) {
                    val newDays = dayRows(dsl, from, to, zone, hour)
                    assertEquals(dayKey(LegacyStatsQueries.dayRows(dsl, from, to, zone, hour)), dayKey(newDays), "dayRows $from..$to $zone")
                    assertTrue(newDays.any { it.introduced } || from == endDay.minusDays(30), "fixture exercises introduced")
                    val newSched = scheduledReviews(dsl, from, to, zone, hour, null)
                    assertEquals(schedKey(LegacyStatsQueries.scheduledReviews(dsl, from, to, zone, hour, null)), schedKey(newSched), "scheduled $from..$to $zone")
                }
            }
            val ends = (0..12).map { end - it * 10 * 86_400_000L } + listOf(end - 1, 0L)
            val known = knownSeries(dsl, ends)
            assertEquals(LegacyStatsQueries.knownSeries(dsl, ends), known)
            assertTrue(known.first() > 0, "fixture has known words")
            assertEquals(emptyList(), knownSeries(dsl, emptyList()))
        }
    }

    @Test
    fun lastAtOrBeforeFindsTheLastKeyNotAfterEnd() {
        val keys = longArrayOf(1, 3, 3, 5)
        assertEquals(null, lastAtOrBefore(keys.size, 0) { keys[it] })
        assertEquals(0, lastAtOrBefore(keys.size, 2) { keys[it] })
        assertEquals(2, lastAtOrBefore(keys.size, 3) { keys[it] })
        assertEquals(3, lastAtOrBefore(keys.size, 99) { keys[it] })
        assertEquals(null, lastAtOrBefore(0, 99) { keys[it] })
    }
}
