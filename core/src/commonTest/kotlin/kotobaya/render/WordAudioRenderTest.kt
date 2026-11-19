package kotobaya.render

import kotobaya.cards.BuiltInNoteTypes
import kotobaya.contracts.cards.NoteTypeInfo
import kotobaya.contracts.cards.TemplateInfo
import kotobaya.contracts.js.JsNoteType
import kotobaya.contracts.js.JsRenderInput
import kotobaya.contracts.render.CardSide
import kotobaya.contracts.render.DictionaryData
import kotobaya.contracts.render.FuriganaSegment
import kotobaya.contracts.render.RenderInput
import kotobaya.contracts.render.RenderOptions
import kotobaya.contracts.render.RenderedCard
import kotobaya.core.kotobayaCore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Word audio" on `CardRenderer` (ADR-047, C-29): block W, `{{audio:word}}`, `wordAudio` / `autoplayAudio`. */
class WordAudioRenderTest {
    private val d = DictionaryData(
        expression = "食べる",
        reading = "たべる",
        furigana = listOf(FuriganaSegment("食", "た"), FuriganaSegment("べる", null)),
        glosses = listOf("to eat"),
        pitchDrops = listOf(2),
    )
    private val gen = "b5bb9d8014a0f9b1d61e21e796d78dccdf1352f23cd32812f4850b878ae4944c"
    private val own = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
    private val vocab = BuiltInNoteTypes.VOCAB.info
    private val plain = RenderOptions(furigana = false, pitch = false, showMnemonic = false, mediaUrlTemplate = RenderFixtures.MEDIA)
    private val vocabKinds = listOf("meaning", "reading", "listening", "production", "pitch")

    private fun render(
        kind: String,
        side: CardSide,
        fields: Map<String, String> = emptyMap(),
        wordAudio: String? = null,
        type: NoteTypeInfo = vocab,
        options: RenderOptions = plain,
        frontFurigana: Boolean = false,
    ): RenderedCard =
        DefaultCardRenderer.render(RenderInput(type, kind, fields, "", d, null, side, options, null, frontFurigana = frontFurigana, wordAudio = wordAudio))

    private fun upToDivider(r: RenderedCard) = r.html.substringBefore("<hr class=\"kb-divider\">")
    private fun button(h: String) = "<button class=\"kb-audio\" data-kb-audio=\"$h\"></button>"
    private fun custom(front: String, back: String, base: NoteTypeInfo = vocab) =
        base.copy(templates = listOf(TemplateInfo("meaning", true, front, back)))

    // --- the four worked examples ---

    @Test
    fun deckMaterializedNoteWithAGeneratedHash() {
        val back = render("meaning", CardSide.BACK, wordAudio = gen)
        assertEquals(
            "<div class=\"kb-card kb-vocab kb-kind-meaning kb-back\"><div class=\"kb-expression\"><span class=\"kb-field-expression\">食べる</span>${button(gen)}</div>",
            upToDivider(back),
        )
        assertEquals(gen, back.wordAudio)
        assertEquals(gen, back.autoplayAudio)
        assertEquals(listOf(gen), back.audio)
        val front = render("meaning", CardSide.FRONT, wordAudio = gen)
        assertEquals(render("meaning", CardSide.FRONT).html, front.html)
        assertNull(front.wordAudio)
        assertNull(front.autoplayAudio)
        assertEquals(emptyList(), front.audio)
    }

    @Test
    fun aNoteWithItsOwnAudioIgnoresTheGeneratedHash() {
        for (wa in listOf(null, gen)) {
            val back = render("meaning", CardSide.BACK, mapOf("audio" to "[sound:$own]"), wordAudio = wa)
            assertEquals(
                "<div class=\"kb-card kb-vocab kb-kind-meaning kb-back\"><div class=\"kb-expression\"><span class=\"kb-field-expression\">食べる</span>" +
                    "<span class=\"kb-field-audio\">${button(own)}</span></div>",
                upToDivider(back),
            )
            assertEquals(own, back.wordAudio)
            assertEquals(own, back.autoplayAudio)
            assertEquals(listOf(own), back.audio)
            val front = render("meaning", CardSide.FRONT, mapOf("audio" to "[sound:$own]"), wordAudio = wa)
            assertNull(front.wordAudio)
            assertNull(front.autoplayAudio)
        }
    }

