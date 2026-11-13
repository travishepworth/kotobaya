package kotobaya.android.ankiconnect

import java.io.IOException
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class AnkiConnectServerTest {
    private var key: String? = null
    private var origins = listOf("http://localhost", "moz-extension://*")
    private val server = AnkiConnectServer(AnkiConnectHttp(AnkiConnectProtocol(FakeBackend()) { key }) { origins })

    @After
    fun tearDown() = server.close()

    private class Resp(val status: Int, val headers: Map<String, String>, val body: String)

    private fun request(port: Int, method: String, body: String = "", vararg headers: Pair<String, String>): Resp {
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 5000
            val bytes = body.toByteArray()
            val head = StringBuilder("$method / HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n")
            headers.forEach { (k, v) -> head.append("$k: $v\r\n") }
            head.append("Content-Length: ${bytes.size}\r\n\r\n")
            s.getOutputStream().apply { write(head.toString().toByteArray()); write(bytes); flush() }
            val raw = s.getInputStream().readBytes()
            val text = String(raw, Charsets.UTF_8)
            val (h, b) = text.split("\r\n\r\n", limit = 2)
            val lines = h.split("\r\n")
            return Resp(
                lines[0].split(' ')[1].toInt(),
                lines.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() },
                b,
            )
        }
    }

    @Test
    fun theSocketIsBoundToLoopbackOnly() {
        val port = server.start(0)
        val addr = assertNotNull(server.boundAddress)
        assertTrue(addr.address.isLoopbackAddress)
        assertEquals("127.0.0.1", addr.address.hostAddress)
        assertEquals(port, addr.port)
        // another interface's address must not reach it
        val other = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress && it.address.size == 4 }
        if (other != null) {
            assertFailsWith<IOException> { Socket().use { it.connect(InetSocketAddress(other, port), 1000) } }
        }
        server.stop()
        assertNull(server.boundAddress)
    }

    @Test
    fun postAnswersTheEnvelopeAndGetAnswersTheBanner() {
        val port = server.start(0)
        val r = request(port, "POST", """{"action":"version","version":6}""")
        assertEquals(200, r.status)
        assertEquals(6, JSONObject(r.body).getInt("result"))
        assertEquals("AnkiConnect v.6", request(port, "GET").body)
    }

    @Test
    fun originsAreCheckedAndAllowedOnesEchoed() {
        val port = server.start(0)
        val body = """{"action":"version","version":6}"""
        val ok = request(port, "POST", body, "Origin" to "moz-extension://abc-123")
        assertEquals(200, ok.status)
        assertEquals("moz-extension://abc-123", ok.headers["access-control-allow-origin"])
        assertEquals(200, request(port, "POST", body, "Origin" to "http://localhost").status)
        val bad = request(port, "POST", body, "Origin" to "https://evil.example")
        assertEquals(403, bad.status)
        assertNull(bad.headers["access-control-allow-origin"])
        assertEquals(200, request(port, "POST", body).status) // no Origin: not a browser
        val pre = request(port, "OPTIONS", "", "Origin" to "moz-extension://abc", "Access-Control-Request-Headers" to "content-type")
        assertEquals(204, pre.status)
        assertEquals("moz-extension://abc", pre.headers["access-control-allow-origin"])
        assertEquals(403, request(port, "OPTIONS", "", "Origin" to "https://evil.example").status)
    }

    @Test
    fun aNonLoopbackHostHeaderIsRefused() {
        val http = AnkiConnectHttp(AnkiConnectProtocol(FakeBackend()) { null }) { origins }
        assertEquals(403, http.handle("POST", mapOf("host" to "attacker.example:8765"), "{}").status)
        assertEquals(200, http.handle("POST", mapOf("host" to "localhost:8765"), """{"action":"version"}""").status)
        assertEquals(200, http.handle("POST", mapOf("host" to "[::1]:8765"), """{"action":"version"}""").status)
    }

    @Test
    fun apiKeyIsEnforcedOverHttp() {
        key = "k"
        val port = server.start(0)
        val denied = JSONObject(request(port, "POST", """{"action":"deckNames","version":6}""").body)
        assertEquals("valid api key must be provided", denied.getString("error"))
        val allowed = JSONObject(request(port, "POST", """{"action":"deckNames","version":6,"key":"k"}""").body)
        assertEquals(2, allowed.getJSONArray("result").length())
    }

    @Test
    fun aTakenPortFailsToStart() {
        val port = server.start(0)
        val second = AnkiConnectServer(AnkiConnectHttp(AnkiConnectProtocol(FakeBackend()) { null }) { origins })
        try {
            assertFailsWith<IOException> { second.start(port) }
        } finally {
            second.close()
        }
    }
}
