package kotobaya.android.shell.api.mining

import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotobaya.android.dict.DictLookup
import kotobaya.android.media.SpeechResolveQueue
import kotobaya.android.media.TtsAudioTable
import kotobaya.android.shell.api.LocalApi
import kotobaya.android.shell.api.LocalRoutes
import kotobaya.android.shell.api.ShellGate
import kotobaya.android.shell.api.ShellRequest
import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.api.ShellServices
import kotobaya.android.shell.api.Upstream
import kotobaya.android.shell.api.mining.MiningTestSupport.VOCAB
import kotobaya.android.shell.api.mining.MiningTestSupport.call
import kotobaya.android.shell.api.mining.MiningTestSupport.device
import kotobaya.android.shell.api.mining.MiningTestSupport.fieldError
import kotobaya.android.shell.api.mining.MiningTestSupport.IN_PACKAGE
import kotobaya.android.shell.api.mining.MiningTestSupport.json
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.android.shell.parity.ParityDevice
import kotobaya.android.shell.parity.ParityJson
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonString
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** `resolveSpeech` (C-31): answered from `tts_audio` on the device, the native resolver gets the missing pairs. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class ResolveSpeechRouteTest {
    private class Queue : SpeechResolveQueue {
        val requested = ArrayList<Triple<String, String, Boolean>>()
        val failed = HashMap<Pair<String, String>, Long>()

        override fun request(voice: String, text: String, urgent: Boolean) {
            requested += Triple(voice, text, urgent)
        }

        override fun failedRemainingMs(voice: String, text: String): Long? = failed[voice to text]
    }

    private class Map(private val byType: kotlin.collections.Map<KClass<*>, Any>) : ShellServices {
        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> get(type: KClass<T>): T = (byType[type] ?: error("no ${type.simpleName}")) as T
    }

    private class Wiring(val d: ParityDevice) {
        val table = TtsAudioTable(d.driver)
        val queue = Queue()
        val api = LocalApi(
            routes = LocalRoutes.all,
            gate = object : ShellGate {
                override fun isPaired() = true

                override fun isRebuildPending() = false

                override suspend fun awaitReady() = Unit
            },
            upstream = Upstream { _, op, _ -> error("forwarded ${op.operationId}") },
            services = Map(
                mapOf(
                    SqlDelightDeviceStore::class to d.store,
                    DictLookup::class to d.dictLookup,
                    TtsAudioTable::class to table,
                    SpeechResolveQueue::class to queue,
                ),
            ),
            newRequestId = { "t-1" },
            cache = null,
        )

        fun resolve(body: String): ShellResponse = runBlocking {
            api.handle(ShellRequest.fromRaw("POST", "/api/v1/tts/resolve", "", mapOf("content-type" to "application/json"), body))
        }
    }

    private fun items(r: ShellResponse) = ParityJson.arr(r.json().members["items"]).map { ParityJson.obj(it).members }

    @Test
    fun aPairWithARowIsReadyOneWithoutIsPendingAndRequestedAndAFailedOneIsFailed() {
        val w = Wiring(device())
        val hash = "a".repeat(64)
        w.table.upsert("3", "あり", hash, 640, 1)
        w.queue.failed["3" to "だめ"] = 120_000
        val r = w.resolve("""{"items":[{"text":"あり"},{"text":"なし","voice":"8"},{"text":"だめ"}],"urgent":true}""")
        assertEquals(200, r.status)
        assertEquals("1", r.headers["kotobaya-local"])
        val it = items(r)
        assertEquals(JsonString("ready"), it[0]["status"])
        assertEquals(JsonString(hash), it[0]["blob_hash"])
        assertEquals(JsonNumber(640.0), it[0]["duration_ms"])
        assertEquals(JsonString("pending"), it[1]["status"])
        assertEquals(JsonNull, it[1]["blob_hash"])
        assertEquals(JsonNumber(60_000.0), it[1]["retry_after_ms"], "offline")
        assertEquals(JsonString("failed"), it[2]["status"])
        assertEquals(JsonNumber(120_000.0), it[2]["retry_after_ms"])
        assertEquals(listOf(Triple("8", "なし", true)), w.queue.requested, "only the missing pair goes to the resolver")
    }

    @Test
    fun aNoteIdResolvesToItsSpeechPairAndAnUnknownNoteIsNone() {
        val d = device()
        val w = Wiring(d)
        val created = d.call("POST", "/api/v1/notes", """{"id":"0193b000-0001-7000-8000-0000000000a1","note_type_id":"$VOCAB","entry_seq":$IN_PACKAGE}""")
        assertEquals(201, created.status)
        val r = w.resolve("""{"items":[{"note_id":"0193b000-0001-7000-8000-0000000000a1"},{"note_id":"0193b000-0001-7000-8000-00000000dead"}]}""")
        val it = items(r)
        assertEquals(JsonString("pending"), it[0]["status"])
        assertEquals(JsonString("tts"), it[0]["source"])
        assertEquals(JsonString("none"), it[1]["status"])
        assertEquals(JsonNull, it[1]["blob_hash"])
        assertEquals(1, w.queue.requested.size)
        assertEquals("3", w.queue.requested.single().first)
        assertTrue(w.queue.requested.single().second.isNotEmpty())
        assertNull(r.headers["kotobaya-shell-partial"])
    }

    @Test
    fun validationIsTheServers() {
        val w = Wiring(device())
        val n = "0193b000-0001-7000-8000-0000000000a1"
        assertEquals("/items/0 invalid_value", w.resolve("""{"items":[{"text":"あ","note_id":"$n"}]}""").fieldError())
        assertEquals("/items/0 invalid_value", w.resolve("""{"items":[{"note_id":"$n","voice":"3"}]}""").fieldError())
        val many = (0..20).joinToString(",") { """{"text":"あ$it"}""" }
        assertEquals("/urgent invalid_value", w.resolve("""{"items":[$many],"urgent":true}""").fieldError())
        assertEquals(422, w.resolve("""{"items":[]}""").status)
        assertTrue(w.queue.requested.isEmpty(), "nothing is enqueued for a rejected request")
    }
}
