package kotobaya.android.shell.api.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.sync.client.protocol.SyncPhase

/** SHELL-A6 acceptance: `getSyncStatus` mirrors the status repository; `syncNow` offline is `503 shell_offline`. */
class SyncRoutesTest {
    private val ops = FakeDeviceOps()
    private val api = deviceApi(ops)

    private fun num(o: kotobaya.contracts.sync.JsonObject, k: String) = (o.at(k) as JsonNumber).value.toLong()

    @Test
    fun statusMirrorsTheRepositoryView() {
        ops.pending = 37
        ops.failures = 2
        ops.phase = SyncPhase.AuthRequired
        val r = api.get("/shell/v1/sync")
        assertEquals(200, r.status)
        val j = r.json()
        assertEquals("auth_required", j.str("phase"))
        assertEquals(37, num(j, "pending_ops"))
        assertEquals(2, num(j, "rejected_ops"))
        assertEquals(2, num(j, "consecutive_failures"))
        assertEquals(48213, num(j, "pull_seq"))
        assertEquals("2026-11-20T10:00:00.000Z", j.str("last_sync_at"))
        assertEquals(true, j.bool("online"))
        assertEquals(false, j.bool("stale"))
        assertEquals(kotobaya.contracts.sync.JsonNull, j.at("progress"))
    }

    @Test
    fun noNetworkReadsAsOfflineAndNeverSyncedWhilePairedIsStale() {
        ops.online = false
        ops.lastSyncAt = null
        val j = api.get("/shell/v1/sync").json()
        assertEquals("offline", j.str("phase"))
        assertEquals(true, j.bool("stale"))
        assertEquals(kotobaya.contracts.sync.JsonNull, j.at("last_sync_at"))
    }

    @Test
    fun syncNowOfflineIs503AndStartsNothing() {
        ops.online = false
        val r = api.post("/shell/v1/sync")
        assertEquals(503, r.status)
        assertEquals("shell_offline", r.json().str("code"))
        assertEquals(0, ops.syncsStarted)
    }

    @Test
    fun syncNowOnlineStartsOneCycleAndAnswers202Syncing() {
        val r = api.post("/shell/v1/sync")
        assertEquals(202, r.status)
        assertEquals("syncing", r.json().str("phase"))
        assertEquals(1, ops.syncsStarted)
    }

    @Test
    fun infoWorksUnpairedAndNeverCarriesAToken() {
        ops.paired = false
        val unpaired = deviceApi(ops, paired = { false })
        val r = unpaired.get("/shell/v1/info")
        assertEquals(200, r.status)
        assertEquals(false, r.json().bool("paired"))
        assertNull(r.json().str("token"))
    }
}
