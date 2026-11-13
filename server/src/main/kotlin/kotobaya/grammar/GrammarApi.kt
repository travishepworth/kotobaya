package kotobaya.grammar

import com.fasterxml.jackson.databind.JsonNode
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
import kotobaya.server.Uuid7
import kotobaya.server.json
import kotobaya.server.respondJson
import kotobaya.sync.OpApply
import org.jooq.DSLContext
import java.util.Base64

private fun ts(col: String) = "to_char($col at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"')"

private val SELECT = "select jsonb_build_object('id', g.id, 'language', g.language, 'title', g.title, 'pattern', g.pattern, " +
    "'meaning', g.meaning, 'explanation', g.explanation, 'notes', g.notes, 'jlpt', g.jlpt, " +
    "'detection_rules', g.detection_rules, 'related_ids', to_jsonb(g.related_ids), 'links', g.links, 'is_stub', g.is_stub, " +
    "'note_id', (select n.id from app.note n where n.grammar_point_id = g.id and n.deleted_at is null limit 1), " +
    "'example_count', (select count(*) from app.sentence_grammar_span s join app.sentence se on se.id = s.sentence_id " +
    "where s.grammar_point_id = g.id and s.deleted_at is null), " +
    "'created_at', ${ts("g.created_at")}, 'updated_at', ${ts("g.updated_at")}, 'deleted_at', ${ts("g.deleted_at")})::text from app.grammar_point g"

private fun view(dsl: DSLContext, id: String): ObjectNode? =
    dsl.fetchOptional("$SELECT where g.id = ?::uuid", id).map { json.readTree(it.get(0, String::class.java)) as ObjectNode }.orElse(null)

private val PROPS = setOf("title", "pattern", "meaning", "explanation", "notes", "jlpt", "detection_rules", "related_ids", "links", "is_stub")

/** `listGrammarPoints`, `createGrammarPoint`, `getGrammarPoint`, `updateGrammarPoint`, `deleteGrammarPoint`. */
internal class GrammarApi(private val db: Database, private val opApply: OpApply, private val validator: RuleValidator) {
    suspend fun list(call: ApplicationCall) {
        val q = call.request.queryParameters
        val deleted = call.includeDeleted()
        val e = Errors()
        val where = mutableListOf<String>()
        val args = mutableListOf<Any>()
        if (!deleted) where += "g.deleted_at is null"
        q["q"]?.let { v ->
            if (v.isEmpty() || v.length > 200) {
                e.add("/q", "invalid_value", "1 to 200 characters.")
            } else {
                where += "((g.title || ' ' || g.pattern) ilike ? or g.meaning ilike ?)"
                val like = "%" + v.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
                args += like
                args += like
            }
        }
        q["jlpt"]?.let { v ->
            val n = v.toIntOrNull()
            if (n != null && n in 1..5) {
                where += "g.jlpt = ?"
                args += n
            } else {
                e.add("/jlpt", "invalid_value", "Must be 1 to 5.")
            }
        }
        q["is_stub"]?.let { v ->
            when (v) {
                "true", "false" -> {
                    where += "g.is_stub = ?"
                    args += v.toBoolean()
                }
                else -> e.add("/is_stub", "invalid_value", "Must be true or false.")
            }
        }
        val limit = q["limit"]?.let { v ->
            v.toIntOrNull()?.takeIf { it in 1..500 } ?: run { e.add("/limit", "out_of_range", "Must be 1 to 500."); null }
        } ?: 50
        val hash = (where + args.map { it.toString() }).hashCode().toString(16)
        var offset = 0
        q["cursor"]?.let { c ->
            val parts = runCatching { String(Base64.getUrlDecoder().decode(c), Charsets.UTF_8).split('|') }.getOrNull()
            val n = parts?.getOrNull(0)?.toIntOrNull()
            if (parts == null || parts.size != 2 || n == null || n < 0 || parts[1] != hash) {
                e.add("/cursor", "invalid_value", "Malformed or foreign cursor.")
            } else {
                offset = n
            }
        }
        e.throwIfAny()
        val page = db.tx { dsl ->
            val sql = SELECT + (if (where.isEmpty()) "" else " where " + where.joinToString(" and ")) +
                " order by g.jlpt desc nulls last, g.title, g.id limit ${limit + 1} offset $offset"
            val rows = dsl.fetch(sql, *args.toTypedArray()).map { json.readTree(it.get(0, String::class.java)) }
            val next = if (rows.size > limit) Base64.getUrlEncoder().withoutPadding().encodeToString("${offset + limit}|$hash".toByteArray()) else null
            mapOf("items" to rows.take(limit), "next_cursor" to next)
        }
        call.respondJson(page)
    }

