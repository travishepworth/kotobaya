package kotobaya.android.notes

import kotobaya.cards.DefaultCardGenerator
import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.cards.CardGenerator
import kotobaya.contracts.cards.NoteTypeInfo
import kotobaya.contracts.cards.TemplateInfo
import kotobaya.contracts.queue.AddKanjiMode
import kotobaya.contracts.render.DictionaryData
import kotobaya.contracts.render.KanjiData
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.Op
import kotobaya.contracts.sync.OpType
import kotobaya.render.DefaultCardRenderer
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.apply.MergeReaders
import kotobaya.sync.client.apply.Row
import kotobaya.sync.client.mint.MintRefusal
import kotobaya.sync.client.mint.MintRequest
import kotobaya.sync.client.mint.MintResult
import kotobaya.sync.client.mint.OpMinter
import kotobaya.sync.client.store.Clock
import kotobaya.sync.client.store.DeviceStore
import kotobaya.sync.client.store.OpIdSource
import kotobaya.sync.client.store.StoreTransaction

/** A note to add (from Takoboto, a share intent, AnkiConnect, the UI). */
data class NoteRequest(
    /** Caller's idempotency key: a retry with the same id returns the note the first call created. */
    val requestId: String? = null,
    /** The word as written (kanji or kana). At least one of [word] / [reading] is required. */
    val word: String? = null,
    /** Its kana reading. */
    val reading: String? = null,
    /** Sentence the word was mined from → `source_sentence` (and `fields.sentence` if the type has it and none is given). */
    val sentence: String? = null,
    /** `note.origin`: the source app (`takoboto`, `share`, `ankiconnect`, …). */
    val origin: String = "manual",
    /** User deck to add the word to (`deck.item_put`); null = deckless (mined). */
    val deckId: String? = null,
    val tags: List<String> = emptyList(),
    /** Raw field values by field key; `""` values are dropped (OPS.md `note.created`). */
    val fields: Map<String, String> = emptyMap(),
    val noteTypeId: String = NoteCreator.VOCAB_NOTE_TYPE_ID,
    /** Create even if the word is already there (a knowing duplicate without `entry_seq`, see [NoteCreator]). */
    val force: Boolean = false,
)

sealed interface CreateResult {
    /**
     * The note exists. [retried] = this [NoteRequest.requestId] had already created it (nothing minted now;
     * [noteId] is the note or, if it was merged since, its survivor; [ops] is empty).
     */
    data class Created(
        val noteId: String,
        val cardIds: List<String>,
        val kanjiNoteIds: List<String>,
        val match: MatchResult,
        val ops: List<Op>,
        val retried: Boolean = false,
    ) : CreateResult

    /** A live local note already studies this word; nothing was created. */
    data class Duplicate(val existing: ExistingNote) : CreateResult

    /** Nothing was created. */
    data class Refused(val reason: CreateRefusal) : CreateResult
}

sealed interface CreateRefusal {
    data object NoWord : CreateRefusal
    data class UnknownNoteType(val noteTypeId: String) : CreateRefusal

    /** The deck does not exist, is deleted or is not a `user` deck (OPS.md `deck.item_put` `invalid_target`). */
    data class DeckNotWritable(val deckId: String) : CreateRefusal

    /** The minter refused (not paired, clock, rebuild, schema e.g. a tag with a space, size). */
    data class Mint(val refusal: MintRefusal) : CreateRefusal
}

