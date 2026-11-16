package kotobaya.android.shell.api.device

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import java.net.URLEncoder
import javax.crypto.KeyGenerator
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotobaya.android.net.ApiClient
import kotobaya.android.pairing.PairResult
import kotobaya.android.pairing.PairingRepository
import kotobaya.android.pairing.PairingTarget
import kotobaya.android.pairing.TokenStore
import kotobaya.android.settings.SettingsStore
import kotobaya.db.app.AppDatabase
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** SHELL-A6 acceptance: `pairShell` over the real [PairingRepository] against a mock server. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PairRouteTest {
    private val server = MockWebServer().apply { start() }
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val db = AppDatabase(AndroidSqliteDriver(AppDatabase.Schema, ctx, null))
    private val settings = SettingsStore(ctx)
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val tokens = TokenStore(ctx.getSharedPreferences("test-token-a6", Context.MODE_PRIVATE)) { key }
    private val repo = PairingRepository(
        db, settings, tokens, "Pixel 9",
        clientFor = { url -> ApiClient({ url }, { null }, { true }) },
        newDeviceId = { "0193aaaa-0000-7000-8000-000000000001" },
    )
    private val token = "kbd_" + "a".repeat(40)
    private val base get() = server.url("/api/v1").toString().trimEnd('/')

    private val ops = object : FakeDeviceOps() {
        override fun info() = super.info().copy(
            paired = tokens.token != null, authRequired = authRequired, serverUrl = settings.serverBaseUrl,
            deviceId = repo.deviceId,
        )

        override suspend fun pair(target: PairingTarget, name: String?): PairResult {
            pairs++
            return repo.pair(target, name, retryOnHlcRegressed = false)
        }
    }.also { it.paired = false }
    private val api = deviceApi(ops, paired = { false })

    @After
    fun stop() = server.shutdown()

    private fun uri(code: String = "K7Q2M9XD") =
        "kotobaya://pair?server=${URLEncoder.encode(base, "UTF-8")}&code=$code"

    private fun paired() = server.enqueue(
        MockResponse().setResponseCode(201).setBody(
            """{"token":"$token","token_id":"tok-1","scopes":["read","write","review"],"device":{},"server_device_id":"s","high_water_seq":0}""",
        ),
    )

    private fun problem(status: Int, code: String, extra: String = "") = server.enqueue(
        MockResponse().setResponseCode(status).setHeader("Content-Type", "application/problem+json")
            .setBody("""{"type":"x","title":"t","status":$status,"code":"$code","request_id":"r"$extra}"""),
    )

    @Test
    fun validCodePairsAndStoresTheTokenWithoutRevealingIt() {
        paired()
        val r = api.post("/shell/v1/pair", """{"uri":"${uri()}","name":"My phone"}""")
        assertEquals(200, r.status)
        assertEquals(token, tokens.token)
        assertEquals(true, r.json().bool("paired"))
        assertEquals(base, r.json().str("server_url"))
        assertFalse(r.body!!.contains(token) || r.body!!.contains("kbd_") || r.body!!.contains("\"token\""))
        assertTrue(r.headers.values.none { it.contains(token) })
        val sent = server.takeRequest()
        assertTrue(sent.body.readUtf8().let { it.contains("\"name\":\"My phone\"") && it.contains("K7Q2M9XD") })
        assertEquals("tok-1", db.syncOpsQueries.getSyncState("pairing.token_id").executeAsOne())
        assertEquals("My phone", db.syncOpsQueries.getSyncState("pairing.device_name").executeAsOne())
        assertEquals(1, ops.syncsStarted)
    }

    @Test
    fun serverUrlAndTypedCodeFormWorks() {
        paired()
        val r = api.post("/shell/v1/pair", """{"server_url":"$base","code":"k7q2-m9xd"}""")
        assertEquals(200, r.status)
        assertTrue(server.takeRequest().body.readUtf8().contains("K7Q2M9XD"))
    }

    @Test
    fun badCodeReturnsTheServerProblemAndStoresNothing() {
        problem(401, "invalid_pairing_code")
        val r = api.post("/shell/v1/pair", """{"uri":"${uri()}"}""")
        assertEquals(401, r.status)
        assertEquals("invalid_pairing_code", r.json().str("code"))
        assertNull(tokens.token)
        assertFalse(r.body!!.contains(token))
        assertEquals(0, ops.syncsStarted)
    }

    @Test
    fun rateLimitKeepsRetryAfter() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30").setHeader("Content-Type", "application/problem+json").setBody("""{"code":"rate_limited"}"""))
        val r = api.post("/shell/v1/pair", """{"uri":"${uri()}"}""")
        assertEquals(429, r.status)
        assertEquals("30", r.headers["retry-after"])
    }

    @Test
    fun alreadyPairedIs409AndNothingIsSent() {
        ops.paired = true
        paired()
        api.post("/shell/v1/pair", """{"uri":"${uri()}"}""")
        val again = api.post("/shell/v1/pair", """{"uri":"${uri()}"}""")
        assertEquals(409, again.status)
        assertEquals("already_exists", again.json().str("code"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun invalidBodiesAre422AndNeverReachTheServer() {
        fun code(body: String): String? {
            val r = api.post("/shell/v1/pair", body)
            assertEquals(422, r.status, body)
            return ((r.json().at("errors") as kotobaya.contracts.sync.JsonArray).items[0] as kotobaya.contracts.sync.JsonObject).str("code")
        }
        assertEquals("required", code("{}"))
        assertEquals("conflicting_fields", code("""{"uri":"${uri()}","code":"K7Q2M9XD"}"""))
        assertEquals("invalid_value", code("""{"uri":"kotobaya://pair?server=x"}"""))
        assertEquals("invalid_value", code("""{"server_url":"$base","code":"K7Q2"}"""))
        assertEquals("invalid_value", code("""{"server_url":"ftp://x","code":"K7Q2M9XD"}"""))
        assertEquals("unknown_field", code("""{"uri":"${uri()}","x":1}"""))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun offlineIs503ShellOffline() {
        ops.online = false
        val r = api.post("/shell/v1/pair", """{"uri":"${uri()}"}""")
        assertEquals(503, r.status)
        assertEquals("shell_offline", r.json().str("code"))
        assertEquals(0, server.requestCount)
    }
}