    suspend fun get(call: ApplicationCall) {
        val id = call.pathId("grammar_point_id")
        val deleted = call.includeDeleted()
        val row = db.tx { view(it, id) } ?: throw Problems.notFound("No such grammar point.")
        if (!deleted && !row["deleted_at"].isNull) throw gone("grammar point", row["deleted_at"].asText())
        call.respondJson(row)
    }

    suspend fun create(call: ApplicationCall) {
        val body = call.jsonBody()
        val e = Errors()
        e.unknownKeys(body, PROPS + "id")
        val id = e.uuid(body, "id")
        val p = json.createObjectNode()
        e.string(body, "title", 1, 300, required = true)?.let { p.put("title", it) }
        validateProps(body, e, p)
        e.throwIfAny()
        // An existing id is the idempotent 200 before anything else: a retry never meets 422/503 from the worker.
        if (id != null) db.tx { view(it, id) }?.let { return call.send(Reply(HttpStatusCode.OK, it)) }
        validator.check(call, p.get("detection_rules"))
        val reply = opApply.rest(call.callerTokenId()) { tx ->
            if (id != null) view(tx.dsl, id)?.let { return@rest Reply(HttpStatusCode.OK, it) }
            val newId = id ?: Uuid7.generate().toString()
            p.put("id", newId)
            tx.emit(OpType.GRAMMAR_CREATED, payload(p))
            Reply(HttpStatusCode.Created, view(tx.dsl, newId) ?: error("grammar point $newId missing"), tx.lastSeq)
        }
        call.send(reply)
    }

    suspend fun update(call: ApplicationCall) {
        val id = call.pathId("grammar_point_id")
        val body = call.jsonBody()
        val e = Errors()
        e.unknownKeys(body, PROPS)
        if (body.isEmpty) e.add("", "required", "At least one property is required.")
        val set = json.createObjectNode()
        e.string(body, "title", 1, 300)?.let { set.put("title", it) }
        validateProps(body, e, set)
        e.throwIfAny()
        validator.check(call, set.get("detection_rules"))
        val reply = opApply.rest(call.callerTokenId(), { it.removePrefix("/set") }) { tx ->
            val row = view(tx.dsl, id) ?: throw Problems.notFound("No such grammar point.")
            if (!row["deleted_at"].isNull) throw gone("grammar point", row["deleted_at"].asText())
            val p = json.createObjectNode().put("id", id)
            p.set<JsonNode>("set", set)
            tx.emit(OpType.GRAMMAR_UPDATED, payload(p))
            Reply(HttpStatusCode.OK, view(tx.dsl, id) ?: error("grammar point $id vanished"), tx.lastSeq)
        }
        call.send(reply)
    }

    suspend fun delete(call: ApplicationCall) {
        val id = call.pathId("grammar_point_id")
        val reply = opApply.rest(call.callerTokenId()) { tx ->
            val row = view(tx.dsl, id) ?: throw Problems.notFound("No such grammar point.")
            if (!row["deleted_at"].isNull) return@rest Reply(HttpStatusCode.NoContent, null)
            tx.emit(OpType.GRAMMAR_DELETED, payload(json.createObjectNode().put("id", id)))
            Reply(HttpStatusCode.NoContent, null, tx.lastSeq)
        }
        call.send(reply)
    }

