package kotobaya.android.shell.api.confusables

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotobaya.android.shell.api.cards.LocalWritesSupport.assertOpsValidAndConverge
import kotobaya.android.shell.api.cards.LocalWritesSupport.call
import kotobaya.android.shell.api.cards.LocalWritesSupport.device
import kotobaya.android.shell.api.cards.LocalWritesSupport.items
import kotobaya.android.shell.api.cards.LocalWritesSupport.json
import kotobaya.android.shell.api.cards.LocalWritesSupport.mintedTypes
import kotobaya.android.shell.api.cards.LocalWritesSupport.problem
import kotobaya.android.shell.api.cards.LocalWritesSupport.runScenario
import kotobaya.android.shell.api.identity.IdentityTestSupport.minted
import kotobaya.android.shell.parity.ParityDevice
import kotobaya.android.shell.parity.ParityJson
import kotobaya.android.shell.parity.ParityScenario
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonString
import kotobaya.sync.client.apply.LearningTable
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** LF-A8b: `setConfusable`, `listConfusables`, `listWordConfusables` answered on the device (SHELL.md 4.3, L-10). */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class ConfusableWritesTest {
    private val scenario = "confusable-writes-local"
    private val seed = ParityScenario.load(scenario).seedLog
    private val n1 = "0193a1b2-0001-7000-8000-000000000001"
    private val n2 = "0193a1b2-0001-7000-8000-000000000002"
    private val n3 = "0193a1b2-0001-7000-8000-000000000003"
    private val t0 = 1_795_168_800_000L
    private val me = "01931f3e-0000-7000-8000-0000000000d4"

    private fun d(): ParityDevice = device(me, t0, seed)

    private fun ParityDevice.set(a: String, b: String, reason: String, status: String) =
        call("PUT", "/api/v1/confusables/$a/$b/$reason", """{"status":"$status"}""")

    private fun suggested(a: String, b: String, reason: String, at: Long) =
        RemoteOps.op("confusable.suggested", "confusable", "$a|$b|$reason", """{"note_a_id":"$a","note_b_id":"$b","reason":"$reason"}""", at, RemoteOps.SERVER)

    @Test
    fun confusableWritesLocalScenarioPassesWithoutTheUpstream() {
        val d = runScenario(scenario)
        assertEquals(listOf("confusable.confirmed", "confusable.rejected"), d.mintedTypes())
        val payload = ParityJson.parse("""{"note_a_id":"$n1","note_b_id":"$n2","reason":"meaning"}""")
        d.minted().forEach { assertEquals(payload, ParityJson.at(it, "/payload")) }
    }

    @Test
    fun mintedConfusableOpsValidateAndConvergeInAnyOrder() {
        val d = runScenario(scenario)
        assertOpsValidAndConverge(d, seed, listOf(LearningTable.CONFUSABLE), t0 + 60_000, emptySet())
    }

    @Test
    fun repeatingADecisionMintsAgainAndThePairIsOrdered() {
        val d = d()
        val first = d.set(n2, n1, "look_alike", "confirmed")
        assertEquals(200, first.status)
        assertEquals(JsonString(n1), first.json().members["note_a_id"])
        assertEquals(JsonString(n2), first.json().members["note_b_id"])
        assertEquals(JsonString(n1), ParityJson.at(first.json(), "/note_a/id"))
        assertEquals(200, d.set(n1, n2, "look_alike", "confirmed").status)
        assertEquals(listOf("confusable.confirmed", "confusable.confirmed"), d.mintedTypes())
        d.minted().forEach { assertEquals(JsonString(n1), ParityJson.at(it, "/payload/note_a_id")) }
        assertEquals(2, d.localChanges)
    }

    @Test
    fun refusalsMintNothing() {
        val d = d()
        val unknown = "0193a1b2-0001-7000-8000-00000000dead"
        assertEquals("422 /note_b_id invalid_value", d.set(n1, n1, "meaning", "confirmed").problem())
        assertEquals("404 not_found", d.set(n1, unknown, "meaning", "confirmed").problem())
        assertEquals("404 not_found", d.set(unknown, n2, "meaning", "confirmed").problem())
        assertEquals("422 /reason invalid_value", d.set(n1, n2, "similar", "confirmed").problem())
        assertEquals("422 /status invalid_value", d.set(n1, n2, "meaning", "suggested").problem())
        assertEquals("422 /note_a_id invalid_value", d.set("x", n2, "meaning", "confirmed").problem())
        assertEquals("422 /status required", d.call("PUT", "/api/v1/confusables/$n1/$n2/meaning", "{}").problem())
        assertEquals("422 /why unknown_field", d.call("PUT", "/api/v1/confusables/$n1/$n2/meaning", """{"status":"confirmed","why":1}""").problem())
        assertEquals(emptyList(), d.mintedTypes())
        assertEquals(0, d.localChanges)
        // a deleted note: 404, and its pairs are hidden from both lists
        assertEquals(200, d.set(n1, n2, "meaning", "confirmed").status)
        d.pull(listOf(RemoteOps.op("note.deleted", "note", n2, """{"id":"$n2"}""", t0 + 10_000)), "delete n2")
        assertEquals("404 not_found", d.set(n1, n2, "meaning", "rejected").problem())
        assertEquals(emptyList(), d.call("GET", "/api/v1/confusables").items())
        assertEquals(emptyList(), d.call("GET", "/api/v1/words/1000360/confusables").items())
        assertEquals(listOf("confusable.confirmed"), d.mintedTypes())
    }

    @Test
    fun aMergeLoserActsOnItsSurvivor() {
        val d = d()
        d.pull(
            listOf(
                RemoteOps.op("note.created", "note", n3, """{"id":"$n3","note_type_id":"00000000-0000-7000-8000-000000000101","entry_seq":1000360}""", t0 - 5_000),
                RemoteOps.op("note.merged", "note", n3, """{"loser_id":"$n3","survivor_id":"$n1"}""", t0 - 4_000, RemoteOps.SERVER),
            ),
            "merge n3 into n1",
        )
        val r = d.set(n3, n2, "homophone", "confirmed")
        assertEquals(200, r.status)
        assertEquals(JsonString(n1), r.json().members["note_a_id"])
        assertEquals(ParityJson.parse("""{"note_a_id":"$n1","note_b_id":"$n2","reason":"homophone"}"""), ParityJson.at(d.minted().single(), "/payload"))
        assertEquals("422 /note_b_id invalid_value", d.set(n3, n1, "homophone", "confirmed").problem())
        assertEquals(1, d.call("GET", "/api/v1/confusables", query = "note_id=$n1").items().size)
        assertEquals(1, d.mintedTypes().size)
    }

    @Test
    fun theWordListPutsConfirmedFirstAndFiltersByStatus() {
        val d = d()
        assertEquals(200, d.set(n1, n2, "meaning", "confirmed", ).status)
        assertEquals(200, d.call("PUT", "/api/v1/confusables/$n1/$n2/homophone", """{"status":"rejected"}""", at = t0 + 1_000).status)
        d.pull(listOf(suggested(n1, n2, "look_alike", t0 + 5_000)), "suggestion")
        val word = d.call("GET", "/api/v1/words/1000360/confusables").items()
        assertEquals(listOf("meaning", "look_alike"), word.map { ParityJson.str(it.members["reason"]) })
        assertEquals(listOf("confirmed", "suggested"), word.map { ParityJson.str(it.members["status"]) })
        assertEquals(listOf("homophone"), d.call("GET", "/api/v1/words/1000470/confusables", query = "status=rejected").items().map { ParityJson.str(it.members["reason"]) })
        assertEquals(emptyList(), d.call("GET", "/api/v1/words/1999999/confusables").items())
        assertEquals("422 /seq invalid_value", d.call("GET", "/api/v1/words/0/confusables").problem())
        assertEquals("422 /status invalid_value", d.call("GET", "/api/v1/words/1000360/confusables", query = "status=maybe").problem())
        assertEquals("422 /limit unknown_field", d.call("GET", "/api/v1/words/1000360/confusables", query = "limit=1").problem())
        // the full list: updated_at descending
        assertEquals(listOf("look_alike", "homophone", "meaning"), d.call("GET", "/api/v1/confusables").items().map { ParityJson.str(it.members["reason"]) })
        assertEquals(listOf("look_alike"), d.call("GET", "/api/v1/confusables", query = "status=suggested").items().map { ParityJson.str(it.members["reason"]) })
        // a suggestion never replaces a decision, whatever its clock
        d.pull(listOf(suggested(n1, n2, "meaning", t0 + 9_000)), "late suggestion")
        assertEquals(JsonString("confirmed"), d.call("GET", "/api/v1/confusables", query = "status=confirmed").items().single().members["status"])
    }

    @Test
    fun listConfusablesPagesByKeyset() {
        val d = d()
        listOf("meaning", "homophone", "look_alike").forEachIndexed { i, reason ->
            assertEquals(200, d.call("PUT", "/api/v1/confusables/$n1/$n2/$reason", """{"status":"confirmed"}""", at = t0 + i * 1_000L).status)
        }
        val page1 = d.call("GET", "/api/v1/confusables", query = "limit=2").json()
        assertEquals(listOf("look_alike", "homophone"), ParityJson.arr(page1.members["items"]).map { ParityJson.str(ParityJson.at(it, "/reason")) })
        val cursor = ParityJson.str(page1.members["next_cursor"])
        // a pair written between the pages is not repeated and does not shift the next page
        assertEquals(200, d.call("PUT", "/api/v1/confusables/$n1/$n2/meaning", """{"status":"rejected"}""", at = t0 + 10_000).status)
        val page2 = d.call("GET", "/api/v1/confusables", query = "limit=2&cursor=$cursor").json()
        assertEquals(emptyList(), ParityJson.arr(page2.members["items"]))
        assertEquals(JsonNull, page2.members["next_cursor"])
        assertEquals("422 /cursor invalid_value", d.call("GET", "/api/v1/confusables", query = "limit=2&status=confirmed&cursor=$cursor").problem())
        assertEquals("422 /cursor invalid_value", d.call("GET", "/api/v1/confusables", query = "cursor=nonsense").problem())
        assertEquals("422 /note_id invalid_value", d.call("GET", "/api/v1/confusables", query = "note_id=x").problem())
        assertTrue(d.forwarded.isEmpty())
    }
}
