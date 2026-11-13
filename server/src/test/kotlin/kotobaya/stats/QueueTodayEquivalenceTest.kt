package kotobaya.stats

import kotobaya.auth.AuthFixture
import kotobaya.review.QueueLoader
import org.jooq.DSLContext
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BUG-1: `QueueLoader.loadToday` (also behind `/stats/summary` and `GET /review/queue`) gives the same rows as its 0.1.4
 * query: hand-computed values on a fixture with a merged card, resets, ties and undone rows, and an oracle comparison
 * against [legacyTodaySql] over many windows of generated data.
 */
class QueueTodayEquivalenceTest : AuthFixture() {
    /** `QueueLoader.loadToday`'s SQL exactly as it was before BUG-1 (per-row LATERAL; quadratic). Parameters: `start, now`. */
    private val legacyTodaySql =
        "select coalesce(c.merged_into_id, c.id)::text, sc.note_id::text, r.review_type, " +
            "(r.review_type = 'learning' and (prev.id is null or prev.manual_action = 'reset')) " +
            "from app.review_log r join app.card c on c.id = r.card_id " +
            "join app.card sc on sc.id = coalesce(c.merged_into_id, c.id) " +
            "left join lateral (select p.id, p.manual_action from app.review_log p join app.card pc on pc.id = p.card_id " +
            "  where coalesce(pc.merged_into_id, pc.id) = coalesce(c.merged_into_id, c.id) and p.undone_at is null " +
            "  and p.review_type in ('scheduled', 'learning', 'manual') and (p.reviewed_at, p.hlc, p.id) < (r.reviewed_at, r.hlc, r.id) " +
            "  order by p.reviewed_at desc, p.hlc desc, p.id desc limit 1) prev on true " +
            "where r.undone_at is null and r.review_type in ('scheduled', 'learning', 'drill', 'cram') " +
            "and r.reviewed_at >= ?::timestamptz and r.reviewed_at <= ?::timestamptz"

    /**
     * Rows as `root note type introduced`. `introduced` is read the way `loadToday` reads it (`Boolean::class.java`): SQL NULL
     * (a learning row after a non-reset row: `false or null`, in both queries) maps to false. The raw SQL value follows in
     * brackets so the legacy comparison also pins the three-valued result.
     */
    private fun rows(dsl: DSLContext, sql: String, vararg params: Any): List<String> =
        dsl.fetch(sql, *params).map { r -> "${r.get(0)} ${r.get(1)} ${r.get(2)} ${r.get(3, Boolean::class.java)} [${r.get(3)}]" }.sorted()

    private fun newRows(dsl: DSLContext, lo: Long, hi: Long) =
        rows(dsl, QueueLoader.TODAY_SQL, Timestamp(lo), Timestamp(hi), Timestamp(lo), Timestamp(hi))

