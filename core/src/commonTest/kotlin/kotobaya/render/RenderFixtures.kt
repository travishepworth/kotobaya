package kotobaya.render

import kotobaya.cards.BuiltInNoteType
import kotobaya.cards.BuiltInNoteTypes
import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.cards.NoteTypeInfo
import kotobaya.contracts.render.CardSide
import kotobaya.contracts.render.ClozeSentence
import kotobaya.contracts.render.DictionaryData
import kotobaya.contracts.render.FuriganaSegment
import kotobaya.contracts.render.KanjiData
import kotobaya.contracts.render.RenderInput
import kotobaya.contracts.render.RenderOptions

/** Shared inputs of the render tests: one note per built-in note type. */
object RenderFixtures {
    val AUDIO: String = "a".repeat(64)
    val SENTENCE_AUDIO: String = "c".repeat(64)
    val IMAGE: String = "b".repeat(64)
    const val MEDIA: String = "/api/v1/blobs/{sha256}"

    val OPTIONS: RenderOptions = RenderOptions(furigana = true, pitch = true, showMnemonic = true, mediaUrlTemplate = MEDIA)

    val TABEMONO: DictionaryData = DictionaryData(
        expression = "食べ物",
        reading = "たべもの",
        furigana = listOf(FuriganaSegment("食", "た"), FuriganaSegment("べ", null), FuriganaSegment("物", "もの")),
        glosses = listOf("food", "provisions"),
        pitchDrops = listOf(3, 2),
    )

    val SHOKU: KanjiData = KanjiData("食", listOf("eat", "food"), listOf("ショク", "ジキ"), listOf("た.べる", "く.う"), 9)

    val CLOZE: ClozeSentence =
        ClozeSentence("0193a1b2-0001-7000-8000-000000000301", "行かざるを得ない。", 2, 8, "I have no choice but to go.")

    /** Note contents per built-in type (dictionary / kanji data where the type uses it). */
    class Note(
        val type: BuiltInNoteType,
        val fields: Map<String, String>,
        val dictionary: DictionaryData? = null,
        val kanji: KanjiData? = null,
    )

    val NOTES: List<Note> = listOf(
        Note(
            BuiltInNoteTypes.VOCAB,
            mapOf(
                "expression" to "", // filled from the dictionary
                "reading" to "",
                "meaning" to "food; <i>something to eat</i>",
                "sentence" to "美味[おい]しい 食[た]べ 物[もの]",
                "sentence_translation" to "Tasty food",
                "audio" to "[sound:$AUDIO]",
                "sentence_audio" to "[sound:$SENTENCE_AUDIO]",
                "image" to "<img src=\"blob:$IMAGE\" alt=\"food\">",
                "notes" to "<b>common</b> word",
            ),
            dictionary = TABEMONO,
        ),
        Note(BuiltInNoteTypes.KANJI, mapOf("literal" to "食", "notes" to "人 + 良"), kanji = SHOKU),
        Note(
            BuiltInNoteTypes.PHRASE,
            mapOf(
                "expression" to "お疲れ様",
                "reading" to "お 疲[つか]れ 様[さま]",
                "meaning" to "thanks for your hard work",
                "audio" to "[sound:$AUDIO]",
            ),
        ),
        Note(BuiltInNoteTypes.GRAMMAR, mapOf("pattern" to "〜ざるを得ない", "meaning" to "cannot help but", "notes" to "Formal.")),
        Note(
            BuiltInNoteTypes.SENTENCE,
            mapOf(
                "sentence" to "猫[ねこ]が 好[す]きです。",
                "reading" to "猫[ねこ]が 好[す]きです。",
                "translation" to "I like cats.",
                "audio" to "[sound:$AUDIO]",
                "image" to "<img src=\"blob:$IMAGE\">",
            ),
        ),
        Note(BuiltInNoteTypes.CUSTOM, mapOf("front" to "What is <b>2+2</b>?", "back" to "4")),
    )

    fun input(
        note: Note,
        kind: String,
        side: CardSide,
        options: RenderOptions = OPTIONS,
        noteType: NoteTypeInfo = note.type.info,
    ): RenderInput = RenderInput(
        noteType = noteType,
        kind = kind,
        fields = note.fields,
        mnemonic = "食 = person + good\nfood is <good>",
        dictionary = note.dictionary,
        cloze = if (note.type.info.baseType == NoteBaseType.GRAMMAR) CLOZE else null,
        side = side,
        options = options,
        kanji = note.kanji,
    )

    /** Every built-in note type x kind x side. */
    fun goldenCases(): List<Triple<Note, String, CardSide>> = NOTES.flatMap { note ->
        note.type.info.templates.flatMap { t -> CardSide.entries.map { Triple(note, t.kind, it) } }
    }

    /** `"<base>/<kind>/<front|back>"`. */
    fun key(note: Note, kind: String, side: CardSide): String =
        "${note.type.info.baseType.wire}/$kind/${side.name.lowercase()}"
}
