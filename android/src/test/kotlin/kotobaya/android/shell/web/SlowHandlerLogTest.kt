package kotobaya.android.shell.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotobaya.android.shell.api.ShellResponse
import kotobaya.contracts.sync.JsonObject

class SlowHandlerLogTest {
    private var now = 0L
    private val slow = ArrayList<Pair<String, Long>>()

    private fun core(handlerMs: Long) = BridgeCore(
        scope = CoroutineScope(SupervisorJob()),
        io = Dispatchers.Unconfined,
        main = Dispatchers.Unconfined,
        handle = { now += handlerMs; ShellResponse(200) },
        helloData = { JsonObject(emptyMap()) },
        nowMs = { now },
        onSlow = { id, ms -> slow += id to ms },
    )

    private fun send(core: BridgeCore, id: String) {
        core.onMessage(reqText(id, body = null), ShellOrigin.ORIGIN, true) { }
    }

    @Test
    fun aSlowHandlerIsLoggedWithItsOperationIdAndDurationOnly() {
        send(core(300), "00000000-0000-4000-8000-0000000000a1")
        assertEquals(listOf("getMeta" to 300L), slow)
        val line = BridgeCore.slowLine("getMeta", 300)
        assertTrue("getMeta" in line && "300" in line)
        assertFalse("body" in line)
    }

    @Test
    fun aHandlerAtTheThresholdIsNotLogged() {
        send(core(250), "00000000-0000-4000-8000-0000000000a2")
        assertTrue(slow.isEmpty())
    }
}
