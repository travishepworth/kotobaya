package kotobaya.android.shell.api.media

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
import kotobaya.android.shell.api.confusables.RemoteOps
import kotobaya.android.shell.parity.ParityJson
import kotobaya.android.shell.parity.ParityScenario
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonString
import kotobaya.sync.client.store.PendingState
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * LF-A8b offline and route-table acceptance for the confusable, grammar and media operations: with the device offline
 * and a `SyncCoordinator` that never runs a cycle (the parity device), every write succeeds, its ops wait in
 * `pending_op`, the local reads show the change in the same test, and nothing reaches the upstream.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class LocalWritesOfflineBTest {
    private val t0 = 1_795_168_800_000L
    private val me = "01931f3e-0000-7000-8000-0000000000d4"
    private val n1 = "0193a1b2-0001-7000-8000-000000000001"
    private val n2 = "0193a1b2-0001-7000-8000-000000000002"
    private val g = "0193b000-0008-7000-8000-000000000009"
    private val s = "0193b000-0009-7000-8000-000000000009"
    private val m = "0193a1b2-0007-7000-8000-000000000009"

    @Test
    fun everyWriteSucceedsOfflineAndTheReadsShowItAtOnce() {
        val seed = ParityScenario.load("confusable-writes-local").seedLog + listOf(
            RemoteOps.op("media.created", "media", m, """{"id":"$m","title":"M","kind":"novel","source":"file"}""", t0 - 9_000, RemoteOps.SERVER),
            RemoteOps.op("deck.created", "deck", m, """{"id":"$m","name":"M","type":"media","media_id":"$m"}""", t0 - 9_000, RemoteOps.SERVER, 1),
        )
        val d = device(me, t0, seed)
        assertEquals(200, d.call("PUT", "/api/v1/confusables/$n1/$n2/meaning", """{"status":"confirmed"}""").status)
        assertEquals(1, d.call("GET", "/api/v1/confusables").items().size)
        assertEquals(1, d.call("GET", "/api/v1/words/1000360/confusables").items().size)

        assertEquals(201, d.call("POST", "/api/v1/grammar", """{"id":"$g","title":"Offline"}""").status)
        assertEquals(200, d.call("PATCH", "/api/v1/grammar/$g", """{"jlpt":3}""").status)
        assertEquals(JsonNumber(3.0), d.call("GET", "/api/v1/grammar/$g").json().members["jlpt"])
        assertEquals(listOf(g), d.call("GET", "/api/v1/grammar").items().map { ParityJson.str(it.members["id"]) })
        assertEquals(200, d.call("PUT", "/api/v1/grammar/$g/spans/$s/0/2").status)
        assertEquals(204, d.call("DELETE", "/api/v1/grammar/$g/spans/$s/0/2").status)
        assertEquals(204, d.call("DELETE", "/api/v1/grammar/$g").status)
        assertEquals(410, d.call("GET", "/api/v1/grammar/$g").status)

        assertEquals(200, d.call("PATCH", "/api/v1/media/$m", """{"title":"Renamed"}""").status)
        assertEquals(JsonString("Renamed"), d.call("GET", "/api/v1/media/$m").json().members["title"])
        assertEquals(204, d.call("DELETE", "/api/v1/media/$m").status)
        assertTrue(d.call("GET", "/api/v1/media").items().isEmpty())

        val types = listOf(
            "confusable.confirmed", "grammar.created", "grammar.updated", "grammar.span_put", "grammar.span_removed", "grammar.deleted",
            "media.updated", "media.deleted", "deck.deleted",
        )
        assertEquals(types, d.mintedTypes())
        assertEquals(types.size, d.store.read { it.outbox.inState(PendingState.PENDING) }.size)
        assertEquals(emptyList(), d.forwarded)
    }

    @Test
    fun theFourteenOperationsAreLocalRoutes() {
        val ids = setOf(
            "setConfusable", "listConfusables", "listWordConfusables",
            "createGrammarPoint", "updateGrammarPoint", "deleteGrammarPoint", "putGrammarSpan", "removeGrammarSpan", "listGrammarPoints", "getGrammarPoint",
            "updateMedia", "deleteMedia", "listMedia", "getMedia",
        )
        val registered = LocalRoutes.all.map { it.operationId }
        ids.forEach { id ->
            assertEquals(OpClass.LOCAL, ApiOperations.byId.getValue(id).opClass, id)
            assertEquals(1, registered.count { it == id }, "$id has one local route")
        }
    }
}
