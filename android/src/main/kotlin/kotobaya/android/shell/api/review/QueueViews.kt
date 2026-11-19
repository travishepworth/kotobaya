package kotobaya.android.shell.api.review

import kotobaya.android.media.WordAudioLookup
import kotobaya.android.media.WordAudios
import kotobaya.android.queue.WordText
import kotobaya.contracts.render.WordAudio
import kotobaya.render.FieldPlainText
import kotobaya.render.PlainTextMode
import kotobaya.android.dict.DictEntryDetail
import kotobaya.android.dict.InstalledDictionary
import kotobaya.android.dict.KanjiDetail
import kotobaya.android.queue.KanjiSource
import kotobaya.android.grammar.GrammarSentenceSource
import kotobaya.android.review.CardRows
import kotobaya.android.review.InstalledKanjiWords
import kotobaya.android.review.InstalledRenderData
import kotobaya.android.sync.SqlLearningTables
import kotobaya.android.shell.api.identity.obj
import kotobaya.android.shell.api.identity.timestamp
import kotobaya.cards.BuiltInNoteTypes
import kotobaya.cards.DefaultCardGenerator
import kotobaya.cards.DefaultKanjiRequirement
import kotobaya.cards.grammar.GrammarCandidate
import kotobaya.cards.grammar.GrammarCardSentences
import kotobaya.contracts.WordStatus
import kotobaya.contracts.cards.KanjiRequirement
import kotobaya.contracts.cards.NoteTypeInfo
import kotobaya.contracts.dict.KanjiPartSource
import kotobaya.contracts.dict.KanjiParts
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
import kotobaya.dict.DefaultKanjiParts
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
    /** ADR-042: the dictionary facts of `KanjiRequirement` (a shared cache in the app). */
    private val kanjiSource: KanjiSource = KanjiSource(dict),
    /** ADR-047: the device table of generated speech; null = nothing generated (a `tts` item reports `blob_hash: null`). */
    private val wordAudio: WordAudioLookup? = null,
) {
    private val voice: String by lazy { WordAudios.voice(t) }
    private val renderData = InstalledRenderData(dict)
    private val exemptions: Boolean by lazy {
        KanjiRequirement.exemptionsEnabled(t.get(LearningTable.SETTINGS, listOf(JsonString(KanjiRequirement.SETTING_KANJI_EXEMPTIONS)))?.get("value"))
    }
    private val noteTypes = HashMap<String, NoteTypeInfo?>()
    private val decks = HashMap<String, Row?>()

    /** Items for [cards] (card id to `review_type`) in order; a card that is gone (or its note) is dropped. */
    fun items(cards: List<Pair<String, String>>, nowMs: Long): List<JsonValue> = cards.mapNotNull { (id, type) -> item(id, type, nowMs) }

    private fun item(cardId: String, reviewType: String, nowMs: Long): JsonObject? {
        val card = CardRows.get(t, LearningTable.CARD, cardId) ?: return null
        val noteId = CardRows.str(card["note_id"]) ?: return null
        val note = CardRows.get(t, LearningTable.NOTE, noteId) ?: return null
        val cardJson = AnswerViews.card(card, note, leechThreshold)
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
        // ADR-041 / ADR-042: vocab notes only; the shared core decides both
        val vocab = CardRows.str(note["type"]) == "vocab"
        val entryForText = entryDetail.takeIf { entry != JsonNull }
        val word = if (vocab) wordTexts(note, entryForText) else null
        val furigana = word != null && frontFurigana(entrySeq, word.first)
        out["front_furigana"] = JsonBool(furigana)
        if (word != null) out["kanji_parts"] = guarded { kanjiParts(word.first, word.second) } ?: JsonArray(emptyList())
        // ARCH-CARD-TEXT: the core's plain-text render from the same inputs the item gives an HTML client
        textOf(note, kind, entryForText, kanjiDetail.takeIf { kanji != JsonNull }, picked?.third, furigana)?.let { out["text"] = it }
        out["word_audio"] = wordAudioOf(note, entryForText)
        out["preview"] = if (reviewType == "drill" || reviewType == "cram") JsonNull else preview(card, nowMs)
        return JsonObject(out)
    }

    // ---- card ---------------------------------------------------------------------------------------------------------

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

    private fun statusOf(seq: Long): WordStatus = wordStatus(t.get(LearningTable.WORD_STATUS, listOf(num(seq))))

    private fun wordStatus(row: Row?): WordStatus {
        val wire = CardRows.str(row?.get("status"))
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
            .mapNotNull { id -> decks.getOrPut(id) { CardRows.get(t, LearningTable.DECK, id) } }
            .filter { isNull(it["deleted_at"]) && (smartToo || CardRows.str(it["type"]) != "smart") }
            .sortedWith(compareBy<Row>({ (it["priority"] as? JsonNumber)?.value ?: 0.0 }, { CardRows.str(it["id"]).orEmpty() }))
            .toList()

    // ---- kanji examples (C-25) ------------------------------------------------------------------------------------------

    /** The core picker over the installed dictionary's words with the kanji and the replica's status / note / deck. */
    private fun kanjiExamples(literal: String, kanji: KanjiDetail?): JsonArray {
        val words = InstalledKanjiWords(dict).words(literal)
        // one IN read per table for all candidates (chunks of 500), not three lookups per word
        val keys = words.map { num(it.seq) }
        val sql = t as? SqlLearningTables
        val statuses = sql?.whereIn(LearningTable.WORD_STATUS, "entry_seq", keys)?.associateBy { seq(it["entry_seq"]) }
        val notes = sql?.whereIn(LearningTable.NOTE, "entry_seq", keys)?.filter { isNull(it["deleted_at"]) }?.groupBy { seq(it["entry_seq"]) }
        val items = sql?.whereIn(LearningTable.DECK_ITEM, "entry_seq", keys)?.groupBy { seq(it["entry_seq"]) }
        val candidates = words.map { w ->
            KanjiExampleCandidate(
                seq = w.seq,
                headword = w.headword,
                reading = w.reading,
                freqRank = w.freqRank,
                status = if (statuses != null) wordStatus(statuses[w.seq]) else statusOf(w.seq),
                hasNote = if (notes != null) notes[w.seq].orEmpty().isNotEmpty() else liveNote("entry_seq", num(w.seq)) != null,
                inDecks = if (items != null) decksOf(items[w.seq].orEmpty(), smartToo = true).isNotEmpty() else entryDecks(w.seq).isNotEmpty(),
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

    // ---- kanji breakdown and furigana front (ADR-041, ADR-042) ---------------------------------------------------------

    /**
     * The plain `expression` and `reading` texts of a vocab note as the core text renderer resolves them (a non-blank
     * field wins, else the entry's headword / reading): the front of `meaning` is `expression[kanji]`, `reading` is
     * `reading[kana]`, over the built-in vocab type so a custom template cannot change them.
     */
    private fun wordTexts(note: Row, entry: DictEntryDetail?): Pair<String, String> {
        val out = DefaultCardTextRenderer.renderText(
            TextRenderInput(
                noteType = BuiltInNoteTypes.VOCAB.info,
                kind = "meaning",
                fields = CardRows.fieldsOf(note),
                dictionary = entry?.let(::textDictionary),
                cloze = null,
            ),
        )
        val fields = CardRows.fieldsOf(note)
        // the same core text calls as the server's KanjiNeeds: expression in KANJI mode, reading in KANA mode
        return WordText.expression(fields["expression"], null).ifEmpty { out.front } to WordText.reading(fields["reading"], out.reading)
    }

    /** `KanjiRequirement.evaluate(...).frontFurigana` for the displayed [expression] of the note's entry. */
    private fun frontFurigana(entrySeq: Long?, expression: String): Boolean {
        val all = DefaultCardGenerator.kanjiOf(expression)
        if (all.isEmpty()) return false
        return DefaultKanjiRequirement.evaluate(kanjiSource.form(entrySeq, expression), all.map(kanjiSource::facts), exemptions).frontFurigana
    }

    /** `QueueItem.kanji_parts`: core `KanjiParts.build` over the local dictionary and the user's kanji notes. */
    private fun kanjiParts(expression: String, reading: String): JsonArray {
        val literals = DefaultCardGenerator.kanjiOf(expression).take(KanjiParts.MAX_PARTS)
        val sources = literals.map { l ->
            val k = dict.kanji(l)
            KanjiPartSource(KanjiReadings(l, k?.onReadings.orEmpty(), k?.kunReadings.orEmpty()), k?.meanings.orEmpty(), noteKeyword(l))
        }
        return JsonArray(
            DefaultKanjiParts.build(expression, reading, sources).map { p ->
                obj(
                    "literal" to JsonString(p.literal),
                    "keyword" to (p.keyword?.let(::JsonString) ?: JsonNull),
                    "meanings" to strings(p.meanings),
                    "on" to strings(p.on),
                    "kun" to strings(p.kun),
                    "used_reading" to (p.usedReading?.let { u -> obj("type" to JsonString(u.type.wire), "reading" to JsonString(u.reading), "kana" to JsonString(u.kana)) } ?: JsonNull),
                )
            },
        )
    }

    /** Plain text of the `keyword` field of the earliest live kanji note of [literal] by `(created_at, id)`; null when blank. */
    private fun noteKeyword(literal: String): String? {
        val note = t.where(LearningTable.NOTE, "kanji_literal", JsonString(literal)).filter { CardRows.live(it) && CardRows.str(it["type"]) == "kanji" }
            .minWithOrNull(compareBy<Row>({ (it["created_at"] as? JsonNumber)?.value ?: 0.0 }, { CardRows.str(it["id"]).orEmpty() })) ?: return null
        return FieldPlainText.of(CardRows.fieldsOf(note)["keyword"], PlainTextMode.KANJI).takeIf { it.isNotEmpty() }
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
    private fun textOf(note: Row, kind: String, entry: DictEntryDetail?, kanji: KanjiDetail?, cloze: ClozeSentence?, furigana: Boolean): JsonObject? {
        val info = noteInfo(note) ?: return null
        val out = DefaultCardTextRenderer.renderText(
            TextRenderInput(
                noteType = info,
                kind = kind,
                fields = CardRows.fieldsOf(note),
                dictionary = entry?.let(::textDictionary),
                cloze = cloze,
                frontFurigana = furigana,
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

    private fun noteInfo(note: Row): NoteTypeInfo? {
        val typeId = CardRows.str(note["note_type_id"])
        return typeId?.let { id -> noteTypes.getOrPut(id) { CardRows.get(t, LearningTable.NOTE_TYPE, id)?.let(CardRows::noteTypeInfo) } }
            ?: BuiltInNoteTypes.ALL.firstOrNull { it.info.baseType.wire == CardRows.str(note["type"]) }?.info
    }

    /**
     * `QueueItem.word_audio` (ADR-047, SCHEMA.md 6.6 "Server" rule 1 on the device): the core resolver over the note and
     * the L-7 dictionary data; a `tts` item's hash comes from the device table via [wordAudio]. Enqueues nothing.
     */
    private fun wordAudioOf(note: Row, entry: DictEntryDetail?): JsonValue {
        val info = noteInfo(note) ?: return JsonNull
        return when (val a = WordAudios.resolve(info, CardRows.fieldsOf(note), entry?.let(WordAudios::dictionary))) {
            null -> JsonNull
            is WordAudio.Note -> obj("source" to JsonString("note"), "blob_hash" to JsonString(a.blobHash), "text" to JsonNull, "voice" to JsonNull)
            is WordAudio.Tts -> obj(
                "source" to JsonString("tts"),
                "blob_hash" to (guarded { wordAudio?.blobHash(voice, a.text) }?.let(::JsonString) ?: JsonNull),
                "text" to JsonString(a.text),
                "voice" to JsonString(voice),
            )
        }
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
        val HTML_TAG = Regex("<[^>]*>")
        val WHITESPACE = Regex("\\s+")
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
