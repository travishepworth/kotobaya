package kotobaya.review

import com.fasterxml.jackson.databind.JsonNode
import kotobaya.cards.BuiltInNoteTypes
import kotobaya.contracts.queue.AddKanjiMode
import kotobaya.contracts.queue.PendingEntry
import kotobaya.contracts.sync.OpType
import kotobaya.notes.NoteViews
import kotobaya.notes.payload
import kotobaya.server.Uuid7
import kotobaya.server.json
import kotobaya.sync.OpTx
import org.jooq.DSLContext

/** A deck's materialization settings: `add_kanji` if **present** (SCHEMA.md 6.2; null = absent) and `card_kinds` (null = absent). */
internal class DeckEntrySettings(val addKanji: AddKanjiMode?, val cardKinds: List<String>?) {
    companion object {
        /** SQL select list for a deck row aliased [alias]: present add_kanji, card_kinds as JSON text. */
        fun columns(alias: String): String =
            "case when jsonb_typeof($alias.settings->'add_kanji') = 'string' then $alias.settings->>'add_kanji' end, " +
                "case when jsonb_typeof($alias.settings->'card_kinds') = 'array' then ($alias.settings->'card_kinds')::text end"

        fun of(addKanji: String?, cardKinds: String?): DeckEntrySettings = DeckEntrySettings(
            AddKanjiMode.entries.firstOrNull { it.wire == addKanji },
            cardKinds?.let { text ->
                val node = json.readTree(text)
                if (!node.isArray || node.any { !it.isTextual }) null else node.map { it.asText() }
            },
        )

        fun load(dsl: DSLContext, deckId: String): DeckEntrySettings =
            dsl.fetchOptional("select ${columns("d")} from app.deck d where d.id = ?::uuid", deckId)
                .map { of(it.get(0, String::class.java), it.get(1, String::class.java)) }
                .orElse(DeckEntrySettings(null, null))
    }
}

/**
 * C-27 (ADR-032): turns the core builder's `toMaterialize` into notes and cards, as the server device, through the
 * op-apply path (CLAUDE.md rule 8). Only `GET /review/queue` calls it, inside its op transaction, which holds the
 * sync lock: the "no live note holds this entry" check and the emit cannot race another server transaction, so
 * the server never creates a second live note for an entry. A device that created one offline is merged by
 * SYNC.md 8.1 when it pushes (the note that held the entry first on the server survives).
 *
 * Step A (contracts/core README "C-27"; ADR-040/042): the kanji notes the build named (`kanjiToMaterialize`, the core's
 * decision), `origin` `auto_kanji`, built-in kanji type, each followed by its `card.created`s; skipped when a live kanji
 * note holds the literal or the user deleted one. Step B, per entry in the builder's order (what is created is
 * [DeckEntryPlan]'s, the same computation that told the builder what was missing):
 * 1. skip it if a live note holds `entry_seq` (idempotent), the dictionary entry is gone, or no vocab card kind
 *    is left;
 * 2. `note.created` {built-in vocab type, `entry_seq`, empty `fields` (rendered from the dictionary), `origin`
 *    `deck`}, then one `card.created` per [DeckEntryPlan.vocabKinds].
 */
internal class DeckMaterializer {
    /**
     * Emits the ops for step A ([kanji], the build's `kanjiToMaterialize`) and step B ([entries]) of README "C-27", in that
     * order; returns the ids of the notes it created (kanji and vocab).
     */
    fun materialize(tx: OpTx, kanji: List<String>, entries: List<PendingEntry>): List<String> {
        if (kanji.isEmpty() && entries.isEmpty()) return emptyList()
        val views = NoteViews(tx.dsl)
        val plan = DeckEntryPlan(tx.dsl)
        val settings = HashMap<String, DeckEntrySettings>()
        val created = ArrayList<String>()
        // Step A: kanji notes. Skipped when a live kanji note holds the literal (idempotent) or the user deleted one (never
        // re-created), or when the kanji type would give it no card.
        for (literal in kanji.distinct()) {
            if (views.liveHolder("kanji_literal", literal) != null || plan.blocked(literal)) continue
            val kinds = plan.kanjiKinds(literal)
            if (kinds.isEmpty()) continue
            val id = Uuid7.generate().toString()
            val p = json.createObjectNode().put("id", id).put("note_type_id", BuiltInNoteTypes.KANJI.id)
                .put("kanji_literal", literal).put("origin", "auto_kanji")
            tx.emit(OpType.NOTE_CREATED, payload(p))
            kinds.forEach { emitCard(tx, id, it) }
            plan.kanjiCreated(literal)
            created += id
        }
        for (e in entries) {
            if (views.liveHolder("entry_seq", e.entrySeq) != null) continue
            val dict = tx.dsl.fetchOptional(
                "select headword, headword_reading, senses::text from ref.dict_entry where seq = ?", e.entrySeq,
            ).map { EntryDict(e.entrySeq, it.get(0, String::class.java), it.get(1, String::class.java), EntryDict.glossesOf(it.get(2, String::class.java))) }
                .orElse(null) ?: continue
            val deck = settings.getOrPut(e.deckId) { DeckEntrySettings.load(tx.dsl, e.deckId) }
            val vocabKinds = plan.vocabKinds(dict, deck.cardKinds)
            if (vocabKinds.isEmpty()) continue

            val noteId = Uuid7.generate().toString()
            val p = json.createObjectNode().put("id", noteId).put("note_type_id", BuiltInNoteTypes.VOCAB.id).put("entry_seq", e.entrySeq)
            p.set<JsonNode>("fields", json.createObjectNode())
            p.put("origin", ORIGIN)
            tx.emit(OpType.NOTE_CREATED, payload(p))
            vocabKinds.forEach { emitCard(tx, noteId, it) }
            created += noteId
        }
        return created
    }

    private fun emitCard(tx: OpTx, noteId: String, kind: String) {
        tx.emit(OpType.CARD_CREATED, payload(json.createObjectNode().put("id", Uuid7.generate().toString()).put("note_id", noteId).put("kind", kind)))
    }

    companion object {
        /** `note.created.origin` of a materialized deck entry. */
        const val ORIGIN = "deck"
    }
}