    @Test
    fun notGeneratedYetGivesNoButton() {
        val back = render("meaning", CardSide.BACK)
        assertEquals(
            "<div class=\"kb-card kb-vocab kb-kind-meaning kb-back\"><div class=\"kb-expression\"><span class=\"kb-field-expression\">食べる</span></div>",
            upToDivider(back),
        )
        assertNull(back.wordAudio)
        assertNull(back.autoplayAudio)
    }

    @Test
    fun listeningPlaysOnTheFrontAndNotAgainOnTheBack() {
        val front = render("listening", CardSide.FRONT, wordAudio = gen)
        assertEquals("<div class=\"kb-card kb-vocab kb-kind-listening kb-front\">${button(gen)}</div>", front.html)
        assertEquals(gen, front.wordAudio)
        assertEquals(gen, front.autoplayAudio)
        val back = render("listening", CardSide.BACK, wordAudio = gen)
        assertEquals(gen, back.wordAudio)
        assertNull(back.autoplayAudio)
        assertEquals(1, Regex("data-kb-audio").findAll(back.html).count(), back.html) // no second W in kb-expression
        assertTrue(back.html.startsWith("<div class=\"kb-card kb-vocab kb-kind-listening kb-back\">${button(gen)}<hr class=\"kb-divider\">"), back.html)
    }

    // --- byte for byte before ADR-047 ---

    @Test
    fun noAudioAndNoHashGivesThePreAdr047OutputForAllFiveVocabKinds() {
        val note = RenderFixtures.NOTES.first()
        val noAudio = RenderFixtures.Note(note.type, note.fields - "audio", note.dictionary)
        val oldSpan = "<span class=\"kb-field-audio\">${button(RenderFixtures.AUDIO)}</span>"
        for (kind in vocabKinds) for (side in CardSide.entries) {
            val key = RenderFixtures.key(note, kind, side)
            val expected = RENDER_GOLDENS.getValue(key).html.replace(oldSpan, "")
            val r = DefaultCardRenderer.render(RenderFixtures.input(noAudio, kind, side))
            assertEquals(expected, r.html, key)
            assertNull(r.wordAudio, key)
            assertNull(r.autoplayAudio, key)
            val empty = DefaultCardRenderer.render(RenderFixtures.input(RenderFixtures.Note(note.type, note.fields + ("audio" to ""), note.dictionary), kind, side))
            assertEquals(expected, empty.html, key)
        }
    }

    // --- placement in the built-in templates ---

    @Test
    fun builtInBacksCarryWInsideTheExpressionOnce() {
        for (kind in listOf("meaning", "reading", "production", "pitch")) {
            val back = render(kind, CardSide.BACK, wordAudio = gen)
            assertEquals(1, Regex("data-kb-audio").findAll(back.html).count(), kind)
            assertTrue("<span class=\"kb-field-expression\">食べる</span>${button(gen)}</div>" in back.html, back.html)
            assertEquals(gen, back.wordAudio, kind)
            assertEquals(gen, back.autoplayAudio, kind)
            val front = render(kind, CardSide.FRONT, wordAudio = gen)
            assertEquals(render(kind, CardSide.FRONT).html, front.html, kind)
            assertNull(front.wordAudio, kind)
            assertNull(front.autoplayAudio, kind)
        }
    }

    @Test
    fun wFollowsTheFuriganaFrontRuby() {
        val back = render("meaning", CardSide.BACK, mapOf("expression" to "寿司", "reading" to "すし"), wordAudio = gen, frontFurigana = true)
        assertTrue(
            back.html.startsWith(
                "<div class=\"kb-card kb-vocab kb-kind-meaning kb-back\"><div class=\"kb-expression\"><span class=\"kb-field-expression\"><ruby>寿司<rt>すし</rt></ruby></span>${button(gen)}</div>",
            ),
            back.html,
        )
    }

    @Test
    fun anAudioFieldWithoutASoundIsNotShownByTheBuiltInVocabTemplates() {
        val back = render("meaning", CardSide.BACK, mapOf("audio" to "tabemono.mp3"), wordAudio = gen)
        assertTrue("tabemono" !in back.html, back.html)
        assertEquals(gen, back.wordAudio)
        val upper = render("meaning", CardSide.BACK, mapOf("audio" to "[sound:${own.uppercase()}]"))
        assertTrue("data-kb-audio" !in upper.html, upper.html)
    }

