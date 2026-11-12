package kotobaya.android.pairing

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import kotobaya.db.app.AppDatabase
import java.io.File
import javax.crypto.KeyGenerator
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotobaya.android.net.ApiClient
import kotobaya.android.net.ApiError
import kotobaya.android.settings.SettingsStore
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Test as JTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PairingRepositoryTest {
    private val server = MockWebServer().apply { start() }
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val db = AppDatabase(AndroidSqliteDriver(AppDatabase.Schema, ctx, null))
    private val settings = SettingsStore(ctx)
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val tokens = TokenStore(ctx.getSharedPreferences("test-token", Context.MODE_PRIVATE)) { key }
    private var online = true
    private var ids = 0
    private val repo = PairingRepository(
        db, settings, tokens, "Pixel 9",
        clientFor = { url -> ApiClient({ url }, { null }, { online }) },
        newDeviceId = { "0193aaaa-0000-7000-8000-00000000000${++ids}" },
    )
    private val target get() = PairingTarget(server.url("/api/v1").toString().trimEnd('/'), "K7Q2M9XD")
    private val token = "kbd_" + "a".repeat(40)

    @After
    fun stop() = server.shutdown()

    private fun problem(status: Int, code: String, pointer: String? = null, ecode: String? = null) {
        val errors = if (pointer != null) ""","errors":[{"pointer":"$pointer","code":"$ecode"}]""" else ""
        server.enqueue(
            MockResponse().setResponseCode(status).setHeader("Content-Type", "application/problem+json")
                .setBody("""{"type":"x","title":"t","status":$status,"code":"$code","request_id":"r"$errors}"""),
        )
    }

    private fun failure(): ApiError = assertIs<PairResult.Failed>(runBlocking { repo.pair(target) }).error

    @JTest
    fun successWritesDeviceIdTokenAndBaseUrl() {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"token":"$token","token_id":"t","scopes":["read","write","review"],"device":{},"server_device_id":"s","high_water_seq":0}""",
            ),
        )
        val r = assertIs<PairResult.Paired>(runBlocking { repo.pair(target) })
        assertEquals(r.deviceId, db.syncOpsQueries.getSyncState("device_id").executeAsOne())
        assertEquals(token, tokens.token)
        assertEquals(target.serverBaseUrl, settings.serverBaseUrl)
        assertNull(settings.deviceToken)
        val req = server.takeRequest()
        assertEquals("/api/v1/devices/pair", req.path)
        assertNull(req.getHeader("Authorization"))
        assertTrue(req.body.readUtf8().contains(""""kind":"android""""))
        assertTrue(repo.isPaired)
    }

    @JTest
    fun tokenIsNotReadableFromPlainPrefs() {
        tokens.token = token
        val dir = File(ctx.applicationInfo.dataDir, "shared_prefs")
        val all = dir.listFiles().orEmpty().joinToString("\n") { it.readText() }
        assertTrue(all.contains("device_token_enc"))
        assertFalse(all.contains(token))
        assertEquals(token, tokens.token)
        tokens.token = null
        assertNull(tokens.token)
    }

    @JTest
    fun distinctTypedErrors() {
        problem(401, "invalid_pairing_code")
        assertIs<ApiError.InvalidPairingCode>(failure())
        problem(422, "validation_failed", "/kind", "invalid_value")
        assertTrue(assertIs<ApiError.Validation>(failure()).isKindConflict)
        problem(401, "unauthenticated")
        assertIs<ApiError.Unauthorized>(failure())
        problem(403, "insufficient_scope")
        assertIs<ApiError.Forbidden>(failure())
        problem(429, "rate_limited")
        assertIs<ApiError.RateLimited>(failure())
        problem(500, "internal")
        assertEquals(500, assertIs<ApiError.Server>(failure()).status)
        server.enqueue(MockResponse().setResponseCode(201).setBody("not json"))
        assertIs<ApiError.BadBody>(failure())
        server.shutdown()
        assertIs<ApiError.Unreachable>(failure())
        online = false
        assertIs<ApiError.Offline>(failure())
        assertFalse(repo.isPaired)
    }

    @JTest
    fun hlcRegressedMintsNewDeviceIdAndRetriesSameCode() {
        db.syncOpsQueries.putSyncState("device_id", "0193aaaa-0000-7000-8000-0000000000ff")
        db.syncOpsQueries.putSyncState("hlc_last", "0193a1b2c3d4-0001-x")
        problem(422, "validation_failed", "/hlc_last", "hlc_regressed")
        server.enqueue(
            MockResponse().setResponseCode(201).setBody("""{"token":"$token","server_device_id":"s"}"""),
        )
        val r = assertIs<PairResult.Paired>(runBlocking { repo.pair(target) })
        assertNotEquals("0193aaaa-0000-7000-8000-0000000000ff", r.deviceId)
        val first = server.takeRequest().body.readUtf8()
        val second = server.takeRequest().body.readUtf8()
        assertTrue(first.contains("hlc_last"))
        assertFalse(second.contains("hlc_last"))
        assertTrue(second.contains(r.deviceId))
    }

    @JTest
    fun unpairKeepsDeviceIdAndOutbox() {
        db.syncOpsQueries.insertPendingOp("op-1", "t", 1, "card", "c", "h", "{}", 1)
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"token":"$token","server_device_id":"s"}"""))
        val id = assertIs<PairResult.Paired>(runBlocking { repo.pair(target) }).deviceId
        repo.unpair()
        assertNull(tokens.token)
        assertEquals(id, repo.deviceId)
        assertEquals(1, db.syncOpsQueries.pendingOpsByState("pending").executeAsList().size)
    }

    @JTest
    fun uuidV7Shape() {
        assertTrue(Regex("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(uuidV7()))
    }
}
