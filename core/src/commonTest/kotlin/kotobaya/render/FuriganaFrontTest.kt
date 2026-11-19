package kotobaya.render

import kotobaya.cards.BuiltInNoteTypes
import kotobaya.contracts.js.JsDictionary
import kotobaya.contracts.js.JsNoteType
import kotobaya.contracts.js.JsRenderInput
import kotobaya.contracts.js.JsTemplate
import kotobaya.contracts.render.CardSide
import kotobaya.contracts.render.DictionaryData
import kotobaya.contracts.render.FuriganaSegment
import kotobaya.contracts.render.RenderInput
import kotobaya.contracts.render.RenderOptions
import kotobaya.contracts.render.TextRenderInput
import kotobaya.core.kotobayaCore
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ADR-042 "Furigana front": the HTML front shows `<ruby>word<rt>reading</rt></ruby>` over the whole word, the
 * text front `word（reading）`; with the flag off every render is byte-identical to the goldens.
 */
class FuriganaFrontTest {
    private val sushi = DictionaryData("寿司", "すし", listOf(FuriganaSegment("寿司", "すし")), listOf("sushi"), listOf(2))
    private val coffee = DictionaryData("珈琲", "コーヒー", listOf(FuriganaSegment("珈琲", "コーヒー")), listOf("coffee"), listOf(3))
    private val vocab = BuiltInNoteTypes.VOCAB.info
    private val plain = RenderOptions(furigana = true, pitch = true, showMnemonic = false, mediaUrlTemplate = RenderFixtures.MEDIA)

    private fun html(d: DictionaryData?, kind: String, side: CardSide, on: Boolean, fields: Map<String, String> = emptyMap(), options: RenderOptions = plain) =
        DefaultCardRenderer.render(RenderInput(vocab, kind, fields, "", d, null, side, options, null, frontFurigana = on)).html

    private fun text(d: DictionaryData?, kind: String, on: Boolean, fields: Map<String, String> = emptyMap()) =
        DefaultCardTextRenderer.renderText(TextRenderInput(vocab, kind, fields, d, null, null, frontFurigana = on))

    @Test
    fun htmlGoldens() {
        assertEquals(
            """<div class="kb-card kb-vocab kb-kind-meaning kb-front"><div class="kb-expression"><span class="kb-field-expression"><ruby>寿司<rt>すし</rt></ruby></span></div></div>""",
            html(sushi, "meaning", CardSide.FRONT, on = true),
        )
        assertEquals(
            """<div class="kb-card kb-vocab kb-kind-meaning kb-front"><div class="kb-expression"><span class="kb-field-expression"><ruby>珈琲<rt>コーヒー</rt></ruby></span></div></div>""",
            html(coffee, "meaning", CardSide.FRONT, on = true),
        )
        // The back repeats the front unchanged.
        val back = html(sushi, "meaning", CardSide.BACK, on = true)
        assertEquals(true, back.startsWith("""<div class="kb-card kb-vocab kb-kind-meaning kb-back"><div class="kb-expression"><span class="kb-field-expression"><ruby>寿司<rt>すし</rt></ruby></span></div><hr class="kb-divider">"""), back)
        // Pitch: the expression carries the ruby, the separate reading line stays.
        assertEquals(
            """<div class="kb-card kb-vocab kb-kind-pitch kb-front"><div class="kb-expression"><span class="kb-field-expression"><ruby>寿司<rt>すし</rt></ruby></span></div><div class="kb-reading"><span class="kb-field-reading">すし</span></div></div>""",
            html(sushi, "pitch", CardSide.FRONT, on = true),
        )
        // Independent of the user's furigana switch.
        assertEquals(html(sushi, "meaning", CardSide.FRONT, on = true), html(sushi, "meaning", CardSide.FRONT, on = true, options = plain.copy(furigana = false)))
        // Field regions wrap the ruby as they wrap the plain value.
        assertEquals(
            """<div class="kb-card kb-vocab kb-kind-meaning kb-front"><div class="kb-expression"><span class="kb-field-expression" data-field="expression" data-field-source="dictionary"><ruby>寿司<rt>すし</rt></ruby></span></div></div>""",
            html(sushi, "meaning", CardSide.FRONT, on = true, options = plain.copy(fieldRegions = true)),
        )
        HtmlChecker.assertSafe(html(sushi, "meaning", CardSide.BACK, on = true), RenderFixtures.MEDIA)
    }

    @Test
    fun markupAndEntitiesInExpressionAndReadingAreEscaped() {
        // CORE-28b: markup is dropped, entities decoded to text and the text escaped again inside the ruby.
        val fields = mapOf("expression" to "<img src=x onerror=alert(1)>a&amp;b", "reading" to "<b>え</b>&lt;い&gt;&amp;")
        val out = html(null, "meaning", CardSide.FRONT, on = true, fields = fields)
        assertEquals(
            """<div class="kb-card kb-vocab kb-kind-meaning kb-front"><div class="kb-expression"><span class="kb-field-expression"><ruby>a&amp;b<rt>え&lt;い&gt;&amp;</rt></ruby></span></div></div>""",
            out,
        )
        HtmlChecker.assertSafe(out, RenderFixtures.MEDIA)
        // A bare `&` and `<`-looking text in the values.
        val bare = html(null, "meaning", CardSide.FRONT, on = true, fields = mapOf("expression" to "a&b", "reading" to "x<y"))
        HtmlChecker.assertSafe(bare, RenderFixtures.MEDIA)
        assertEquals("a&b（え<い>&）", text(null, "meaning", on = true, fields = fields).front)
    }

