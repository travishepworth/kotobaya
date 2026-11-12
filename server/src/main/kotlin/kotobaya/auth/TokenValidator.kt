package kotobaya.auth

import kotobaya.server.Database
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** A valid credential: one `app.api_token` row. */
data class Principal(
    val tokenId: UUID,
    val name: String,
    val kind: TokenKind,
    /** In the order read, write, review, import, admin. */
    val scopes: List<String>,
    val deviceId: UUID?,
    val expiresAt: Instant?,
)

/**
 * Turns a token string into a [Principal] (openapi §2 validity rule: not revoked, not expired, and its device
 * not revoked). Results are cached for at most [ttlMillis] (the contract allows 5 s); expiry is re-checked
 * against the clock on every hit, and [revoke], [invalidateToken], [invalidateDevice] and [invalidateAll]
 * take effect for the very next request: they bump a generation counter, so a lookup that raced with the
 * invalidation is never cached.
 *
 * T105b reuses this: after revoking a device or a token in its own transaction it calls the invalidate methods.
 */
class TokenValidator(
    private val db: Database,
    private val clock: () -> Instant = Instant::now,
    private val ttlMillis: Long = 5_000,
    private val nanoTime: () -> Long = System::nanoTime,
    private val maxEntries: Int = 10_000,
) {
    private class Entry(val principal: Principal, val loadedAtNanos: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val generation = AtomicLong()

    /** The principal for [token], or null when it is malformed, unknown, revoked or expired. */
    suspend fun validate(token: String): Principal? {
        if (!Tokens.isWellFormed(token)) return null
        val hash = Tokens.hash(token)
        val now = clock()
        cache[hash]?.let { e ->
            if (nanoTime() - e.loadedAtNanos < ttlMillis * 1_000_000) {
                return e.principal.takeIf { it.expiresAt == null || it.expiresAt.isAfter(now) }
            }
            cache.remove(hash, e)
        }
        val gen = generation.get()
        val loadedAt = nanoTime()
        val principal = load(hash, now) ?: return null
        if (cache.size >= maxEntries) cache.clear()
        cache[hash] = Entry(principal, loadedAt)
        // An invalidation ran while we were reading: drop what we cached (it may predate the revoke).
        if (generation.get() != gen) cache.remove(hash)
        return principal
    }

    private suspend fun load(hash: String, now: Instant): Principal? = db.tx { dsl ->
        dsl.fetchOptional(
            """
            select t.id, t.name, t.kind, t.scopes, t.device_id, t.expires_at
            from app.api_token t
            left join app.device d on d.id = t.device_id
            where t.token_hash = ?
              and t.revoked_at is null
              and (t.expires_at is null or t.expires_at > ?::timestamptz)
              and (t.device_id is null or d.revoked_at is null)
            """.trimIndent(),
            hash, OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC),
        ).map { r ->
            @Suppress("UNCHECKED_CAST")
            val scopes = (r.get(3) as Array<String>).toSet()
            Principal(
                tokenId = r.get(0, UUID::class.java),
                name = r.get(1, String::class.java),
                kind = TokenKind.fromWire(r.get(2, String::class.java)),
                scopes = ALL_SCOPES.filter { it in scopes },
                deviceId = r.get(4, UUID::class.java),
                expiresAt = r.get(5, OffsetDateTime::class.java)?.toInstant(),
            )
        }.orElse(null)
    }

    /** Sets `revoked_at` on one token (no-op if already revoked) and makes it invalid at once. True if it was live. */
    suspend fun revoke(tokenId: UUID): Boolean {
        val n = db.tx { dsl ->
            dsl.execute(
                "update app.api_token set revoked_at = ?::timestamptz where id = ? and revoked_at is null",
                OffsetDateTime.ofInstant(clock(), java.time.ZoneOffset.UTC), tokenId,
            )
        }
        invalidateToken(tokenId)
        return n > 0
    }

    fun invalidateToken(tokenId: UUID) {
        generation.incrementAndGet()
        cache.values.removeIf { it.principal.tokenId == tokenId }
    }

    fun invalidateDevice(deviceId: UUID) {
        generation.incrementAndGet()
        cache.values.removeIf { it.principal.deviceId == deviceId }
    }

    fun invalidateAll() {
        generation.incrementAndGet()
        cache.clear()
    }
}
