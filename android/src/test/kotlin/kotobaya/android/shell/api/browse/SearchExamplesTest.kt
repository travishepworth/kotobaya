package kotobaya.android.shell.api.browse

import java.io.File
import java.net.URLEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotobaya.android.shell.api.actions.ActionsTestSupport.C1
import kotobaya.android.shell.api.actions.ActionsTestSupport.C2
import kotobaya.android.shell.api.actions.ActionsTestSupport.N1
import kotobaya.android.shell.api.actions.ActionsTestSupport.json
import kotobaya.android.shell.api.actions.ActionsTestSupport.problem
import kotobaya.android.shell.api.actions.CardQueryResult
import kotobaya.android.shell.api.browse.BrowseTestSupport.device
import kotobaya.android.shell.api.browse.BrowseTestSupport.get
import kotobaya.android.shell.api.browse.BrowseTestSupport.ids
import kotobaya.android.shell.parity.ParityJson
import kotobaya.contracts.sync.JsonArray
import kotobaya.search.DefaultSearchQueryParser
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every row of `contracts/core/search-examples.tsv` through `listCards`. A `+` row answers `200` with exactly the ids
 * the shared selection ([kotobaya.android.shell.api.actions.LocalCardQuery], which `bulkCards` also acts on) selects
 * on the same replica (the only other outcome allowed is the documented forward, `503 shell_offline` offline); a `-` row
 * is `422 invalid_query` at the parser's own position. There is no golden parity scenario for the listing yet, so a
 * few selections are also pinned by hand on the two seed cards.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class SearchExamplesTest {
    private data class Row(val expect: Char, val query: String)

    private fun rows(): List<Row> = File("../contracts/core/search-examples.tsv").readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { line ->
            val cols = line.split('\t')
            Row(cols[0][0], ParityJson.parse(cols[1]).let { (it as kotobaya.contracts.sync.JsonString).value })
        }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20") // the shell keeps + as +

    @Test
    fun everyExampleCompilesAndReturnsTheSharedSelection() {
        val d = device()
        var local = 0
        for (row in rows()) {
            val res = d.get("/api/v1/cards", "query=${enc(row.query)}&sort=created_at&limit=500")
            val parsed = DefaultSearchQueryParser.parse(row.query)
            if (row.expect == '-') {
                assertEquals(422, res.status, row.query)
                assertEquals("invalid_query", ParityJson.str(res.json().members["code"]), row.query)
                val first = ParityJson.obj((res.json().members["errors"] as JsonArray).items[0])
                assertEquals("/query", ParityJson.str(first.members["pointer"]), row.query)
                assertEquals(parsed.errors.first().span.start.toLong(), ParityJson.long(first.members["position"]), row.query)
                continue
            }
            val expected = d.cardQuery.select(row.query, d.clock.now)
            assertTrue(expected is CardQueryResult.Ids, "${row.query}: $expected")
            assertEquals(200, res.status, "${row.query}: ${res.body}")
            assertEquals(expected.ids.sorted(), ids(res).sorted(), row.query)
            local++
        }
        assertTrue(local >= 25, "most examples are answered locally ($local)")
    }

    @Test
    fun aFewSelectionsByHand() {
        val d = device()
        fun q(s: String) = ids(d.get("/api/v1/cards", "query=${enc(s)}&sort=created_at")).sorted()
        assertEquals(listOf(C1, C2), q(""))
        assertEquals(listOf(C1, C2), q("kind:meaning"))
        assertEquals(listOf(C1), q("nid:$N1"))
        assertEquals(emptyList(), q("cid:0193a1b2-0002-7000-8000-000000000001"))
        assertEquals(emptyList(), q("is:suspended"))
        assertEquals(listOf(C1, C2), q("-is:suspended"))
        assertEquals(listOf(C1, C2), q("is:new"))
    }

    @Test
    fun theResponseIsTheCardPageOfTheContract() {
        val d = device()
        val body = d.get("/api/v1/cards", "query=nid:$N1").json()
        val card = ParityJson.obj((body.members["items"] as JsonArray).items.single())
        assertEquals(setOf("id", "note_id", "kind", "preset_id", "suspended", "buried_until", "flags", "state", "step", "stability", "difficulty", "due_at", "interval_days", "last_review_at", "reps", "lapses", "created_at", "updated_at", "deleted_at", "merged_into_id", "note", "leech"), card.members.keys)
    }

    @Test
    fun unknownParametersAndBadSortAreRefusedAsTheServerDoes() {
        val d = device()
        assertEquals("422 /cursr unknown_field", d.get("/api/v1/cards", "cursr=1").problem())
        assertEquals("422 /sort invalid_value", d.get("/api/v1/cards", "sort=--due_at").problem())
        assertEquals("422 /limit out_of_range", d.get("/api/v1/cards", "limit=501").problem())
    }
}
