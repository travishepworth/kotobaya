package kotobaya.render

import kotobaya.cards.BuiltInNoteTypes
import kotobaya.contracts.cards.NoteTypeInfo
import kotobaya.contracts.cards.TemplateInfo
import kotobaya.contracts.render.CardSide
import kotobaya.contracts.render.ClozeSentence
import kotobaya.contracts.render.DictionaryData
import kotobaya.contracts.render.KanjiData
import kotobaya.contracts.render.RenderInput
import kotobaya.contracts.render.RenderOptions
import kotobaya.contracts.render.RenderedCard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CardRendererTest {
    private val opts = RenderOptions(furigana = true, pitch = false, showMnemonic = false, mediaUrlTemplate = "/b/{sha256}")

    private fun custom(front: String, back: String = "{{FrontSide}}"): NoteTypeInfo =
        BuiltInNoteTypes.CUSTOM.info.copy(
            fieldKeys = listOf("front", "back", "reading"),
            templates = listOf(TemplateInfo("basic", true, front, back)),
        )

    private fun render(
        type: NoteTypeInfo,
        fields: Map<String, String>,
        kind: String = "basic",
        side: CardSide = CardSide.FRONT,
        options: RenderOptions = opts,
        dictionary: DictionaryData? = null,
        kanji: KanjiData? = null,
        cloze: ClozeSentence? = null,
        mnemonic: String = "",
    ): RenderedCard = DefaultCardRenderer.render(RenderInput(type, kind, fields, mnemonic, dictionary, cloze, side, options, kanji))

    /** Inner HTML (root div removed). */
    private fun inner(r: RenderedCard): String = r.html.substringAfter('>').removeSuffix("</div>")

    // --- missing / empty fields ---

    @Test
    fun missingAndEmptyFieldsRenderAsNothingWithoutWarnings() {
        val r = render(BuiltInNoteTypes.VOCAB.info, emptyMap(), kind = "meaning", side = CardSide.BACK)
        assertEquals(
            "<div class=\"kb-expression\"></div><hr class=\"kb-divider\"><div class=\"kb-meaning\"></div>",
            inner(r),
        )
        assertEquals(emptyList(), r.warnings)
        assertEquals(emptyList(), r.audio)
    }

    @Test
    fun whitespaceOnlyFieldCountsAsEmptyForSections() {
        val t = custom("{{#front}}A{{/front}}{{^front}}B{{/front}}{{^back}}C{{/back}}")
        assertEquals("BC", inner(render(t, mapOf("front" to "  \n"))))
        assertEquals("<span class=\"kb-field-front\">x</span>", inner(render(custom("{{front}}{{#back}}never{{/back}}"), mapOf("front" to "x"))))
    }

    @Test
    fun unbalancedSectionsWarnAndNeverThrow() {
        val r = render(custom("{{#front}}a{{/back}}b"), mapOf("front" to "x"))
        assertEquals("ab", inner(r))
        assertEquals(listOf("render.warning.unbalanced_section"), r.warnings)
    }

    @Test
    fun unknownPlaceholdersRenderEmptyWithAWarning() {
        for (p in listOf("{{nope:front}}", "{{Front}}", "{{pitch:reading}}", "{{cloze:blank}}", "{{furigana:}}")) {
            val r = render(custom("[$p]"), mapOf("front" to "x"))
            assertEquals("[]", inner(r), p)
            assertEquals(listOf("render.warning.unknown_placeholder"), r.warnings, p)
        }
    }

    @Test
    fun unknownKindRendersAnEmptyCardWithAWarning() {
        val r = render(BuiltInNoteTypes.VOCAB.info, mapOf("expression" to "x"), kind = "nonsense")
        assertEquals("<div class=\"kb-card kb-vocab kb-kind-nonsense kb-front\"></div>", r.html)
        assertEquals(listOf("render.warning.unknown_kind"), r.warnings)
    }

    @Test
    fun frontSideIsTheRenderedFrontAndOnlyOnTheBack() {
        val t = custom("{{front}}{{FrontSide}}", "{{FrontSide}}<hr>{{back}}")
        val fields = mapOf("front" to "<b>q</b>", "back" to "a")
        assertEquals("<span class=\"kb-field-front\"><b>q</b></span>", inner(render(t, fields)))
        assertEquals(
            "<span class=\"kb-field-front\"><b>q</b></span><hr><span class=\"kb-field-back\">a</span>",
            inner(render(t, fields, side = CardSide.BACK)),
        )
    }

    @Test
    fun customTemplateOverridesOnlyTheSideItSets() {
        val type = BuiltInNoteTypes.CUSTOM.info.copy(templates = listOf(TemplateInfo("basic", true, "<i>{{front}}</i>", null)))
        val r = render(type, mapOf("front" to "q", "back" to "a"), side = CardSide.BACK)
        assertEquals("<i><span class=\"kb-field-front\">q</span></i><hr class=\"kb-divider\"><span class=\"kb-field-back\">a</span>", inner(r))
    }

    @Test
    fun mnemonicOnlyOnTheBackWhenShownAndEscapedWithLineBreaks() {
        val t = custom("{{front}}")
        val fields = mapOf("front" to "q")
        val on = opts.copy(showMnemonic = true)
        assertEquals("<span class=\"kb-field-front\">q</span>", inner(render(t, fields, options = on, mnemonic = "m")))
        assertEquals(
            "<span class=\"kb-field-front\">q</span>",
            inner(render(t, fields, side = CardSide.BACK, mnemonic = "m")),
        )
        assertEquals(
            "<span class=\"kb-field-front\">q</span><div class=\"kb-mnemonic\">a &lt;b&gt;<br>c<br>d</div>",
            inner(render(t, fields, side = CardSide.BACK, options = on, mnemonic = "a <b>\r\nc\rd")),
        )
        assertEquals(
            "<span class=\"kb-field-front\">q</span>",
            inner(render(t, fields, side = CardSide.BACK, options = on, mnemonic = "  \n ")),
        )
    }

    // --- furigana ---

    @Test
    fun bracketFuriganaInAllModes() {
        val t = custom("{{furigana:front}}|{{kana:front}}|{{kanji:front}}|{{text:front}}|{{front}}")
        val r = render(t, mapOf("front" to "今日[きょう]は 晴[は]れ"))
        assertEquals(
            "<span class=\"kb-field-front\"><ruby>今日<rt>きょう</rt></ruby>は<ruby>晴<rt>は</rt></ruby>れ</span>|" +
                "<span class=\"kb-field-front\">きょうははれ</span>|" +
                "<span class=\"kb-field-front\">今日は晴れ</span>|" +
                "<span class=\"kb-field-front\">今日[きょう]は 晴[は]れ</span>|" +
                "<span class=\"kb-field-front\">今日[きょう]は 晴[は]れ</span>",
            inner(r),
        )
    }

    @Test
    fun furiganaOffKeepsBaseTextForBracketsAndRubyMarkup() {
        val t = custom("{{furigana:front}}|{{back}}")
        val r = render(t, mapOf("front" to "漢字[かんじ]", "back" to "<ruby>漢<rp>(</rp><rt>かん</rt><rp>)</rp></ruby>字"), options = opts.copy(furigana = false))
        assertEquals("<span class=\"kb-field-front\">漢字</span>|<span class=\"kb-field-back\">漢字</span>", inner(r))
    }

    @Test
    fun rubyMarkupIsKeptWhenFuriganaIsOn() {
        val r = render(custom("{{front}}"), mapOf("front" to "<ruby>漢<rt>かん</rt></ruby>"))
        assertEquals("<span class=\"kb-field-front\"><ruby>漢<rt>かん</rt></ruby></span>", inner(r))
    }

    @Test
    fun textModeStripsHtmlAndDropsRubyText() {
        val r = render(custom("{{text:front}}"), mapOf("front" to "<b>a</b><ruby>漢<rt>かん</rt></ruby><script>x</script>&amp;"))
        assertEquals("<span class=\"kb-field-front\">a漢&amp;</span>", inner(r))
    }

    // --- dictionary fill-in ---

    private val taberu = DictionaryData(
        expression = "食べる",
        reading = "たべる",
        furigana = listOf(
            kotobaya.contracts.render.FuriganaSegment("食", "た"),
            kotobaya.contracts.render.FuriganaSegment("べる", null),
        ),
        glosses = listOf("to eat", "<to> live on"),
        pitchDrops = listOf(2),
    )

    @Test
    fun emptyVocabFieldsAreFilledFromTheDictionary() {
        val r = render(BuiltInNoteTypes.VOCAB.info, mapOf("expression" to " "), kind = "meaning", side = CardSide.BACK, dictionary = taberu)
        assertEquals(
            "<div class=\"kb-expression\"><span class=\"kb-field-expression\">食べる</span></div><hr class=\"kb-divider\">" +
                "<div class=\"kb-reading\"><span class=\"kb-field-reading\"><ruby>食<rt>た</rt></ruby>べる</span></div>" +
                "<div class=\"kb-meaning\"><span class=\"kb-field-meaning\">to eat; &lt;to&gt; live on</span></div>",
            inner(r),
        )
    }

    @Test
    fun nonEmptyFieldWinsOverTheDictionary() {
        val r = render(
            BuiltInNoteTypes.VOCAB.info,
            mapOf("expression" to "喰べる", "meaning" to "to devour"),
            kind = "production",
            dictionary = taberu,
        )
        assertEquals("<div class=\"kb-meaning\"><span class=\"kb-field-meaning\">to devour</span></div>", inner(r))
    }

    @Test
    fun dictionaryWithoutAlignmentPutsRubyOverTheWholeWord() {
        val d = taberu.copy(furigana = emptyList())
        assertEquals(
            "<span class=\"kb-field-reading\"><ruby>食べる<rt>たべる</rt></ruby></span>",
            inner(render(custom("{{furigana:reading}}"), emptyMap(), dictionary = d)),
        )
        val kana = d.copy(expression = "これ", reading = "これ")
        assertEquals("<span class=\"kb-field-reading\">これ</span>", inner(render(custom("{{furigana:reading}}"), emptyMap(), dictionary = kana)))
    }

    @Test
    fun multiSegmentAlignmentKeepsEachRubyOnItsSegment() {
        val d = DictionaryData(
            "お茶漬け", "おちゃづけ",
            listOf(
                kotobaya.contracts.render.FuriganaSegment("お", null),
                kotobaya.contracts.render.FuriganaSegment("茶", "ちゃ"),
                kotobaya.contracts.render.FuriganaSegment("漬", "づ"),
                kotobaya.contracts.render.FuriganaSegment("け", null),
            ),
            emptyList(), emptyList(),
        )
        assertEquals(
            "<span class=\"kb-field-reading\">お<ruby>茶<rt>ちゃ</rt></ruby><ruby>漬<rt>づ</rt></ruby>け</span>|<span class=\"kb-field-reading\">おちゃづけ</span>",
            inner(render(custom("{{furigana:reading}}|{{kana:reading}}"), emptyMap(), dictionary = d)),
        )
    }

    // --- kanji fill-in (C-2) ---

    private val shoku = KanjiData("食", listOf("eat", "food"), listOf("ショク"), listOf("た.べる", "く.う"))

    @Test
    fun literalOnlyKanjiNoteIsFilledFromKanjiData() {
        val r = render(BuiltInNoteTypes.KANJI.info, mapOf("literal" to "食"), kind = "keyword", side = CardSide.BACK, kanji = shoku)
        assertEquals(
            "<div class=\"kb-literal\"><span class=\"kb-field-literal\">食</span></div><hr class=\"kb-divider\">" +
                "<div class=\"kb-keyword\"><span class=\"kb-field-keyword\">eat; food</span></div>" +
                "<div class=\"kb-reading\"><span class=\"kb-field-readings\">ショク、た.べる、く.う</span></div>",
            inner(r),
        )
    }

    @Test
    fun kanjiFillInFillsAnEmptyLiteralAndLetsNonEmptyFieldsWin() {
        val r = render(BuiltInNoteTypes.KANJI.info, mapOf("keyword" to "meal"), kind = "writing", side = CardSide.BACK, kanji = shoku)
        assertEquals(
            "<div class=\"kb-keyword\"><span class=\"kb-field-keyword\">meal</span></div><hr class=\"kb-divider\">" +
                "<div class=\"kb-literal\"><span class=\"kb-field-literal\">食</span></div>" +
                "<div class=\"kb-reading\"><span class=\"kb-field-readings\">ショク、た.べる、く.う</span></div>",
            inner(r),
        )
    }

    @Test
    fun kanjiDataIsIgnoredForOtherBaseTypes() {
        val r = render(custom("{{literal}}{{keyword}}"), emptyMap(), kanji = shoku)
        assertEquals("", inner(r))
    }

    @Test
    fun kanjiWithoutDataRendersEmptyFields() {
        val r = render(BuiltInNoteTypes.KANJI.info, mapOf("literal" to "食"), kind = "keyword", side = CardSide.BACK)
        assertEquals(
            "<div class=\"kb-literal\"><span class=\"kb-field-literal\">食</span></div><hr class=\"kb-divider\">" +
                "<div class=\"kb-keyword\"></div><div class=\"kb-reading\"></div>",
            inner(r),
        )
    }

    // --- pitch ---

    private fun pitchSpans(reading: String, vararg drops: Int): String {
        val d = DictionaryData("x", reading, emptyList(), emptyList(), drops.toList())
        val r = render(BuiltInNoteTypes.VOCAB.info, mapOf("reading" to "r"), kind = "pitch", side = CardSide.BACK, dictionary = d)
        return r.html.substringAfter("<div class=\"kb-reading\"><span class=\"kb-pitch\">", "")
            .substringBefore("</div>").let { if (it.isEmpty()) "" else "<span class=\"kb-pitch\">$it" }
    }

    private fun m(mora: String, high: Boolean, drop: Boolean = false) =
        "<span class=\"kb-mora ${if (high) "kb-pitch-high" else "kb-pitch-low"}${if (drop) " kb-pitch-drop" else ""}\">$mora</span>"

    private fun p(vararg morae: String) = "<span class=\"kb-pitch\">" + morae.joinToString("") + "</span>"

    @Test
    fun pitchHeibanAtamadakaNakadakaOdaka() {
        assertEquals(p(m("さ", false), m("く", true), m("ら", true)), pitchSpans("さくら", 0))
        assertEquals(p(m("い", true, true), m("の", false), m("ち", false)), pitchSpans("いのち", 1))
        assertEquals(p(m("た", false), m("べ", true, true), m("る", false)), pitchSpans("たべる", 2))
        assertEquals(p(m("お", false), m("と", true), m("こ", true, true)), pitchSpans("おとこ", 3))
    }

    @Test
    fun pitchJoinsSmallKanaButNotSokuonOrLongVowel() {
        assertEquals(
            p(m("きょ", false), m("う", true), m("しゃ", true), m("ー", true), m("っ", true), m("と", true)),
            pitchSpans("きょうしゃーっと", 0),
        )
    }

    @Test
    fun pitchOneSpanPerDistinctPatternAndInvalidDropsSkipped() {
        assertEquals(
            p(m("あ", true, true), m("め", false)) + p(m("あ", false), m("め", true)),
            pitchSpans("あめ", 1, -1, 1, 0),
        )
        assertEquals(p(m("あ", false), m("め", true)), pitchSpans("あめ", 5))
    }

    @Test
    fun pitchOnlyWhenEnabledOrOnPitchCards() {
        val d = taberu
        val meaningOff = render(BuiltInNoteTypes.VOCAB.info, emptyMap(), kind = "meaning", side = CardSide.BACK, dictionary = d)
        assertFalse("kb-pitch" in meaningOff.html)
        val meaningOn = render(BuiltInNoteTypes.VOCAB.info, emptyMap(), kind = "meaning", side = CardSide.BACK, dictionary = d, options = opts.copy(pitch = true))
        assertTrue("kb-pitch-drop" in meaningOn.html)
        val pitchCard = render(BuiltInNoteTypes.VOCAB.info, emptyMap(), kind = "pitch", side = CardSide.BACK, dictionary = d)
        assertTrue("kb-pitch-drop" in pitchCard.html)
        val noData = render(BuiltInNoteTypes.VOCAB.info, emptyMap(), kind = "pitch", side = CardSide.BACK, dictionary = d.copy(pitchDrops = emptyList()))
        assertFalse("kb-pitch" in noData.html)
        assertFalse("kb-pitch" in render(BuiltInNoteTypes.VOCAB.info, emptyMap(), kind = "pitch", side = CardSide.FRONT, dictionary = d).html)
    }

    // --- grammar cloze ---

    @Test
    fun clozeWithoutSentenceWarns() {
        val r = render(BuiltInNoteTypes.GRAMMAR.info, mapOf("pattern" to "p"), kind = "cloze")
        assertEquals("", inner(r))
        assertEquals(listOf("render.warning.missing_cloze"), r.warnings)
    }

    @Test
    fun clozeWithInvalidSpanShowsTheSentenceAndWarns() {
        for ((s, e) in listOf(3 to 3, -1 to 2, 2 to 99, 4 to 1)) {
            val c = ClozeSentence(null, "abcdef", s, e, null)
            val r = render(BuiltInNoteTypes.GRAMMAR.info, emptyMap(), kind = "cloze", cloze = c)
            assertEquals("<div class=\"kb-sentence kb-cloze\">abcdef</div>", inner(r))
            assertEquals(listOf("render.warning.invalid_cloze"), r.warnings)
        }
    }

    @Test
    fun clozeSpanAtTheEdgesAndEscaped() {
        val c = ClozeSentence(null, "<a>&b", 0, 3, "  ")
        val r = render(BuiltInNoteTypes.GRAMMAR.info, emptyMap(), kind = "cloze", side = CardSide.BACK, cloze = c)
        assertTrue(inner(r).startsWith("<div class=\"kb-sentence kb-cloze\"><span class=\"kb-cloze-answer\">&lt;a&gt;</span>&amp;b</div>"))
        assertFalse("kb-translation" in r.html)
    }

    // --- media / audio ---

    @Test
    fun mediaUrlTemplateMustContainTheHashPlaceholderExactlyOnce() {
        for (bad in listOf("/b/", "/b/{sha256}/{sha256}", "{SHA256}")) {
            assertFailsWith<IllegalArgumentException> { render(custom("x"), emptyMap(), options = opts.copy(mediaUrlTemplate = bad)) }
        }
    }

    @Test
    fun hostsDifferOnlyInTheMediaUrl() {
        val note = RenderFixtures.NOTES.first()
        val web = DefaultCardRenderer.render(RenderFixtures.input(note, "meaning", CardSide.BACK)).html
        val android = DefaultCardRenderer.render(
            RenderFixtures.input(note, "meaning", CardSide.BACK, RenderFixtures.OPTIONS.copy(mediaUrlTemplate = "https://appassets.androidplatform.net/media/{sha256}")),
        ).html
        assertEquals(web.replace("/api/v1/blobs/", "https://appassets.androidplatform.net/media/"), android)
        assertTrue(web != android)
    }

    // --- empty-front helper (T201a -> T213a/T201b) ---

    @Test
    fun frontIsEmptyForKindsWhoseQuestionIsMissing() {
        val vocab = BuiltInNoteTypes.VOCAB.info
        val withSentence = mapOf("expression" to "猫", "sentence" to "猫がいる", "image" to "<img src=\"blob:${"b".repeat(64)}\">")
        assertTrue(DefaultCardRenderer.frontIsEmpty(vocab, "listening", withSentence))
        assertFalse(DefaultCardRenderer.frontIsEmpty(vocab, "listening", withSentence + ("audio" to "[sound:${"a".repeat(64)}]")))
        assertTrue(DefaultCardRenderer.frontIsEmpty(vocab, "production", withSentence))
        assertFalse(DefaultCardRenderer.frontIsEmpty(vocab, "production", emptyMap(), dictionary = taberu))
        assertFalse(DefaultCardRenderer.frontIsEmpty(vocab, "meaning", withSentence))
        assertTrue(DefaultCardRenderer.frontIsEmpty(vocab, "meaning", mapOf("expression" to "<script>x</script> ")))
        assertTrue(DefaultCardRenderer.frontIsEmpty(vocab, "unknown", withSentence))
    }

    @Test
    fun frontIsEmptyUsesKanjiDataAndTreatsGrammarSentencesAsPresent() {
        assertTrue(DefaultCardRenderer.frontIsEmpty(BuiltInNoteTypes.KANJI.info, "writing", mapOf("literal" to "食")))
        assertFalse(DefaultCardRenderer.frontIsEmpty(BuiltInNoteTypes.KANJI.info, "writing", mapOf("literal" to "食"), kanji = shoku))
        assertFalse(DefaultCardRenderer.frontIsEmpty(BuiltInNoteTypes.GRAMMAR.info, "cloze", mapOf("pattern" to "p")))
        assertFalse(DefaultCardRenderer.frontIsEmpty(BuiltInNoteTypes.GRAMMAR.info, "spot", emptyMap()))
        assertTrue(DefaultCardRenderer.frontIsEmpty(BuiltInNoteTypes.GRAMMAR.info, "recognition", emptyMap()))
        assertTrue(DefaultCardRenderer.frontIsEmpty(BuiltInNoteTypes.CUSTOM.info, "basic", mapOf("back" to "a")))
        assertFalse(DefaultCardRenderer.frontIsEmpty(BuiltInNoteTypes.CUSTOM.info, "basic", mapOf("front" to "<img src=\"blob:${"b".repeat(64)}\">")))
    }

    @Test
    fun rootCarriesBaseTypeKindAndSideAndExtraCssPassesThrough() {
        val type = custom("x").copy(css = ".user-x { color: red }")
        val r = render(type, emptyMap(), side = CardSide.BACK)
        assertTrue(r.html.startsWith("<div class=\"kb-card kb-custom kb-kind-basic kb-back\">"))
        assertEquals(".user-x { color: red }", r.extraCss)
    }
}
