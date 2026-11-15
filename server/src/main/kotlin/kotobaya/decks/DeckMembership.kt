package kotobaya.decks

import org.jooq.DSLContext

/** A deck holding a note, as `QueueItem.decks` lists it. */
internal class NoteDeck(val id: String, val name: String, val priority: Int)

/**
 * C-25: which live decks hold an entry or a note (`deck_ids` of `DictEntry`, `DictEntrySummary` and `Note`;
 * `QueueItem.decks`). Reads only. Order is always draw order: `(deck.priority, deck.id)` ascending.
 *
 * - An entry is held by a live deck with a live item `e:<seq>` (the rule of `KanjiWord.decks`).
 * - A note is held through a live item `n:<id>` naming it or a merge loser of it, or through `e:<entry_seq>`:
 *   the review queue's membership (`QueueLoader.memberships`) without smart decks. A tombstoned note and a
 *   merge loser are in no deck.
 */
internal object DeckMembership {
    /** SQL expression of type `text[]`: the `deck_ids` of the entry whose seq is the SQL expression [seqExpr]. */
    fun entryDeckIdsSql(seqExpr: String): String =
        "array(select cd.id::text from app.deck cd where cd.deleted_at is null and exists (" +
            "select 1 from app.deck_item cx where cx.deck_id = cd.id and cx.entry_seq = $seqExpr and cx.deleted_at is null) " +
            "order by cd.priority, cd.id)"

    /** SQL expression of type `jsonb` (array of ids): the `deck_ids` of the `app.note` row aliased [note]. */
    fun noteDeckIdsSql(note: String): String =
        "(select coalesce(jsonb_agg(cd.id order by cd.priority, cd.id), '[]'::jsonb) from app.deck cd " +
            "where cd.deleted_at is null and cd.type <> 'smart' and $note.deleted_at is null and $note.merged_into_id is null and cd.id in (" +
            "select cx.deck_id from app.deck_item cx where cx.deleted_at is null and cx.entry_seq = $note.entry_seq " +
            "union all select cx.deck_id from app.deck_item cx where cx.deleted_at is null and cx.note_id = $note.id " +
            "union all select cx.deck_id from app.deck_item cx join app.note cl on cl.id = cx.note_id " +
            "where cx.deleted_at is null and cl.merged_into_id = $note.id))"

    /** `deck_ids` per entry, for the entries of [seqs] that are in a deck; one query. */
    fun ofEntries(dsl: DSLContext, seqs: Collection<Long>): Map<Long, List<String>> {
        if (seqs.isEmpty()) return emptyMap()
        val out = LinkedHashMap<Long, MutableList<String>>()
        dsl.fetch(
            "select distinct x.entry_seq, d.id::text, d.priority, d.id from app.deck_item x " +
                "join app.deck d on d.id = x.deck_id and d.deleted_at is null " +
                "where x.deleted_at is null and x.entry_seq = any(?::bigint[]) order by d.priority, d.id",
            seqs.toSet().toTypedArray(),
        ).forEach { r -> out.getOrPut(r.get(0, Long::class.java)) { ArrayList() } += r.get(1, String::class.java) }
        return out
    }

    /** The decks of each note of [noteIds] that is in one, in draw order; one query. Same rule as [noteDeckIdsSql]. */
    fun ofNotes(dsl: DSLContext, noteIds: Collection<String>): Map<String, List<NoteDeck>> {
        if (noteIds.isEmpty()) return emptyMap()
        val ids = noteIds.toSet().toTypedArray()
        val out = LinkedHashMap<String, MutableList<NoteDeck>>()
        dsl.fetch(
            "select m.note_id, d.id::text, d.name, d.priority from (" +
                "select n.id::text as note_id, x.deck_id from app.deck_item x " +
                "join app.note n on n.entry_seq = x.entry_seq and n.deleted_at is null and n.merged_into_id is null " +
                "where x.deleted_at is null and x.entry_seq is not null and n.id = any(?::uuid[]) " +
                "union " +
                "select n.id::text, x.deck_id from app.deck_item x join app.note src on src.id = x.note_id " +
                "join app.note n on n.id = coalesce(src.merged_into_id, src.id) " +
                "where x.deleted_at is null and x.note_id is not null and n.deleted_at is null and n.merged_into_id is null " +
                "and n.id = any(?::uuid[])" +
                ") m join app.deck d on d.id = m.deck_id and d.deleted_at is null and d.type <> 'smart' " +
                "order by d.priority, d.id",
            ids, ids,
        ).forEach { r ->
            out.getOrPut(r.get(0, String::class.java)) { ArrayList() } +=
                NoteDeck(r.get(1, String::class.java), r.get(2, String::class.java), (r.get(3) as Number).toInt())
        }
        return out
    }
}
