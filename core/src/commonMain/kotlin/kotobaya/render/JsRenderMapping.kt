package kotobaya.render

import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.cards.NoteTypeInfo
import kotobaya.contracts.cards.TemplateInfo
import kotobaya.contracts.js.JsRenderInput
import kotobaya.contracts.js.JsRenderedCard
import kotobaya.contracts.render.CardSide
import kotobaya.contracts.render.ClozeSentence
import kotobaya.contracts.render.DictionaryData
import kotobaya.contracts.render.FuriganaSegment
import kotobaya.contracts.render.KanjiData
import kotobaya.contracts.render.RenderInput
import kotobaya.contracts.render.RenderOptions
import kotobaya.contracts.render.RenderedCard

/** `CoreJs.renderCard`: the flattened JS input to [RenderInput], rendered, and back. */
internal fun renderJs(input: JsRenderInput): JsRenderedCard = DefaultCardRenderer.render(input.toRenderInput()).toJs()

/** Maps the JS shape; unknown `baseType` / `side` values are host bugs and throw [IllegalArgumentException]. */
internal fun JsRenderInput.toRenderInput(): RenderInput {
    val nt = noteType
    val base = NoteBaseType.entries.firstOrNull { it.wire == nt.baseType }
        ?: throw IllegalArgumentException("unknown base type: ${nt.baseType}")
    val cardSide = when (side) {
        "front" -> CardSide.FRONT
        "back" -> CardSide.BACK
        else -> throw IllegalArgumentException("unknown side: $side")
    }
    val fieldMap = LinkedHashMap<String, String>()
    for (f in fields) fieldMap[f.key] = f.value // duplicate keys: the last wins
    return RenderInput(
        noteType = NoteTypeInfo(
            baseType = base,
            fieldKeys = nt.fieldKeys.toList(),
            templates = nt.templates.map { TemplateInfo(it.kind, it.isDefault, it.front, it.back) },
            css = nt.css,
        ),
        kind = kind,
        fields = fieldMap,
        mnemonic = mnemonic,
        dictionary = dictionary?.let { d ->
            DictionaryData(
                expression = d.expression,
                reading = d.reading,
                furigana = d.furigana.map { FuriganaSegment(it.text, it.reading) },
                glosses = d.glosses.toList(),
                pitchDrops = d.pitchDrops.toList(),
            )
        },
        cloze = cloze?.let { ClozeSentence(it.sentenceId, it.text, it.start, it.end, it.translation) },
        side = cardSide,
        options = RenderOptions(furigana, pitch, showMnemonic, mediaUrlTemplate),
        kanji = kanji?.let { KanjiData(it.literal, it.meanings.toList(), it.onyomi.toList(), it.kunyomi.toList(), it.strokeCount) },
    )
}

internal fun RenderedCard.toJs(): JsRenderedCard =
    JsRenderedCard(html, audio.toTypedArray(), extraCss, warnings.toTypedArray())
