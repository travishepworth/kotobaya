package kotobaya.render

import kotobaya.cards.BuiltInNoteTypes
import kotobaya.contracts.cards.TemplateInfo
import kotobaya.contracts.render.CardText
import kotobaya.contracts.render.ClozeSentence
import kotobaya.contracts.render.DictionaryData
import kotobaya.contracts.render.TextRenderInput
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ARCH-CARD-TEXT: the plain-text render (`QueueItem.text`) of every built-in note type and kind, plus the edge
 * cases the `CardTextRenderer` contract names. Common test code: the jvm and js runs assert the same strings.
 */
class TextRenderGoldenTest {
    private fun text(note: RenderFixtures.Note, kind: String): CardText =
        DefaultCardTextRenderer.renderText(
            TextRenderInput(
                note.type.info, kind, note.fields, note.dictionary,
                if (note.type.info.baseType.wire == "grammar") RenderFixtures.CLOZE else null, note.kanji,
            ),
        )

    private fun t(front: String, back: String, reading: String?, sentence: String?, vararg warnings: String) =
        CardText(front, back, reading, sentence, warnings.map { "render.warning.$it" })

    private val vocabReading = "たべもの"
    private val vocabSentence = "美味しい食べ物"
    private val kanjiReadings = "ショク、ジキ、た.べる、く.う"
    private val phraseReading = "おつかれさま"
    private val catReading = "ねこがすきです。"

    private val goldens: Map<String, CardText> = mapOf(
        "vocab/meaning" to t("食べ物", "たべもの\nfood; something to eat", vocabReading, vocabSentence),
        "vocab/reading" to t("食べ物", "たべもの\nfood; something to eat", vocabReading, vocabSentence),
        "vocab/listening" to t("", "食べ物\nたべもの\nfood; something to eat", vocabReading, vocabSentence),
        "vocab/production" to t("food; something to eat", "食べ物\nたべもの", vocabReading, vocabSentence),
        "vocab/pitch" to t("食べ物", "たべもꜜの / たべꜜもの", vocabReading, vocabSentence),
        "kanji/keyword" to t("食", "eat; food\n$kanjiReadings", kanjiReadings, null),
        "kanji/writing" to t("eat; food", "食\n$kanjiReadings", kanjiReadings, null),
        "kanji/components" to t("食", "人 + 良", kanjiReadings, null),
        "phrase/meaning" to t("お疲れ様", "おつかれさま\nthanks for your hard work", phraseReading, null),
        "phrase/production" to t("thanks for your hard work", "お疲れ様\nおつかれさま", phraseReading, null),
        "phrase/listening" to t("", "お疲れ様\nおつかれさま\nthanks for your hard work", phraseReading, null),
        "grammar/cloze" to t(
            "行か＿＿＿。\nI have no choice but to go.",
            "行か【ざるを得ない】。\n〜ざるを得ない\ncannot help but",
            null, null,
        ),
        "grammar/recognition" to t("〜ざるを得ない", "cannot help but", null, null),
        "grammar/spot" to t(
            "行かざるを得ない。",
            "行か【ざるを得ない】。\n〜ざるを得ない\ncannot help but\nI have no choice but to go.",
            null, null,
        ),
        "grammar/production" to t("cannot help but", "〜ざるを得ない", null, null),
        "sentence/comprehension" to t("猫が好きです。", "$catReading\nI like cats.", catReading, null),
        "sentence/listening" to t("", "猫が好きです。\n$catReading\nI like cats.", catReading, null),
        "custom/basic" to t("What is 2+2?", "4", null, null),
    )

    @Test
    fun goldensCoverEveryBuiltInNoteTypeAndKind() {
        val expected = BuiltInNoteTypes.ALL.flatMap { t -> t.info.templates.map { "${t.info.baseType.wire}/${it.kind}" } }.toSet()
        assertEquals(expected, goldens.keys)
        assertEquals(18, expected.size)
    }

    @Test
    fun everyBuiltInNoteTypeAndKindMatchesItsGolden() {
        for (note in RenderFixtures.NOTES) for (tpl in note.type.info.templates) {
            val key = "${note.type.info.baseType.wire}/${tpl.kind}"
            assertEquals(goldens.getValue(key), text(note, tpl.kind), key)
        }
    }

    // ---------------------------------------------------------------- edge cases of the contract

    private val taberu = DictionaryData("食べる", "たべる", emptyList(), listOf("to eat", "to live on (e.g. a salary)"), emptyList())

    private fun vocab(kind: String, fields: Map<String, String>, dictionary: DictionaryData?) =
        DefaultCardTextRenderer.renderText(TextRenderInput(BuiltInNoteTypes.VOCAB.info, kind, fields, dictionary, null))

