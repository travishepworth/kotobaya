package kotobaya.render

import kotobaya.contracts.js.JsCloze
import kotobaya.contracts.js.JsDictionary
import kotobaya.contracts.js.JsField
import kotobaya.contracts.js.JsFurigana
import kotobaya.contracts.js.JsKanji
import kotobaya.contracts.js.JsNoteType
import kotobaya.contracts.js.JsRenderInput
import kotobaya.contracts.js.JsTemplate
import kotobaya.contracts.render.CardSide
import kotobaya.core.kotobayaCore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CoreJsRenderTest {
    private fun jsInput(note: RenderFixtures.Note, kind: String, side: String, fields: Array<JsField>? = null): JsRenderInput {
        val info = note.type.info
        val d = note.dictionary
        val k = note.kanji
        val c = RenderFixtures.CLOZE
        return JsRenderInput(
            noteType = JsNoteType(
                info.baseType.wire,
                info.fieldKeys.toTypedArray(),
                info.templates.map { JsTemplate(it.kind, it.isDefault, it.front, it.back) }.toTypedArray(),
                info.css,
            ),
            kind = kind,
            fields = fields ?: note.fields.map { JsField(it.key, it.value) }.toTypedArray(),
            mnemonic = "食 = person + good\nfood is <good>",
            dictionary = d?.let {
                JsDictionary(
                    it.expression,
                    it.reading,
                    it.furigana.map { s -> JsFurigana(s.text, s.reading) }.toTypedArray(),
                    it.glosses.toTypedArray(),
                    it.pitchDrops.toTypedArray(),
                )
            },
            cloze = if (info.baseType.wire == "grammar") JsCloze(c.sentenceId, c.text, c.start, c.end, c.translation) else null,
            side = side,
            furigana = true,
            pitch = true,
            showMnemonic = true,
            mediaUrlTemplate = RenderFixtures.MEDIA,
            kanji = k?.let { JsKanji(it.literal, it.meanings.toTypedArray(), it.onyomi.toTypedArray(), it.kunyomi.toTypedArray(), it.strokeCount) },
        )
    }

    @Test
    fun renderCardMatchesTheGoldensForEveryBuiltInKind() {
        for ((note, kind, side) in RenderFixtures.goldenCases()) {
            val key = RenderFixtures.key(note, kind, side)
            val out = kotobayaCore().renderCard(jsInput(note, kind, side.name.lowercase()))
            val golden = RENDER_GOLDENS.getValue(key)
            assertEquals(golden.html, out.html, key)
            assertEquals(golden.audio, out.audio.toList(), key)
            assertEquals(golden.warnings, out.warnings.toList(), key)
        }
    }

    @Test
    fun duplicateFieldKeysLastWins() {
        val note = RenderFixtures.NOTES.last()
        val out = kotobayaCore().renderCard(jsInput(note, "basic", "front", arrayOf(JsField("front", "first"), JsField("front", "last"))))
        assertEquals("<div class=\"kb-card kb-custom kb-kind-basic kb-front\"><span class=\"kb-field-front\">last</span></div>", out.html)
    }

    @Test
    fun unknownSideOrBaseTypeIsAHostBug() {
        val note = RenderFixtures.NOTES.last()
        assertFailsWith<IllegalArgumentException> { kotobayaCore().renderCard(jsInput(note, "basic", "middle")) }
        val bad = jsInput(note, "basic", "front").let {
            JsRenderInput(
                JsNoteType("weird", it.noteType.fieldKeys, it.noteType.templates, ""), it.kind, it.fields, it.mnemonic,
                null, null, it.side, true, true, true, it.mediaUrlTemplate,
            )
        }
        assertFailsWith<IllegalArgumentException> { kotobayaCore().renderCard(bad) }
        assertEquals(CardSide.FRONT, jsInput(note, "basic", "front").toRenderInput().side)
    }
}
