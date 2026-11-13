package kotobaya.render

import kotobaya.cards.BuiltInNoteTypes
import kotobaya.contracts.cards.NoteTypeInfo
import kotobaya.contracts.cards.TemplateInfo
import kotobaya.contracts.js.JsField
import kotobaya.contracts.js.JsKanji
import kotobaya.contracts.js.JsNoteType
import kotobaya.contracts.js.JsRenderInput
import kotobaya.contracts.js.JsTemplate
import kotobaya.contracts.render.CardSide
import kotobaya.contracts.render.DictionaryData
import kotobaya.contracts.render.FieldSource
import kotobaya.contracts.render.KanjiData
import kotobaya.contracts.render.RenderInput
import kotobaya.contracts.render.RenderOptions
import kotobaya.contracts.render.RenderedCard
import kotobaya.contracts.render.RenderedField
import kotobaya.core.kotobayaCore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What every output must satisfy whatever the note holds: each `data-field` sits in the renderer's
 * own wrapper start tag, names a key [RenderedCard.fields] lists as rendered, and carries that
 * entry's source; with the option off there is no `data-field` attribute and no entry at all.
 * (`data-field="` with a real quote can only be inside a tag: text and attribute values are escaped.)
 */
object RegionChecker {
    private val REGION = Regex("""<span class="kb-field-([a-z][a-z0-9_]*)" data-field="([a-z][a-z0-9_]*)" data-field-source="([a-z]+)">""")

    /** The regions of [html] as (key, source wire), in document order. */
    fun regions(html: String): List<Pair<String, String>> = REGION.findAll(html).map { it.groupValues[2] to it.groupValues[3] }.toList()

    fun assertConsistent(card: RenderedCard, on: Boolean, context: String) {
        val html = card.html
        val regions = regions(html)
        assertEquals(html.split("data-field=\"").size - 1, regions.size, "data-field outside a region wrapper: $context\n$html")
        assertEquals(html.split("data-field-source=\"").size - 1, regions.size, "data-field-source outside a region wrapper: $context\n$html")
        if (!on) {
            assertEquals(emptyList(), regions, "regions with the option off: $context\n$html")
            assertEquals(emptyList(), card.fields, "fields with the option off: $context")
            return
        }
        for (m in REGION.findAll(html)) assertEquals(m.groupValues[1], m.groupValues[2], "class and data-field disagree: $context\n$html")
        assertEquals(card.fields.map { it.key }.distinct(), card.fields.map { it.key }, "duplicate field entries: $context")
        val byKey = card.fields.associateBy { it.key }
        for ((key, source) in regions) {
            val f = byKey[key] ?: error("region $key without a fields entry: $context\n$html")
            assertTrue(f.rendered, "region $key but rendered=false: $context")
            assertEquals(f.source.wire, source, "source of region $key: $context")
            assertTrue(f.source != FieldSource.EMPTY, "empty field with a region: $context")
        }
        val withRegion = regions.map { it.first }.toSet()
        for (f in card.fields) assertEquals(f.key in withRegion, f.rendered, "rendered flag of ${f.key}: $context\n$html")
    }
}

/** C-19: `RenderOptions.fieldRegions`. Common code, so jvm and js assert the same strings. */
class FieldRegionTest {
    private val on = RenderOptions(furigana = true, pitch = false, showMnemonic = false, mediaUrlTemplate = "/b/{sha256}", fieldRegions = true)

    private fun custom(front: String, back: String = "{{FrontSide}}"): NoteTypeInfo =
        BuiltInNoteTypes.CUSTOM.info.copy(templates = listOf(TemplateInfo("basic", true, front, back)))

    private fun render(
        type: NoteTypeInfo,
        fields: Map<String, String>,
        kind: String = "basic",
        side: CardSide = CardSide.FRONT,
        options: RenderOptions = on,
        dictionary: DictionaryData? = null,
        kanji: KanjiData? = null,
    ): RenderedCard {
        val card = DefaultCardRenderer.render(RenderInput(type, kind, fields, "", dictionary, null, side, options, kanji))
        HtmlChecker.assertSafe(card.html, options.mediaUrlTemplate)
        RegionChecker.assertConsistent(card, options.fieldRegions, card.html)
        return card
    }

    private fun inner(r: RenderedCard): String = r.html.substringAfter('>').removeSuffix("</div>")

