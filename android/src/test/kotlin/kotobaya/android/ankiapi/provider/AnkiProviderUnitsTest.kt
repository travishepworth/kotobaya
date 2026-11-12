package kotobaya.android.ankiapi.provider

import android.app.Application
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AnkiProviderUnitsTest {
    @Test
    fun checksumMatchesAnkiForPlainAndHtmlFields() {
        // int(sha1("食べる".encode()).hexdigest()[:8], 16), as Anki computes it
        assertEquals(567984260L, AnkiContract.fieldChecksum("食べる"))
        assertEquals(567984260L, AnkiContract.fieldChecksum("<b>食べる</b>"))
        assertEquals(AnkiContract.fieldChecksum("a & b"), AnkiContract.fieldChecksum("a &amp; b"))
    }

    @Test
    fun fieldSplitKeepsEmptyFields() {
        assertEquals(listOf("a", "", ""), AnkiContract.splitFields("a\u001f\u001f"))
        assertEquals(listOf(""), AnkiContract.splitFields(""))
        assertEquals("a\u001f\u001f", AnkiContract.joinFields(listOf("a", "", "")))
    }

    @Test
    fun selectionParserForms() {
        fun f(sel: String?, vararg args: String) = NoteSelection.parse(sel, arrayOf(*args))
        assertEquals(NoteSelection.Parsed.All, f(null))
        assertEquals(NoteSelection.Parsed.All, f("  "))
        assertEquals(
            NoteSelection.Parsed.Filter(NoteFilter(5, setOf(1, 2), null)),
            f("mid=5 and csum in (1, 2)"),
        )
        assertEquals(NoteSelection.Parsed.Filter(NoteFilter(5, setOf(9), null)), f("mid = 5 AND csum = 9"))
        assertEquals(NoteSelection.Parsed.Filter(NoteFilter(5, null, "x")), f("mid:5 sfld=?", "x"))
        assertEquals(NoteSelection.Parsed.Filter(NoteFilter(null, null, "it's")), f("sfld='it''s'"))
        assertEquals(NoteSelection.Parsed.Filter(NoteFilter(null, null, "q")), f("sfld=\"q\""))
        assertEquals(NoteSelection.Parsed.NotUnderstood, f("flags = 0"))
    }

    @Test
    fun callLogIsBounded() {
        ProviderCallLog.clear()
        repeat(ProviderCallLog.CAPACITY + 50) { ProviderCallLog.log("line $it") }
        val lines = ProviderCallLog.snapshot()
        assertEquals(ProviderCallLog.CAPACITY, lines.size)
        assertTrue(lines.first().endsWith("line 50"))
        ProviderCallLog.log("x".repeat(10_000))
        assertTrue(ProviderCallLog.snapshot().last().length <= ProviderCallLog.MAX_LINE_CHARS + 1)
        ProviderCallLog.clear()
    }
}