    @Test
    fun textGoldens() {
        assertEquals("寿司（すし）", text(sushi, "meaning", on = true).front)
        assertEquals("珈琲（コーヒー）", text(coffee, "meaning", on = true).front)
        assertEquals("寿司（すし）\nすし", text(sushi, "pitch", on = true).front)
        assertEquals(text(sushi, "meaning", on = false).back, text(sushi, "meaning", on = true).back)
        assertEquals("寿司", text(sushi, "meaning", on = false).front)
    }

    @Test
    fun offIsByteIdenticalToTheGoldens() {
        for ((note, kind, side) in RenderFixtures.goldenCases()) {
            val key = RenderFixtures.key(note, kind, side)
            val out = DefaultCardRenderer.render(RenderFixtures.input(note, kind, side).copy(frontFurigana = false))
            assertEquals(RENDER_GOLDENS.getValue(key).html, out.html, key)
        }
    }

    @Test
    fun notAppliedToOtherKindsCustomTemplatesOtherTypesOrAnEmptyReading() {
        for ((note, kind, side) in RenderFixtures.goldenCases()) {
            if (note.type.info.baseType == vocab.baseType && kind in setOf("meaning", "pitch")) continue
            val key = RenderFixtures.key(note, kind, side)
            val out = DefaultCardRenderer.render(RenderFixtures.input(note, kind, side).copy(frontFurigana = true))
            assertEquals(RENDER_GOLDENS.getValue(key).html, out.html, key)
        }
        for (kind in listOf("reading", "production", "listening")) {
            for (side in CardSide.entries) {
                assertEquals(html(sushi, kind, side, on = false), html(sushi, kind, side, on = true), "$kind/$side")
            }
            assertEquals(text(sushi, kind, on = false), text(sushi, kind, on = true), kind)
        }
        // A custom front template is rendered as written.
        val custom = vocab.copy(templates = vocab.templates.map { if (it.kind == "meaning") it.copy(front = "<div>{{kanji:expression}}</div>") else it })
        fun customHtml(on: Boolean) =
            DefaultCardRenderer.render(RenderInput(custom, "meaning", emptyMap(), "", sushi, null, CardSide.FRONT, plain, null, frontFurigana = on)).html
        assertEquals(customHtml(false), customHtml(true))
        fun customText(on: Boolean) = DefaultCardTextRenderer.renderText(TextRenderInput(custom, "meaning", emptyMap(), sushi, null, null, frontFurigana = on))
        assertEquals(customText(false), customText(true))
        // An empty reading, or one equal to the expression: nothing to show.
        val noReading = mapOf("expression" to "寿司")
        assertEquals(html(null, "meaning", CardSide.FRONT, on = false, fields = noReading), html(null, "meaning", CardSide.FRONT, on = true, fields = noReading))
        assertEquals("寿司", text(null, "meaning", on = true, fields = noReading).front)
        val same = mapOf("expression" to "すし", "reading" to "すし")
        assertEquals(html(null, "meaning", CardSide.FRONT, on = false, fields = same), html(null, "meaning", CardSide.FRONT, on = true, fields = same))
        assertEquals("すし", text(null, "meaning", on = true, fields = same).front)
        // Another base type (phrase meaning) is never changed.
        val phrase = BuiltInNoteTypes.PHRASE.info
        fun phraseHtml(on: Boolean) =
            DefaultCardRenderer.render(RenderInput(phrase, "meaning", mapOf("expression" to "お疲れ様", "reading" to "おつかれさま"), "", null, null, CardSide.FRONT, plain, null, frontFurigana = on)).html
        assertEquals(phraseHtml(false), phraseHtml(true))
    }

    @Test
    fun theWebPassesTheFlagThroughCoreJs() {
        val js = JsRenderInput(
            noteType = JsNoteType(vocab.baseType.wire, vocab.fieldKeys.toTypedArray(), vocab.templates.map { JsTemplate(it.kind, it.isDefault, it.front, it.back) }.toTypedArray(), vocab.css),
            kind = "meaning",
            fields = emptyArray(),
            mnemonic = "",
            dictionary = JsDictionary("寿司", "すし", emptyArray(), arrayOf("sushi"), arrayOf(2)),
            cloze = null,
            side = "front",
            furigana = true,
            pitch = true,
            showMnemonic = false,
            mediaUrlTemplate = RenderFixtures.MEDIA,
            frontFurigana = true,
        )
        assertEquals(html(sushi.copy(furigana = emptyList()), "meaning", CardSide.FRONT, on = true), kotobayaCore().renderCard(js).html)
    }
}
