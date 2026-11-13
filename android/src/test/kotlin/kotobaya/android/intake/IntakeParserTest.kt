package kotobaya.android.intake

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IntakeParserTest {
    private fun send(text: String?) = IntakeParser.parse(IntakeParser.ACTION_SEND, "text/plain", text, null, null)

    private fun view(uri: String?) = IntakeParser.parse(IntakeParser.ACTION_VIEW, null, null, null, uri)

    @Test
    fun shareOfASentenceFillsTheSentence() {
        val t = assertIs<IntakeTarget.AddNote>(send("  猫が好きです。 "))
        assertEquals("", t.word)
        assertEquals("猫が好きです。", t.sentence)
        assertEquals("share", t.origin)
        assertFalse(t.trimmed)
    }

    @Test
    fun shareOfAShortWordFillsTheWord() {
        val t = assertIs<IntakeTarget.AddNote>(send("食べる"))
        assertEquals("食べる", t.word)
        assertEquals(null, t.sentence)
    }

    @Test
    fun emptyOrNonTextShareIsInvalid() {
        assertEquals(IntakeTarget.Invalid(InvalidReason.EMPTY), send("   "))
        assertEquals(IntakeTarget.Invalid(InvalidReason.EMPTY), send(null))
        assertEquals(
            IntakeTarget.Invalid(InvalidReason.UNSUPPORTED),
            IntakeParser.parse(IntakeParser.ACTION_SEND, "image/png", "x", null, null),
        )
    }

    @Test
    fun processTextIsTheSelection() {
        val t = assertIs<IntakeTarget.AddNote>(IntakeParser.parse(IntakeParser.ACTION_PROCESS_TEXT, "text/plain", null, "猫", null))
        assertEquals("猫", t.word)
        val long = assertIs<IntakeTarget.AddNote>(
            IntakeParser.parse(IntakeParser.ACTION_PROCESS_TEXT, "text/plain", null, "猫は 寝ている", null),
        )
        assertEquals("猫は 寝ている", long.sentence)
    }

    @Test
    fun addLinkWithAndWithoutSentence() {
        val a = assertIs<IntakeTarget.AddNote>(view("kotobaya://add?word=%E7%8C%AB&sentence=%E7%8C%AB%E3%81%8C%E5%AF%9D%E3%82%8B"))
        assertEquals("猫", a.word)
        assertEquals("猫が寝る", a.sentence)
        assertEquals("deeplink", a.origin)
        val b = assertIs<IntakeTarget.AddNote>(view("kotobaya://add?word=neko&reading=ねこ"))
        assertEquals("neko", b.word)
        assertEquals("ねこ", b.reading)
        assertEquals(null, b.sentence)
    }

    @Test
    fun rawJapaneseAndPlusAreDecoded() {
        val a = assertIs<IntakeTarget.AddNote>(view("kotobaya://add?word=食べる&sentence=a+b"))
        assertEquals("食べる", a.word)
        assertEquals("a b", a.sentence)
    }

    @Test
    fun missingWordAndMalformedUris() {
        assertEquals(IntakeTarget.Invalid(InvalidReason.MISSING_WORD), view("kotobaya://add?sentence=x"))
        assertEquals(IntakeTarget.Invalid(InvalidReason.MISSING_WORD), view("kotobaya://add"))
        assertEquals(IntakeTarget.Invalid(InvalidReason.MALFORMED), view("kotobaya://add?word=%E7%8"))
        assertEquals(IntakeTarget.Invalid(InvalidReason.MALFORMED), view("kotobaya://add?word=%ZZ"))
        assertEquals(IntakeTarget.Invalid(InvalidReason.UNSUPPORTED), view("https://example.com/add?word=x"))
        assertEquals(IntakeTarget.Invalid(InvalidReason.UNSUPPORTED), view("kotobaya://nope"))
        assertEquals(IntakeTarget.Invalid(InvalidReason.EMPTY), view(null))
        assertEquals(IntakeTarget.Invalid(InvalidReason.UNSUPPORTED), IntakeParser.parse("android.intent.action.MAIN", null, null, null, null))
    }

    @Test
    fun wordLink() {
        assertEquals(IntakeTarget.OpenWord("1358280"), view("kotobaya://word/1358280"))
        assertEquals(IntakeTarget.Invalid(InvalidReason.MALFORMED), view("kotobaya://word/"))
        assertEquals(IntakeTarget.Invalid(InvalidReason.MALFORMED), view("kotobaya://word/a/b"))
    }

    @Test
    fun overlongTextIsCappedAndFlagged() {
        val t = assertIs<IntakeTarget.AddNote>(send("あ".repeat(IntakeParser.MAX_CHARS + 50)))
        assertEquals(IntakeParser.MAX_CHARS, t.sentence!!.length)
        assertTrue(t.trimmed)
        // a surrogate pair straddling the cap is not split
        val pairs = "a".repeat(IntakeParser.MAX_CHARS - 1) + "😀😀"
        val p = assertIs<IntakeTarget.AddNote>(send(pairs))
        assertFalse(Character.isHighSurrogate(p.sentence!!.last()))
        assertTrue(p.trimmed)
    }
}
