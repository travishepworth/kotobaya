package kotobaya.decks

import com.fasterxml.jackson.databind.node.ObjectNode
import kotobaya.server.json
import org.jooq.DSLContext

private fun ts(col: String) = "to_char($col at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"')"

private const val IDENTITY = "case when di.item_key like 'e:%' then di.item_key when sn.entry_seq is not null " +
    "then 'e:' || sn.entry_seq::text else 'n:' || sn.id::text end"

/**
 * The live items of a deck as readers see them (SYNC.md 8.5): `n:<loser>` follows `merged_into_id`; a note item
 * whose survivor has an `entry_seq` has the identity `e:<seq>`, else `n:<survivor id>`; items of one identity are
 * one item (smallest `(position, item_key)`); an item whose resolved note is a tombstone is hidden.
 * [deckExpr] is a SQL expression for the deck id.
 */
internal fun visibleItems(deckExpr: String): String =
    "select x.* from (select di.item_key, di.entry_seq as di_entry, di.position, di.occurrence_count, di.added_at, " +
        "sn.id as sid, sn.entry_seq as sseq, $IDENTITY as identity, " +
        "case when ($IDENTITY) like 'e:%' then substr($IDENTITY, 3)::bigint end as eseq, " +
        "row_number() over (partition by $IDENTITY order by di.position, di.item_key) as rn " +
        "from app.deck_item di left join app.note n on n.id = di.note_id " +
        "left join app.note sn on sn.id = coalesce(n.merged_into_id, n.id) " +
        "where di.deck_id = $deckExpr and di.deleted_at is null and (sn.id is null or sn.deleted_at is null)) x where x.rn = 1"

private val DECK_SELECT = "select jsonb_build_object(" +
    "'id', d.id, 'name', d.name, 'type', d.type, 'language', d.language, 'priority', d.priority, 'enabled', d.enabled, " +
    "'new_cards_per_day', d.new_cards_per_day, 'reviews_per_day', d.reviews_per_day, 'settings', d.settings, " +
    "'media_id', d.media_id, 'list_key', d.list_key, 'query', d.query, 'content_version', d.content_version, " +
    "'content_blob_hash', d.content_blob_hash, " +
    "'item_count', case when d.type = 'smart' then 0 else (select count(*) from (${visibleItems("d.id")}) v) end, " +
    "'created_at', ${ts("d.created_at")}, 'updated_at', ${ts("d.updated_at")}, 'deleted_at', ${ts("d.deleted_at")})::text from app.deck d"

private const val ENTRY_SUMMARY = "jsonb_build_object('seq', e.seq, 'headword', e.headword, 'headword_reading', e.headword_reading, " +
    "'is_common', e.is_common, " +
    "'glosses', coalesce((select jsonb_agg(g ->> 'text') from jsonb_array_elements(e.senses -> 0 -> 'gloss') g), '[]'::jsonb), " +
    "'pos', to_jsonb(e.pos), 'freq_rank', e.freq_rank, 'retired', e.retired_release_id is not null, " +
    "'status', coalesce(ws.status, 'new'), " +
    "'note_id', (select vn.id from app.note vn where vn.entry_seq = e.seq and vn.deleted_at is null limit 1))"

internal enum class ItemSort(val wire: String, val order: String) {
    POSITION("position", "v.position, v.identity"),
    OCCURRENCE("occurrence_count", "v.occurrence_count desc, v.identity"),
    FREQ("freq_rank", "e.freq_rank asc nulls last, v.identity"),
}

/** The survivor of a note: itself, or the note its `merged_into_id` names (one hop). */
internal class Survivor(val id: String, val entrySeq: Long?, val deleted: Boolean)

/** Read side of the decks API: rows as the API's JSON. Reads only; every write is an op. */
internal class DeckViews(private val dsl: DSLContext) {
    private fun parse(text: String): ObjectNode = json.readTree(text) as ObjectNode

    fun deck(id: String): ObjectNode? =
        dsl.fetchOptional("$DECK_SELECT where d.id = ?::uuid", id).map { parse(it.get(0, String::class.java)) }.orElse(null)

