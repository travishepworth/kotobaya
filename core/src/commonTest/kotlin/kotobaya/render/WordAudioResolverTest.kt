package kotobaya.render

import kotobaya.cards.BuiltInNoteTypes
import kotobaya.contracts.cards.NoteTypeInfo
import kotobaya.contracts.render.DictionaryData
import kotobaya.contracts.render.FuriganaSegment
import kotobaya.contracts.render.WordAudio
import kotobaya.contracts.render.WordAudioInput
import kotobaya.contracts.render.WordAudioRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The worked-example table of `WordAudio.kt` (ADR-047, C-29); common code, so jvm and js assert the same. */
class WordAudioResolverTest {
    private val d = DictionaryData(
        expression = "食べる",
        reading = "たべる",
        furigana = listOf(FuriganaSegment("食", "た"), FuriganaSegment("べる", null)),
        glosses = listOf("to eat"),
        pitchDrops = listOf(2),
    )
    private val h1 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
    private val h2 = "b5bb9d8014a0f9b1d61e21e796d78dccdf1352f23cd32812f4850b878ae4944c"
    private val vocab = BuiltInNoteTypes.VOCAB.info

    private fun resolve(fields: Map<String, String>, dictionary: DictionaryData?, type: NoteTypeInfo = vocab): WordAudio? =
        DefaultWordAudioResolver.resolve(WordAudioInput(type, fields, dictionary))

    @Test
    fun deckMaterializedNoteSpeaksTheDictionaryReading() = assertEquals(WordAudio.Tts("たべる"), resolve(emptyMap(), d))

    @Test
    fun theNotesOwnSoundWins() = assertEquals(WordAudio.Note(h1), resolve(mapOf("audio" to "[sound:$h1]"), d))

    @Test
    fun anAudioFieldWithoutASoundFallsThroughToTts() = assertEquals(WordAudio.Tts("たべる"), resolve(mapOf("audio" to "<br>"), d))

    @Test
    fun anInvalidSoundReferenceIsNotASound() {
        assertEquals(WordAudio.Tts("たべる"), resolve(mapOf("audio" to "[sound:AAAA]"), d))
        assertEquals(WordAudio.Tts("たべる"), resolve(mapOf("audio" to "[sound:${h1.uppercase()}]"), d))
        assertEquals(WordAudio.Tts("たべる"), resolve(mapOf("audio" to "tabemono.mp3&nbsp;"), d))
    }

    @Test
    fun twoSoundsGiveTheFirst() {
        assertEquals(WordAudio.Note(h1), resolve(mapOf("audio" to "[sound:$h1] [sound:$h2]"), d))
        assertEquals(WordAudio.Note(h2), resolve(mapOf("audio" to "[sound:${h1.uppercase()}]<br>[sound:$h2]"), d))
    }

    @Test
    fun theUsersReadingWins() = assertEquals(WordAudio.Tts("くう"), resolve(mapOf("reading" to "くう"), d))

    @Test
    fun aListOfReadingsSpeaksTheFirst() {
        assertEquals(WordAudio.Tts("あした"), resolve(mapOf("reading" to "あした、あす"), null))
        for (sep in listOf(",", "，", ";", "；", "/", "／")) {
            assertEquals(WordAudio.Tts("あした"), resolve(mapOf("reading" to "あした${sep}あす"), null), sep)
        }
    }

    @Test
    fun spacesAreRemoved() {
        assertEquals(WordAudio.Tts("たべる"), resolve(mapOf("reading" to "た べ る"), null))
        assertEquals(WordAudio.Tts("たべる"), resolve(mapOf("reading" to "た　べ る\t"), null))
    }

    @Test
    fun anEmptyReadingFallsBackToTheExpressionInKanaMode() =
        assertEquals(WordAudio.Tts("たべる"), resolve(mapOf("expression" to "食[た]べる"), null))

