package kotobaya.grammar

import com.fasterxml.jackson.databind.node.ObjectNode
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import kotobaya.contracts.sync.OpType
import kotobaya.notes.Errors
import kotobaya.notes.Reply
import kotobaya.notes.callerTokenId
import kotobaya.notes.gone
import kotobaya.notes.jsonBody
import kotobaya.notes.pathId
import kotobaya.notes.payload
import kotobaya.notes.rest
import kotobaya.notes.send
import kotobaya.server.Problems
import kotobaya.server.json
import kotobaya.sync.OpApply
import org.jooq.DSLContext

private fun ts(col: String) = "to_char($col at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"')"

/** `putGrammarSpan`, `removeGrammarSpan`: manual spans, each an op (`grammar.span_put` / `grammar.span_removed`). */
internal class GrammarSpansApi(private val opApply: OpApply) {
    private class Key(val point: String, val sentence: String, val start: Int, val end: Int)

    private fun key(call: ApplicationCall): Key {
        val point = call.pathId("grammar_point_id")
        val sentence = call.pathId("sentence_id")
        val e = Errors()
        fun offset(name: String, min: Long): Int? {
            val n = call.parameters[name]?.toLongOrNull()
            if (n == null || n < min || n > Int.MAX_VALUE - 1) {
                e.add("/$name", "invalid_value", "Must be an integer of at least $min.")
                return null
            }
            return n.toInt()
        }
        val start = offset("start_offset", 0)
        val end = offset("end_offset", 1)
        if (start != null && end != null && end <= start) e.add("/end_offset", "invalid_value", "Must be greater than start_offset.")
        e.throwIfAny()
        return Key(point, sentence, start ?: 0, end ?: 1)
    }

    /** The point's `deleted_at` (null when live); 404 when it does not exist. */
    private fun pointDeletedAt(dsl: DSLContext, id: String): String? {
        val r = dsl.fetchOptional("select ${ts("deleted_at")} from app.grammar_point where id = ?::uuid", id).orElse(null)
            ?: throw Problems.notFound("No such grammar point.")
        return r.get(0, String::class.java)
    }

    private fun span(dsl: DSLContext, k: Key): ObjectNode? = dsl.fetchOptional(
        "select media_id::text, deleted_at is null, ${ts("created_at")} from app.sentence_grammar_span " +
            "where sentence_id = ?::uuid and grammar_point_id = ?::uuid and start_offset = ? and end_offset = ? and origin = 'manual'",
        k.sentence, k.point, k.start, k.end,
    ).map { r ->
        json.createObjectNode().apply {
            put("sentence_id", k.sentence)
            put("grammar_point_id", k.point)
            put("start_offset", k.start)
            put("end_offset", k.end)
            put("origin", "manual")
            r.get(0, String::class.java)?.let { put("media_id", it) } ?: putNull("media_id")
            putNull("rule_index")
            put("created_at", r.get(2, String::class.java))
            put("live", r.get(1, Boolean::class.java))
        }
    }.orElse(null)

    private fun ObjectNode.public(): ObjectNode = apply { remove("live") }

    private fun payloadOf(k: Key): ObjectNode = json.createObjectNode()
        .put("sentence_id", k.sentence).put("grammar_point_id", k.point).put("start_offset", k.start).put("end_offset", k.end)

    suspend fun put(call: ApplicationCall) {
        val k = key(call)
        val body: ObjectNode = if ((call.request.contentLength() ?: 0L) == 0L) json.createObjectNode() else call.jsonBody()
        val e = Errors()
        e.unknownKeys(body, setOf("media_id"))
        var hint: String? = null
        body.get("media_id")?.let { v -> if (!v.isNull) hint = e.uuidValue(v, "/media_id") }
        e.throwIfAny()
        val reply = opApply.rest(call.callerTokenId()) { tx ->
            pointDeletedAt(tx.dsl, k.point)?.let { throw gone("grammar point", it) }
            var media = hint
            val sentence = tx.dsl.fetchOptional("select media_id::text, text from app.sentence where id = ?::uuid", k.sentence).orElse(null)
            if (sentence != null) {
                // Offsets are UTF-16 code units, which is what String.length counts.
                val length = sentence.get(1, String::class.java).length
                if (k.end > length) {
                    Errors().apply { add("/end_offset", "out_of_range", "Beyond the sentence text (length $length).") }.throwIfAny()
                }
                media = sentence.get(0, String::class.java)
            }
            val existing = span(tx.dsl, k)
            if (existing != null && existing["live"].asBoolean()) return@rest Reply(HttpStatusCode.OK, existing.public())
            val p = payloadOf(k)
            if (media != null) p.put("media_id", media) else p.putNull("media_id")
            tx.emit(OpType.GRAMMAR_SPAN_PUT, payload(p))
            Reply(HttpStatusCode.OK, (span(tx.dsl, k) ?: error("span vanished")).public(), tx.lastSeq)
        }
        call.send(reply)
    }

    suspend fun remove(call: ApplicationCall) {
        val k = key(call)
        val reply = opApply.rest(call.callerTokenId()) { tx ->
            pointDeletedAt(tx.dsl, k.point)
            val existing = span(tx.dsl, k)
            if (existing == null || !existing["live"].asBoolean()) return@rest Reply(HttpStatusCode.NoContent, null)
            tx.emit(OpType.GRAMMAR_SPAN_REMOVED, payload(payloadOf(k)))
            Reply(HttpStatusCode.NoContent, null, tx.lastSeq)
        }
        call.send(reply)
    }
}