    fun liveListDeck(listKey: String): ObjectNode? =
        dsl.fetchOptional("$DECK_SELECT where d.list_key = ? and d.deleted_at is null", listKey)
            .map { parse(it.get(0, String::class.java)) }.orElse(null)

    fun decks(type: String?, includeDeleted: Boolean): List<ObjectNode> {
        val where = mutableListOf<String>()
        val args = mutableListOf<Any>()
        if (!includeDeleted) where += "d.deleted_at is null"
        if (type != null) {
            where += "d.type = ?"
            args += type
        }
        val w = if (where.isEmpty()) "" else " where " + where.joinToString(" and ")
        return dsl.fetch("$DECK_SELECT$w order by d.priority, d.id", *args.toTypedArray()).map { parse(it.get(0, String::class.java)) }
    }

    private fun itemSelect(tail: String) =
        "select jsonb_build_object('deck_id', ?::uuid, 'item_key', v.identity, " +
            "'entry_seq', v.eseq, " +
            "'note_id', case when v.identity like 'n:%' then v.sid end, " +
            "'position', v.position, 'occurrence_count', v.occurrence_count, 'added_at', ${ts("v.added_at")}, " +
            "'entry', case when e.seq is null then null else $ENTRY_SUMMARY end, " +
            "'status', case when v.eseq is not null then coalesce(ws.status, 'new') end)::text " +
            "from (${visibleItems("?::uuid")}) v " +
            "left join ref.dict_entry e on e.seq = v.eseq " +
            "left join app.word_status ws on ws.entry_seq = v.eseq $tail"

    /** The visible item with canonical key [identity], or null. */
    fun item(deckId: String, identity: String): ObjectNode? =
        dsl.fetch(itemSelect("where v.identity = ?"), deckId, deckId, identity)
            .firstOrNull()?.let { parse(it.get(0, String::class.java)) }

    /** [offset] and up to [limit] + 1 items in [sort] order (the extra one tells whether more exist); [statuses] keep entry items only. */
    fun items(deckId: String, statuses: Set<String>?, sort: ItemSort, offset: Int, limit: Int): List<ObjectNode> {
        val args = mutableListOf<Any>(deckId, deckId)
        var where = ""
        if (statuses != null) {
            where = "where v.eseq is not null and coalesce(ws.status, 'new') = any(?::text[])"
            args.add(statuses.toTypedArray())
        }
        return dsl.fetch(itemSelect("$where order by ${sort.order} offset $offset limit ${limit + 1}"), *args.toTypedArray())
            .map { parse(it.get(0, String::class.java)) }
    }

    /** Every live `deck_item` key of the deck whose effective identity is [identity] (SYNC.md 8.5 removal). */
    fun liveKeysOf(deckId: String, identity: String): List<String> =
        dsl.fetch(
            "select di.item_key from app.deck_item di left join app.note n on n.id = di.note_id " +
                "left join app.note sn on sn.id = coalesce(n.merged_into_id, n.id) " +
                "where di.deck_id = ?::uuid and di.deleted_at is null and ($IDENTITY) = ? order by di.item_key",
            deckId, identity,
        ).map { it.get(0, String::class.java) }

    /** The survivor of [noteId] following one `merged_into_id` hop, or null when the note does not exist. */
    fun survivor(noteId: String): Survivor? =
        dsl.fetch(
            "select sn.id::text, sn.entry_seq, sn.deleted_at is not null from app.note n " +
                "join app.note sn on sn.id = coalesce(n.merged_into_id, n.id) where n.id = ?::uuid",
            noteId,
        ).firstOrNull()?.let { Survivor(it.get(0, String::class.java), it.get(1, Long::class.javaObjectType), it.get(2, Boolean::class.java)) }

    fun entryExists(seq: Long): Boolean = dsl.fetchExists(dsl.selectOne().from("ref.dict_entry").where("seq = ?", seq))

    fun maxPosition(deckId: String): Long =
        (dsl.fetchValue("select coalesce(max(position), 0) from app.deck_item where deck_id = ?::uuid and deleted_at is null", deckId) as Number).toLong()

    fun livePosition(deckId: String, key: String): Long? =
        (dsl.fetchValue("select position from app.deck_item where deck_id = ?::uuid and item_key = ? and deleted_at is null", deckId, key) as Number?)?.toLong()
}
