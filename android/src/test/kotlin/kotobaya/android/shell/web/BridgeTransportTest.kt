package kotobaya.android.shell.web

import android.net.Uri
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotobaya.android.shell.api.AlwaysOpenGate
import kotobaya.android.shell.api.LocalApi
import kotobaya.android.shell.api.LocalRoute
import kotobaya.android.shell.api.ShellRequest
import kotobaya.android.shell.api.ShellResponse
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.sync.DefaultCanonicalJson
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** SHELL-A3 acceptance: the WebMessageListener adapter in front of the real [LocalApi] (SHELL.md 2.1, 2.4). */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class BridgeTransportTest {
    private val dispatched = ArrayList<ShellRequest>()
    private val api = LocalApi(
        routes = listOf(
            LocalRoute("getMeta", "GET", "/api/v1/meta") { ShellResponse.json(JsonObject(mapOf("ok" to JsonString("meta")))) },
            LocalRoute("createNote", "POST", "/api/v1/notes") { ShellResponse.json(JsonObject(mapOf("ok" to JsonString("note")))) },
        ),
        gate = AlwaysOpenGate(),
        newRequestId = { "rid" },
    )
    private val transport = BridgeTransport(
        BridgeCore(
            scope = CoroutineScope(SupervisorJob()),
            io = Dispatchers.Unconfined,
            main = Dispatchers.Unconfined,
            handle = { r -> dispatched.add(r); api.handle(r) },
            helloData = { JsonObject(emptyMap()) },
        ),
    )
    private val view = WebView(ApplicationProvider.getApplicationContext())

    private class Proxy : JavaScriptReplyProxy() {
        val sent = ArrayList<String>()

        override fun postMessage(message: String) {
            sent.add(message)
        }

        override fun postMessage(arrayBuffer: ByteArray) = error("the bridge sends text only")
    }

    private fun post(text: String, origin: String = ShellOrigin.ORIGIN, mainFrame: Boolean = true): Proxy {
        val p = Proxy()
        transport.onPostMessage(view, WebMessageCompat(text), Uri.parse(origin), mainFrame, p)
        return p
    }

    private fun res(p: Proxy): BridgeMessage.Res =
        (BridgeCodec.decode(p.sent.single(), BridgeDirection.NATIVE_TO_WEB) as Decoded.Valid).message as BridgeMessage.Res

    @Test
    fun mainFrameOfTheShellOriginIsDispatched() {
        val p = post(reqText("00000000-0000-4000-8000-000000000001"))
        assertEquals(listOf("/api/v1/meta"), dispatched.map { it.path })
        val r = res(p)
        assertEquals(200, r.status)
        assertEquals("1", r.headers["kotobaya-local"])
    }

    @Test
    fun otherOriginIsNotDispatched() {
        val p1 = post(reqText("00000000-0000-4000-8000-000000000002"), origin = "https://evil.example")
        val p2 = post(reqText("00000000-0000-4000-8000-000000000003"), origin = "http://appassets.androidplatform.net")
        val p3 = post(reqText("00000000-0000-4000-8000-000000000004"), origin = "https://appassets.androidplatform.net.evil.example")
        assertEquals(emptyList(), dispatched)
        assertTrue(p1.sent.isEmpty() && p2.sent.isEmpty() && p3.sent.isEmpty())
    }

    @Test
    fun nonMainFrameIsNotDispatched() {
        val p = post(reqText("00000000-0000-4000-8000-000000000005"), mainFrame = false)
        assertEquals(emptyList(), dispatched)
        assertTrue(p.sent.isEmpty())
    }

    @Test
    fun oversizeBodyIs415ShellUnsupported() {
        // 1.5 M kana: 4.5 MB of UTF-8, under the schema's 4194304-character bound, over the 4 MiB byte limit
        val body = DefaultCanonicalJson.encode(JsonObject(mapOf("text" to JsonString("あ".repeat(1_500_000)))))
        val r = res(post(reqText("00000000-0000-4000-8000-000000000006", "POST", "/api/v1/notes", """{"content-type":"application/json"}""", body)))
        assertEquals(415, r.status)
        assertEquals("application/problem+json", r.headers["content-type"])
        val problem = DefaultCanonicalJson.decode(r.body!!).value as JsonObject
        assertEquals(JsonString("shell_unsupported"), problem.members["code"])
    }

    @Test
    fun nonJsonBodyIs415ShellUnsupported() {
        val r = res(post(reqText("00000000-0000-4000-8000-000000000007", "POST", "/api/v1/notes", """{"content-type":"text/plain"}""", "hi")))
        assertEquals(415, r.status)
        val ok = res(post(reqText("00000000-0000-4000-8000-000000000008", "POST", "/api/v1/notes", """{"content-type":"application/json; charset=utf-8"}""", "{}")))
        assertEquals(200, ok.status)
    }

    @Test
    fun arrayBufferMessagesAreIgnored() {
        val p = Proxy()
        transport.onPostMessage(view, WebMessageCompat(byteArrayOf(1, 2)), Uri.parse(ShellOrigin.ORIGIN), true, p)
        assertEquals(emptyList(), dispatched)
        assertTrue(p.sent.isEmpty())
    }
}
