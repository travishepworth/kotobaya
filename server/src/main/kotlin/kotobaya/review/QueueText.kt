package kotobaya.review

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotobaya.cards.BuiltInNoteTypes
import kotobaya.contracts.cards.NoteTypeInfo
import kotobaya.contracts.render.ClozeSentence
import kotobaya.contracts.render.DictionaryData
import kotobaya.contracts.render.FuriganaSegment
import kotobaya.contracts.render.KanjiData
import kotobaya.contracts.render.TextRenderInput
import kotobaya.render.DefaultCardTextRenderer
import kotobaya.server.json

/**
 * ARCH-CARD-TEXT: `QueueItem.text`, the core's plain-text render of an item, from the same JSON the item carries
 * (`card.note`, `entry`, `kanji`, `grammar_sentence`). The mapping to the core's inputs is the web review
 * screen's (`web/src/lib/review/model.ts`), so the text says what the web shows.
 */
internal object QueueText {
    /**
     * `CardText` JSON for one item. [noteType] null (row missing) falls back to the built-in type of `note.type`; null
     * when that is unknown too (the item then has no `text`, the rest of the page is unaffected).
     */
    fun of(noteType: NoteTypeInfo?, card: JsonNode, entry: JsonNode?, kanji: JsonNode?, grammarSentence: JsonNode?, frontFurigana: Boolean = false): ObjectNode? {
        val note = card["note"]
        val info = noteType ?: BuiltInNoteTypes.ALL.firstOrNull { it.info.baseType.wire == note["type"]?.asText() }?.info ?: return null
        val fields = LinkedHashMap<String, String>()
        note["fields"]?.fields()?.forEach { (k, v) -> if (v.isTextual) fields[k] = v.asText() }
        val out = DefaultCardTextRenderer.renderText(
            TextRenderInput(
                noteType = info,
                kind = card["kind"].asText(),
                fields = fields,
                dictionary = entry.present()?.let(::dictionaryData),
                cloze = grammarSentence.present()?.let(::cloze),
                kanji = kanji.present()?.let(::kanjiData),
                frontFurigana = frontFurigana,
            ),
        )
        return json.createObjectNode()
            .put("front", out.front)
            .put("back", out.back)
            .put("reading", out.reading)
            .put("sentence", out.sentence)
    }

    private fun JsonNode?.present(): JsonNode? = this?.takeUnless { it.isNull || it.isMissingNode }

    private fun strings(n: JsonNode?): List<String> = n?.mapNotNull { it.takeIf(JsonNode::isTextual)?.asText() }.orEmpty()

    /** Headword, its reading over the whole headword, every gloss of every sense in order, the headword reading's pitch (none without a row for it). */
    fun dictionaryData(e: JsonNode): DictionaryData {
        val headword = e["headword"].asText()
        val reading = e["headword_reading"].asText()
        val pitch = e["pitch"]?.let { p -> p.firstOrNull { it["reading"]?.asText() == reading } }
        return DictionaryData(
            expression = headword,
            reading = reading,
            furigana = listOf(FuriganaSegment(headword, reading.takeUnless { it == headword })),
            glosses = e["senses"]?.flatMap { s -> s["gloss"]?.mapNotNull { g -> g["text"]?.takeIf(JsonNode::isTextual)?.asText() }.orEmpty() }.orEmpty(),
            pitchDrops = pitch?.get("positions")?.map(JsonNode::asInt).orEmpty(),
        )
    }

    fun kanjiData(k: JsonNode): KanjiData = KanjiData(
        literal = k["literal"].asText(),
        meanings = strings(k["meanings"]),
        onyomi = strings(k["on_readings"]),
        kunyomi = strings(k["kun_readings"]),
        strokeCount = k["stroke_count"]?.takeIf { it.isInt }?.asInt(),
    )

    /** `GrammarCardSentence` → the renderer's cloze (`sentence_id`, `text`, `start_offset`, `end_offset`, `translation`). */
    fun cloze(g: JsonNode): ClozeSentence = ClozeSentence(
        sentenceId = g["sentence_id"].present()?.asText(),
        text = g["text"].asText(),
        start = g["start_offset"].asInt(),
        end = g["end_offset"].asInt(),
        translation = g["translation"].present()?.asText(),
    )
}