    @Test
    fun twoSoundsInTheAudioFieldShowBothAndTheFirstIsTheWordAudio() {
        val back = render("meaning", CardSide.BACK, mapOf("audio" to "[sound:$own] [sound:$gen]"))
        assertEquals(own, back.wordAudio)
        assertEquals(listOf(own, gen), back.audio)
    }

    @Test
    fun sentenceAudioIsNeverTheWordAudio() {
        val back = render("meaning", CardSide.BACK, mapOf("sentence_audio" to "[sound:$own]"))
        assertNull(back.wordAudio)
        assertNull(back.autoplayAudio)
        assertEquals(listOf(own), back.audio)
    }

    @Test
    fun anInvalidHashIsTreatedAsNull() {
        for (bad in listOf("", "abc", gen.uppercase(), gen + "0", "\"><img src=x>", " $gen")) {
            assertEquals(render("meaning", CardSide.BACK).html, render("meaning", CardSide.BACK, wordAudio = bad).html, bad)
        }
    }

    @Test
    fun otherBaseTypesIgnoreTheHash() {
        for (note in RenderFixtures.NOTES.drop(1)) for (t in note.type.info.templates) for (side in CardSide.entries) {
            val plainOut = DefaultCardRenderer.render(RenderFixtures.input(note, t.kind, side))
            val withHash = DefaultCardRenderer.render(RenderFixtures.input(note, t.kind, side).copy(wordAudio = gen))
            assertEquals(plainOut, withHash, RenderFixtures.key(note, t.kind, side))
            assertNull(withHash.wordAudio)
            assertNull(withHash.autoplayAudio)
        }
    }

    // --- custom templates ---

    @Test
    fun customTemplatesPlaceWWithTheAudioWordPlaceholder() {
        val type = custom("{{expression}}{{audio:word}}", "{{FrontSide}}<hr class=\"kb-divider\">{{meaning}}{{audio:word}}")
        val front = render("meaning", CardSide.FRONT, wordAudio = gen, type = type)
        assertEquals("<div class=\"kb-card kb-vocab kb-kind-meaning kb-front\"><span class=\"kb-field-expression\">食べる</span>${button(gen)}</div>", front.html)
        assertEquals(gen, front.wordAudio)
        assertEquals(gen, front.autoplayAudio)
        val back = render("meaning", CardSide.BACK, wordAudio = gen, type = type)
        assertEquals(2, Regex("data-kb-audio").findAll(back.html).count())
        assertEquals(gen, back.wordAudio)
        assertNull(back.autoplayAudio) // already played with the question
        assertEquals(listOf(gen), back.audio)
        assertEquals(emptyList(), back.warnings)
    }

    @Test
    fun customBackOnlyWAutoplaysOnReveal() {
        val type = custom("{{expression}}", "{{FrontSide}}<hr class=\"kb-divider\">{{audio:word}}")
        val back = render("meaning", CardSide.BACK, mapOf("audio" to "[sound:$own]"), type = type)
        assertEquals(own, back.wordAudio)
        assertEquals(own, back.autoplayAudio)
        assertTrue(back.html.endsWith("<hr class=\"kb-divider\"><span class=\"kb-field-audio\">${button(own)}</span></div>"), back.html)
    }

    @Test
    fun aWInAHiddenSectionIsNotEmitted() {
        val type = custom("{{expression}}", "{{#notes}}{{audio:word}}{{/notes}}{{meaning}}")
        val back = render("meaning", CardSide.BACK, wordAudio = gen, type = type)
        assertNull(back.wordAudio)
        assertNull(back.autoplayAudio)
        assertTrue("data-kb-audio" !in back.html)
    }

    @Test
    fun aBackWithoutFrontSideDoesNotCarryTheFrontsW() {
        val type = custom("{{audio:word}}", "{{meaning}}")
        val back = render("meaning", CardSide.BACK, wordAudio = gen, type = type)
        assertNull(back.wordAudio)
        assertNull(back.autoplayAudio)
    }

