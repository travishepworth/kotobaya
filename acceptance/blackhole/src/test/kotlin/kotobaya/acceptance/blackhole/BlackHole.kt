package kotobaya.acceptance.blackhole

import kotobaya.android.shell.api.ApiOperation
import kotobaya.android.shell.api.ShellRequest
import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.api.Upstream
import kotobaya.sync.client.protocol.SyncLimits
import kotobaya.sync.client.protocol.SyncTransport
import kotobaya.sync.client.protocol.TransportResponse

/** How the network behaves (LF-X1). */
enum class NetMode {
    /** (a) a request is never answered. */
    NEVER,

    /** (b) a request is answered after 8 s. */
    SLOW_8S,

    /** (c) every second request is dropped. */
    DROP_EVERY_SECOND,

    /** After healing. */
    HEALTHY,
}

/**
 * One virtual network shared by a phone's UI-path [Upstream] and its sync transport. Nothing sleeps: a wait is added to
 * a counter, so "zero network wait" on the UI path is `uiWaitedMs` unchanged and no upstream call at all. A request that
 * is never answered counts [FOREVER_MS].
 */
class BlackHoleNet(var mode: NetMode) {
    var uiWaitedMs: Long = 0
        private set
    val uiCalls = ArrayList<String>()

    /** What sync cycles waited for in total (background; never on the UI path). */
    var syncWaitedMs: Long = 0
        private set
    var syncRequests: Int = 0
        private set
    var syncDropped: Int = 0
        private set
    private var requestNo = 0

    /** The wait of one request and whether it is answered. */
    private fun transit(): Pair<Long, Boolean> {
        requestNo++
        return when (mode) {
            NetMode.NEVER -> FOREVER_MS to false
            NetMode.SLOW_8S -> 8_000L to true
            NetMode.DROP_EVERY_SECOND -> if (requestNo % 2 == 0) FOREVER_MS to false else 0L to true
            NetMode.HEALTHY -> 0L to true
        }
    }

    /** The UI path's upstream: each call is a finding, and costs the wait it would have waited. */
    val upstream = Upstream { request: ShellRequest, op: ApiOperation, _ ->
        uiCalls += "${op.operationId} ${request.method} ${request.path}"
        uiWaitedMs += transit().first
        ShellResponse(503)
    }

    private fun syncTransit(): Boolean {
        syncRequests++
        val (wait, answered) = transit()
        syncWaitedMs += wait
        if (!answered) syncDropped++
        return answered
    }

    /** Wraps [inner] (the server) for the sync cycle. */
    fun transport(inner: SyncTransport): SyncTransport = object : SyncTransport {
        override suspend fun push(body: String): TransportResponse =
            if (syncTransit()) inner.push(body) else TransportResponse.NetworkError("black hole")

        override suspend fun pull(since: Long): TransportResponse =
            if (syncTransit()) inner.pull(since) else TransportResponse.NetworkError("black hole")

        override suspend fun limits(): SyncLimits? = if (syncTransit()) inner.limits() else null
    }

    companion object {
        const val FOREVER_MS: Long = 3_600_000
    }
}
