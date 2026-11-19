package kotobaya.decks

import kotobaya.cards.ReadingSetting
import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.WordStatus
import kotobaya.contracts.decks.DeckProgress
import kotobaya.contracts.decks.DeckProgressCount
import kotobaya.contracts.decks.DeckWord
import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.queue.WordCardState
import kotobaya.decks.DefaultDeckProgress
import org.jooq.DSLContext

/**
 * ADR-041: `Deck.progress` for a batch of decks. The host loads the deck words as `DeckProgress` documents them (live
 * items, identity as [visibleItems], the live note of an entry, its live cards, the entry's effective word status) and the
 * core's [DeckProgress] decides; no counting rule lives here. Six statements (items, smart decks, notes, cards, statuses, reading setting) however many decks and items there are.
 */
internal object DeckProgressLoader {
    /** Progress of each non-smart deck of [deckIds]; smart decks are absent from the result. */
    fun load(dsl: DSLContext, deckIds: Collection<String>, core: DeckProgress = DefaultDeckProgress): Map<String, DeckProgressCount> {
        if (deckIds.isEmpty()) return emptyMap()
        val ids = deckIds.toSet().toTypedArray()
        val deckKeys = LinkedHashMap<String, LinkedHashMap<String, Long?>>() // deck -> key -> entry seq (null for n:)
        val noteOfKey = HashMap<String, String>() // n:<id> -> note id
        dsl.fetch(
            "select x.deck_id::text, x.identity, x.eseq, x.sid::text from (select di.deck_id, $IDENTITY as identity, " +
                "case when ($IDENTITY) like 'e:%' then substr($IDENTITY, 3)::bigint end as eseq, sn.id as sid, " +
                "row_number() over (partition by di.deck_id, $IDENTITY order by di.position, di.item_key) as rn " +
                "from app.deck_item di join app.deck d on d.id = di.deck_id and d.type <> 'smart' " +
                "left join app.note n on n.id = di.note_id left join app.note sn on sn.id = coalesce(n.merged_into_id, n.id) " +
                "where di.deck_id = any(array(select unnest(?::uuid[]))) and di.deleted_at is null and (sn.id is null or sn.deleted_at is null)) x " +
                "where x.rn = 1",
            ids,
        ).forEach { r ->
            val deck = r.get(0, String::class.java)
            val key = r.get(1, String::class.java) ?: return@forEach
            deckKeys.getOrPut(deck) { LinkedHashMap() }[key] = (r.get(2) as Number?)?.toLong()
            if (key.startsWith("n:")) noteOfKey[key] = r.get(3, String::class.java)
        }
        for (d in ids) deckKeys.getOrPut(d) { LinkedHashMap() }
        val smart = dsl.fetch("select id::text from app.deck where id = any(array(select unnest(?::uuid[]))) and type = 'smart'", ids)
            .map { it.get(0, String::class.java) }.toSet()

        val seqs = deckKeys.values.flatMap { it.values }.filterNotNull().toSet()
        val noteIds = noteOfKey.values.toSet()
        // The live note of each word: by entry_seq for e: keys, by id for n: keys.
        class Note(val id: String, val type: NoteBaseType?)
        val noteBySeq = HashMap<Long, Note>()
        val noteById = HashMap<String, Note>()
        if (seqs.isNotEmpty() || noteIds.isNotEmpty()) {
            dsl.fetch(
                "select id::text, entry_seq, type from app.note where deleted_at is null and " +
                    "(entry_seq = any(array(select unnest(?::bigint[]))) or id = any(array(select unnest(?::uuid[]))))",
                seqs.toTypedArray(), noteIds.toTypedArray(),
            ).forEach { r ->
                val note = Note(r.get(0, String::class.java), NoteBaseType.entries.firstOrNull { it.wire == r.get(2, String::class.java) })
                noteById[note.id] = note
                (r.get(1) as Number?)?.toLong()?.let { noteBySeq[it] = note }
            }
        }
        val cardsOf = HashMap<String, MutableList<WordCardState>>()
        if (noteById.isNotEmpty()) {
            dsl.fetch(
                "select note_id::text, state, interval_days, kind from app.card where note_id = any(array(select unnest(?::uuid[]))) " +
                    "and deleted_at is null and merged_into_id is null",
                noteById.keys.toTypedArray(),
            ).forEach { r ->
                val state = CardState.entries.first { it.wire == r.get(1, String::class.java) }
                cardsOf.getOrPut(r.get(0, String::class.java)) { ArrayList() } += WordCardState(state, (r.get(2) as Number?)?.toInt(), r.get(3, String::class.java))
            }
        }
        val statusOf = HashMap<Long, WordStatus>()
        if (seqs.isNotEmpty()) {
            dsl.fetch("select entry_seq, status from app.word_status where entry_seq = any(array(select unnest(?::bigint[])))", seqs.toTypedArray())
                .forEach { r -> WordStatus.entries.firstOrNull { it.wire == r.get(1, String::class.java) }?.let { statusOf[(r.get(0) as Number).toLong()] = it } }
        }

        val readingEnabled = ReadingSetting.load(dsl)
        val out = LinkedHashMap<String, DeckProgressCount>()
        for ((deck, keys) in deckKeys) {
            if (deck in smart) continue
            val words = keys.map { (key, seq) ->
                val note = if (seq != null) noteBySeq[seq] else noteById[noteOfKey[key]]
                DeckWord(key, seq?.let(statusOf::get), note?.type, note?.let { cardsOf[it.id] }.orEmpty())
            }
            out[deck] = core.count(words, readingEnabled)
        }
        return out
    }
}
