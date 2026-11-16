package kotobaya.android.shell.api.device

import android.app.Activity
import android.app.Application
import android.content.pm.ActivityInfo
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotobaya.android.shell.api.AlwaysOpenGate
import kotobaya.android.shell.api.LocalApi
import kotobaya.android.shell.api.ShellRequest
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** PORTRAIT-AND: `getDisplayPrefs` / `updateDisplayPrefs` (SHELL.md 6.3, 12). Both work unpaired. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DisplayRouteTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).create().get()
    private val ops = CoreDisplayOps(CoreDisplayOps.prefs(app))

    @Before
    fun attach() {
        CoreDisplayOps.prefs(app).edit().clear().commit()
        ActivityOrientation.attach(activity, CoreDisplayOps.storedLockPortrait(app))
    }

    @After
    fun detach() = ActivityOrientation.detach(activity)

    private fun api(paired: Boolean = true, rebuilding: Boolean = false) = LocalApi(
        routes = routes,
        gate = AlwaysOpenGate(paired = paired, rebuilding = rebuilding),
        services = MapServices(DisplayOps::class to ops),
        newRequestId = { "req" },
    )

    private fun patch(api: LocalApi, body: String) = runBlocking {
        api.handle(ShellRequest("PATCH", "/shell/v1/display", headers = mapOf("content-type" to "application/json"), body = body))
    }

    @Test
    fun defaultFalse() {
        val r = api().get("/shell/v1/display")
        assertEquals(200, r.status)
        assertEquals(false, r.json().bool("lock_portrait"))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, activity.requestedOrientation)
        assertEquals(true, "display" in CoreDeviceOps.CAPABILITIES)
    }

    @Test
    fun patchPersistsAndApplies() {
        val api = api()
        val on = patch(api, """{"lock_portrait":true}""")
        assertEquals(200, on.status)
        assertEquals(true, on.json().bool("lock_portrait"))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)
        assertEquals(true, CoreDisplayOps.storedLockPortrait(app), "survives a restart")
        assertEquals(true, api.get("/shell/v1/display").json().bool("lock_portrait"))
        val off = patch(api, """{"lock_portrait":false}""")
        assertEquals(false, off.json().bool("lock_portrait"))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, activity.requestedOrientation)
    }

    @Test
    fun emptyPatch422() {
        for ((body, pointer) in listOf(
            "{}" to "",
            """{"lock_portrait":"yes"}""" to "/lock_portrait",
            """{"lock_portrait":true,"x":1}""" to "/x",
        )) {
            val r = patch(api(), body)
            assertEquals(422, r.status, body)
            assertEquals(pointer, (((r.json().at("errors") as JsonArray).items[0]) as JsonObject).str("pointer"), body)
        }
        assertEquals(false, CoreDisplayOps.storedLockPortrait(app), "nothing changed")
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, activity.requestedOrientation)
    }

    /** The contract says both operations work unpaired (so the Pair screen honours the lock), unlike the ticket's `401`. */
    @Test
    fun unpairedWorksAndRebuilding503() {
        val unpaired = api(paired = false)
        assertEquals(200, unpaired.get("/shell/v1/display").status)
        assertEquals(200, patch(unpaired, """{"lock_portrait":true}""").status)
        val rebuilding = api(rebuilding = true)
        assertEquals(503, rebuilding.get("/shell/v1/display").status)
        assertEquals("shell_rebuilding", rebuilding.get("/shell/v1/display").json().str("code"))
        assertEquals(503, patch(rebuilding, """{"lock_portrait":false}""").status)
        assertEquals(true, CoreDisplayOps.storedLockPortrait(app), "a rebuilding patch changes nothing")
    }
}
