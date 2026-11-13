package kotobaya.stats

import org.jooq.DSLContext
import java.sql.Timestamp

/**
 * Bulk synthetic learning data for the stats tests, inserted directly (a fixture, not an op path).
 * [notes] vocab notes (entry_seq 1..notes), each with a live `meaning` and `reading` card; every [loserEvery]th note also has
 * a deleted `meaning` card merged into the live one, whose reviews come before the survivor's. Each card gets [perCard]
 * reviews spread over [spanDays] before [endMs]: the first is `learning`, then every 17th a manual reset, every 23rd a drill,
 * every 13th undone, the rest scheduled; offsets alternate between NULL and +540. Most live cards get a review-state
 * schedule cache (for summary and forecast), and every 7th entry a word-status override.
 */
internal object StatsSeed {
    const val VOCAB = "00000000-0000-7000-8000-000000000101"
    private const val DAY_MS = 86_400_000L

    fun seed(dsl: DSLContext, device: String, notes: Int, perCard: Int, spanDays: Int, endMs: Long, loserEvery: Int = 10) {
        dsl.execute(
            "insert into app.note (id, note_type_id, type, entry_seq, created_by_device_id, created_at, updated_at) " +
                "select gen_random_uuid(), ?::uuid, 'vocab', g, ?::uuid, 'epoch', 'epoch' from generate_series(1, ?) g",
            VOCAB, device, notes,
        )
        dsl.execute(
            "insert into app.card (id, note_id, kind, created_at, updated_at) " +
                "select gen_random_uuid(), n.id, k, 'epoch', 'epoch' from app.note n cross join unnest(array['meaning', 'reading']) k " +
                "where n.entry_seq is not null",
        )
        dsl.execute(
            "insert into app.card (id, note_id, kind, created_at, updated_at, deleted_at, merged_into_id) " +
                "select gen_random_uuid(), c.note_id, 'meaning', 'epoch', 'epoch', 'epoch', c.id from app.card c join app.note n on n.id = c.note_id " +
                "where c.kind = 'meaning' and c.merged_into_id is null and c.deleted_at is null and n.entry_seq % ? = 0",
            loserEvery,
        )
        val spanMs = spanDays * DAY_MS
        val base = endMs - spanMs - DAY_MS
        dsl.execute(
            "with cs as (select c.id, row_number() over (order by c.id) as i, c.merged_into_id is not null as loser from app.card c), " +
                "rows as (select cs.id as card_id, cs.i, k, " +
                "  (?::bigint + (cs.i * 7919 % 86400000) + case when cs.loser then 0 else ?::bigint / 4 end " +
                "   + k * (?::bigint * 3 / 4 / ?::int))::bigint as ms " +
                "  from cs cross join generate_series(1, ?) k) " +
                "insert into app.review_log (id, card_id, device_id, hlc, reviewed_at, utc_offset_min, review_type, rating, duration_ms, " +
                "  manual_action, undone_at, undone_hlc) " +
                "select gen_random_uuid(), card_id, ?::uuid, lpad(to_hex(ms), 12, '0') || '-' || lpad(to_hex(k), 4, '0') || '-' || ?, " +
                "  to_timestamp(ms / 1000.0), case when i % 2 = 0 then null else 540 end, " +
                "  case when k = 1 then 'learning' when k % 17 = 0 then 'manual' when k % 23 = 0 then 'drill' else 'scheduled' end, " +
                "  case when k <> 1 and k % 17 = 0 then null else (array[1, 2, 3, 3, 3, 3, 3, 4])[1 + abs(hashtext(card_id::text || k::text)) % 8] end, " +
                "  1000 + k * 10, case when k <> 1 and k % 17 = 0 then 'reset' end, " +
                "  case when k % 13 = 0 then to_timestamp(ms / 1000.0 + 1) end, " +
                "  case when k % 13 = 0 then lpad(to_hex(ms + 1000), 12, '0') || '-0000-' || ? end " +
                "from rows",
            base, spanMs, spanMs, perCard, perCard, device, device, device,
        )
        dsl.execute(
            "update app.card set state = 'review', stability = 5, difficulty = 5, interval_days = 7, " +
                "due_at = ?::timestamptz + (abs(hashtext(id::text)) % 1440 - 360) * interval '1 hour' " +
                "where deleted_at is null and abs(hashtext(id::text)) % 5 <> 0",
            Timestamp(endMs),
        )
        dsl.execute(
            "insert into app.word_status (entry_seq, status, override_status, override_hlc, override_origin, updated_at) " +
                "select g, case when g % 2 = 0 then 'known' else 'ignored' end, case when g % 2 = 0 then 'known' else 'ignored' end, " +
                "lpad(to_hex(?::bigint), 12, '0') || '-0000-' || ?, 'manual', ?::timestamptz - interval '30 days' " +
                "from generate_series(1, ?, 7) g",
            endMs, device, Timestamp(endMs), notes,
        )
        dsl.execute("analyze app.review_log")
        dsl.execute("analyze app.card")
        dsl.execute("analyze app.note")
    }
}
