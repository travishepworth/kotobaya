package kotobaya.android.shell.api.review

import kotobaya.android.dict.DictEntryDetail
import kotobaya.android.dict.InstalledDictionary
import kotobaya.android.dict.KanjiDetail
import kotobaya.android.grammar.GrammarSentenceSource
import kotobaya.android.review.CardRows
import kotobaya.android.review.InstalledKanjiWords
import kotobaya.android.review.InstalledRenderData
import kotobaya.android.shell.api.identity.obj
import kotobaya.android.shell.api.identity.timestamp
import kotobaya.cards.BuiltInNoteTypes
import kotobaya.cards.grammar.GrammarCandidate
import kotobaya.cards.grammar.GrammarCardSentences
import kotobaya.contracts.WordStatus
import kotobaya.contracts.cards.NoteTypeInfo
import kotobaya.contracts.dict.KanjiExampleCandidate
import kotobaya.contracts.dict.KanjiExamplePicker
import kotobaya.contracts.dict.KanjiReadings
import kotobaya.contracts.fsrs.Scheduler
import kotobaya.contracts.render.ClozeSentence
import kotobaya.contracts.render.DictionaryData
import kotobaya.contracts.render.FuriganaSegment
import kotobaya.contracts.render.KanjiData
import kotobaya.contracts.render.TextRenderInput
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.dict.DefaultKanjiExamplePicker
import kotobaya.render.DefaultCardTextRenderer
import kotobaya.sync.DefaultCanonicalJson
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.apply.Row

/**
 * The `QueueItem` view of `getReviewQueue` (openapi.yaml `QueueItem`), from the replica and the dictionary's L-7 steps
 * 1-2 ([InstalledDictionary.entry] / [InstalledDictionary.kanji]: installed package, then the sidecar cache; never the
 * network). The shapes are the server's (`ReviewViews`, `QueueExtras`, `QueueText`); every choice is the core's
 * (scheduler preview, kanji example picker, grammar sentence picker, plain-text renderer). A miss is `null`, never an
 * error: the card is still served (SHELL.md 4.3, 10.7).
 */