    private fun legacyRows(dsl: DSLContext, lo: Long, hi: Long) = rows(dsl, legacyTodaySql, Timestamp(lo), Timestamp(hi))

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
            it, note, kind, if (mergedInto == null) null else Timestamp(0), mergedInto,
        )
    }

    private fun review(dsl: DSLContext, device: UUID, card: UUID, at: String, type: String, counter: Int = 0, undone: Boolean = false) {
        val ms = Instant.parse(at).toEpochMilli()
        dsl.execute(
            "insert into app.review_log (id, card_id, device_id, hlc, reviewed_at, utc_offset_min, review_type, rating, duration_ms, " +
                "manual_action, undone_at, undone_hlc) values (?, ?, ?, ?, ?::timestamptz, null, ?, ?, 1000, ?, ?::timestamptz, ?)",
            UUID.randomUUID(), card, device, "%012x-%04x-%s".format(ms, counter, device), Timestamp(ms), type,
            if (type == "manual") null else 3, if (type == "manual") "reset" else null,
            if (undone) Timestamp(ms + 1000) else null, if (undone) "%012x-0000-%s".format(ms + 1000, device) else null,
        )
    }

    @Test
    fun todayRowsMatchHandComputedValues() {
        val dev = insertDevice()
        db.txBlocking { dsl ->
            val n1 = note(dsl, dev, 1)
            val n2 = note(dsl, dev, 2)
            val a = card(dsl, n1, "meaning")
            val b = card(dsl, n1, "meaning", mergedInto = a)
            val c = card(dsl, n2, "reading")
            // Inserted out of replay order on purpose.
            review(dsl, dev, a, "2024-03-02T10:00:00Z", "scheduled")
            review(dsl, dev, b, "2024-03-01T10:00:00Z", "learning") // first row of root A (on the loser): introduced
            review(dsl, dev, a, "2024-03-02T14:00:00Z", "learning") // prev = 12:00 scheduled (drill does not feed): not introduced
            review(dsl, dev, a, "2024-03-02T12:00:00Z", "scheduled")
            review(dsl, dev, a, "2024-03-02T13:00:00Z", "drill")
            review(dsl, dev, a, "2024-03-04T09:00:00Z", "manual") // reset
            review(dsl, dev, a, "2024-03-04T10:00:00Z", "learning") // prev = reset: introduced
            review(dsl, dev, a, "2024-03-04T11:00:00Z", "learning", undone = true) // undone: ignored
            review(dsl, dev, c, "2024-03-03T12:00:00Z", "scheduled", counter = 2) // same instant, later hlc
            review(dsl, dev, c, "2024-03-03T12:00:00Z", "learning", counter = 1) // earlier hlc: introduced
            review(dsl, dev, c, "2024-03-03T09:00:00Z", "learning", undone = true) // undone: ignored, not a previous row

            val name = mapOf(a.toString() to "A", c.toString() to "C", n1.toString() to "n1", n2.toString() to "n2")
            fun named(rows: List<String>) = rows.map { it.substringBefore(" [").split(' ').joinToString(" ") { w -> name[w] ?: w } }.sorted()
            val lo = Instant.parse("2024-03-01T00:00:00Z").toEpochMilli()
            val hi = Instant.parse("2024-03-04T23:59:59Z").toEpochMilli()
            assertEquals(
                listOf(
                    "A n1 drill false",
                    "A n1 learning false",
                    "A n1 learning true",
                    "A n1 learning true",
                    "A n1 scheduled false",
                    "A n1 scheduled false",
                    "C n2 learning true",
                    "C n2 scheduled false",
                ),
                named(newRows(dsl, lo, hi)),
            )
            // Only the last day: the reset that makes A's learning row new is still seen, though out of the window.
            val day4 = Instant.parse("2024-03-04T10:00:00Z").toEpochMilli()
            assertEquals(listOf("A n1 learning true"), named(newRows(dsl, day4, hi)))
            // Both bounds are inclusive.
            assertEquals(listOf("A n1 learning true"), named(newRows(dsl, day4, day4)))
            assertEquals(emptyList(), newRows(dsl, hi + 1, hi + 86_400_000))
            for ((l, h) in listOf(lo to hi, day4 to hi, lo to day4, hi + 1 to hi + 2)) {
                assertEquals(legacyRows(dsl, l, h), newRows(dsl, l, h), "window $l..$h")
            }
        }
    }

    @Test
    fun todayRowsAgreeWithTheLegacyQueryOnGeneratedData() {
        val dev = insertDevice()
        val end = Instant.parse("2026-11-12T09:30:00Z").toEpochMilli()
        val day = 86_400_000L
        db.txBlocking { StatsSeed.seed(it, dev.toString(), notes = 40, perCard = 30, spanDays = 120, endMs = end, loserEvery = 4) }
        db.txBlocking { dsl ->
            // Learning rows after a reset and after other rows, so that `introducedNew` varies within one day.
            dsl.execute(
                "insert into app.review_log (id, card_id, device_id, hlc, reviewed_at, utc_offset_min, review_type, rating, duration_ms) " +
                    "select gen_random_uuid(), r.card_id, r.device_id, " +
                    "lpad(to_hex((extract(epoch from r.reviewed_at) * 1000)::bigint + 1000), 12, '0') || '-0001-' || r.device_id::text, r.reviewed_at + interval '1 second', null, 'learning', 3, 1000 " +
                    "from app.review_log r where r.review_type in ('manual', 'drill') and r.undone_at is null",
            )
            var learningTrue = 0
            var learningFalse = 0
            val windows = (0 until 125).map { end - (it + 1) * day to end - it * day } + listOf(end - 130 * day to end, end - day to end - day)
            for ((lo, hi) in windows) {
                val got = newRows(dsl, lo, hi)
                assertEquals(legacyRows(dsl, lo, hi), got, "window $lo..$hi")
                learningTrue += got.count { it.substringBefore(" [").endsWith("learning true") }
                learningFalse += got.count { it.substringBefore(" [").endsWith("learning false") }
            }
            assertTrue(learningTrue > 0 && learningFalse > 0, "fixture exercises introducedNew both ways: $learningTrue / $learningFalse")
        }
    }
}
