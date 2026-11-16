package kotobaya.android.shell.web

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotobaya.android.shell.api.ShellRequest
import kotobaya.android.shell.api.ShellResponse
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.sync.DefaultCanonicalJson

/** A [BridgeCore] on unconfined dispatchers: a handler that does not suspend answers synchronously. */
internal class CoreHarness(
    val handler: suspend (ShellRequest) -> ShellResponse = { ShellResponse.json(JsonObject(mapOf("ok" to JsonString(it.path)))) },
) {
    val handled = ArrayList<ShellRequest>()
    val replies = ArrayList<String>()
    val webMessages = ArrayList<BridgeMessage>()
    val reply = BridgeReply { replies.add(it) }
    val core = BridgeCore(
        scope = CoroutineScope(SupervisorJob()),
        io = Dispatchers.Unconfined,
        main = Dispatchers.Unconfined,
        handle = { r -> handled.add(r); handler(r) },
        helloData = { JsonObject(mapOf("high_water_seq" to JsonNumber(7.0))) },
        nowMs = { 1_763_632_800_000 },
        onWebMessage = { webMessages.add(it) },
    )

    fun fromWeb(text: String, origin: String = ShellOrigin.ORIGIN, mainFrame: Boolean = true) =
        core.onMessage(text, origin, mainFrame, reply)

    fun decodedReplies(): List<BridgeMessage> =
        replies.map { (BridgeCodec.decode(it, BridgeDirection.NATIVE_TO_WEB) as Decoded.Valid).message }
}

internal fun reqText(id: String, method: String = "GET", path: String = "/api/v1/meta", headers: String = "{}", body: String? = null): String {
    val b = body?.let { DefaultCanonicalJson.encode(JsonString(it)) } ?: "null"
    return """{"v":1,"t":"req","id":"$id","method":"$method","path":"$path","query":"","headers":$headers,"body":$b}"""
}

/** SHELL-A3 acceptance: the bridge codec against contracts/api/shell/examples, and the req/res invariants of SHELL.md 2.5. */
class BridgeCodecTest {
    private val examples = File("../contracts/api/shell/examples")

    private fun directions(file: File): List<BridgeDirection> = when (file.name.substringBefore('.')) {
        "web" -> listOf(BridgeDirection.WEB_TO_NATIVE)
        "native" -> listOf(BridgeDirection.NATIVE_TO_WEB)
        "any" -> BridgeDirection.values().toList()
        else -> error("example ${file.name} has no direction prefix")
    }

