package kotobaya.android.shell.api.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** SHELL-A6 acceptance: `unpairShell` refuses while ops are pending, and a refusal deletes nothing. */
class UnpairRouteTest {
    private val ops = FakeDeviceOps()
    private val api = deviceApi(ops)

    @Test
    fun pendingOpsRefuseWithoutForceAndNothingIsDeleted() {
        ops.pending = 3
        for (body in listOf(null, "{}", """{"force":false}""")) {
            val r = api.post("/shell/v1/unpair", body)
            assertEquals(409, r.status)
            assertEquals("shell_pending_ops", r.json().str("code"))
        }
        assertEquals(0, ops.unpairs)
        assertEquals(true, ops.info().paired)
    }

    @Test
    fun forceUnpairsEvenWithPendingOps() {
        ops.pending = 3
        val r = api.post("/shell/v1/unpair", """{"force":true}""")
        assertEquals(200, r.status)
        assertEquals(false, r.json().bool("paired"))
        assertEquals(1, ops.unpairs)
    }

    @Test
    fun noPendingOpsUnpairsWithoutBody() {
        val r = api.post("/shell/v1/unpair")
        assertEquals(200, r.status)
        assertFalse(r.json().bool("paired")!!)
        assertEquals(1, ops.unpairs)
    }

    @Test
    fun unknownPropertyIs422() {
        val r = api.post("/shell/v1/unpair", """{"froce":true}""")
        assertEquals(422, r.status)
        assertEquals(0, ops.unpairs)
    }
}