    /** Validates the properties shared by create and update (everything but `id` and `title`) into [out]. */
    private fun validateProps(body: ObjectNode, e: Errors, out: ObjectNode) {
        for ((k, max) in listOf("pattern" to 300, "meaning" to 2000, "explanation" to 100_000, "notes" to 100_000)) {
            e.string(body, k, 0, max)?.let { out.put(k, it) }
        }
        body.get("jlpt")?.let { v ->
            when {
                v.isNull -> out.putNull("jlpt")
                v.isIntegralNumber && v.asInt() in 1..5 -> out.put("jlpt", v.asInt())
                else -> e.add("/jlpt", "invalid_value", "Must be 1 to 5 or null.")
            }
        }
        body.get("is_stub")?.let { v -> if (v.isBoolean) out.put("is_stub", v.asBoolean()) else e.add("/is_stub", "invalid_type", "Must be a boolean.") }
        body.get("detection_rules")?.let { v ->
            if (!v.isArray || v.size() > 20) {
                e.add("/detection_rules", "invalid_value", "An array of at most 20 rules.")
                return@let
            }
            val arr = json.createArrayNode()
            v.forEachIndexed { i, r ->
                val at = "/detection_rules/$i"
                if (r !is ObjectNode) {
                    e.add(at, "invalid_type", "Must be an object.")
                    return@forEachIndexed
                }
                val ruleNode = r.get("rule")
                val rule = if (ruleNode == null) {
                    e.add("$at/rule", "required", "rule is required.")
                    null
                } else {
                    e.stringValue(ruleNode, "$at/rule", 1, 1000)
                }
                val draft = r.get("draft")
                if (draft != null && !draft.isBoolean) e.add("$at/draft", "invalid_type", "Must be a boolean.")
                r.fieldNames().asSequence().filter { it != "rule" && it != "draft" }.forEach { e.add("$at/$it", "unknown_field", "Unknown property $it.") }
                if (rule != null) {
                    arr.add(json.createObjectNode().put("rule", rule).apply { if (draft != null && draft.isBoolean) put("draft", draft.asBoolean()) })
                }
            }
            out.set<JsonNode>("detection_rules", arr)
        }
        body.get("related_ids")?.let { v ->
            if (!v.isArray || v.size() > 50) {
                e.add("/related_ids", "invalid_value", "An array of at most 50 ids.")
                return@let
            }
            val ids = v.mapIndexedNotNull { i, x -> e.uuidValue(x, "/related_ids/$i") }
            if (ids.toSet().size != ids.size) e.add("/related_ids", "invalid_value", "Ids must be unique.")
            out.set<JsonNode>("related_ids", json.createArrayNode().apply { ids.forEach { add(it) } })
        }
        body.get("links")?.let { v ->
            if (!v.isArray || v.size() > 50) {
                e.add("/links", "invalid_value", "An array of at most 50 links.")
                return@let
            }
            val arr = json.createArrayNode()
            v.forEachIndexed { i, l ->
                val at = "/links/$i"
                if (l !is ObjectNode) {
                    e.add(at, "invalid_type", "Must be an object.")
                    return@forEachIndexed
                }
                val t = l.get("title")?.let { e.stringValue(it, "$at/title", 1, 300) } ?: run { if (!l.has("title")) e.add("$at/title", "required", "title is required."); null }
                val u = l.get("url")?.let { e.stringValue(it, "$at/url", 1, 2000) } ?: run { if (!l.has("url")) e.add("$at/url", "required", "url is required."); null }
                l.fieldNames().asSequence().filter { it != "title" && it != "url" }.forEach { e.add("$at/$it", "unknown_field", "Unknown property $it.") }
                if (t != null && u != null) arr.add(json.createObjectNode().put("title", t).put("url", u))
            }
            out.set<JsonNode>("links", arr)
        }
    }
}
