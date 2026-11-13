package kotobaya.acceptance.offline

import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.Op
import kotobaya.contracts.sync.OpDecodeResult
import kotobaya.contracts.sync.OpSource
import kotobaya.contracts.sync.OpType
import kotobaya.sync.DefaultCanonicalJson
import kotobaya.sync.DefaultOpCodec
import kotobaya.sync.client.protocol.SyncLimits
import kotobaya.sync.client.protocol.TransportResponse
import org.testcontainers.DockerClientFactory

/**
 * The server as the simulated devices and the oracle see it. Two implementations: [PgSimServer] (the
 * real `:server` sync code on Postgres) and [MemSimServer] (an in-memory reference server, used when
 * Docker is unavailable or `-Pserver=memory`).
 */
interface SimServer : AutoCloseable {
    /** `postgres` or `memory` (printed in the report). */
    val kind: String

    /** The server's wall clock (ms). */
    var nowMs: Long

    /** `POST /sync/push` as device [deviceId] (HTTP semantics: 413 above `max_push_bytes`, 400 for a bad body). */
    fun push(deviceId: String, body: String): TransportResponse

    /** `GET /sync/pull?since=` with this server's page size. */
    fun pull(since: Long): TransportResponse

    fun limits(): SyncLimits = SyncLimits.DEFAULT

    /** A verified backup of everything committed now (the backup job: `durable_seq` = its `S`, SYNC.md 6.3). */
    fun backup()

    /** Disk lost: restore the newest verified backup and start the server (6.3 finalization: new epoch, clock raised). */
    fun restoreLatestBackup()

    /** The server mints [type] itself (REST, import or job path); returns the logged op. */
    fun emit(type: OpType, payload: JsonObject): Op

    /** The whole log in seq order, pulled form. */
    fun log(): List<Op>

    /**
     * The server's learning tables in device representation, projected as SYNC.md 11; [reference] (a
     * device replica of the same log) names the columns a device has.
     */
    fun projection(reference: Projection): Projection

    val logEpoch: String

    companion object {
        /** The Postgres server when Docker is reachable (and not overridden), else the in-memory one. */
        fun create(startMs: Long, pageSize: Int = 500): SimServer =
            if (usePostgres) PgSimServer(startMs, pageSize) else MemSimServer(startMs, pageSize)

        val usePostgres: Boolean by lazy {
            when (System.getProperty("kotobaya.server")) {
                "memory" -> false
                "postgres" -> true
                else -> runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
            }
        }

        /** Every op of a pull page, decoded. */
        fun pageOps(body: String): Triple<List<Op>, Long, Boolean> {
            val tree = DefaultCanonicalJson.decode(body).value as JsonObject
            val ops = (tree.members.getValue("ops") as kotobaya.contracts.sync.JsonArray).items.map {
                (DefaultOpCodec.decodeTree(it, OpSource.PULLED) as OpDecodeResult.Known).op
            }
            val next = (tree.members.getValue("next_since") as kotobaya.contracts.sync.JsonNumber).value.toLong()
            val more = (tree.members.getValue("has_more") as kotobaya.contracts.sync.JsonBool).value
            return Triple(ops, next, more)
        }
    }
}

/** Pulls the whole log of [server] through its pull endpoint. */
fun pullAll(server: SimServer): List<Op> {
    val out = ArrayList<Op>()
    var since = 0L
    while (true) {
        val r = server.pull(since) as TransportResponse.Ok
        val (ops, next, more) = SimServer.pageOps(r.body)
        out += ops
        since = next
        if (!more) return out
    }
}
