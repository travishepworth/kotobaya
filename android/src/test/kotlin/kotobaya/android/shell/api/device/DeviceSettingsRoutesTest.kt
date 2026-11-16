package kotobaya.android.shell.api.device

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotobaya.android.ankiconnect.AnkiConnectPrefs
import kotobaya.android.ankiconnect.AnkiConnectStatus
import kotobaya.android.dict.DictSettingsPort
import kotobaya.android.dict.GapFillCounts
import kotobaya.android.dict.InstallResult
import kotobaya.android.dict.InstallState
import kotobaya.android.dict.PackageInfo
import kotobaya.android.media.MediaSettingsPort
import kotobaya.android.media.StorageBreakdown
import kotobaya.android.notify.NotifyPrefs
import kotobaya.android.shell.api.AlwaysOpenGate
import kotobaya.android.shell.api.LocalApi
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** SHELL-A7 acceptance: one route test per operation against a fake of the feature; install dedupe; permission below API 33. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DeviceSettingsRoutesTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun api(vararg entries: Pair<kotlin.reflect.KClass<*>, Any>) =
        LocalApi(routes = routes, gate = AlwaysOpenGate(), services = MapServices(*entries), newRequestId = { "req" })

    private fun patch(api: LocalApi, path: String, body: String) = runBlocking {
        api.handle(kotobaya.android.shell.api.ShellRequest("PATCH", path, headers = mapOf("content-type" to "application/json"), body = body))
    }

    private fun num(o: JsonObject, k: String) = (o.at(k) as JsonNumber).value.toLong()

    private fun pointer(r: kotobaya.android.shell.api.ShellResponse): String? =
        ((r.json().at("errors") as JsonArray).items[0] as JsonObject).str("pointer")

    // ------------------------------------------------------------------------------------ dictionary

    private class FakeDictPort : DictSettingsPort {
        override val installState = MutableStateFlow<InstallState>(InstallState.Idle)
        override val generation: StateFlow<Long> = MutableStateFlow(0L)
        var chosen = "top60k"
        var runs = 0
        val gate = CompletableDeferred<Unit>()
        var info: PackageInfo? = PackageInfo(3, "top60k", "x", 1)

        override fun scope() = chosen
        override fun setScope(scope: String) { chosen = scope }
        override suspend fun startup() = Unit
        override fun info() = info
        override fun installedBytes() = 1234L
        override fun refreshDictionary() = Unit
        override suspend fun runUpdate() {
            runs++
            gate.await()
            installState.value = InstallState.Finished(InstallResult.UpToDate)
        }
    }

    private fun dictOps(port: FakeDictPort, online: Boolean = true) = CoreDictOps(
        port, { GapFillCounts(212, 3) }, { 3L }, { online }, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined,
    )

    @Test
    fun getDictInstallReportsThePackageScopeAndGapFill() {
        val j = api(DictOps::class to dictOps(FakeDictPort())).get("/shell/v1/dict").json()
        assertEquals("top60k", j.str("scope"))
        assertEquals("idle", j.str("state"))
        val installed = j.at("installed") as JsonObject
        assertEquals(3, num(installed, "release_id"))
        assertEquals(1234, num(installed, "bytes"))
        assertEquals(212, num(j.at("gap_fill") as JsonObject, "cached"))
        assertEquals(3, num(j.at("gap_fill") as JsonObject, "missing"))
        assertEquals(3, num(j, "server_release_id"))
        assertEquals(JsonNull, j.at("last_result"))
    }

    @Test
    fun startDictInstallTwiceWhileRunningStartsOneInstall() {
        val port = FakeDictPort()
        val api = api(DictOps::class to dictOps(port))
        val first = api.post("/shell/v1/dict/install", """{"scope":"all"}""")
        val second = api.post("/shell/v1/dict/install")
        assertEquals(202, first.status)
        assertEquals(202, second.status)
        assertEquals("checking", second.json().str("state"))
        assertEquals(1, port.runs)
        assertEquals("all", port.chosen)
        port.gate.complete(Unit)
        val done = api.get("/shell/v1/dict").json()
        assertEquals("idle", done.str("state"))
        assertEquals("up_to_date", done.str("last_result"))
        assertEquals(1, port.runs)
    }

    @Test
    fun startDictInstallValidatesTheBodyAndRefusesOffline() {
        val port = FakeDictPort()
        val online = api(DictOps::class to dictOps(port))
        assertEquals(422, online.post("/shell/v1/dict/install", """{"scope":"everything"}""").status)
        assertEquals(422, online.post("/shell/v1/dict/install", """{"nope":1}""").status)
        val offline = api(DictOps::class to dictOps(port, online = false)).post("/shell/v1/dict/install")
        assertEquals(503, offline.status)
        assertEquals("shell_offline", offline.json().str("code"))
        assertEquals(0, port.runs)
    }

    // ------------------------------------------------------------------------------------ storage

    private class FakeMedia : MediaSettingsPort {
        override fun breakdown() = StorageBreakdown(100, 2, 0, 5, 1000)
        var cleared = 0
        override fun clearCache() { cleared++ }
        override var prefetchDays = 2
        override var budgetMb = 500
        override var allowMetered = false
    }

    @Test
    fun storageRoundTripsPrefsAndClearsTheCache() {
        val media = FakeMedia()
        val api = api(StorageOps::class to CoreStorageOps(media))
        val before = api.get("/shell/v1/storage").json()
        assertEquals(1105, num(before, "total_bytes"))
        val r = patch(api, "/shell/v1/storage", """{"budget_mb":200,"allow_metered":true,"clear_cache":true}""")
        assertEquals(200, r.status)
        assertEquals(200, num(r.json(), "budget_mb"))
        assertEquals(true, r.json().bool("allow_metered"))
        assertEquals(2, num(r.json(), "prefetch_days"))
        assertEquals(1, media.cleared)
    }

    @Test
    fun storageInvalidBodyIs422() {
        val api = api(StorageOps::class to CoreStorageOps(FakeMedia()))
        assertEquals("", pointer(patch(api, "/shell/v1/storage", "{}")))
        assertEquals("/prefetch_days", pointer(patch(api, "/shell/v1/storage", """{"prefetch_days":31}""")))
        assertEquals("/budget_mb", pointer(patch(api, "/shell/v1/storage", """{"budget_mb":"x"}""")))
        assertEquals("/zzz", pointer(patch(api, "/shell/v1/storage", """{"zzz":1}""")))
    }

    // ------------------------------------------------------------------------------------ notifications

    private class FakeSystem(override val sdkInt: Int, var granted: Boolean, val dialogAnswer: Boolean? = true) : NotificationSystem {
        var launches = 0
        override fun isGranted() = granted
        override suspend fun launchDialog(): Boolean? {
            launches++
            if (dialogAnswer == true) granted = true
            return dialogAnswer
        }
    }

    private class Denials : DenialCounter {
        override var count = 0
    }

    private fun notify(system: FakeSystem, denials: Denials = Denials()) =
        CoreNotifyOps(NotifyPrefs(app), system, denials, timeoutMs = 500)

    @Test
    fun notificationPrefsRoundTrip() {
        val api = api(NotifyOps::class to notify(FakeSystem(34, granted = true)))
        val r = patch(api, "/shell/v1/notifications", """{"nudges_enabled":true,"nudge_threshold":30,"evening_hour":21}""")
        assertEquals(200, r.status)
        val j = api.get("/shell/v1/notifications").json()
        assertEquals(true, j.bool("nudges_enabled"))
        assertEquals(30, num(j, "nudge_threshold"))
        assertEquals(21, num(j, "evening_hour"))
        assertEquals("granted", j.str("permission"))
        assertEquals(JsonNull, patch(api, "/shell/v1/notifications", """{"evening_hour":null}""").json().at("evening_hour"))
    }

    @Test
    fun notificationInvalidBodyIs422() {
        val api = api(NotifyOps::class to notify(FakeSystem(34, granted = true)))
        assertEquals("", pointer(patch(api, "/shell/v1/notifications", "{}")))
        assertEquals("/evening_hour", pointer(patch(api, "/shell/v1/notifications", """{"evening_hour":24}""")))
        assertEquals("/nudges_enabled", pointer(patch(api, "/shell/v1/notifications", """{"nudges_enabled":"yes"}""")))
    }

    @Test
    fun requestNotificationPermissionBelowApi33AnswersGrantedWithoutLaunching() {
        val system = FakeSystem(32, granted = true)
        val r = api(NotifyOps::class to notify(system)).post("/shell/v1/notifications/permission")
        assertEquals(200, r.status)
        assertEquals("granted", r.json().str("permission"))
        assertEquals(false, r.json().bool("can_request"))
        assertEquals(0, system.launches)
    }

    @Test
    fun requestNotificationPermissionOnApi33LaunchesTheDialogAndCountsDenials() {
        val system = FakeSystem(34, granted = false, dialogAnswer = false)
        val denials = Denials()
        val api = api(NotifyOps::class to notify(system, denials))
        assertEquals("not_requested", api.get("/shell/v1/notifications").json().str("permission"))
        assertEquals("denied", api.post("/shell/v1/notifications/permission").json().str("permission"))
        assertEquals(true, api.get("/shell/v1/notifications").json().bool("can_request"))
        api.post("/shell/v1/notifications/permission")
        assertEquals(false, api.get("/shell/v1/notifications").json().bool("can_request"))
        api.post("/shell/v1/notifications/permission") // denied twice: no dialog any more
        assertEquals(2, system.launches)
    }

    // ------------------------------------------------------------------------------------ push

    private class FakePush : PushOps {
        var state = PushData("off", false)
        override fun state() = state
        override fun update(enabled: Boolean): PushData {
            state = if (enabled) PushData("waiting", false) else PushData("off", false)
            return state
        }
    }

    @Test
    fun pushStateRoundTrips() {
        val api = api(PushOps::class to FakePush())
        assertEquals("off", api.get("/shell/v1/push").json().str("state"))
        assertEquals("waiting", patch(api, "/shell/v1/push", """{"enabled":true}""").json().str("state"))
        assertEquals("waiting", api.get("/shell/v1/push").json().str("state"))
        assertEquals("off", patch(api, "/shell/v1/push", """{"enabled":false}""").json().str("state"))
    }

    @Test
    fun pushInvalidBodyIs422() {
        val api = api(PushOps::class to FakePush())
        assertEquals("/enabled", pointer(patch(api, "/shell/v1/push", "{}")))
        assertEquals("/enabled", pointer(patch(api, "/shell/v1/push", """{"enabled":"yes"}""")))
        assertEquals("/x", pointer(patch(api, "/shell/v1/push", """{"enabled":true,"x":1}""")))
    }

    // ------------------------------------------------------------------------------------ AnkiConnect

    private class FakeEngine(private val prefs: AnkiConnectPrefs, private val portInUse: Int? = null) : AnkiConnectEngine {
        override val status = MutableStateFlow<AnkiConnectStatus>(AnkiConnectStatus.Stopped)
        var applies = 0
        override fun apply() {
            applies++
            status.value = when {
                !prefs.enabled -> AnkiConnectStatus.Stopped
                prefs.port == portInUse -> AnkiConnectStatus.Failed("port in use")
                else -> AnkiConnectStatus.Running(prefs.port)
            }
        }
    }

    @Test
    fun ankiConnectStateRoundTripsAndRestartsTheServer() {
        val prefs = AnkiConnectPrefs(app)
        val engine = FakeEngine(prefs)
        val api = api(AnkiConnectOps::class to CoreAnkiConnectOps(prefs, engine, settleMs = 500))
        val before = api.get("/shell/v1/ankiconnect").json()
        assertEquals("stopped", before.str("status"))
        assertEquals(8765, num(before, "port"))
        assertEquals(JsonArray(emptyList()), before.at("origins"))
        val r = patch(api, "/shell/v1/ankiconnect", """{"enabled":true,"port":9000,"api_key":"k","origins":["https://app.example"]}""")
        assertEquals(200, r.status)
        val j = r.json()
        assertEquals("running", j.str("status"))
        assertEquals(9000, num(j, "port"))
        assertEquals("k", j.str("api_key"))
        assertEquals(1, engine.applies)
        assertEquals("https://app.example", ((j.at("origins") as JsonArray).items[0] as kotobaya.contracts.sync.JsonString).value)
        // an empty list is the built-in default again
        assertEquals(JsonArray(emptyList()), patch(api, "/shell/v1/ankiconnect", """{"origins":[]}""").json().at("origins"))
    }

    @Test
    fun ankiConnectPortInUseStoresTheSettingsAndReportsFailed() {
        val prefs = AnkiConnectPrefs(app)
        val api = api(AnkiConnectOps::class to CoreAnkiConnectOps(prefs, FakeEngine(prefs, portInUse = 9001), settleMs = 500))
        val r = patch(api, "/shell/v1/ankiconnect", """{"enabled":true,"port":9001}""")
        assertEquals(200, r.status)
        assertEquals("failed", r.json().str("status"))
        assertEquals("port in use", r.json().str("status_message"))
        assertEquals(9001, prefs.port)
    }

    @Test
    fun ankiConnectInvalidBodyIs422() {
        val prefs = AnkiConnectPrefs(app)
        val api = api(AnkiConnectOps::class to CoreAnkiConnectOps(prefs, FakeEngine(prefs), settleMs = 100))
        assertEquals("", pointer(patch(api, "/shell/v1/ankiconnect", "{}")))
        assertEquals("/port", pointer(patch(api, "/shell/v1/ankiconnect", """{"port":80}""")))
        assertEquals("/origins/0", pointer(patch(api, "/shell/v1/ankiconnect", """{"origins":["has space"]}""")))
        assertEquals("/origins", pointer(patch(api, "/shell/v1/ankiconnect", """{"origins":["a","a"]}""")))
        assertEquals("/api_key", pointer(patch(api, "/shell/v1/ankiconnect", "{\"api_key\":\"a\\u0001\"}")))
        assertNull(prefs.originList().firstOrNull { it == "has space" })
    }
}
