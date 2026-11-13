package kotobaya.android.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import kotobaya.sync.client.protocol.TransportResponse
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class HttpSyncTransportTest {
    private fun transport(server: MockWebServer, token: String? = "tok") =
        HttpSyncTransport({ server.url("/api/v1").toString() }, { token }, { null })

    @Test
    fun pushSendsTheEngineBodyByteForByteWithTheToken() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"results":[]}"""))
            // a slash and a float that org.json would rewrite
            val body = """{"ops":[{"payload":{"url":"a/b","x":1e+21}}]}"""
            val r = transport(server).push(body)
            assertEquals(TransportResponse.Ok("""{"results":[]}"""), r)
            val req = server.takeRequest()
            assertEquals("POST", req.method)
            assertEquals("/api/v1/sync/push", req.path)
            assertEquals("Bearer tok", req.getHeader("Authorization"))
            assertEquals(body, req.body.readUtf8())
        }
    }

    @Test
    fun pullAsksForSinceAndHttpErrorsAreReturnedNotThrown() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(413))
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":"token_revoked"}"""))
            val t = transport(server)
            assertEquals(413, assertIs<TransportResponse.HttpError>(t.push("{}")).status)
            assertEquals(401, assertIs<TransportResponse.HttpError>(t.pull(42)).status)
            server.takeRequest()
            assertEquals("/api/v1/sync/pull?since=42", server.takeRequest().path)
        }
    }

    @Test
    fun noServerOrNoConnectionIsANetworkError() = runBlocking<Unit> {
        val none = HttpSyncTransport({ null }, { null }, { null })
        assertIs<TransportResponse.NetworkError>(none.pull(0))
        val server = MockWebServer()
        server.start()
        val url = server.url("/api/v1").toString()
        server.shutdown()
        val closed = HttpSyncTransport({ url }, { null }, { null })
        assertIs<TransportResponse.NetworkError>(closed.push("{}"))
    }
}