    private fun fields(r: RenderedCard): List<String> = r.fields.map { "${it.key}:${it.source.wire}:${it.rendered}" }

    // --- goldens ---

    @Test
    fun regionGoldensCoverTheSameCasesAsThePlainGoldens() {
        assertEquals(RENDER_GOLDENS.keys, RENDER_REGION_GOLDENS.keys)
    }

    @Test
    fun everyBuiltInNoteTypeAndKindMatchesItsRegionGolden() {
        val options = RenderFixtures.OPTIONS.copy(fieldRegions = true)
        for ((note, kind, side) in RenderFixtures.goldenCases()) {
            val key = RenderFixtures.key(note, kind, side)
            val golden = RENDER_REGION_GOLDENS.getValue(key)
            val plain = RENDER_GOLDENS.getValue(key)
            val actual = DefaultCardRenderer.render(RenderFixtures.input(note, kind, side, options))
            assertEquals(golden.html, actual.html, key)
            assertEquals(golden.fields, fields(actual), key)
            assertEquals(plain.audio, actual.audio, key)
            assertEquals(plain.warnings, actual.warnings, key)
            HtmlChecker.assertSafe(actual.html, RenderFixtures.MEDIA, key)
            RegionChecker.assertConsistent(actual, true, key)
        }
    }

