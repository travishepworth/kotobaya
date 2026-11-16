package kotobaya.android.shell.api.device

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotobaya.android.dict.GapFillCounts
import kotobaya.android.shell.events.ShellEvents
import kotobaya.android.shell.web.BridgeMessage
import kotobaya.android.sync.NetworkMonitor
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** SHELL-A7: each event source reaches the bridge's send (local write, connectivity, status flow, dictionary). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DeviceEventSourcesTest {
    private val sent = ArrayList<BridgeMessage.Event>()
    private val status = FakeDeviceOps()
    private val events = ShellEvents(
        scope = CoroutineScope(Dispatchers.Unconfined),
        send = { synchronized(sent) { sent += it } },
        syncChanged = { 48213L to 1 },
        syncStatus = { status.syncStatus().toJson() },
        intervalMs = 0,
    )

    private fun types() = synchronized(sent) { sent.map { it.type } }

    @Test
    fun aLocalWriteEmitsSyncChanged() {
        events.onLocalWrite()
        assertEquals(1, types().count { it == "sync.changed" })
    }

    @Test
    fun theStatusFlowEmitsShellSync() {
        val flow = MutableStateFlow<kotobaya.android.sync.SyncStatusView?>(null)
        events.watch(flow)
        assertEquals(1, types().count { it == "shell.sync" })
        status.pending = 5
        flow.value = status.syncStatus().view
        assertEquals(2, types().count { it == "shell.sync" })
    }

    @Test
    fun theNetworkMonitorHookReachesConnectivity() {
        val app: Application = ApplicationProvider.getApplicationContext()
        val monitor = NetworkMonitor(app)
        monitor.onConnectivity = events::onConnectivity
        monitor.register { }
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val callback = shadowOf(cm).networkCallbacks.single()
        val net = org.robolectric.shadows.ShadowNetwork.newInstance(1)
        callback.onLost(net)
        callback.onCapabilitiesChanged(net, capsWithInternet())
        assertEquals(listOf("shell.connectivity", "shell.connectivity"), types().filter { it == "shell.connectivity" })
    }

    private fun capsWithInternet(): NetworkCapabilities {
        val caps = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        return caps
    }

    private class FakeDict : DictOps {
        var cached = 0
        val changes = MutableStateFlow(0)

        override fun state() = DictInstallData(
            null, null, 0, "top60k", "idle", 0, 0, null, null, null, GapFillCounts(cached, 0),
        )

        override fun isOnline() = true
        override fun start(scope: String?) = Unit
        override fun changes() = kotlinx.coroutines.flow.flow<Unit> { changes.collect { emit(Unit) } }
    }

    @Test
    fun dictionaryChangesEmitShellDictAndIdenticalStatesAreNotRepeated() {
        val dict = FakeDict()
        val out = ArrayList<BridgeMessage.Event>()
        val watcher = DictEvents(CoroutineScope(Dispatchers.Unconfined), dict, { out += it }, intervalMs = 0)
        watcher.start()
        assertEquals(1, out.size)
        assertEquals("shell.dict", out[0].type)
        watcher.poke() // nothing changed
        assertEquals(1, out.size)
        dict.cached = 7
        watcher.poke()
        assertEquals(2, out.size)
    }
}