    private fun files(dir: String): List<File> =
        File(examples, dir).listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }.also { assertTrue(it.isNotEmpty(), dir) }

    @Test
    fun everyValidExampleDecodes() {
        for (f in files("bridge")) {
            for (d in directions(f)) {
                val r = BridgeCodec.decode(f.readText(), d)
                assertFalse(r is Decoded.Invalid, "${f.name} ($d): $r")
                if (r is Decoded.Valid) {
                    // what native would send round-trips through its own codec
                    assertEquals(r, BridgeCodec.decode(BridgeCodec.encode(r.message), d), f.name)
                }
            }
        }
        assertIs<Decoded.Unknown>(BridgeCodec.decode(File(examples, "bridge/any.unknown-type.json").readText(), BridgeDirection.WEB_TO_NATIVE))
    }

    @Test
    fun everyInvalidExampleIsRejected() {
        for (f in files("bridge-invalid")) {
            for (d in directions(f)) {
                assertIs<Decoded.Invalid>(BridgeCodec.decode(f.readText(), d), "${f.name} ($d)")
            }
        }
    }

    @Test
    fun invalidExamplesFromTheWebGetNoReplyAndAreNotDispatched() {
        val h = CoreHarness()
        for (f in files("bridge-invalid")) {
            if (BridgeDirection.WEB_TO_NATIVE in directions(f)) h.fromWeb(f.readText())
        }
        h.fromWeb("not json")
        h.fromWeb("[1,2]")
        h.fromWeb(File(examples, "bridge/any.unknown-type.json").readText())
        assertEquals(emptyList(), h.handled)
        assertEquals(emptyList(), h.replies)
        assertEquals(emptyList(), h.webMessages)
    }

    @Test
    fun validRequestExamplesAreAnsweredOnceEach() {
        val h = CoreHarness()
        val reqs = files("bridge").filter { it.name.startsWith("web.req.") }
        reqs.forEach { h.fromWeb(it.readText()) }
        // the examples share ids; every distinct id is answered exactly once
        val ids = reqs.map { (BridgeCodec.decode(it.readText(), BridgeDirection.WEB_TO_NATIVE) as Decoded.Valid).message as BridgeMessage.Req }
            .map { it.id }.distinct()
        assertEquals(ids, h.decodedReplies().map { (it as BridgeMessage.Res).id })
    }

    @Test
    fun duplicateIdAnsweredOnce() {
        val h = CoreHarness()
        val id = "7b1e3c52-9d0a-4f6e-8a51-2f4c8d1e9a01"
        h.fromWeb(reqText(id))
        h.fromWeb(reqText(id))
        h.fromWeb(reqText(id, path = "/api/v1/settings"))
        assertEquals(1, h.handled.size)
        assertEquals(1, h.replies.size)
        val res = h.decodedReplies().single() as BridgeMessage.Res
        assertEquals(id, res.id)
        assertEquals(200, res.status)
    }

    @Test
    fun responsesArePostedInCompletionOrder() {
        val gates = mapOf("/api/v1/meta" to CompletableDeferred<Unit>(), "/api/v1/settings" to CompletableDeferred())
        val h = CoreHarness { r ->
            gates.getValue(r.path).await()
            ShellResponse.json(JsonObject(mapOf("p" to JsonString(r.path))))
        }
        val a = "00000000-0000-4000-8000-00000000000a"
        val b = "00000000-0000-4000-8000-00000000000b"
        h.fromWeb(reqText(a, path = "/api/v1/meta"))
        h.fromWeb(reqText(b, path = "/api/v1/settings"))
        assertEquals(emptyList(), h.replies)
        gates.getValue("/api/v1/settings").complete(Unit)
        gates.getValue("/api/v1/meta").complete(Unit)
        assertEquals(listOf(b, a), h.decodedReplies().map { (it as BridgeMessage.Res).id })
    }

    @Test
    fun handlerExceptionStillGetsOneResponse() {
        val h = CoreHarness { error("boom") }
        h.fromWeb(reqText("00000000-0000-4000-8000-000000000001"))
        val res = h.decodedReplies().single() as BridgeMessage.Res
        assertEquals(500, res.status)
    }

    @Test
    fun reloadDropsLateResponsesAndForgetsIds() {
        val gate = CompletableDeferred<Unit>()
        val h = CoreHarness { gate.await(); ShellResponse.noContent() }
        val id = "00000000-0000-4000-8000-000000000002"
        h.fromWeb(reqText(id))
        h.core.onPageStarted()
        gate.complete(Unit)
        assertEquals(emptyList(), h.replies, "the res of the previous page life is dropped")
        h.fromWeb(reqText(id)) // a new page life may reuse the id
        assertEquals(2, h.handled.size)
        assertEquals(1, h.replies.size)
    }

    @Test
    fun readySendsHelloThenThePendingNavigate() {
        val h = CoreHarness()
        assertFalse(h.core.send(BridgeMessage.Navigate("/review")))
        assertFalse(h.core.send(BridgeMessage.Navigate("/dictionary?seq=1358280")))
        assertFalse(h.core.send(BridgeMessage.Event("sync.changed", "2026-11-20T10:00:00.000Z", JsonObject(emptyMap()))))
        assertEquals(emptyList(), h.replies, "nothing is sent before ready")
        h.fromWeb("""{"v":1,"t":"ready","web_version":"0.1.26","min_local_api":1}""")
        h.fromWeb("""{"v":1,"t":"ready","web_version":"0.1.26","min_local_api":1}""") // once per page life
        val sent = h.decodedReplies()
        assertEquals(2, sent.size)
        val hello = sent[0] as BridgeMessage.Event
        assertEquals("hello", hello.type)
        assertEquals("2025-11-20T10:00:00.000Z", hello.at)
        assertEquals(JsonNumber(7.0), hello.data.members["high_water_seq"])
        assertEquals(BridgeMessage.Navigate("/dictionary?seq=1358280"), sent[1])
        assertTrue(h.core.send(BridgeMessage.Navigate("/review")))
        h.core.onPageStarted()
        assertFalse(h.core.isReady)
    }

    @Test
    fun otherWebMessagesAreHandedOn() {
        val h = CoreHarness()
        for (name in listOf("web.back-result.handled", "web.back-state", "web.haptic", "web.theme")) {
            h.fromWeb(File(examples, "bridge/$name.json").readText())
        }
        assertEquals(listOf("back.result", "back.state", "haptic", "theme"), h.webMessages.map { it.t })
        assertEquals(emptyList(), h.replies)
    }
}