    @Test
    fun takobotoShapedNoteRendersFromTheDictionary() {
        // fields {} + entry_seq: everything comes from the dictionary; no pitch data -> the pitch back is the reading
        assertEquals(t("食べる", "たべる\nto eat; to live on (e.g. a salary)", "たべる", null), vocab("meaning", emptyMap(), taberu))
        assertEquals(t("食べる", "たべる", "たべる", null), vocab("pitch", emptyMap(), taberu))
        assertEquals(t("to eat; to live on (e.g. a salary)", "食べる\nたべる", "たべる", null), vocab("production", emptyMap(), taberu))
    }

    @Test
    fun aNonEmptyNoteFieldWinsOverTheDictionary() {
        assertEquals(
            t("食べる", "たべる\nto consume", "たべる", null),
            vocab("meaning", mapOf("meaning" to "to consume", "expression" to " "), taberu),
        )
    }

    @Test
    fun kanaOnlyWordHasNoDuplicateReadingLine() {
        val arigatou = DictionaryData("ありがとう", "ありがとう", emptyList(), listOf("thank you"), listOf(2))
        assertEquals(t("ありがとう", "thank you", "ありがとう", null), vocab("meaning", emptyMap(), arigatou))
    }

    @Test
    fun noDictionaryAndNoFieldsGivesEmptyText() {
        assertEquals(t("", "", null, null), vocab("meaning", emptyMap(), null))
    }

    @Test
    fun pitchLineMarksDownstepsAfterTheMora() {
        val kyou = DictionaryData("今日", "きょう", emptyList(), emptyList(), listOf(1, 0, 2, 1, 7, -1))
        // atamadaka, heiban (no mark), odaka (mark after the last mora), duplicate dropped, out of range unmarked, negative skipped
        assertEquals("きょꜜう / きょう / きょうꜜ / きょう", vocab("pitch", emptyMap(), kyou).back)
    }

    private val grammar = RenderFixtures.NOTES.first { it.type == BuiltInNoteTypes.GRAMMAR }

    private fun grammarText(kind: String, cloze: ClozeSentence?) =
        DefaultCardTextRenderer.renderText(TextRenderInput(BuiltInNoteTypes.GRAMMAR.info, kind, grammar.fields, null, cloze))

    @Test
    fun grammarWithoutClozeFallsBackToPatternAndMeaning() {
        for (kind in listOf("cloze", "spot")) {
            assertEquals(t("〜ざるを得ない", "cannot help but", null, null, "missing_cloze"), grammarText(kind, null), kind)
        }
    }

    @Test
    fun invalidClozeSpanShowsTheSentenceUnmarked() {
        val bad = ClozeSentence(null, "行かざるを得ない。", 5, 50, " ")
        assertEquals(
            t("行かざるを得ない。", "〜ざるを得ない\ncannot help but", null, null, "invalid_cloze"),
            grammarText("cloze", bad),
        )
    }

    @Test
    fun customTemplateFallsBackToTheHtmlRender() {
        val info = BuiltInNoteTypes.VOCAB.info.let { i ->
            i.copy(templates = i.templates.map { if (it.kind == "meaning") TemplateInfo("meaning", true, "<div>{{meaning}}</div>", "{{FrontSide}}<hr>{{expression}}<br>{{kana:reading}}") else it })
        }
        val out = DefaultCardTextRenderer.renderText(TextRenderInput(info, "meaning", emptyMap(), taberu, null))
        assertEquals(t("to eat; to live on (e.g. a salary)", "食べる\nたべる", "たべる", null), out)
    }

    @Test
    fun unknownKindIsEmptyWithAWarning() {
        assertEquals(t("", "", "たべる", null, "unknown_kind"), vocab("nope", emptyMap(), taberu))
    }

    @Test
    fun markupIsReducedToLines() {
        val fields = mapOf(
            "front" to "  a&amp;b <br>  c \t  d　e  x",
            "back" to "<div>x</div><div>x</div><ul><li>y</li></ul>[sound:${RenderFixtures.AUDIO}]" +
                "<img src=\"blob:${RenderFixtures.IMAGE}\" alt=\"pic\"><script>bad()</script><ruby>漢<rt>かん</rt></ruby>",
        )
        val out = DefaultCardTextRenderer.renderText(TextRenderInput(BuiltInNoteTypes.CUSTOM.info, "basic", fields, null, null))
        assertEquals(t("a&b\nc d　e x", "x\ny\n漢", null, null, "dropped_tag"), out)
    }
}
