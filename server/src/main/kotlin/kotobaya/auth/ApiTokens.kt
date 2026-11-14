package kotobaya.auth

import com.fasterxml.jackson.databind.node.ObjectNode
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotobaya.server.Database
import kotobaya.server.OperationHandler
import kotobaya.server.Problems
import kotobaya.server.Uuid7
import kotobaya.server.formatInstant
import kotobaya.server.respondJson
import net.logstash.logback.argument.StructuredArguments.kv
import org.jooq.DSLContext
import org.jooq.Record
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** `/tokens*`: personal access tokens (created here, shown once, stored hashed) and the list/revoke of every kind. */
internal class ApiTokens(
    private val db: Database,
    private val validator: TokenValidator,
    private val events: AuthEvents,
    private val clock: () -> Instant,
) {
    private val log = LoggerFactory.getLogger("kotobaya.auth")

    val handlers: Map<String, OperationHandler> = mapOf(
        "listTokens" to { listTokens(this) },
        "createToken" to { createToken(this) },
        "getToken" to { getToken(this) },
        "revokeToken" to { revokeToken(this) },
    )

    private suspend fun listTokens(call: ApplicationCall) {
        call.rejectUnknownQuery(setOf("kind", "active"))
        val q = call.request.queryParameters
        val kind = q["kind"]?.also {
            if (TokenKind.entries.none { k -> k.wire == it }) throw validationFailed("/kind", "invalid_value", "kind must be session, device or personal.")
        }
        val active = q["active"]?.let {
            when (it) {
                "true" -> true
                "false" -> false
                else -> throw validationFailed("/active", "invalid_type", "active must be true or false.")
            }
        }
        val now = clock()
        val items = db.tx { dsl ->
            dsl.fetch("$SELECT ${if (kind != null) "where t.kind = ?" else ""} order by t.created_at desc, t.id", *listOfNotNull(kind).toTypedArray())
                .map { tokenJson(it, now) }
        }.filter { active == null || it["active"] == active }
        call.respondJson(mapOf("items" to items))
    }

    private suspend fun getToken(call: ApplicationCall) {
        val id = call.tokenIdParam()
        val now = clock()
        call.respondJson(db.tx { dsl -> load(dsl, id, now) } ?: throw notFound())
    }

    private suspend fun createToken(call: ApplicationCall) {
        val now = clock()
        val body = call.jsonObject()
        val req = parseCreate(body, now)
        val token = Tokens.generate(TokenKind.PERSONAL)
        val id = Uuid7.generate(now.toEpochMilli())
        val info = db.tx { dsl ->
            val deviceId = if (req.withDevice) {
                Uuid7.generate(now.toEpochMilli()).also {
                    dsl.execute("insert into app.device (id, name, kind, paired_at) values (?, ?, 'script', ?::timestamptz)", it, req.name, utc(now))
                }
            } else {
                null
            }
            dsl.execute(
                """
                insert into app.api_token (id, name, kind, token_hash, token_prefix, scopes, device_id, created_at, expires_at)
                values (?, ?, 'personal', ?, ?, ?::text[], ?, ?::timestamptz, ?::timestamptz)
                """.trimIndent(),
                id, req.name, Tokens.hash(token), Tokens.prefix(token), req.scopes.toTypedArray(), deviceId, utc(now), req.expiresAt?.let(::utc),
            )
            load(dsl, id, now) ?: error("token row vanished")
        }
        log.info(
            "token created", kv("event", "auth.token_created"), kv("target_token.id", id.toString()), kv("scopes", req.scopes.joinToString(",")),
        )
        call.respondJson(linkedMapOf("token" to token, "info" to info), HttpStatusCode.Created)
    }

    private class Create(val name: String, val scopes: List<String>, val expiresAt: Instant?, val withDevice: Boolean)

    private fun parseCreate(body: ObjectNode, now: Instant): Create {
        body.rejectUnknown(setOf("name", "scopes", "expires_at", "with_device"))
        val name = body.requiredName("name")
        val scopesNode = body.get("scopes") ?: throw validationFailed("/scopes", "required", "scopes is required.")
        if (!scopesNode.isArray) throw validationFailed("/scopes", "invalid_type", "scopes must be an array.")
        if (scopesNode.isEmpty) throw validationFailed("/scopes", "out_of_range", "scopes must not be empty.")
        val scopes = scopesNode.mapIndexed { i, s ->
            if (!s.isTextual || s.asText() !in ALL_SCOPES) throw validationFailed("/scopes/$i", "invalid_value", "Unknown scope.")
            s.asText()
        }
        if (scopes.toSet().size != scopes.size) throw validationFailed("/scopes", "invalid_value", "scopes must not repeat.")
        val expires = body.get("expires_at")?.takeUnless { it.isNull }?.let {
            val t = it.takeIf { n -> n.isTextual }?.let { n -> runCatching { OffsetDateTime.parse(n.asText()).toInstant() }.getOrNull() }
                ?: throw validationFailed("/expires_at", "invalid_value", "expires_at must be an RFC 3339 timestamp.")
            if (!t.isAfter(now)) throw validationFailed("/expires_at", "out_of_range", "expires_at must be in the future.")
            t
        }
        val withDevice = body.get("with_device")?.let {
            if (!it.isBoolean) throw validationFailed("/with_device", "invalid_type", "with_device must be a boolean.")
            it.asBoolean()
        } ?: false
        return Create(name, ALL_SCOPES.filter { it in scopes }, expires, withDevice)
    }

    private suspend fun revokeToken(call: ApplicationCall) {
        val id = call.tokenIdParam()
        val now = clock()
        db.tx { dsl ->
            dsl.fetchOne("select 1 from app.api_token where id = ?", id) ?: throw notFound()
            dsl.execute("update app.api_token set revoked_at = ?::timestamptz where id = ? and revoked_at is null", utc(now), id)
        }
        validator.invalidateToken(id)
        events.tokensRevoked(listOf(id))
        log.info("token revoked", kv("event", "auth.token_revoked"), kv("target_token.id", id.toString()))
        call.respond(HttpStatusCode.NoContent)
    }

    private fun ApplicationCall.tokenIdParam(): UUID = parseUuid(parameters["token_id"]) ?: throw notFound()

    private fun notFound() = Problems.notFound("No such token.")

    private fun load(dsl: DSLContext, id: UUID, now: Instant): Map<String, Any?>? =
        dsl.fetchOne("$SELECT where t.id = ?", id)?.let { tokenJson(it, now) }

    companion object {
        private const val SELECT =
            "select t.id, t.name, t.kind, t.token_prefix, t.scopes, t.device_id, t.created_at, t.expires_at, t.last_used_at, " +
                "t.revoked_at, d.revoked_at as device_revoked_at from app.api_token t left join app.device d on d.id = t.device_id"

        private fun ts(r: Record, f: String): Instant? = r.get(f, OffsetDateTime::class.java)?.toInstant()

        fun tokenJson(r: Record, now: Instant): Map<String, Any?> {
            @Suppress("UNCHECKED_CAST")
            val held = (r.get("scopes") as Array<String>).toSet()
            val expires = ts(r, "expires_at")
            val revoked = ts(r, "revoked_at")
            val active = revoked == null && (expires == null || expires.isAfter(now)) && ts(r, "device_revoked_at") == null
            return linkedMapOf(
                "id" to r.get("id", UUID::class.java).toString(),
                "name" to r.get("name", String::class.java),
                "kind" to r.get("kind", String::class.java),
                "token_prefix" to r.get("token_prefix", String::class.java),
                "scopes" to ALL_SCOPES.filter { it in held },
                "device_id" to r.get("device_id", UUID::class.java)?.toString(),
                "created_at" to ts(r, "created_at")?.let(::formatInstant),
                "expires_at" to expires?.let(::formatInstant),
                "last_used_at" to ts(r, "last_used_at")?.let(::formatInstant),
                "revoked_at" to revoked?.let(::formatInstant),
                "active" to active,
            )
        }
    }
}
