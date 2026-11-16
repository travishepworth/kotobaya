package kotobaya.android.shell.proxy

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import javax.crypto.KeyGenerator
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import kotobaya.android.pairing.TokenStore
import kotobaya.android.shell.api.ApiOperations
import kotobaya.android.shell.api.ShellRequest
import kotobaya.android.sync.HttpSyncTransport
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.OpType
import kotobaya.sync.client.mint.OpMinter
import kotobaya.sync.client.protocol.SyncEngine
import kotobaya.sync.client.store.Clock
import kotobaya.sync.client.store.InMemoryDeviceStore
import kotobaya.sync.client.store.OpApplier
import kotobaya.sync.client.store.PendingState
import kotobaya.sync.client.store.SyncStateKeys
import kotobaya.sync.client.store.UuidV7Source
import kotobaya.sync.client.store.bookkeeping
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SHELL-A5 acceptance (SHELL.md 9.2, 10.2): a revoked token makes the server answer `401`; the shell
 * passes that `401` through unchanged and neither the stored pairing nor the pending ops change. The
 * P-2 push is the real engine push over the real HTTP transport, so its AUTH failure is exercised too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RevokedTokenTest {
    private val server = MockWebServer().apply { start() }
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val tokens = TokenStore(ctx.getSharedPreferences("test-token-a5", Context.MODE_PRIVATE)) { key }
    private val token = "kbd_" + "r".repeat(40)
    private val clock = Clock { 1_733_584_666_000L }
    private val store = InMemoryDeviceStore(mutableListOf<String>()) { it.toMutableList() }
    private val applier = OpApplier<MutableList<String>> { tx, op, _ -> tx.learning.add(op.opId) }
    private val base = server.url("/api/v1").toString().trimEnd('/')
    private val engine = SyncEngine(store, HttpSyncTransport({ base }, { tokens.token }, { null }), applier, clock)

    private val engineSync = object : ProxySync {
        override suspend fun push() {
            engine.pushAll()
        }

        override suspend fun pull() {
            engine.pullAll()
        }

        override fun pullCursor(): Long = store.transaction { it.bookkeeping.pullSeq }
    }

    @AfterTest
    fun stop() {
        server.shutdown()
        ctx.getSharedPreferences("test-token-a5", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun aRevokedTokenIsSurfacedAs401AndThePairingAndPendingOpsAreUntouched() = runBlocking {
        tokens.token = token
        store.transaction { it.syncState.put(SyncStateKeys.DEVICE_ID, "01931f3e-0000-7000-8000-0000000000d5") }
        OpMinter(store, applier, clock, UuidV7Source(kotlin.random.Random(5))).mint(
            OpType.SETTINGS_SET,
            JsonObject(mapOf("key" to JsonString("scheduler.new_per_day"), "value" to JsonNumber(15.0))),
        )
        val before = store.rows()
        assertEquals(listOf(PendingState.PENDING), before.map { it.state })

        val problem = """{"type":"urn:kotobaya:problem:unauthenticated","title":"Authentication required","status":401,"code":"unauthenticated","request_id":"s"}"""
        repeat(2) {
            server.enqueue(MockResponse().setResponseCode(401).setBody(problem).setHeader("Content-Type", "application/problem+json"))
        }
        val upstream = ServerUpstream({ base }, { tokens.token }, { true }, engineSync)
        val res = upstream.forward(ShellRequest("GET", "/api/v1/stats/summary"), ApiOperations.byId.getValue("getStatsSummary"), "r1")

        assertEquals("/api/v1/sync/push", server.takeRequest().path) // P-2 push, refused
        assertEquals("/api/v1/stats/summary", server.takeRequest().path)
        assertEquals(401, res.status)
        assertEquals(problem, res.body)
        assertNull(res.headers["kotobaya-local"])
        assertEquals(token, tokens.token)
        assertEquals(before.map { it.opId to it.state }, store.rows().map { it.opId to it.state })
    }
}