/**
 * Creates a note offline (SYNC.md 4; SPEC "Takoboto integration"): match a JMdict entry locally,
 * refuse a local duplicate, generate the cards, and mint `note.created`, `card.created` × n and the
 * optional `deck.item_put` in ONE [OpMinter.mintAll] inside ONE store transaction (with the request-id
 * record), so a kill before commit leaves nothing and a retry finds the note.
 *
 * - **Match** ([EntryMatcher]): only for `vocab` note types. No match → the note has no `entry_seq`, gets
 *   `fields.expression`/`fields.reading` from the request (so its cards render), the tag [UNMATCHED_TAG]
 *   (the flag a later re-match looks for) and **no deck item**, whatever [NoteRequest.deckId] says.
 * - **Duplicate** ([DuplicateCheck], local only): [CreateResult.Duplicate], nothing created. With
 *   [NoteRequest.force] a new note is created **without** `entry_seq`: SYNC.md 4 forbids knowingly creating a
 *   second live note of one entry, so a forced duplicate is a separate entry-less note (not tagged
 *   unmatched); in a deck it is the item `n:<id>`.
 * - **Cards**: [CardGenerator.kindsForNewNote] with the deck's `card_kinds`, minus every kind whose front
 *   renders empty ([DefaultCardRenderer.frontIsEmpty], with the entry's dictionary data) — the
 *   "skip empty front" rule the generator leaves to its caller. No card at all is legal.
 * - **add_kanji**: when the target deck's `settings.add_kanji` is present and not `off` (SCHEMA.md 6.2),
 *   kanji notes (`origin = auto_kanji`) for the word's kanji that have no live kanji note, with their
 *   default cards, minted **before** the vocab note's ops.
 * - **Deck item**: `e:<entry_seq>` at the deck's live maximum position + 1024 (like the server's note
 *   create), skipped when the deck already shows that word (SYNC.md 8.5 effective identity).
 */