    @Test
    fun audioWordIsEmptyWithoutWarningForOtherBaseTypes() {
        val phrase = custom("{{expression}}{{audio:word}}", "{{audio:word}}", BuiltInNoteTypes.PHRASE.info)
        val r = DefaultCardRenderer.render(
            RenderInput(phrase, "meaning", mapOf("expression" to "x", "audio" to "[sound:$own]"), "", null, null, CardSide.FRONT, plain, null, wordAudio = gen),
        )
        assertEquals("<div class=\"kb-card kb-phrase kb-kind-meaning kb-front\"><span class=\"kb-field-expression\">x</span></div>", r.html)
        assertEquals(emptyList(), r.warnings)
        assertNull(r.wordAudio)
    }

    @Test
    fun otherAudioPrefixesAreReservedAndUnknown() {
        for (p in listOf("audio:Word", "audio:sentence", "audio:reading", "audio:", "audio:word_back", "wordaudio:back")) {
            val type = custom("{{expression}}{{$p}}", "{{FrontSide}}")
            val r = render("meaning", CardSide.FRONT, mapOf("audio" to "[sound:$own]"), wordAudio = gen, type = type, options = plain.copy(fieldRegions = true))
            assertEquals("<div class=\"kb-card kb-vocab kb-kind-meaning kb-front\"><span class=\"kb-field-expression\" data-field=\"expression\" data-field-source=\"dictionary\">食べる</span></div>", r.html, p)
            assertEquals(listOf("render.warning.unknown_placeholder"), r.warnings, p)
            assertEquals(listOf("expression"), r.fields.map { it.key }, p)
            assertNull(r.wordAudio, p)
        }
    }

    // --- field regions ---

    @Test
    fun withRegionsTheAudioFieldIsReferredAtWsPosition() {
        val regions = plain.copy(fieldRegions = true)
        val own = render("meaning", CardSide.BACK, mapOf("audio" to "[sound:$own]"), options = regions)
        assertEquals(listOf("expression:dictionary:true", "audio:note:true"), own.fields.take(2).map { "${it.key}:${it.source.wire}:${it.rendered}" })
        assertTrue("<span class=\"kb-field-audio\" data-field=\"audio\" data-field-source=\"note\">" in own.html, own.html)
        val generated = render("meaning", CardSide.BACK, wordAudio = gen, options = regions)
        assertEquals(listOf("expression:dictionary:true", "audio:empty:false"), generated.fields.take(2).map { "${it.key}:${it.source.wire}:${it.rendered}" })
        assertTrue("食べる</span>${button(gen)}</div>" in generated.html, generated.html) // bare: no region
        val front = render("meaning", CardSide.FRONT, wordAudio = gen, options = regions)
        assertTrue("audio" !in front.fields.map { it.key }, front.fields.toString())
    }

    // --- JS facade ---

    @Test
    fun coreJsPassesWordAudioAndReturnsBothMembers() {
        fun input(side: String, wordAudio: String?) = JsRenderInput(
            noteType = JsNoteType(vocab.baseType.wire, vocab.fieldKeys.toTypedArray(), emptyArray(), vocab.css),
            kind = "listening",
            fields = emptyArray(),
            mnemonic = "",
            dictionary = null,
            cloze = null,
            side = side,
            furigana = false,
            pitch = false,
            showMnemonic = false,
            mediaUrlTemplate = RenderFixtures.MEDIA,
            wordAudio = wordAudio,
        )
        val front = kotobayaCore().renderCard(input("front", gen))
        assertEquals(gen, front.wordAudio)
        assertEquals(gen, front.autoplayAudio)
        val back = kotobayaCore().renderCard(input("back", gen))
        assertEquals(gen, back.wordAudio)
        assertNull(back.autoplayAudio)
        val none = kotobayaCore().renderCard(input("front", null))
        assertNull(none.wordAudio)
        assertNull(none.autoplayAudio)
        assertEquals(gen, input("front", gen).toRenderInput().wordAudio)
    }

    @Test
    fun outputPassesTheStrictChecker() {
        for (kind in vocabKinds) for (side in CardSide.entries) {
            HtmlChecker.assertSafe(render(kind, side, wordAudio = gen).html, RenderFixtures.MEDIA)
            HtmlChecker.assertSafe(render(kind, side, mapOf("audio" to "[sound:$own]")).html, RenderFixtures.MEDIA)
        }
    }
}
