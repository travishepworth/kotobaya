package kotobaya.parse

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotobaya.contracts.sync.OpType
import kotobaya.notes.Errors
import kotobaya.notes.Reply
import kotobaya.notes.callerTokenId
import kotobaya.notes.gone
import kotobaya.notes.includeDeleted
import kotobaya.notes.jsonBody
import kotobaya.notes.pathId
import kotobaya.notes.payload
import kotobaya.notes.rest
import kotobaya.notes.send
import kotobaya.server.Database
import kotobaya.server.Problems
import kotobaya.server.json
import kotobaya.server.respondJson
import kotobaya.sync.OpApply
import org.jooq.DSLContext
import java.util.Base64

private val UUID_RE = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
private const val MAX_SEQ = 9_007_199_254_740_991L

private fun ts(col: String) = "to_char($col at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"')"

private val ROW = "select jsonb_build_object('id', o.id, 'media_id', o.media_id, 'sentence_id', o.sentence_id, " +
    "'match_text', o.match_text, 'tokens', o.tokens, 'note', o.note, 'created_at', ${ts("o.created_at")}, " +
    "'updated_at', ${ts("o.updated_at")}, 'deleted_at', ${ts("o.deleted_at")})::text, " +
    "to_char(o.updated_at at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"') from app.parse_override o"

/** `listParseOverrides`, `putParseOverride`, `deleteParseOverride`. */
internal class ParseOverrideApi(private val db: Database, private val opApply: OpApply) {
    private fun row(dsl: DSLContext, id: String): ObjectNode? =
        dsl.fetchOptional("$ROW where o.id = ?::uuid", id).map { json.readTree(it.get(0, String::class.java)) as ObjectNode }.orElse(null)

    suspend fun list(call: ApplicationCall) {
        val q = call.request.queryParameters
        val e = Errors()
        val where = mutableListOf<String>()
        val args = mutableListOf<Any>()
        q["media_id"]?.let { v ->
            if (UUID_RE.matches(v)) { where += "o.media_id = ?::uuid"; args += v } else e.add("/media_id", "invalid_value", "Must be a lowercase UUID.")
        }
        q["scope"]?.let { v ->
            when (v) {
                "global" -> where += "o.media_id is null"
                "media" -> where += "o.media_id is not null and o.sentence_id is null"
                "sentence" -> where += "o.sentence_id is not null"
                else -> e.add("/scope", "invalid_value", "Must be one of global, media, sentence.")
            }
        }
        if (!call.includeDeleted()) where += "o.deleted_at is null"
        val limit = q["limit"]?.let { v ->
            v.toIntOrNull()?.takeIf { it in 1..500 } ?: run { e.add("/limit", "out_of_range", "Must be 1 to 500."); null }
        } ?: 50
        val hash = (where + args.map { it.toString() }).hashCode().toString(16)
        q["cursor"]?.let { c ->
            val parts = runCatching { String(Base64.getUrlDecoder().decode(c), Charsets.UTF_8).split('|') }.getOrNull()
            if (parts == null || parts.size != 3 || parts[2] != hash || !UUID_RE.matches(parts[1]) || runCatching { java.time.OffsetDateTime.parse(parts[0]) }.isFailure) {
                e.add("/cursor", "invalid_value", "Malformed or foreign cursor.")
            } else {
                where += "(o.updated_at < ?::timestamptz or (o.updated_at = ?::timestamptz and o.id > ?::uuid))"
                args += parts[0]; args += parts[0]; args += parts[1]
            }
        }
        e.throwIfAny()
        val page = db.tx { dsl ->
            val sql = ROW + (if (where.isEmpty()) "" else " where " + where.joinToString(" and ")) +
                " order by o.updated_at desc, o.id limit ${limit + 1}"
            val rows = dsl.fetch(sql, *args.toTypedArray())
            val shown = rows.take(limit)
            val next = if (rows.size > limit) {
                val last = shown.last()
                val id = json.readTree(last.get(0, String::class.java))["id"].asText()
                Base64.getUrlEncoder().withoutPadding().encodeToString("${last.get(1, String::class.java)}|$id|$hash".toByteArray())
            } else {
                null
            }
            mapOf("items" to shown.map { json.readTree(it.get(0, String::class.java)) }, "next_cursor" to next)
        }
        call.respondJson(page)
    }

