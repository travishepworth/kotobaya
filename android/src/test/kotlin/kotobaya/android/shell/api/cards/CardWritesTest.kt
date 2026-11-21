package kotobaya.android.shell.api.cards

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotobaya.android.shell.api.actions.ActionsTestSupport.obj
import kotobaya.android.shell.api.actions.ActionsTestSupport.remote
import kotobaya.android.shell.api.cards.LocalWritesSupport.assertOpsValidAndConverge
import kotobaya.android.shell.api.cards.LocalWritesSupport.call
import kotobaya.android.shell.api.cards.LocalWritesSupport.device
import kotobaya.android.shell.api.cards.LocalWritesSupport.json
import kotobaya.android.shell.api.cards.LocalWritesSupport.mintedTypes
import kotobaya.android.shell.api.cards.LocalWritesSupport.problem
import kotobaya.android.shell.api.cards.LocalWritesSupport.runScenario
import kotobaya.android.shell.api.identity.IdentityTestSupport.minted
import kotobaya.android.shell.parity.ParityDevice
import kotobaya.android.shell.parity.ParityJson
import kotobaya.android.shell.parity.ParityScenario
import kotobaya.contracts.sync.JsonString
import kotobaya.sync.client.apply.LearningTable
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * LF-A8: `createCard`, `updateCard`, `deleteCard` answered on the device (SHELL.md 4.3, L-10): the parity scenario
 * `card-writes-local` with no upstream, the minted ops (schema, device's own, out-of-order convergence), idempotency
 * (L-4) and the refusals that mint nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class CardWritesTest {
    private val scenario = "card-writes-local"
    private val n1 = "0193a1b2-0001-7000-8000-000000000001"
    private val c1 = "0193a1b2-0002-7000-8000-000000000010"
    private val slow = "0193a1b2-0006-7000-8000-000000000001"
    private val listening = "0193b000-0002-7000-8000-000000000001"
    private val t0 = 1_795_168_800_000L

    private fun seeded(): ParityDevice = device(DEVICE, t0, ParityScenario.load(scenario).seedLog)

    @Test
    fun cardWritesLocalScenarioPassesWithoutTheUpstream() {
        val d = runScenario(scenario)
        assertEquals(
            listOf(
                "card.suspended", "card.flag_set", "card.preset_set", "card.unsuspended", "card.buried", "card.unburied",
                "card.created", "card.deleted",
            ),
            d.mintedTypes(),
        )
        assertEquals(6, d.localChanges, "one trigger per minting request (steps 0-3, 5, 10)")
    }

    @Test
    fun mintedCardOpsValidateAndConvergeInAnyOrder() {
        val d = runScenario(scenario)
        val created = d.minted().single { ParityJson.str(it.members["op_type"]) == "card.created" }
        assertEquals(
            ParityJson.parse("""{"id":"$listening","note_id":"$n1","kind":"listening"}"""),
            ParityJson.at(created, "/payload"),
        )
        assertOpsValidAndConverge(d, ParityScenario.load(scenario).seedLog, listOf(LearningTable.CARD), t0 + 60_000, setOf("card.created"))
    }

    @Test
    fun createTwiceWithTheSameIdOrTheSameNoteAndKindMintsOneOp() {
        val d = seeded()
        val first = d.call("POST", "/api/v1/cards", """{"id":"$listening","note_id":"$n1","kind":"listening"}""", at = t0 + 1_000)
        assertEquals(201, first.status)
        val again = d.call("POST", "/api/v1/cards", """{"id":"$listening","note_id":"$n1","kind":"listening"}""")
        assertEquals(200, again.status)
        assertEquals(first.json(), again.json())
        val noId = d.call("POST", "/api/v1/cards", """{"note_id":"$n1","kind":"listening"}""")
        assertEquals(200, noId.status)
        assertEquals(JsonString(listening), noId.json().members["id"])
        // the seeded meaning card, asked for without an id: the existing live card
        assertEquals(JsonString(c1), d.call("POST", "/api/v1/cards", """{"note_id":"$n1","kind":"meaning"}""").json().members["id"])
        assertEquals(listOf("card.created"), d.mintedTypes())
        assertEquals(1, d.localChanges)
    }

    @Test
    fun createWithoutIdMintsADeviceUuidV7() {
        val d = seeded()
        val r = d.call("POST", "/api/v1/cards", """{"note_id":"$n1","kind":"production"}""", at = t0 + 1_000)
        assertEquals(201, r.status)
        val id = ParityJson.str(r.json().members["id"])
        assertEquals('7', id[14])
        assertEquals(id, ParityJson.str(ParityJson.at(d.minted().single(), "/payload/id")))
    }

    @Test
    fun deleteTwiceMintsOneOp() {
        val d = seeded()
        assertEquals(204, d.call("DELETE", "/api/v1/cards/$c1").status)
        assertEquals(204, d.call("DELETE", "/api/v1/cards/$c1").status)
        assertEquals("410 gone", d.call("GET", "/api/v1/cards/$c1").problem())
        assertEquals(listOf("card.deleted"), d.mintedTypes())
        assertEquals("404 not_found", d.call("DELETE", "/api/v1/cards/0193a1b2-0002-7000-8000-0000000000ff").problem())
    }

    @Test
    fun refusalsMintNothing() {
        val d = seeded()
        val unknown = "0193a1b2-0002-7000-8000-0000000000ff"
        assertEquals("404 not_found", d.call("PATCH", "/api/v1/cards/$unknown", """{"flags":1}""").problem())
        assertEquals("422 /preset_id invalid_value", d.call("PATCH", "/api/v1/cards/$c1", """{"preset_id":"$unknown"}""").problem())
        assertEquals("422 /flags out_of_range", d.call("PATCH", "/api/v1/cards/$c1", """{"flags":8}""").problem())
        assertEquals("422 /nope unknown_field", d.call("PATCH", "/api/v1/cards/$c1", """{"nope":1}""").problem())
        assertEquals("422 /kind invalid_value", d.call("POST", "/api/v1/cards", """{"note_id":"$n1","kind":"nope"}""").problem())
        assertEquals("422 /kind invalid_value", d.call("POST", "/api/v1/cards", """{"note_id":"$n1","kind":"reading"}""").problem())
        assertEquals("404 not_found", d.call("POST", "/api/v1/cards", """{"note_id":"$unknown","kind":"meaning"}""").problem())
        // an existing id for another kind is a conflict
        assertEquals("409 conflict", d.call("POST", "/api/v1/cards", """{"id":"$c1","note_id":"$n1","kind":"listening"}""").problem())
        // a deleted preset is not live
        assertEquals(204, d.call("DELETE", "/api/v1/presets/$slow").status)
        assertEquals("422 /preset_id invalid_value", d.call("PATCH", "/api/v1/cards/$c1", """{"preset_id":"$slow"}""").problem())
        assertEquals(listOf("fsrs_params.deleted"), d.mintedTypes())
    }

    @Test
    fun tombstonesAnswerGoneAndNothingIsMinted() {
        val d = seeded()
        assertEquals(204, d.call("DELETE", "/api/v1/notes/$n1").status)
        assertEquals("410 gone", d.call("PATCH", "/api/v1/cards/$c1", """{"suspended":true}""").problem())
        assertEquals("410 gone", d.call("POST", "/api/v1/cards", """{"note_id":"$n1","kind":"listening"}""").problem())
        assertEquals(listOf("note.deleted"), d.mintedTypes())
    }

    @Test
    fun aMergeLoserIsAnsweredWithItsSurvivor() {
        val d = seeded()
        val loser = "0193a1b2-0002-7000-8000-000000000011"
        // another replica created a second meaning card; the server merged it into the first one
        d.pull(
            listOf(
                remote(1, "card.created", "card", loser, t0 - 1_000, obj("id" to JsonString(loser), "note_id" to JsonString(n1), "kind" to JsonString("meaning"))),
                remote(2, "card.merged", "card", loser, t0 - 500, obj("loser_id" to JsonString(loser), "survivor_id" to JsonString(c1)), device = SERVER),
            ),
            "merge",
        )
        val r = d.call("PATCH", "/api/v1/cards/$loser", """{"flags":3}""", at = t0 + 1_000)
        assertEquals(200, r.status)
        assertEquals("/api/v1/cards/$c1", r.headers["content-location"])
        assertEquals(JsonString(c1), r.json().members["id"])
        assertEquals(c1, ParityJson.str(ParityJson.at(d.minted().single(), "/payload/id")))
        // deleting a merge loser deletes nothing
        assertEquals(204, d.call("DELETE", "/api/v1/cards/$loser").status)
        assertEquals(listOf("card.flag_set"), d.mintedTypes())
        assertNull(d.call("GET", "/api/v1/cards/$c1").json().members["deleted_at"]?.takeIf { it is JsonString })
    }

    companion object {
        const val DEVICE = "01931f3e-0000-7000-8000-0000000000d4"
        const val SERVER = "00000000-0000-7000-8000-000000000001"
    }
}
