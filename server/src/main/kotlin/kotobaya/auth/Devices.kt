package kotobaya.auth

import com.fasterxml.jackson.databind.node.ObjectNode
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import kotobaya.server.ApiException
import kotobaya.server.Database
import kotobaya.server.OperationHandler
import kotobaya.server.Problems
import kotobaya.server.SERVER_DEVICE_ID
import kotobaya.server.SYNC_LOCK
import kotobaya.server.TokenInfo
import kotobaya.server.setToken
import kotobaya.server.Uuid7
import kotobaya.server.formatInstant
import kotobaya.server.respondJson
import net.logstash.logback.argument.StructuredArguments.kv
import org.jooq.DSLContext
import org.jooq.Record
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * `/devices*`: pairing codes, pairing, the device list, `/devices/self`, patch and revoke (openapi `devices` tag).
 * Device rows are system data written directly (`x-emits-ops: []`), not through sync ops.
 */
internal class Devices(
    private val db: Database,
    private val validator: TokenValidator,
    private val events: AuthEvents,
    private val codes: PairingCodes,
    private val limiter: LoginRateLimiter,
    private val clock: () -> Instant,
    private val clientAddress: (ApplicationCall) -> String,
    private val publicUrl: String?,
    private val trustedPeer: (ApplicationCall) -> Boolean,
) {
    private val log = LoggerFactory.getLogger("kotobaya.auth")
    private val serverDevice: UUID = UUID.fromString(SERVER_DEVICE_ID)

    val handlers: Map<String, OperationHandler> = mapOf(
        "listDevices" to { listDevices(this) },
        "createPairing" to { createPairing(this) },
        "pairDevice" to { pairDevice(this) },
        "getOwnDevice" to { getOwnDevice(this) },
        "updateOwnDevice" to { updateOwnDevice(this) },
        "getDevice" to { getDevice(this) },
        "updateDevice" to { updateDevice(this) },
        "revokeDevice" to { revokeDevice(this) },
    )

    // ------------------------------------------------------------------ reads

    private suspend fun listDevices(call: ApplicationCall) {
        call.rejectUnknownQuery(emptySet())
        val items = db.tx { dsl -> dsl.fetch("select $COLUMNS from app.device order by paired_at, id").map(::deviceJson) }
        call.respondJson(mapOf("items" to items))
    }

    private suspend fun getDevice(call: ApplicationCall) {
        val id = call.deviceIdParam()
        call.respondJson(db.tx { dsl -> load(dsl, id) } ?: throw notFound())
    }

    private suspend fun getOwnDevice(call: ApplicationCall) {
        val id = call.selfDevice()
        call.respondJson(db.tx { dsl -> load(dsl, id) } ?: throw deviceRequired())
    }

    // ------------------------------------------------------------------ patch

    private suspend fun updateDevice(call: ApplicationCall) {
        val id = call.deviceIdParam()
        db.tx { dsl -> load(dsl, id) } ?: throw notFound()
        val patch = parsePatch(call.jsonObject())
        val updated = db.tx { dsl ->
            val row = dsl.fetchOne("select kind, revoked_at from app.device where id = ? for update", id) ?: throw notFound()
            if (row.get(0, String::class.java) == "server") throw conflict("not_applicable", "The server device cannot be changed.")
            if (row.get(1) != null) throw conflict("not_applicable", "A revoked device cannot be changed.")
            apply(dsl, id, patch)
        }
        call.respondJson(updated)
    }

    private suspend fun updateOwnDevice(call: ApplicationCall) {
        val id = call.selfDevice()
        val patch = parsePatch(call.jsonObject())
        call.respondJson(db.tx { dsl -> apply(dsl, id, patch) })
    }

    private class Patch(val name: String?, val pushTopicSet: Boolean, val pushTopic: String?)

    private fun parsePatch(body: ObjectNode): Patch {
        body.rejectUnknown(setOf("name", "push_topic"))
        if (body.isEmpty) throw validationFailed("", "required", "Send at least one of name, push_topic.")
        val name = body.get("name")?.let { checkName("name", it) }
        val topic = body.get("push_topic")
        return Patch(name, topic != null, topic?.let(::checkPushTopic))
    }

    private fun apply(dsl: DSLContext, id: UUID, p: Patch): Map<String, Any?> {
        val sets = buildList {
            if (p.name != null) add("name = ?" to p.name)
            if (p.pushTopicSet) add("push_topic = ?" to p.pushTopic)
        }
        dsl.execute("update app.device set ${sets.joinToString { it.first }} where id = ?", *sets.map { it.second }.toTypedArray(), id)
        return load(dsl, id) ?: throw notFound()
    }

    // ------------------------------------------------------------------ revoke

    private suspend fun revokeDevice(call: ApplicationCall) {
        val id = call.deviceIdParam()
        val now = clock()
        val revokedTokens = db.tx { dsl ->
            val row = dsl.fetchOne("select kind, name, revoked_at from app.device where id = ? for update", id) ?: throw notFound()
            if (row.get(0, String::class.java) == "server") throw conflict("not_applicable", "The server device cannot be revoked.")
            val tokens = revokeTokensOf(dsl, id, now)
            if (row.get(2) == null) {
                dsl.execute("update app.device set revoked_at = ?::timestamptz where id = ?", utc(now), id)
                events.deviceEventInTx(dsl, EVENT_DEVICE_REVOKED, id, row.get(1, String::class.java))
            }
            tokens
        }
        afterRevoke(id, revokedTokens)
        log.info("device revoked", kv("event", "auth.device_revoked"), kv("device.id", id.toString()))
        call.respond(HttpStatusCode.NoContent)
    }

    /** Revokes every live token of [deviceId] in the caller's transaction; returns their ids. */
    private fun revokeTokensOf(dsl: DSLContext, deviceId: UUID, now: Instant): List<UUID> =
        dsl.fetch(
            "update app.api_token set revoked_at = ?::timestamptz where device_id = ? and revoked_at is null returning id",
            utc(now), deviceId,
        ).map { it.get(0, UUID::class.java) }

    private fun afterRevoke(deviceId: UUID, tokens: List<UUID>) {
        validator.invalidateDevice(deviceId)
        tokens.forEach(validator::invalidateToken)
        if (tokens.isNotEmpty()) events.tokensRevoked(tokens)
    }

    // ------------------------------------------------------------------ pairing

    private suspend fun createPairing(call: ApplicationCall) {
        val (code, expires) = codes.create(clock())
        val server = URLEncoder.encode(baseUrl(call) + API_BASE, Charsets.UTF_8)
        call.respondJson(
            mapOf("code" to code, "expires_at" to formatInstant(expires), "pairing_uri" to "kotobaya://pair?server=$server&code=$code"),
            HttpStatusCode.Created,
        )
    }

    /**
     * The public origin for `pairing_uri`, in this order: `PUBLIC_URL`; `X-Forwarded-Proto` and
     * `X-Forwarded-Host` (or `Host`), believed only from a trusted proxy peer (`AUTH_TRUSTED_PROXIES`); `https://` +
     * `Host` when the request itself arrived over TLS. Otherwise the server cannot know the URL the phone must use (a
     * TLS-terminating ingress talks plain http to us), so it refuses rather than put an `http` URL in the QR code.
     */
    private fun baseUrl(call: ApplicationCall): String {
        publicOrigin(publicUrl, trustedPeer(call), call.request.local.scheme) { call.request.headers[it] }?.let { return it }
        log.error("cannot build pairing_uri: set PUBLIC_URL", kv("event", "auth.pairing"))
        throw ApiException(
            HttpStatusCode.InternalServerError, "internal", "Public URL unknown",
            "The server does not know its public URL. Set the PUBLIC_URL environment variable (Helm: api.publicUrl), " +
                "or list the ingress in AUTH_TRUSTED_PROXIES so its X-Forwarded-Proto/Host are believed.",
        )
    }

    private class PairRequest(
        val code: String,
        val deviceId: UUID,
        val name: String,
        val kind: String,
        val pushTopic: String?,
        val hlcLast: String?,
    )

    private suspend fun pairDevice(call: ApplicationCall) {
        val address = clientAddress(call)
        val now = clock()
        limiter.begin(address, now)?.let { wait ->
            log.warn("pairing rate limited", kv("event", "auth.pair"), kv("outcome", "rate_limited"), kv("client", address))
            call.response.header(HttpHeaders.RetryAfter, wait.toString())
            throw ApiException(HttpStatusCode.TooManyRequests, "rate_limited", "Too many attempts", "Try again in $wait seconds.")
        }
        // A wrong code stays recorded as a failure; success clears the address; any other outcome is forgotten.
        var forget = true
        try {
            val body = call.jsonObject()
            val code = body.get("code")
            if (code == null) throw validationFailed("/code", "required", "code is required.")
            if (!code.isTextual || code.asText().length !in 8..16) throw validationFailed("/code", "invalid_value", "code must be 8 to 16 characters.")
            // 401 comes before 422 (openapi §5 check order).
            if (!codes.isValid(code.asText(), now)) {
                forget = false
                throw invalidPairingCode()
            }
            val req = parsePair(body)
            pair(call, req, now)
            forget = false
            limiter.succeeded(address, now)
        } finally {
            if (forget) limiter.uncounted(address, now)
        }
    }

    private fun parsePair(body: ObjectNode): PairRequest {
        body.rejectUnknown(setOf("code", "device_id", "name", "kind", "push_topic", "hlc_last"))
        val idNode = body.get("device_id") ?: throw validationFailed("/device_id", "required", "device_id is required.")
        val deviceId = parseUuid(idNode.takeIf { it.isTextual }?.asText())
            ?: throw validationFailed("/device_id", "invalid_value", "device_id must be a lowercase UUID.")
        if (deviceId == serverDevice) throw validationFailed("/device_id", "invalid_value", "device_id is the server device.")
        val name = body.requiredName("name")
        val kind = body.get("kind")?.let {
            if (!it.isTextual || it.asText() !in setOf("android", "script")) throw validationFailed("/kind", "invalid_value", "kind must be android or script.")
            it.asText()
        } ?: "android"
        val topic = body.get("push_topic")?.let(::checkPushTopic)
        val hlc = body.get("hlc_last")?.let {
            if (!it.isTextual || !HLC.matches(it.asText())) throw validationFailed("/hlc_last", "invalid_value", "hlc_last is not an HLC.")
            if (it.asText().substring(18) != deviceId.toString()) {
                throw validationFailed("/hlc_last", "invalid_value", "The device part of hlc_last is not device_id.")
            }
            it.asText()
        }
        return PairRequest(body.get("code").asText(), deviceId, name, kind, topic, hlc)
    }

    private class Paired(val device: Map<String, Any?>, val tokenId: UUID, val token: String, val revoked: List<UUID>, val head: Long)

    private suspend fun pair(call: ApplicationCall, req: PairRequest, now: Instant) {
        // Taken before the transaction so two racing requests cannot both use it; put back unless it commits.
        val expires = codes.take(req.code, now) ?: throw invalidPairingCode()
        val token = Tokens.generate(TokenKind.DEVICE)
        val tokenId = Uuid7.generate(now.toEpochMilli())
        val paired = try {
            db.tx { dsl ->
                // The same lock as push (SYNC.md 5.2): no op of this device can be logged between the check and the commit.
                dsl.execute("select pg_advisory_xact_lock(?)", SYNC_LOCK)
                val existingKind = dsl.fetchOne("select kind from app.device where id = ? for update", req.deviceId)?.get(0, String::class.java)
                if (existingKind == "script" && req.kind != "script") {
                    // A `with_device` script's id: re-pairing it as a phone would silently revoke the script's token.
                    throw validationFailed("/kind", "invalid_value", "device_id belongs to a script device; pair it with kind script or use a new device_id.")
                }
                req.hlcLast?.let { last -> checkHlc(dsl, req.deviceId, last) }
                dsl.execute(
                    """
                    insert into app.device (id, name, kind, paired_at, push_topic) values (?, ?, ?, ?::timestamptz, ?)
                    on conflict (id) do update set name = excluded.name, kind = excluded.kind,
                      push_topic = excluded.push_topic, paired_at = excluded.paired_at, revoked_at = null
                    """.trimIndent(),
                    req.deviceId, req.name, req.kind, utc(now), req.pushTopic,
                )
                val revoked = revokeTokensOf(dsl, req.deviceId, now)
                dsl.execute(
                    """
                    insert into app.api_token (id, name, kind, token_hash, token_prefix, scopes, device_id, created_at)
                    values (?, ?, 'device', ?, ?, array['read', 'write', 'review'], ?, ?::timestamptz)
                    """.trimIndent(),
                    tokenId, req.name, Tokens.hash(token), Tokens.prefix(token), req.deviceId, utc(now),
                )
                val head = (dsl.fetchValue("select coalesce(max(seq), 0) from app.change") as Number).toLong()
                events.deviceEventInTx(dsl, EVENT_DEVICE_PAIRED, req.deviceId, req.name)
                Paired(load(dsl, req.deviceId) ?: error("device row vanished"), tokenId, token, revoked, head)
            }
        } catch (e: Throwable) {
            codes.restore(req.code, expires)
            throw e
        }
        afterRevoke(req.deviceId, paired.revoked)
        call.setToken(TokenInfo(tokenId.toString(), req.name, req.deviceId.toString()))
        log.info(
            "device paired", kv("event", "auth.pair"), kv("outcome", "ok"), kv("device.id", req.deviceId.toString()),
        )
        call.respondJson(
            linkedMapOf(
                "token" to paired.token,
                "token_id" to paired.tokenId.toString(),
                "scopes" to DEVICE_SCOPES,
                "device" to paired.device,
                "server_device_id" to SERVER_DEVICE_ID,
                "high_water_seq" to paired.head,
            ),
            HttpStatusCode.Created,
        )
    }

    /** `hlc_regressed` when [last] is below the greatest HLC logged for [deviceId] (byte-wise; equal is fine). */
    private fun checkHlc(dsl: DSLContext, deviceId: UUID, last: String) {
        val max = dsl.fetchValue("select max(hlc collate \"C\") from app.change where device_id = ?", deviceId) as String?
        if (max != null && last < max) {
            throw ApiException(
                HttpStatusCode.UnprocessableEntity, "validation_failed", "Validation failed",
                "This device's local database is older than its synced history; pair as a new device.",
                mapOf(
                    "errors" to listOf(
                        mapOf("pointer" to "/hlc_last", "code" to "hlc_regressed", "detail" to "hlc_last $last is below $max, the latest op logged for this device."),
                    ),
                ),
            )
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun ApplicationCall.deviceIdParam(): UUID = parseUuid(parameters["device_id"]) ?: throw notFound()

    private fun ApplicationCall.selfDevice(): UUID = (authenticated ?: throw Problems.unauthenticated()).requireDevice()

    private fun load(dsl: DSLContext, id: UUID): Map<String, Any?>? =
        dsl.fetchOne("select $COLUMNS from app.device where id = ?", id)?.let(::deviceJson)

    private fun notFound() = Problems.notFound("No such device.")

    private fun invalidPairingCode() = ApiException(
        HttpStatusCode.Unauthorized, "invalid_pairing_code", "Invalid pairing code", "The pairing code is unknown, expired or already used.",
    )

    companion object {
        const val API_BASE = "/api/v1"
        val DEVICE_SCOPES = listOf("read", "write", "review")
        /** See [baseUrl]; null when the public origin cannot be known. [scheme] is the scheme the request arrived with. */
        fun publicOrigin(publicUrl: String?, trustedPeer: Boolean, scheme: String, header: (String) -> String?): String? {
            publicUrl?.let { return it.trimEnd('/') }
            fun first(name: String) = header(name)?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }
            if (trustedPeer) {
                val proto = first("X-Forwarded-Proto")?.lowercase()
                val host = first("X-Forwarded-Host") ?: first(HttpHeaders.Host)
                if (proto in setOf("http", "https") && host != null && HOST.matches(host)) return "$proto://$host"
            }
            if (scheme == "https") {
                val host = first(HttpHeaders.Host)
                if (host != null && HOST.matches(host)) return "https://$host"
            }
            return null
        }

        /** A host name or IP literal with an optional port; nothing that could break out of the URI. */
        private val HOST = Regex("^([A-Za-z0-9.-]+|\\[[0-9A-Fa-f:.]+])(:[0-9]{1,5})?$")
        private val HLC = Regex("^[0-9a-f]{12}-[0-9a-f]{4}-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        private const val COLUMNS = "id, name, kind, paired_at, last_seen_at, last_push_at, last_pull_seq, push_topic, revoked_at"

        private fun ts(r: Record, f: String): String? = r.get(f, OffsetDateTime::class.java)?.toInstant()?.let(::formatInstant)

        fun deviceJson(r: Record): Map<String, Any?> = linkedMapOf(
            "id" to r.get("id", UUID::class.java).toString(),
            "name" to r.get("name", String::class.java),
            "kind" to r.get("kind", String::class.java),
            "paired_at" to ts(r, "paired_at"),
            "last_seen_at" to ts(r, "last_seen_at"),
            "last_push_at" to ts(r, "last_push_at"),
            "last_pull_seq" to r.get("last_pull_seq", Long::class.javaObjectType),
            "push_topic" to r.get("push_topic", String::class.java),
            "revoked_at" to ts(r, "revoked_at"),
        )
    }
}
