package kotobaya.android.net

import kotobaya.db.app.AppDatabase
import org.json.JSONObject

/** `/meta` `limits`, with the contract defaults for a client that has not read `/meta` yet. */
data class MetaLimits(
    val maxPushOps: Long = 500,
    val maxPushBytes: Long = 8_388_608,
    val maxOpBytes: Long = 1_048_576,
    val maxPullOps: Long = 1000,
    val maxBlobBytes: Long = 2_147_483_648,
    val maxJsonBytes: Long = 4_194_304,
    val maxPageSize: Long = 500,
) {
    companion object {
        val DEFAULTS = MetaLimits()

        fun fromMeta(meta: JSONObject): MetaLimits {
            val l = meta.optJSONObject("limits") ?: throw ApiError.BadBody("meta.limits missing")
            val d = DEFAULTS
            return MetaLimits(
                l.optLong("max_push_ops", d.maxPushOps), l.optLong("max_push_bytes", d.maxPushBytes),
                l.optLong("max_op_bytes", d.maxOpBytes), l.optLong("max_pull_ops", d.maxPullOps),
                l.optLong("max_blob_bytes", d.maxBlobBytes), l.optLong("max_json_bytes", d.maxJsonBytes),
                l.optLong("max_page_size", d.maxPageSize),
            )
        }
    }
}

/**
 * Durable copy of the last `/meta` limits in `sync_state` (keys `meta.*`, device-local, not
 * contract keys). The sync client reads [current]; T303c calls [refresh] after every successful sync.
 */
class MetaLimitsStore(private val db: AppDatabase) {
    private val q get() = db.syncOpsQueries

    /** True once limits have been fetched from a server at least once. */
    fun hasFetched(): Boolean = q.getSyncState(FETCHED).executeAsOneOrNull() != null

    /** The stored limits, or [MetaLimits.DEFAULTS] if never fetched. */
    fun current(): MetaLimits {
        if (!hasFetched()) return MetaLimits.DEFAULTS
        val m = db.metaLimitsQueries.syncStateByPrefix("meta.%").executeAsList().associate { it.key to it.value_ }
        val d = MetaLimits.DEFAULTS
        fun f(k: String, dflt: Long) = m["meta.$k"]?.toLongOrNull() ?: dflt
        return MetaLimits(
            f("max_push_ops", d.maxPushOps), f("max_push_bytes", d.maxPushBytes),
            f("max_op_bytes", d.maxOpBytes), f("max_pull_ops", d.maxPullOps),
            f("max_blob_bytes", d.maxBlobBytes), f("max_json_bytes", d.maxJsonBytes),
            f("max_page_size", d.maxPageSize),
        )
    }

    fun save(l: MetaLimits) {
        db.transaction {
            q.putSyncState("meta.max_push_ops", l.maxPushOps.toString())
            q.putSyncState("meta.max_push_bytes", l.maxPushBytes.toString())
            q.putSyncState("meta.max_op_bytes", l.maxOpBytes.toString())
            q.putSyncState("meta.max_pull_ops", l.maxPullOps.toString())
            q.putSyncState("meta.max_blob_bytes", l.maxBlobBytes.toString())
            q.putSyncState("meta.max_json_bytes", l.maxJsonBytes.toString())
            q.putSyncState("meta.max_page_size", l.maxPageSize.toString())
            q.putSyncState(FETCHED, "1")
        }
    }

    /** GET /meta and persist the limits. Returns the meta body. */
    suspend fun refresh(client: ApiClient): JSONObject {
        val meta = client.get("/meta").body
        save(MetaLimits.fromMeta(meta))
        return meta
    }

    private companion object {
        const val FETCHED = "meta.fetched"
    }
}