internal class QueueViews(
    private val t: LearningTables,
    private val dict: InstalledDictionary,
    private val scheduler: Scheduler,
    private val leechThreshold: Int,
) {
    private val renderData = InstalledRenderData(dict)
    private val noteTypes = HashMap<String, NoteTypeInfo?>()

    /** Items for [cards] (card id to `review_type`) in order; a card that is gone (or its note) is dropped. */
    fun items(cards: List<Pair<String, String>>, nowMs: Long): List<JsonValue> = cards.mapNotNull { (id, type) -> item(id, type, nowMs) }

    private fun item(cardId: String, reviewType: String, nowMs: Long): JsonObject? {
        val card = CardRows.get(t, LearningTable.CARD, cardId) ?: return null
        val noteId = CardRows.str(card["note_id"]) ?: return null
        val note = CardRows.get(t, LearningTable.NOTE, noteId) ?: return null
        val cardJson = cardView(card, note)
        val entrySeq = seq(note["entry_seq"])
        val literal = CardRows.str(note["kanji_literal"])
        val entryDetail = entrySeq?.let { s -> guarded { dict.entry(s) } }
        val kanjiDetail = literal?.let { l -> guarded { dict.kanji(l) } }
        val entry: JsonValue = if (entrySeq != null && entryDetail != null) entryView(entryDetail) else JsonNull
        val kanji: JsonValue = if (literal != null && kanjiDetail != null) kanjiView(kanjiDetail) else JsonNull

        val out = LinkedHashMap<String, JsonValue>()
        out["card"] = cardJson
        out["entry"] = entry
        out["kanji"] = kanji
        // C-15: grammar cards (not production) show a rotating example sentence from the user's media
        val pointId = CardRows.str(note["grammar_point_id"])
        val kind = CardRows.str(card["kind"]).orEmpty()
        val grammarCloze = pointId != null && kind != "production"
        val picked = if (grammarCloze && pointId != null) guarded { grammarSentence(cardId, pointId, int(card["reps"]) ?: 0) } else null
        out["sentence"] = picked?.first ?: JsonNull
        if (grammarCloze) out["grammar_sentence"] = picked?.second ?: JsonNull
        out["review_type"] = JsonString(reviewType)
        // C-26: the first showing (state new; equals the core QueueItem.isNew on queue items)
        out["is_new"] = JsonBool(CardRows.str(card["state"]) == "new")
        // C-25: the note's decks (first 3) and their number
        val decks = noteDecks(note)
        out["decks"] = JsonArray(decks.take(MAX_DECKS).map { d -> obj("id" to d["id"].orNull(), "name" to d["name"].orNull(), "priority" to d["priority"].orNull()) })
        out["deck_count"] = num(decks.size.toLong())
        if (literal != null) out["kanji_examples"] = guarded { kanjiExamples(literal, kanjiDetail) } ?: JsonArray(emptyList())
        // ARCH-CARD-TEXT: the core's plain-text render from the same inputs the item gives an HTML client
        textOf(note, kind, entryDetail.takeIf { entry != JsonNull }, kanjiDetail.takeIf { kanji != JsonNull }, picked?.third)?.let { out["text"] = it }
        out["preview"] = if (reviewType == "drill" || reviewType == "cram") JsonNull else preview(card, nowMs)
        return JsonObject(out)
    }

    // ---- card ---------------------------------------------------------------------------------------------------------

    /** `Card` with its `note` summary (the server's `ReviewViews.cards`). */
    private fun cardView(card: Row, note: Row): JsonObject {
        val lapses = int(card["lapses"]) ?: 0
        return obj(
            "id" to card["id"].orNull(),
            "note_id" to card["note_id"].orNull(),
            "kind" to card["kind"].orNull(),
            "preset_id" to card["preset_id"].orNull(),
            "suspended" to (card["suspended"] as? JsonBool ?: JsonBool(false)),
            "buried_until" to stamp(card["buried_until"]),
            "flags" to (card["flags"] as? JsonNumber ?: num(0)),
            "state" to card["state"].orNull(),
            "step" to card["step"].orNull(),
            "stability" to card["stability"].orNull(),
            "difficulty" to card["difficulty"].orNull(),
            "due_at" to stamp(card["due_at"]),
            "interval_days" to card["interval_days"].orNull(),
            "last_review_at" to stamp(card["last_review_at"]),
            "reps" to (card["reps"] as? JsonNumber ?: num(0)),
            "lapses" to num(lapses.toLong()),
            "created_at" to stamp(card["created_at"]),
            "updated_at" to stamp(card["updated_at"]),
            "deleted_at" to stamp(card["deleted_at"]),
            "merged_into_id" to card["merged_into_id"].orNull(),
            "note" to obj(
                "id" to note["id"].orNull(),
                "note_type_id" to note["note_type_id"].orNull(),
                "type" to note["type"].orNull(),
                "entry_seq" to note["entry_seq"].orNull(),
                "kanji_literal" to note["kanji_literal"].orNull(),
                "grammar_point_id" to note["grammar_point_id"].orNull(),
                "fields" to (note["fields"] as? JsonObject ?: JsonObject(emptyMap())),
                "tags" to (note["tags"] as? JsonArray ?: JsonArray(emptyList())),
                "mnemonic" to (note["mnemonic"] as? JsonString ?: JsonString("")),
                "source_sentence" to (note["source_sentence"] as? JsonString ?: JsonString("")),
            ),
            "leech" to JsonBool(lapses >= leechThreshold),
        )
    }

    /** The four ratings' due time and interval, from the core scheduler at [nowMs] (unfuzzed, as the server). */
    private fun preview(card: Row, nowMs: Long): JsonValue {
        val params = scheduler.paramsOf(CardRows.presetOf(t, card))
        val out = scheduler.preview(CardRows.scheduleOf(card), nowMs, params, null, null).associateBy { it.rating.value }
        val members = LinkedHashMap<String, JsonValue>()
        for ((key, rating) in RATINGS) {
            val o = checkNotNull(out[rating]) { "preview lacks rating $rating" }
            members[key] = obj(
                "due_at" to JsonString(timestamp(checkNotNull(o.after.dueAt) { "preview without due_at" })),
                "interval_days" to num((o.after.intervalDays ?: 0).toLong()),
            )
        }
        return JsonObject(members)
    }

    // ---- dictionary -----------------------------------------------------------------------------------------------------

    /** `DictEntry` with the per-user overlay from the replica (`status`, `note_id`, `deck_ids`). */
    private fun entryView(d: DictEntryDetail): JsonObject = obj(
        "seq" to num(d.seq),
        "language" to JsonString(d.language),
        "headword" to JsonString(d.headword),
        "headword_reading" to JsonString(d.headwordReading),
        "is_common" to JsonBool(d.isCommon),
        "kanji_forms" to parse(d.kanjiForms.toString()),
        "readings" to parse(d.readings.toString()),
        "senses" to parse(d.senses.toString()),
        "pos" to strings(d.pos),
        "misc" to strings(d.misc),
        "freq_rank" to (d.freqRank?.let { num(it.toLong()) } ?: JsonNull),
        "retired" to JsonBool(d.retired),
        "freqs" to JsonArray(d.freqs.map { obj("list_id" to JsonString(it.listId), "rank" to num(it.rank.toLong())) }),
        "pitch" to JsonArray(d.pitch.map { p -> obj("reading" to JsonString(p.reading), "positions" to JsonArray(p.positions.map { num(it.toLong()) })) }),
        "kanji" to strings(d.kanji),
        "status" to JsonString(statusOf(d.seq).wire),
        "note_id" to (liveNote("entry_seq", num(d.seq))?.let { it["id"] } ?: JsonNull),
        "deck_ids" to JsonArray(entryDecks(d.seq).map { it["id"].orNull() }),
    )

    /** `Kanji` with `note_id` (a live note of the literal). */
    private fun kanjiView(k: KanjiDetail): JsonObject = obj(
        "literal" to JsonString(k.literal),
        "on_readings" to strings(k.onReadings),
        "kun_readings" to strings(k.kunReadings),
        "nanori" to strings(k.nanori),
        "meanings" to strings(k.meanings),
        "stroke_count" to (k.strokeCount?.let { num(it.toLong()) } ?: JsonNull),
        "grade" to (k.grade?.let { num(it.toLong()) } ?: JsonNull),
        "jlpt" to (k.jlpt?.let { num(it.toLong()) } ?: JsonNull),
        "freq_rank" to (k.freqRank?.let { num(it.toLong()) } ?: JsonNull),
        "radical" to (k.radical?.let(::JsonString) ?: JsonNull),
        "components" to strings(k.components),
        "note_id" to (liveNote("kanji_literal", JsonString(k.literal))?.let { it["id"] } ?: JsonNull),
    )

    private fun statusOf(seq: Long): WordStatus {
        val wire = CardRows.str(t.get(LearningTable.WORD_STATUS, listOf(num(seq)))?.get("status"))
        return WordStatus.entries.firstOrNull { it.wire == wire } ?: WordStatus.NEW
    }

    private fun liveNote(column: String, value: JsonValue): Row? =
        t.where(LearningTable.NOTE, column, value).filter { isNull(it["deleted_at"]) }.minByOrNull { CardRows.str(it["id"]).orEmpty() }

    // ---- decks (C-25) ---------------------------------------------------------------------------------------------------

    /** Live decks with a live item `e:<seq>`, by priority then id (`DictEntry.deck_ids`). */
    private fun entryDecks(seq: Long): List<Row> =
        decksOf(t.where(LearningTable.DECK_ITEM, "entry_seq", num(seq)), smartToo = true)

    /**
     * `Note.deck_ids` as the server's `DeckMembership.ofNotes`: live non-smart decks with a live item `e:<entry_seq>` of
     * the note or `n:<id>` of the note or a note merged into it; a deleted or merged-away note is in no deck.
     */
    private fun noteDecks(note: Row): List<Row> {
        if (!CardRows.live(note)) return emptyList()
        val noteId = CardRows.str(note["id"]) ?: return emptyList()
        val sources = listOf(noteId) + t.where(LearningTable.NOTE, "merged_into_id", JsonString(noteId)).mapNotNull { CardRows.str(it["id"]) }
        val items = sources.flatMap { t.where(LearningTable.DECK_ITEM, "note_id", JsonString(it)) } +
            (seq(note["entry_seq"])?.let { t.where(LearningTable.DECK_ITEM, "entry_seq", num(it)) }.orEmpty())
        return decksOf(items, smartToo = false)
    }

    private fun decksOf(items: List<Row>, smartToo: Boolean): List<Row> =
        items.asSequence().filter { isNull(it["deleted_at"]) }.mapNotNull { CardRows.str(it["deck_id"]) }.distinct()
            .mapNotNull { CardRows.get(t, LearningTable.DECK, it) }
            .filter { isNull(it["deleted_at"]) && (smartToo || CardRows.str(it["type"]) != "smart") }
            .sortedWith(compareBy<Row>({ (it["priority"] as? JsonNumber)?.value ?: 0.0 }, { CardRows.str(it["id"]).orEmpty() }))
            .toList()

    // ---- kanji examples (C-25) ------------------------------------------------------------------------------------------

    /** The core picker over the installed dictionary's words with the kanji and the replica's status / note / deck. */
    private fun kanjiExamples(literal: String, kanji: KanjiDetail?): JsonArray {
        val words = InstalledKanjiWords(dict).words(literal)
        val candidates = words.map { w ->
            KanjiExampleCandidate(
                seq = w.seq,
                headword = w.headword,
                reading = w.reading,
                freqRank = w.freqRank,
                status = statusOf(w.seq),
                hasNote = liveNote("entry_seq", num(w.seq)) != null,
                inDecks = entryDecks(w.seq).isNotEmpty(),
            )
        }
        val readings = KanjiReadings(literal, kanji?.onReadings.orEmpty(), kanji?.kunReadings.orEmpty())
        return JsonArray(
            DefaultKanjiExamplePicker.pick(readings, candidates, KanjiExamplePicker.DEFAULT_LIMIT).map { e ->
                obj(
                    "seq" to num(e.candidate.seq),
                    "headword" to JsonString(e.candidate.headword),
                    "reading" to JsonString(e.candidate.reading),
                    "gloss" to JsonString(renderData.lookup(e.candidate.seq)?.glosses.orEmpty().joinToString("; ")),
                    "reading_type" to JsonString(e.readingType.wire),
                )
            },
        )
    }

    // ---- grammar sentence (C-15) ----------------------------------------------------------------------------------------

    /** (`Sentence`, `GrammarCardSentence`, the renderer's cloze) of the core pick over the replica, or null. */
    private fun grammarSentence(cardId: String, pointId: String, reps: Int): Triple<JsonValue, JsonValue, ClozeSentence>? {
        val last = GrammarSentenceSource.lastShown(t, cardId)
        val pick: GrammarCandidate = GrammarCardSentences.pick(GrammarSentenceSource.candidates(t, pointId), last, reps) ?: return null
        val s = CardRows.get(t, LearningTable.SENTENCE, pick.sentenceId) ?: return null
        val mediaId = CardRows.str(s["media_id"]) ?: return null
        val segmentId = CardRows.str(s["segment_id"]) ?: return null
        val media = CardRows.get(t, LearningTable.MEDIA, mediaId) ?: return null
        val segment = CardRows.get(t, LearningTable.MEDIA_SEGMENT, segmentId) ?: return null
        val spans = t.where(LearningTable.SENTENCE_GRAMMAR_SPAN, "sentence_id", JsonString(pick.sentenceId))
            .filter { isNull(it["deleted_at"]) }
            .filter { sp -> CardRows.str(sp["grammar_point_id"])?.let { CardRows.get(t, LearningTable.GRAMMAR_POINT, it) }?.let { isNull(it["deleted_at"]) } == true }
            .sortedWith(
                compareBy<Row>(
                    { int(it["start_offset"]) ?: 0 }, { int(it["end_offset"]) ?: 0 },
                    { CardRows.str(it["grammar_point_id"]).orEmpty() }, { CardRows.str(it["origin"]).orEmpty() },
                ),
            )
        val location = s["location"].orNull()
        val sentence = obj(
            "id" to JsonString(pick.sentenceId),
            "media_id" to JsonString(mediaId),
            "segment_id" to JsonString(segmentId),
            "ordinal" to s["ordinal"].orNull(),
            "text" to s["text"].orNull(),
            "tokens" to (s["tokens"] ?: JsonArray(emptyList())),
            "origin" to s["origin"].orNull(),
            "start_ms" to s["start_ms"].orNull(),
            "end_ms" to s["end_ms"].orNull(),
            "audio_blob_hash" to s["audio_blob_hash"].orNull(),
            "image_blob_hash" to s["image_blob_hash"].orNull(),
            "location" to location,
            "unknown_count" to num(pick.unknownCount.toLong()),
            "grammar_spans" to JsonArray(
                spans.map {
                    obj(
                        "grammar_point_id" to it["grammar_point_id"].orNull(),
                        "start_offset" to it["start_offset"].orNull(),
                        "end_offset" to it["end_offset"].orNull(),
                        "origin" to it["origin"].orNull(),
                    )
                },
            ),
        )
        val gs = obj(
            "sentence_id" to JsonString(pick.sentenceId),
            "text" to JsonString(pick.text),
            "start_offset" to num(pick.start.toLong()),
            "end_offset" to num(pick.end.toLong()),
            "translation" to JsonNull,
            "unknown_count" to num(pick.unknownCount.toLong()),
            "rotation" to num(reps.toLong()),
            "last_shown_sentence_id" to (last?.let(::JsonString) ?: JsonNull),
            "source" to obj(
                "media_id" to JsonString(mediaId),
                "media_title" to media["title"].orNull(),
                "segment_id" to JsonString(segmentId),
                "segment_title" to segment["title"].orNull(),
                "start_ms" to s["start_ms"].orNull(),
                "location" to location,
            ),
        )
        return Triple(sentence, gs, ClozeSentence(pick.sentenceId, pick.text, pick.start, pick.end, null))
    }

    // ---- text (ARCH-CARD-TEXT) ------------------------------------------------------------------------------------------

    /**
     * `CardText` from the note's fields, entry, kanji and grammar cloze (the server's `QueueText`); null when the note
     * type is unknown (no row, not a built-in base type).
     */
    private fun textOf(note: Row, kind: String, entry: DictEntryDetail?, kanji: KanjiDetail?, cloze: ClozeSentence?): JsonObject? {
        val typeId = CardRows.str(note["note_type_id"])
        val info = typeId?.let { id -> noteTypes.getOrPut(id) { CardRows.get(t, LearningTable.NOTE_TYPE, id)?.let(CardRows::noteTypeInfo) } }
            ?: BuiltInNoteTypes.ALL.firstOrNull { it.info.baseType.wire == CardRows.str(note["type"]) }?.info
            ?: return null
        val out = DefaultCardTextRenderer.renderText(
            TextRenderInput(
                noteType = info,
                kind = kind,
                fields = CardRows.fieldsOf(note),
                dictionary = entry?.let(::textDictionary),
                cloze = cloze,
                kanji = kanji?.let { KanjiData(it.literal, it.meanings, it.onReadings, it.kunReadings, it.strokeCount) },
            ),
        )
        return obj(
            "front" to JsonString(out.front),
            "back" to JsonString(out.back),
            "reading" to (out.reading?.let(::JsonString) ?: JsonNull),
            "sentence" to (out.sentence?.let(::JsonString) ?: JsonNull),
        )
    }

    /** The server's `QueueText.dictionaryData`: every gloss of every sense, furigana over the whole headword. */
    private fun textDictionary(e: DictEntryDetail): DictionaryData {
        val senses = parse(e.senses.toString()) as? JsonArray
        val glosses = senses?.items.orEmpty().flatMap { s ->
            ((s as? JsonObject)?.members?.get("gloss") as? JsonArray)?.items.orEmpty()
                .mapNotNull { g -> ((g as? JsonObject)?.members?.get("text") as? JsonString)?.value }
        }
        return DictionaryData(
            expression = e.headword,
            reading = e.headwordReading,
            furigana = listOf(FuriganaSegment(e.headword, e.headwordReading.takeUnless { it == e.headword })),
            glosses = glosses,
            pitchDrops = e.pitch.firstOrNull { it.reading == e.headwordReading }?.positions.orEmpty(),
        )
    }

    private companion object {
        /** `QueueItem.decks` lists at most this many decks; `deck_count` has the total. */
        const val MAX_DECKS = 3
        val RATINGS = listOf("again" to 1, "hard" to 2, "good" to 3, "easy" to 4)

        fun isNull(v: JsonValue?): Boolean = v == null || v == JsonNull

        fun JsonValue?.orNull(): JsonValue = this ?: JsonNull

        fun num(v: Long): JsonNumber = JsonNumber(v.toDouble())

        fun int(v: JsonValue?): Int? = (v as? JsonNumber)?.value?.toInt()

        fun seq(v: JsonValue?): Long? = (v as? JsonNumber)?.value?.toLong()

        fun stamp(v: JsonValue?): JsonValue = (v as? JsonNumber)?.value?.toLong()?.let { JsonString(timestamp(it)) } ?: JsonNull

        fun strings(v: List<String>): JsonArray = JsonArray(v.map(::JsonString))

        fun parse(text: String): JsonValue = DefaultCanonicalJson.decode(text).value ?: JsonNull

        /** A dictionary or grammar read that fails costs that part of the item (null), never the card (SHELL.md 10.7). */
        inline fun <T> guarded(block: () -> T?): T? = try {
            block()
        } catch (e: RuntimeException) {
            null
        }
    }
}
