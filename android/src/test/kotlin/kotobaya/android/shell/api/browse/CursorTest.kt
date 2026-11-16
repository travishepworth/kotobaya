package kotobaya.android.shell.api.browse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotobaya.android.shell.api.actions.ActionsTestSupport.T0
import kotobaya.android.shell.api.actions.ActionsTestSupport.problem
import kotobaya.android.shell.api.browse.BrowseTestSupport.device
import kotobaya.android.shell.api.browse.BrowseTestSupport.get
import kotobaya.android.shell.api.browse.BrowseTestSupport.ids
import kotobaya.android.shell.api.browse.BrowseTestSupport.next
import kotobaya.android.shell.api.browse.BrowseTestSupport.notesAndCards
import kotobaya.android.shell.api.browse.BrowseTestSupport.uuid
import kotobaya.android.shell.parity.ParityDevice
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Keyset paging of `listCards` / `listNotes`: every id exactly once, also when rows are created between pages. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class CursorTest {
    /** 250 extra notes with a card each (252 with the seed); created_at ties in groups of five. */
    private fun big(): ParityDevice = device().also { d ->
        d.clock.now = T0 + 1_000
        d.pull(notesAndCards(0, 250, { T0 - 100_000 + (it / 5) * 10L }, firstOp = 1000), "250 cards")
    }

    /** Pages of 100, calling [between] after the first page; returns every id in listing order. */
    private fun walk(d: ParityDevice, path: String, params: String, between: () -> Unit): List<String> {
        val seen = ArrayList<String>()
        var cursor: String? = null
        var first = true
        do {
            val r = d.get(path, "$params&limit=100" + (cursor?.let { "&cursor=$it" } ?: ""))
            assertEquals(200, r.status, r.body)
            seen += ids(r)
            cursor = next(r)
            if (first) between()
            first = false
        } while (cursor != null)
        return seen
    }

    @Test
    fun pagingOver250CardsReturnsEachIdOnce() {
        for (sort in listOf("created_at", "-created_at", "lapses", "-due_at")) {
            val seen = walk(big(), "/api/v1/cards", "sort=$sort") {}
            assertEquals(252, seen.size, sort)
            assertEquals(252, seen.toSet().size, sort)
        }
    }

    @Test
    fun aCardInsertedBetweenPagesNeitherRepeatsNorHidesAnother() {
        // all lapses are 0: the order is the id ascending, so a new card with a larger id belongs to a later page
        val d = big()
        val seen = walk(d, "/api/v1/cards", "sort=lapses") {
            d.clock.now = T0 + 2_000
            d.pull(notesAndCards(251, 2, { T0 }, firstOp = 5000), "two cards between pages")
        }
        assertEquals(seen.size, seen.toSet().size, "no id twice")
        val before = (0 until 250).map { uuid(2, it) } + listOf("0193a1b2-0002-7000-8000-000000000010", "0193a1b2-0002-7000-8000-000000000020")
        assertTrue(seen.containsAll(before), "every card that existed before paging is listed")
        assertTrue(uuid(2, 251) in seen && uuid(2, 252) in seen, "cards sorting after the cursor are listed once")
    }

    @Test
    fun aNewestFirstListingSkipsACardCreatedAheadOfTheCursor() {
        val d = big()
        val seen = walk(d, "/api/v1/cards", "sort=-created_at") {
            d.clock.now = T0 + 2_000
            d.pull(notesAndCards(300, 1, { T0 + 5_000 }, firstOp = 7000), "a newest card between pages")
        }
        assertEquals(seen.size, seen.toSet().size)
        assertEquals(252, seen.size)
        assertTrue(uuid(2, 300) !in seen)
    }

    @Test
    fun notesPageTheSameWay() {
        val d = big()
        val seen = walk(d, "/api/v1/notes", "") {
            d.pull(notesAndCards(400, 1, { T0 + 5_000 }, firstOp = 8000), "a newest note between pages")
        }
        assertEquals(252, seen.size)
        assertEquals(252, seen.toSet().size)
    }

    @Test
    fun aCursorOfAnotherRequestShapeIsRefused() {
        val d = big()
        val c = next(d.get("/api/v1/cards", "sort=created_at&limit=10"))
        assertEquals("422 /cursor invalid_value", d.get("/api/v1/cards", "sort=-created_at&limit=10&cursor=$c").problem())
        assertEquals("422 /cursor invalid_value", d.get("/api/v1/cards", "cursor=garbage").problem())
    }
}
