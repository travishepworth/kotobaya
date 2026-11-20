package kotobaya.android.shell.api.cards

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotobaya.android.shell.api.ApiOperations
import kotobaya.android.shell.api.LocalRoutes
import kotobaya.android.shell.api.OpClass
import kotobaya.android.shell.api.cards.LocalWritesSupport.call
import kotobaya.android.shell.api.cards.LocalWritesSupport.device
import kotobaya.android.shell.api.cards.LocalWritesSupport.items
import kotobaya.android.shell.api.cards.LocalWritesSupport.json
import kotobaya.android.shell.api.cards.LocalWritesSupport.mintedTypes
import kotobaya.android.shell.parity.ParityScenario
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonString
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * LF-A8 offline and route-table acceptance for the card, preset and note type writes: with the device offline and a
 * `SyncCoordinator` that never runs a cycle (the parity device), every write succeeds, its ops wait in `pending_op`, the
 * local reads show the change in the same test, and nothing reaches the upstream.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class LocalWritesOfflineTest {
    private val t0 = 1_795_168_800_000L
    private val n1 = "0193a1b2-0001-7000-8000-000000000001"
    private val c1 = "0193a1b2-0002-7000-8000-000000000010"
    private val preset = "0193b000-0006-7000-8000-000000000009"
    private val type = "0193b000-0007-7000-8000-000000000009"
    private val card = "0193b000-0002-7000-8000-000000000009"

    @Test
    fun everyWriteSucceedsOfflineAndTheReadsShowItAtOnce() {
        val d = device(CardWritesTest.DEVICE, t0, ParityScenario.load("card-writes-local").seedLog)
        assertEquals(201, d.call("POST", "/api/v1/presets", """{"id":"$preset","name":"Offline"}""").status)
        assertEquals(200, d.call("PATCH", "/api/v1/presets/$preset", """{"fuzz":false}""").status)
        assertEquals(JsonBool(false), d.call("GET", "/api/v1/presets/$preset").json().members["fuzz"])
        assertEquals(201, d.call("POST", "/api/v1/note-types", """{"id":"$type","name":"T","base_type":"custom","fields":[{"key":"front","label":"F","kind":"text"}],"templates":{"basic":{"default":true,"front":null,"back":null}}}""").status)
        assertEquals(200, d.call("PATCH", "/api/v1/note-types/$type", """{"name":"T2"}""").status)
        assertEquals(JsonString("T2"), d.call("GET", "/api/v1/note-types/$type").json().members["name"])
        assertEquals(201, d.call("POST", "/api/v1/cards", """{"id":"$card","note_id":"$n1","kind":"listening"}""").status)
        assertEquals(200, d.call("PATCH", "/api/v1/cards/$c1", """{"flags":5,"preset_id":"$preset"}""").status)
        val shown = d.call("GET", "/api/v1/cards/$c1").json()
        assertEquals(JsonNumber(5.0), shown.members["flags"])
        assertEquals(JsonString(preset), shown.members["preset_id"])
        assertEquals(204, d.call("DELETE", "/api/v1/cards/$card").status)
        assertEquals(410, d.call("GET", "/api/v1/cards/$card").status)
        assertEquals(204, d.call("DELETE", "/api/v1/note-types/$type").status)
        assertTrue(d.call("GET", "/api/v1/note-types").items().none { it.members["id"] == JsonString(type) })
        assertEquals(204, d.call("DELETE", "/api/v1/presets/$preset").status)
        assertTrue(d.call("GET", "/api/v1/presets").items().none { it.members["id"] == JsonString(preset) })
        assertEquals(
            listOf(
                "fsrs_params.created", "fsrs_params.updated", "note_type.created", "note_type.updated", "card.created",
                "card.flag_set", "card.preset_set", "card.deleted", "note_type.deleted", "fsrs_params.deleted",
            ),
            d.mintedTypes(),
        )
        assertEquals(emptyList(), d.forwarded)
    }

    @Test
    fun theNineWritesAreLocalRoutesAndNeverReachTheUpstream() {
        val ids = setOf(
            "createCard", "updateCard", "deleteCard", "createPreset", "updatePreset", "deletePreset",
            "createNoteType", "updateNoteType", "deleteNoteType",
        )
        val registered = LocalRoutes.all.map { it.operationId }
        ids.forEach { id ->
            assertEquals(OpClass.LOCAL, ApiOperations.byId.getValue(id).opClass, id)
            assertEquals(1, registered.count { it == id }, "$id has one local route")
        }
    }
}
