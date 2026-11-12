package kotobaya.auth

import kotobaya.server.Database
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps `api_token.last_used_at` and `device.last_seen_at` current, writing at most once per [interval] per token
 * (openapi `Token.last_used_at`: "updated at most once a minute"). Failures are logged, never fail the request.
 */
internal class UsageRecorder(
    private val db: Database,
    private val clock: () -> Instant,
    private val interval: Duration = Duration.ofMinutes(1),
    private val maxEntries: Int = 10_000,
) {
    private val log = LoggerFactory.getLogger("kotobaya.auth")
    private val lastWrite = ConcurrentHashMap<UUID, Instant>()

    suspend fun record(p: Principal) {
        val now = clock()
        val prev = lastWrite[p.tokenId]
        if (prev != null && Duration.between(prev, now) < interval) return
        if (lastWrite.size >= maxEntries) lastWrite.clear()
        lastWrite[p.tokenId] = now
        try {
            db.tx { dsl ->
                dsl.execute("update app.api_token set last_used_at = ?::timestamptz where id = ?", utc(now), p.tokenId)
                p.deviceId?.let { dsl.execute("update app.device set last_seen_at = ?::timestamptz where id = ?", utc(now), it) }
            }
        } catch (e: Exception) {
            log.warn("could not record token use: {}", e.message)
        }
    }
}
