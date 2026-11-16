package kotobaya.android.shell.api.browse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotobaya.android.shell.api.actions.ActionsTestSupport.N1
import kotobaya.android.shell.api.actions.ActionsTestSupport.N2
import kotobaya.android.shell.api.actions.ActionsTestSupport.SEQ1
import kotobaya.android.shell.api.actions.ActionsTestSupport.problem
import kotobaya.android.shell.api.browse.BrowseTestSupport.device
import kotobaya.android.shell.api.browse.BrowseTestSupport.get
import kotobaya.android.shell.api.browse.BrowseTestSupport.ids
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class NotesTest {
    @Test
    fun filtersCombine() {
        val d = device()
        assertEquals(setOf(N1, N2), ids(d.get("/api/v1/notes")).toSet())
        assertEquals(listOf(N1), ids(d.get("/api/v1/notes", "entry_seq=$SEQ1")))
        assertEquals(listOf(N1), ids(d.get("/api/v1/notes", "entry_seq=$SEQ1&type=vocab")))
        assertEquals(emptyList(), ids(d.get("/api/v1/notes", "entry_seq=$SEQ1&type=kanji")))
        assertEquals(emptyList(), ids(d.get("/api/v1/notes", "tag=nope")))
    }

    @Test
    fun badFiltersAreRefusedAsTheServerDoes() {
        val d = device()
        assertEquals("422 /tag invalid_value", d.get("/api/v1/notes", "tag=leech").problem())
        assertEquals("422 /type invalid_value", d.get("/api/v1/notes", "type=x").problem())
        assertEquals("422 /nope unknown_field", d.get("/api/v1/notes", "nope=1").problem())
    }
}
