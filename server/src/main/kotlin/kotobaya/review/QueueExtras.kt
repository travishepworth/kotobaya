package kotobaya.review

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotobaya.contracts.WordStatus
import kotobaya.contracts.dict.KanjiExampleCandidate
import kotobaya.contracts.dict.KanjiExamplePicker
import kotobaya.contracts.dict.KanjiReadings
import kotobaya.decks.DeckMembership
import kotobaya.decks.NoteDeck
import kotobaya.dict.DefaultKanjiExamplePicker
import kotobaya.server.json
import org.jooq.DSLContext

/**
 * C-25 (ADR-029): the back-side extras of a page of queue items, `QueueItem.decks` / `deck_count` and
 * `QueueItem.kanji_examples`. [load] runs a fixed number of statements however many items the page has (one
 * per kind of data, none per item); the choice of example words is the core's [DefaultKanjiExamplePicker].
 */
internal class QueueExtras private constructor(
    private val decksByNote: Map<String, List<NoteDeck>>,
    private val entryDecks: Map<Long, List<String>>,
    private val candidates: Map<String, List<Candidate>>,
) {
    private class Candidate(val core: KanjiExampleCandidate, val gloss: String)

    /** `DictEntry.deck_ids` of an entry of the page. */
    fun entryDeckIds(seq: Long): List<String> = entryDecks[seq].orEmpty()

    /** Sets `decks` (first [MAX_DECKS] in draw order) and `deck_count` of the item of note [noteId]. */
    fun putDecks(item: ObjectNode, noteId: String) {
        val all = decksByNote[noteId].orEmpty()
        val arr = item.putArray("decks")
        for (d in all.take(MAX_DECKS)) arr.addObject().put("id", d.id).put("name", d.name).put("priority", d.priority)
        item.put("deck_count", all.size)
    }

    /** `kanji_examples` for a kanji note: [kanji] is the item's `Kanji` JSON (null or JSON null: no readings). */
    fun kanjiExamples(literal: String, kanji: JsonNode?): ArrayNode {
        fun strings(n: JsonNode?): List<String> = n?.mapNotNull { it.takeIf(JsonNode::isTextual)?.asText() }.orEmpty()
        val k = kanji?.takeUnless { it.isNull }
        val readings = KanjiReadings(literal, strings(k?.get("on_readings")), strings(k?.get("kun_readings")))
        val mine = candidates[literal].orEmpty()
        val gloss = mine.associate { it.core.seq to it.gloss }
        val out = json.createArrayNode()
        for (e in DefaultKanjiExamplePicker.pick(readings, mine.map { it.core }, KanjiExamplePicker.DEFAULT_LIMIT)) {
            out.addObject()
                .put("seq", e.candidate.seq)
                .put("headword", e.candidate.headword)
                .put("reading", e.candidate.reading)
                .put("gloss", gloss[e.candidate.seq].orEmpty())
                .put("reading_type", e.readingType.wire)
        }
        return out
    }

    companion object {
        /** `QueueItem.decks` lists at most this many decks; `deck_count` has the total. */
        const val MAX_DECKS = 3

        /**
         * Loads the extras of one page: the decks of [noteIds], the `deck_ids` of [entrySeqs] and the example-word
         * candidates of [literals]. At most three statements, each skipped when its input is empty.
         */
        fun load(dsl: DSLContext, noteIds: Collection<String>, entrySeqs: Collection<Long>, literals: Collection<String>): QueueExtras =
            QueueExtras(DeckMembership.ofNotes(dsl, noteIds), DeckMembership.ofEntries(dsl, entrySeqs), candidates(dsl, literals))

        /**
         * Per kanji, the picker's window: the first `CANDIDATE_LIMIT` eligible entries in pick order (the picker's
         * contract allows any superset of its window; it classifies and picks). Eligible and pick order are spelled
         * in SQL exactly as `KanjiExamplePicker` "pick" 1-3 does.
         */
        private fun candidates(dsl: DSLContext, literals: Collection<String>): Map<String, List<Candidate>> {
            if (literals.isEmpty()) return emptyMap()
            val out = LinkedHashMap<String, MutableList<Candidate>>()
            dsl.fetch(
                "with cand as (" +
                    "select k.literal, e.seq, e.headword, e.headword_reading, e.freq_rank, (e.senses->0->'gloss')::text as glosses, " +
                    "coalesce((select ws.status from app.word_status ws where ws.entry_seq = e.seq), 'new') as status, " +
                    "exists (select 1 from app.note n where n.entry_seq = e.seq and n.deleted_at is null) as has_note, " +
                    "exists (select 1 from app.deck_item x join app.deck d on d.id = x.deck_id " +
                    "where x.entry_seq = e.seq and x.deleted_at is null and d.deleted_at is null) as in_decks " +
                    "from ref.entry_kanji k join ref.dict_entry e on e.seq = k.seq " +
                    "where k.literal = any(?::text[]) and e.retired_release_id is null and position(k.literal in e.headword) > 0" +
                    "), ranked as (" +
                    "select c.*, row_number() over (partition by c.literal order by " +
                    "(case when c.has_note or c.in_decks or c.status in ('learning', 'known', 'never_forget') then 0 else 1 end), " +
                    "c.freq_rank asc nulls last, c.seq) as rn " +
                    "from cand c where c.status not in ('blacklisted', 'ignored')) " +
                    "select literal, seq, headword, headword_reading, freq_rank, glosses, status, has_note, in_decks " +
                    "from ranked where rn <= ? order by literal, rn",
                literals.toSet().toTypedArray(), KanjiExamplePicker.CANDIDATE_LIMIT,
            ).forEach { r ->
                val status = r.get("status", String::class.java)
                val glosses = r.get("glosses", String::class.java)
                out.getOrPut(r.get("literal", String::class.java)) { ArrayList() } += Candidate(
                    KanjiExampleCandidate(
                        seq = r.get("seq", Long::class.java),
                        headword = r.get("headword", String::class.java),
                        reading = r.get("headword_reading", String::class.java),
                        freqRank = r.get("freq_rank", Int::class.javaObjectType),
                        status = WordStatus.entries.firstOrNull { it.wire == status } ?: WordStatus.NEW,
                        hasNote = r.get("has_note", Boolean::class.java),
                        inDecks = r.get("in_decks", Boolean::class.java),
                    ),
                    if (glosses == null) "" else json.readTree(glosses).mapNotNull { it["text"]?.asText() }.joinToString("; "),
                )
            }
            return out
        }
    }
}