    suspend fun put(call: ApplicationCall) {
        val id = call.pathId("override_id")
        val body = call.jsonBody()
        val e = Errors()
        e.unknownKeys(body, setOf("media_id", "sentence_id", "match_text", "tokens", "note"))
        val media = nullableUuid(e, body, "media_id")
        val sentence = nullableUuid(e, body, "sentence_id")
        if (sentence != null && media == null) e.add("/sentence_id", "invalid_value", "sentence_id requires media_id.")
        val match = e.string(body, "match_text", 1, 200, required = true)
        if (match != null && match.indexOf('\u0000') >= 0) e.add("/match_text", "invalid_value", "Must not contain U+0000.")
        val note = e.string(body, "note", 0, 1000) ?: ""
        val tokens = tokens(e, body.get("tokens"))
        if (match != null && tokens != null) {
            val joined = tokens.joinToString("") { it.get("surface").asText() }
            if (joined != match) e.add("/tokens", "invalid_value", "Token surfaces must concatenate exactly to match_text.")
        }
        e.throwIfAny()

        val reply = opApply.rest(call.callerTokenId()) { tx ->
            val existing = row(tx.dsl, id)
            if (existing != null && !existing["deleted_at"].isNull) throw gone("parse override", existing["deleted_at"].asText())
            val p = json.createObjectNode().put("id", id)
            p.put("media_id", media)
            p.put("sentence_id", sentence)
            p.put("match_text", match)
            p.set<JsonNode>("tokens", tokens)
            p.put("note", note)
            tx.emit(OpType.PARSE_OVERRIDE_PUT, payload(p))
            Reply(HttpStatusCode.OK, row(tx.dsl, id) ?: error("override $id missing after parse_override.put"), tx.lastSeq)
        }
        call.send(reply)
    }

    suspend fun delete(call: ApplicationCall) {
        val id = call.pathId("override_id")
        val reply = opApply.rest(call.callerTokenId()) { tx ->
            val existing = row(tx.dsl, id) ?: throw Problems.notFound("No such parse override.")
            if (!existing["deleted_at"].isNull) return@rest Reply(HttpStatusCode.NoContent, null)
            tx.emit(OpType.PARSE_OVERRIDE_DELETED, payload(json.createObjectNode().put("id", id)))
            Reply(HttpStatusCode.NoContent, null, tx.lastSeq)
        }
        call.send(reply)
    }

    /** A UUID or null; absent is null. */
    private fun nullableUuid(e: Errors, body: ObjectNode, key: String): String? {
        val v = body.get(key)
        if (v == null || v.isNull) return null
        return e.uuidValue(v, "/$key")
    }

    /** 1..50 override tokens (`OverrideToken`, strict); returns the array to store, or null after recording errors. */
    private fun tokens(e: Errors, node: JsonNode?): ArrayNode? {
        if (node == null) {
            e.add("/tokens", "required", "tokens is required.")
            return null
        }
        if (!node.isArray) {
            e.add("/tokens", "invalid_type", "Must be an array.")
            return null
        }
        if (node.isEmpty || node.size() > 50) {
            e.add("/tokens", "out_of_range", "Between 1 and 50 tokens.")
            return null
        }
        val out = json.createArrayNode()
        node.forEachIndexed { i, t ->
            val at = "/tokens/$i"
            if (t !is ObjectNode) {
                e.add(at, "invalid_type", "Must be an object.")
                return@forEachIndexed
            }
            for (k in t.fieldNames().asSequence()) {
                if (k !in setOf("surface", "lemma", "seq", "reading", "class")) e.add("$at/$k", "unknown_field", "Unknown property $k.")
            }
            val surface = t.get("surface")?.let { e.stringValue(it, "$at/surface", 1, Int.MAX_VALUE) }
            if (t.get("surface") == null) e.add("$at/surface", "required", "surface is required.")
            if (surface != null && surface.indexOf('\u0000') >= 0) e.add("$at/surface", "invalid_value", "Must not contain U+0000.")
            t.get("lemma")?.let { v -> e.stringValue(v, "$at/lemma", 1, Int.MAX_VALUE)?.let { s -> if (s.indexOf('\u0000') >= 0) e.add("$at/lemma", "invalid_value", "Must not contain U+0000.") } }
            val seq = t.get("seq")
            when {
                seq == null -> e.add("$at/seq", "required", "seq is required (null = no entry).")
                seq.isNull -> Unit
                !seq.isIntegralNumber || !seq.canConvertToLong() || seq.asLong() !in 1..MAX_SEQ -> e.add("$at/seq", "out_of_range", "Must be null or an integer from 1 to $MAX_SEQ.")
            }
            t.get("reading")?.let { v ->
                e.stringValue(v, "$at/reading", 1, 200)?.let { s ->
                    if (s.indexOf('\u0000') >= 0 || s.any { it.code in 0x30A1..0x30F6 }) {
                        e.add("$at/reading", "invalid_value", "Must be hiragana (no katakana, no U+0000).")
                    }
                }
            }
            t.get("class")?.let { v ->
                if (!v.isTextual || v.asText() !in setOf("content", "particle", "auxiliary", "name", "other")) {
                    e.add("$at/class", "invalid_value", "Must be one of content, particle, auxiliary, name, other.")
                }
            }
            out.add(t)
        }
        return if (e.any) null else out
    }
}