    @Test
    fun aKanjiExpressionIsSpokenAsWritten() = assertEquals(WordAudio.Tts("食べる"), resolve(mapOf("expression" to "食べる"), null))

    @Test
    fun nothingToSpeakIsNone() {
        assertNull(resolve(emptyMap(), null))
        assertNull(resolve(mapOf("reading" to "<br>"), null))
        assertNull(resolve(mapOf("reading" to "　", "expression" to " "), null))
    }

    @Test
    fun aReadingThatIsOnlyASeparatorOrSpaceTriesTheExpression() {
        assertEquals(WordAudio.Tts("食べる"), resolve(mapOf("reading" to "、あす", "expression" to "食べる"), null))
        assertEquals(WordAudio.Tts("食べる"), resolve(mapOf("reading" to "　", "expression" to "食べる"), null))
    }

    @Test
    fun onlyTheFirstLineIsSpoken() =
        assertEquals(WordAudio.Tts("あした"), resolve(mapOf("reading" to "<br>あした<br>あす"), null))

    @Test
    fun markupAndEntitiesAreRemoved() =
        assertEquals(WordAudio.Tts("たべる"), resolve(mapOf("reading" to "<b>たべ</b>る[sound:$h1]<img src=\"blob:$h2\">"), null))

    @Test
    fun aTooLongReadingIsNoneAndTheExpressionIsNotTried() {
        assertNull(resolve(mapOf("reading" to "あ".repeat(101)), d))
        assertNull(resolve(mapOf("reading" to "あ".repeat(101), "expression" to "食べる"), null))
        assertEquals(WordAudio.Tts("あ".repeat(100)), resolve(mapOf("reading" to "あ".repeat(100)), d))
    }

    @Test
    fun lengthIsCountedInUtf16CodeUnits() {
        val surrogate = "𠮷" // 𠮷: 2 code units
        assertEquals(WordAudio.Tts(surrogate.repeat(50)), resolve(mapOf("reading" to surrogate.repeat(50)), null))
        assertNull(resolve(mapOf("reading" to surrogate.repeat(50) + "あ"), null))
    }

    @Test
    fun noFolding() = assertEquals(WordAudio.Tts("タベル"), resolve(mapOf("reading" to "タベル"), null))

    @Test
    fun otherBaseTypesHaveNoWordAudio() {
        val all = mapOf("audio" to "[sound:$h1]", "reading" to "たべる", "expression" to "食べる")
        for (t in listOf(BuiltInNoteTypes.KANJI, BuiltInNoteTypes.PHRASE, BuiltInNoteTypes.SENTENCE, BuiltInNoteTypes.GRAMMAR, BuiltInNoteTypes.CUSTOM)) {
            assertNull(resolve(all, d, t.info), t.info.baseType.wire)
            assertNull(resolve(emptyMap(), d, t.info), t.info.baseType.wire)
        }
    }

    @Test
    fun neverThrowsForOddContent() {
        val odd = listOf("", "<", "[sound:", "", "<ruby>食<rt>た</rt></ruby>べる", "&#xD800;", "[", "]]][[[", "\uD800")
        for (v in odd) {
            resolve(mapOf("audio" to v, "reading" to v, "expression" to v), d)
            resolve(mapOf("audio" to v, "reading" to v, "expression" to v), null)
        }
    }

    @Test
    fun effectiveVoice() {
        assertEquals("8", DefaultWordAudioResolver.effectiveVoice("8"))
        assertEquals("08", DefaultWordAudioResolver.effectiveVoice("08"))
        assertEquals("123456789", DefaultWordAudioResolver.effectiveVoice("123456789"))
        for (bad in listOf(null, "", "ずんだもん", "3 ", " 3", "1234567890", "8\n", "-1", "１")) {
            assertEquals(WordAudioRules.DEFAULT_VOICE, DefaultWordAudioResolver.effectiveVoice(bad), bad.toString())
        }
    }
}
