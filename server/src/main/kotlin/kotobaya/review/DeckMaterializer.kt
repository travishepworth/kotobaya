package kotobaya.review

import com.fasterxml.jackson.databind.JsonNode
import kotobaya.cards.BuiltInNoteTypes
import kotobaya.cards.DefaultCardGenerator
import kotobaya.contracts.cards.CardGenerator
import kotobaya.contracts.cards.NoteTypeInfo
import kotobaya.contracts.queue.AddKanjiMode
import kotobaya.contracts.queue.PendingEntry
import kotobaya.contracts.sync.OpType
import kotobaya.notes.NoteViews
import kotobaya.notes.payload
import kotobaya.notes.toNoteTypeInfo
import kotobaya.server.Uuid7
import kotobaya.server.json
import kotobaya.sync.OpTx

/**
 * C-27 (ADR-032): turns the core builder's `toMaterialize` into notes and cards, as the server device, through the
 * op-apply path (CLAUDE.md rule 8). Only `GET /review/queue` calls it, inside its op transaction, which holds the
 * sync lock: the "no live note holds this entry" check and the emit cannot race another server transaction, so
 * the server never creates a second live note for an entry. A device that created one offline is merged by
 * SYNC.md 8.1 when it pushes (the server's note, holding the entry first, survives).
 *
 * Per entry, in the builder's order (contracts/core README "C-27"):
 * 1. skip it if a live note holds `entry_seq` (created meanwhile; idempotent) or the dictionary entry is gone;
 * 2. if the drawing deck's `settings.add_kanji` is **present** and not `off` (SCHEMA.md 6.2): one kanji note
 *    (`origin` `auto_kanji`, built-in kanji type, default kinds) per kanji of the headword without a live kanji
 *    note ([CardGenerator.kanjiToAdd]), each followed by its `card.created`;
 * 3. `note.created` {built-in vocab type, `entry_seq`, empty `fields` (rendered from the dictionary), `origin`
 *    `deck`}, then one `card.created` per [CardGenerator.kindsForNewNote] with the drawing deck's `card_kinds`.
 */
internal class DeckMaterializer(private val cards: CardGenerator = DefaultCardGenerator) {
    /** Emits the ops for [entries]; returns how many vocab notes it created. */
    fun materialize(tx: OpTx, entries: List<PendingEntry>): Int {
        if (entries.isEmpty()) return 0
        val views = NoteViews(tx.dsl)
        val vocab = typeInfo(views, BuiltInNoteTypes.VOCAB.id) ?: BuiltInNoteTypes.VOCAB.info
        val kanjiType = typeInfo(views, BuiltInNoteTypes.KANJI.id)
        var created = 0
        for (e in entries) {
            if (views.liveHolder("entry_seq", e.entrySeq) != null) continue
            val headword = tx.dsl.fetchOptional("select headword from ref.dict_entry where seq = ?", e.entrySeq)
                .map { it.get(0, String::class.java) }.orElse(null) ?: continue
            val deck = tx.dsl.fetchOptional(
                "select case when jsonb_typeof(settings->'add_kanji') = 'string' then settings->>'add_kanji' end, " +
                    "case when jsonb_typeof(settings->'card_kinds') = 'array' then (settings->'card_kinds')::text end " +
                    "from app.deck where id = ?::uuid",
                e.deckId,
            ).orElse(null)
            val addKanji = AddKanjiMode.entries.firstOrNull { it.wire == deck?.get(0, String::class.java) } ?: AddKanjiMode.OFF
            val cardKinds = deck?.get(1, String::class.java)?.let(::stringArray)

            if (addKanji != AddKanjiMode.OFF && kanjiType != null) {
                val literals = cards.kanjiOf(headword)
                val existing = if (literals.isEmpty()) {
                    emptySet()
                } else {
                    tx.dsl.fetch(
                        "select kanji_literal from app.note where kanji_literal = any(?) and deleted_at is null",
                        literals.toTypedArray(),
                    ).map { it.get(0, String::class.java) }.toSet()
                }
                for (literal in cards.kanjiToAdd(headword, existing, addKanji)) {
                    val id = Uuid7.generate().toString()
                    val p = json.createObjectNode().put("id", id).put("note_type_id", BuiltInNoteTypes.KANJI.id)
                        .put("kanji_literal", literal).put("origin", "auto_kanji")
                    tx.emit(OpType.NOTE_CREATED, payload(p))
                    cards.kindsForNewNote(kanjiType, null).forEach { emitCard(tx, id, it) }
                }
            }

            val noteId = Uuid7.generate().toString()
            val p = json.createObjectNode().put("id", noteId).put("note_type_id", BuiltInNoteTypes.VOCAB.id).put("entry_seq", e.entrySeq)
            p.set<JsonNode>("fields", json.createObjectNode())
            p.put("origin", ORIGIN)
            tx.emit(OpType.NOTE_CREATED, payload(p))
            cards.kindsForNewNote(vocab, cardKinds).forEach { emitCard(tx, noteId, it) }
            created++
        }
        return created
    }

    private fun typeInfo(views: NoteViews, id: String): NoteTypeInfo? = views.noteType(id)?.toNoteTypeInfo()

    private fun emitCard(tx: OpTx, noteId: String, kind: String) {
        tx.emit(OpType.CARD_CREATED, payload(json.createObjectNode().put("id", Uuid7.generate().toString()).put("note_id", noteId).put("kind", kind)))
    }

    /** Deck `card_kinds`: a JSON array of strings, else null (absent). */
    private fun stringArray(text: String): List<String>? {
        val node = json.readTree(text)
        if (!node.isArray || node.any { !it.isTextual }) return null
        return node.map { it.asText() }
    }

    companion object {
        /** `note.created.origin` of a materialized deck entry. */
        const val ORIGIN = "deck"
    }
}