class NoteCreator<L : LearningTables>(
    private val store: DeviceStore<L>,
    private val minter: OpMinter<L>,
    private val matcher: EntryMatcher,
    private val ids: OpIdSource,
    private val clock: Clock,
    private val kanjiData: (String) -> KanjiData? = { null },
    private val generator: CardGenerator = DefaultCardGenerator,
    private val afterCreate: () -> Unit = {},
) {
    fun create(request: NoteRequest): CreateResult {
        val word = EntryMatcher.clean(request.word)
        val reading = EntryMatcher.clean(request.reading)
        if (word == null && reading == null) return CreateResult.Refused(CreateRefusal.NoWord)
        // the dictionary is another database: read it before the learning transaction
        val match = matcher.match(word, reading)
        val result = store.transaction { tx -> createIn(tx, request, word, reading, match) }
        if (result is CreateResult.Created && !result.retried) runCatching { afterCreate() }
        return result
    }

    private fun createIn(tx: StoreTransaction<L>, req: NoteRequest, word: String?, reading: String?, match: MatchResult): CreateResult {
        val tables = tx.learning
        val requestKey = req.requestId?.takeIf { it.isNotBlank() }?.let { REQUEST_KEY_PREFIX + it }
        requestKey?.let { tx.syncState.get(it) }?.let { MergeReaders.resolveNote(tables, it) }?.let { note ->
            val id = (note.getValue("id") as JsonString).value
            val cards = tables.where(LearningTable.CARD, "note_id", JsonString(id)).filter(DuplicateCheck::isLive).map { str(it, "id") }
            return CreateResult.Created(id, cards, emptyList(), match, emptyList(), retried = true)
        }

        val typeRow = tables.get(LearningTable.NOTE_TYPE, listOf(JsonString(req.noteTypeId)))
            ?.takeIf { (it["deleted_at"] ?: JsonNull) == JsonNull }
            ?: return CreateResult.Refused(CreateRefusal.UnknownNoteType(req.noteTypeId))
        val type = noteTypeInfo(typeRow)
        val deck = req.deckId?.let { id ->
            tables.get(LearningTable.DECK, listOf(JsonString(id)))
                ?.takeIf { (it["deleted_at"] ?: JsonNull) == JsonNull && it["type"] == JsonString("user") }
                ?: return CreateResult.Refused(CreateRefusal.DeckNotWritable(id))
        }

        val entry = (match as? MatchResult.Matched)?.entry?.takeIf { type.baseType == NoteBaseType.VOCAB }
        val existing = if (type.baseType == NoteBaseType.VOCAB) DuplicateCheck.find(tables, entry?.seq, word ?: reading, reading) else null
        if (existing != null && !req.force) return CreateResult.Duplicate(existing)
        val forced = existing != null
        val entrySeq = entry?.seq?.takeUnless { forced }
        val unmatched = type.baseType == NoteBaseType.VOCAB && entry == null

        val now = clock.nowMs()
        val noteId = ids.newOpId(now)
        val fields = LinkedHashMap<String, String>()
        for ((k, v) in req.fields) if (v.isNotEmpty()) fields[k] = v
        if (entrySeq == null) {
            (word ?: reading)?.let { if (fields["expression"].isNullOrEmpty()) fields["expression"] = it }
            reading?.let { if (fields["reading"].isNullOrEmpty()) fields["reading"] = it }
        }
        val sentence = req.sentence?.takeIf { it.isNotBlank() }
        if (sentence != null && "sentence" in type.fieldKeys && fields["sentence"].isNullOrEmpty()) fields["sentence"] = sentence
        val tags = (req.tags + if (unmatched) listOf(UNMATCHED_TAG) else emptyList()).distinct().sorted()
        val dictionary: DictionaryData? = entry?.takeIf { entrySeq != null }?.dictionaryData()

        val requests = ArrayList<MintRequest>()
        val kanjiNoteIds = ArrayList<String>()
        val addKanji = deck?.let(::addKanjiMode) ?: AddKanjiMode.OFF
        if (addKanji != AddKanjiMode.OFF && type.baseType == NoteBaseType.VOCAB) {
            val expression = word?.takeIf(EntryMatcher::containsKanji) ?: entry?.headword ?: word.orEmpty()
            val kanjiType = tables.get(LearningTable.NOTE_TYPE, listOf(JsonString(KANJI_NOTE_TYPE_ID)))?.let(::noteTypeInfo)
            if (kanjiType != null) {
                for (literal in generator.kanjiToAdd(expression, liveKanji(tables), addKanji)) {
                    val id = ids.newOpId(now)
                    kanjiNoteIds += id
                    requests += MintRequest(
                        OpType.NOTE_CREATED,
                        obj("id" to JsonString(id), "note_type_id" to JsonString(KANJI_NOTE_TYPE_ID), "kanji_literal" to JsonString(literal), "origin" to JsonString("auto_kanji")),
                    )
                    val kd = kanjiData(literal) ?: KanjiData(literal, emptyList(), emptyList(), emptyList())
                    for (kind in generator.kindsForNewNote(kanjiType, null)) {
                        if (DefaultCardRenderer.frontIsEmpty(kanjiType, kind, emptyMap(), kanji = kd)) continue
                        requests += cardRequest(ids.newOpId(now), id, kind)
                    }
                }
            }
        }

        val note = LinkedHashMap<String, JsonValue>()
        note["id"] = JsonString(noteId)
        note["note_type_id"] = JsonString(req.noteTypeId)
        entrySeq?.let { note["entry_seq"] = JsonNumber(it.toDouble()) }
        if (fields.isNotEmpty()) note["fields"] = JsonObject(fields.mapValues { JsonString(it.value) })
        if (tags.isNotEmpty()) note["tags"] = JsonArray(tags.map(::JsonString))
        sentence?.let { note["source_sentence"] = JsonString(it) }
        note["origin"] = JsonString(req.origin)
        requests += MintRequest(OpType.NOTE_CREATED, JsonObject(note))

        val cardIds = ArrayList<String>()
        for (kind in generator.kindsForNewNote(type, deck?.let(::cardKinds))) {
            if (DefaultCardRenderer.frontIsEmpty(type, kind, fields, dictionary = dictionary)) continue
            val id = ids.newOpId(now)
            cardIds += id
            requests += cardRequest(id, noteId, kind)
        }

        if (deck != null && !unmatched) {
            val deckId = str(deck, "id")
            val key = if (entrySeq != null) "e:$entrySeq" else "n:$noteId"
            val visible = MergeReaders.visibleDeckItems(tables, deckId)
            if (visible.none { MergeReaders.effectiveIdentity(tables, it) == key }) {
                val max = tables.where(LearningTable.DECK_ITEM, "deck_id", JsonString(deckId))
                    .filter { (it["deleted_at"] ?: JsonNull) == JsonNull }
                    .maxOfOrNull { (it["position"] as? JsonNumber)?.value?.toLong() ?: 0L } ?: 0L
                requests += MintRequest(
                    OpType.DECK_ITEM_PUT,
                    obj("deck_id" to JsonString(deckId), "item_key" to JsonString(key), "position" to JsonNumber((max + DECK_POSITION_STEP).toDouble())),
                )
            }
        }

        return when (val minted = minter.mintAll(requests)) {
            is MintResult.Refused -> CreateResult.Refused(CreateRefusal.Mint(minted.reason))
            is MintResult.Minted -> {
                requestKey?.let { tx.syncState.put(it, noteId) }
                CreateResult.Created(noteId, cardIds, kanjiNoteIds, match, minted.ops)
            }
        }
    }

    private fun cardRequest(id: String, noteId: String, kind: String) =
        MintRequest(OpType.CARD_CREATED, obj("id" to JsonString(id), "note_id" to JsonString(noteId), "kind" to JsonString(kind)))

    private fun liveKanji(tables: LearningTables): Set<String> =
        tables.where(LearningTable.NOTE, "type", JsonString("kanji")).filter(DuplicateCheck::isLive)
            .mapNotNull { (it["kanji_literal"] as? JsonString)?.value }.toSet()

    companion object {
        const val VOCAB_NOTE_TYPE_ID: String = "00000000-0000-7000-8000-000000000101"
        const val KANJI_NOTE_TYPE_ID: String = "00000000-0000-7000-8000-000000000102"

        /** Tag of a note created without a dictionary match; a later re-match removes it. */
        const val UNMATCHED_TAG: String = "unmatched"

        /** Device-local `sync_state` key (the `client.*` namespace) recording which note a request id created. */
        const val REQUEST_KEY_PREFIX: String = "client.note_request."

        /** Deck positions are spaced by 1024 (OPS.md `deck.item_put`). */
        const val DECK_POSITION_STEP: Long = 1024

        private fun obj(vararg members: Pair<String, JsonValue>) = JsonObject(linkedMapOf(*members))

        private fun str(row: Row, col: String): String = (row.getValue(col) as JsonString).value

        private fun settings(deck: Row): Map<String, JsonValue> = (deck["settings"] as? JsonObject)?.members.orEmpty()

        /** Deck `card_kinds`: a JSON array of strings, else absent (null). */
        internal fun cardKinds(deck: Row): List<String>? =
            (settings(deck)["card_kinds"] as? JsonArray)?.items?.let { items ->
                if (items.all { it is JsonString }) items.map { (it as JsonString).value } else null
            }

        /** Deck `add_kanji` for card creation: present = exactly "off" / "before" / "alongside"; absent = OFF (SCHEMA.md 6.2). */
        internal fun addKanjiMode(deck: Row): AddKanjiMode =
            (settings(deck)["add_kanji"] as? JsonString)?.value?.let { s -> AddKanjiMode.entries.firstOrNull { it.wire == s } } ?: AddKanjiMode.OFF

        /** A `note_type` row as the core sees it; templates sorted by kind, like the server (jsonb has no key order). */
        internal fun noteTypeInfo(row: Row): NoteTypeInfo {
            val base = NoteBaseType.entries.first { it.wire == str(row, "base_type") }
            val fieldKeys = (row["fields"] as? JsonArray)?.items.orEmpty()
                .mapNotNull { ((it as? JsonObject)?.members?.get("key") as? JsonString)?.value }
            val templates = (row["templates"] as? JsonObject)?.members.orEmpty().entries.sortedBy { it.key }.map { (kind, v) ->
                val t = (v as? JsonObject)?.members.orEmpty()
                TemplateInfo(kind, (t["default"] as? JsonBool)?.value ?: false, (t["front"] as? JsonString)?.value, (t["back"] as? JsonString)?.value)
            }
            return NoteTypeInfo(base, fieldKeys, templates, (row["css"] as? JsonString)?.value ?: "")
        }
    }
}