    /** The option adds the two attributes to wrapper spans and changes nothing else. */
    @Test
    fun regionGoldensAreThePlainGoldensPlusTheTwoAttributes() {
        val attrs = Regex(""" data-field="[a-z][a-z0-9_]*" data-field-source="(note|dictionary|kanji)"""")
        for ((key, golden) in RENDER_REGION_GOLDENS) {
            assertEquals(RENDER_GOLDENS.getValue(key).html, attrs.replace(golden.html, ""), key)
        }
    }

    @Test
    fun optionOffIsTheDefaultAndGivesNoRegionsAndNoFields() {
        assertEquals(false, RenderFixtures.OPTIONS.fieldRegions)
        for ((note, kind, side) in RenderFixtures.goldenCases()) {
            val key = RenderFixtures.key(note, kind, side)
            val actual = DefaultCardRenderer.render(RenderFixtures.input(note, kind, side))
            assertEquals(RENDER_GOLDENS.getValue(key).html, actual.html, key)
            RegionChecker.assertConsistent(actual, false, key)
        }
    }

    @Test
    fun coreJsPassesTheOptionAndReturnsTheFields() {
        val note = RenderFixtures.NOTES.first { it.kanji != null }
        val info = note.type.info
        val k = RenderFixtures.SHOKU
        fun input(regions: Boolean?): JsRenderInput {
            val type = JsNoteType(
                info.baseType.wire,
                info.fieldKeys.toTypedArray(),
                info.templates.map { JsTemplate(it.kind, it.isDefault, it.front, it.back) }.toTypedArray(),
                info.css,
            )
            val f = note.fields.map { JsField(it.key, it.value) }.toTypedArray()
            val kanji = JsKanji(k.literal, k.meanings.toTypedArray(), k.onyomi.toTypedArray(), k.kunyomi.toTypedArray(), k.strokeCount)
            val m = "食 = person + good\nfood is <good>"
            return if (regions == null) {
                JsRenderInput(type, "keyword", f, m, null, null, "back", true, true, true, RenderFixtures.MEDIA, kanji)
            } else {
                JsRenderInput(type, "keyword", f, m, null, null, "back", true, true, true, RenderFixtures.MEDIA, kanji, regions)
            }
        }
        val golden = RENDER_REGION_GOLDENS.getValue("kanji/keyword/back")
        val out = kotobayaCore().renderCard(input(true))
        assertEquals(golden.html, out.html)
        assertEquals(golden.fields, out.fields.map { "${it.key}:${it.source}:${it.rendered}" })
        for (off in listOf(kotobayaCore().renderCard(input(false)), kotobayaCore().renderCard(input(null)))) {
            assertEquals(RENDER_GOLDENS.getValue("kanji/keyword/back").html, off.html)
            assertEquals(0, off.fields.size)
        }
    }

    // --- sources ---

    @Test
    fun sourceIsNoteWhenTheFieldIsSetElseTheFallbackThatFilledIt() {
        val vocab = BuiltInNoteTypes.VOCAB.info
        val filled = render(vocab, mapOf("meaning" to " "), kind = "meaning", side = CardSide.BACK, dictionary = RenderFixtures.TABEMONO)
        assertEquals(
            listOf(
                "expression:dictionary:true", "sentence:empty:false", "image:empty:false", "reading:dictionary:true",
                "meaning:dictionary:true", "audio:empty:false", "sentence_translation:empty:false", "sentence_audio:empty:false",
                "notes:empty:false",
            ),
            fields(filled),
        )
        assertTrue("""<span class="kb-field-meaning" data-field="meaning" data-field-source="dictionary">food; provisions</span>""" in filled.html)

        val overridden = render(vocab, mapOf("meaning" to "grub"), kind = "meaning", side = CardSide.BACK, dictionary = RenderFixtures.TABEMONO)
        assertEquals(RenderedField("meaning", FieldSource.NOTE, true), overridden.fields.first { it.key == "meaning" })
        assertTrue("""<span class="kb-field-meaning" data-field="meaning" data-field-source="note">grub</span>""" in overridden.html)

        val kanji = BuiltInNoteTypes.KANJI.info
        val k = render(kanji, mapOf("literal" to "", "keyword" to "EAT"), kind = "keyword", side = CardSide.BACK, kanji = RenderFixtures.SHOKU)
        assertEquals(
            listOf("literal:kanji:true", "sentence:empty:false", "image:empty:false", "keyword:note:true", "readings:kanji:true", "notes:empty:false"),
            fields(k),
        )
    }

    @Test
    fun withoutFallbackDataAnEmptyFieldIsEmptyAndHasNoRegion() {
        val k = render(BuiltInNoteTypes.KANJI.info, mapOf("literal" to "食"), kind = "keyword", side = CardSide.BACK)
        assertEquals(RenderedField("keyword", FieldSource.EMPTY, false), k.fields.first { it.key == "keyword" })
        assertEquals(listOf("literal" to "note"), RegionChecker.regions(k.html))
        // kanji data only fills kanji notes
        val c = render(custom("{{literal}}"), emptyMap(), kanji = RenderFixtures.SHOKU)
        assertEquals(listOf("literal:empty:false"), fields(c))
    }

    // --- which fields are listed, and in which order ---

    @Test
    fun fieldsListEveryReferredKeyOnceInOrderOfFirstReference() {
        val type = custom("{{#b}}<b>{{text:a}}</b>{{/b}}{{^c}}{{kana:d}}{{/c}}{{a}}{{furigana:b}}{{a}}")
        val r = render(type, mapOf("a" to "A", "c" to "C", "d" to "D"))
        // b: section hidden (b empty); a first referred inside it. c: inverted section hidden (c set); d only inside it.
        assertEquals(listOf("b:empty:false", "a:note:true", "c:note:false", "d:note:false"), fields(r))
        assertEquals(
            """<span class="kb-field-a" data-field="a" data-field-source="note">A</span><span class="kb-field-a" data-field="a" data-field-source="note">A</span>""",
            inner(r),
        )
    }

    @Test
    fun placeholdersThatAreNotFieldsAreNotListed() {
        val type = custom("{{pitch:reading}}{{cloze:blank}}{{nope:front}}{{Front}}{{#Bad Key}}x{{/Bad Key}}{{FrontSide}}{{/front}}{{back}}")
        val r = render(type, mapOf("front" to "f", "back" to "b", "reading" to "r"))
        assertEquals(listOf("back:note:true"), fields(r))
    }

    @Test
    fun frontSideContributesTheFrontsFieldsAtItsPosition() {
        val fieldsOf = mapOf("front" to "f", "back" to "b", "extra" to "")
        val both = custom("{{front}}{{#extra}}{{extra}}{{/extra}}", "{{back}}{{FrontSide}}{{front}}")
        assertEquals(listOf("front:note:true", "extra:empty:false"), fields(render(both, fieldsOf)))
        val back = render(both, fieldsOf, side = CardSide.BACK)
        assertEquals(listOf("back:note:true", "front:note:true", "extra:empty:false"), fields(back))
        assertEquals(listOf("back" to "note", "front" to "note", "front" to "note"), RegionChecker.regions(back.html))

        val alone = custom("{{front}}", "{{back}}")
        assertEquals(listOf("back:note:true"), fields(render(alone, fieldsOf, side = CardSide.BACK)))

        val hidden = custom("{{front}}", "{{#extra}}{{FrontSide}}{{/extra}}{{back}}")
        assertEquals(listOf("extra:empty:false", "back:note:true"), fields(render(hidden, fieldsOf, side = CardSide.BACK)))
    }

    @Test
    fun unknownKindListsNothing() {
        val r = render(BuiltInNoteTypes.VOCAB.info, mapOf("expression" to "x"), kind = "nope")
        assertEquals(emptyList(), r.fields)
    }

    // --- rendered flag ---

    @Test
    fun aValueThatSanitisesToNothingIsANoteValueWithoutARegion() {
        val r = render(custom("{{front}}|{{back}}"), mapOf("front" to "<script>alert(1)</script>", "back" to "b"))
        assertEquals(listOf("front:note:false", "back:note:true"), fields(r))
    }

    @Test
    fun aPlaceholderInsideATemplateAttributeGivesNoRegion() {
        val hash = "d".repeat(64)
        val r = render(custom("<img src=\"blob:$hash\" alt=\"{{front}}\"><span lang=\"{{back}}\">x</span>"), mapOf("front" to "f", "back" to "b"))
        assertEquals(listOf("front:note:false", "back:note:false"), fields(r))
        assertEquals(emptyList(), RegionChecker.regions(r.html))
    }

    // --- forgery ---

    private val forgeries = listOf(
        """<span class="kb-field-back" data-field="back" data-field-source="dictionary">x</span>""",
        """<span data-field="back" data-field-source="dictionary">x</span>""",
        """<span data-field=back data-field-source=kanji>x</span>""",
        """<SPAN DATA-FIELD="back" DATA-FIELD-SOURCE="note">x</SPAN>""",
        """</span><span class="kb-field-back" data-field="back" data-field-source="dictionary">x""",
        """</span></div><div data-field="back">x""",
        """<b data-field="front" data-field-source="kanji">x</b>""",
        """<img src="blob:${"d".repeat(64)}" alt="&quot; data-field=&quot;back" data-field="back">""",
        """x" data-field="back" data-field-source="dictionary""",
        """<span class="user-a kb-field-back" lang="en" data-field-source="dictionary">x</span>""",
        "0<span data-field=\"back\">x</span>1",
        """&lt;span data-field="back"&gt;x""",
    )

    @Test
    fun aFieldValueCannotForgeRetargetOrResourceARegion() {
        val template = "{{front}}|{{furigana:front}}|{{kana:front}}|{{kanji:front}}|{{text:front}}|{{back}}"
        for (v in forgeries) {
            for (regions in listOf(true, false)) {
                for (side in CardSide.entries) {
                    val r = render(
                        custom(template, "{{FrontSide}}<hr>$template"),
                        mapOf("front" to v),
                        side = side,
                        options = on.copy(fieldRegions = regions),
                        dictionary = RenderFixtures.TABEMONO,
                    )
                    // every region is `front` from the note; `back` is empty, and nothing made it look filled
                    assertTrue(RegionChecker.regions(r.html).all { it == ("front" to "note") }, "$v\n${r.html}")
                    if (regions) assertEquals(listOf(RenderedField("front", FieldSource.NOTE, true), RenderedField("back", FieldSource.EMPTY, false)), r.fields, v)
                }
            }
        }
    }

    @Test
    fun aCustomTemplateCannotWriteARegionItself() {
        for (v in forgeries) {
            for (regions in listOf(true, false)) {
                for (side in CardSide.entries) {
                    val type = custom("$v{{front}}$v", "{{FrontSide}}$v")
                    val r = render(type, mapOf("front" to "f"), side = side, options = on.copy(fieldRegions = regions))
                    assertTrue(RegionChecker.regions(r.html).all { it == ("front" to "note") }, "$v\n${r.html}")
                    assertEquals(regions, RegionChecker.regions(r.html).isNotEmpty(), v)
                }
            }
        }
    }

    @Test
    fun fallbackDataCannotForgeARegionEither() {
        val evil = """<span data-field="reading" data-field-source="note">x</span>"""
        val d = RenderFixtures.TABEMONO.copy(expression = evil, glosses = listOf(evil), furigana = emptyList())
        val r = render(BuiltInNoteTypes.VOCAB.info, emptyMap(), kind = "meaning", side = CardSide.BACK, dictionary = d)
        assertTrue(RegionChecker.regions(r.html).all { it.second == "dictionary" }, r.html)
        assertEquals(setOf("expression", "reading", "meaning"), RegionChecker.regions(r.html).map { it.first }.toSet())
    }
}
