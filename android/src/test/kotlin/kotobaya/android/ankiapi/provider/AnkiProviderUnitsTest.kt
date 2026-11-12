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
    fun selectionParserRejectsLeftoversOrAndUnboundPlaceholders() {
        fun f(sel: String, vararg args: String) = NoteSelection.parse(sel, arrayOf(*args))
        val nu = NoteSelection.Parsed.NotUnderstood
        assertEquals(nu, f("mid=1 or csum in (5)"))
        assertEquals(nu, f("mid=1 and flags=0"))
        assertEquals(nu, f("mid=1 and not csum=5"))
        // mid=? is not bound, so the leftover `?` makes it unparseable rather than misbinding args[0].
        assertEquals(nu, f("mid=? and sfld=?", "5", "x"))
        assertEquals(nu, f("mid=1 and sfld=?"))
        assertEquals(
            NoteSelection.Parsed.Filter(NoteFilter(1, null, "x")),
            f("(mid=1) AND (sfld=?)", "x"),
        )
        assertEquals(nu, f("mid=1 and mid=2"))
    }

    @Test
    fun checksumIsOverTheFirstFieldAndSortFieldIsStripped() {
        val store = InMemoryAnkiStore()
        val model = store.addModel(
            NewModel("M", listOf("Word", "Reading"), 1, "", null, 1, 0, null, null, null),
        )
        val note = store.addNote(NewNote(model.id, listOf("<b>食べる</b>", "<i>たべる</i>"), emptyList(), null, null))
        assertEquals(567984260L, note.checksum)
        assertEquals("たべる", note.sortField)
    }

    @Test
    fun stripHtmlIsBoundedOnHugeInput() {
        val huge = "<".repeat(1_000_000)
        assertTrue(AnkiContract.stripHtmlMedia(huge).length <= AnkiContract.MAX_STRIP_CHARS)
        AnkiContract.fieldChecksum(huge)
    }

    @Test
    fun releaseLogsShapesNotFieldValues() {
        val values = android.content.ContentValues().apply {
            put("mid", 5L)
            put("flds", "食べる\u001fto eat")
        }
        try {
            ProviderCallLog.revealValues = false
            val shown = ProviderCallLog.show(values)
            assertTrue("食べる" !in shown && "mid=5" in shown && "len=10 fields=2" in shown, shown)
            val sel = ProviderCallLog.showSelection("mid=5 and sfld='食べる'")
            assertTrue("食べる" !in sel && "mid=5" in sel, sel)
            ProviderCallLog.revealValues = true
            assertTrue("食べる<US>to eat" in ProviderCallLog.show(values))
        } finally {
            ProviderCallLog.revealValues = false
        }
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
