package kotobaya.parse

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import kotobaya.notes.Errors
import kotobaya.notes.jsonBody
import kotobaya.server.ApiException
import kotobaya.server.Database
import kotobaya.server.OperationHandler
import kotobaya.server.Problems
import kotobaya.server.json
import kotobaya.server.requestId
import kotobaya.server.respondJson
import kotobaya.sync.OpApply
import org.jooq.DSLContext
import org.slf4j.LoggerFactory

private const val MAX_TEXT = 20_000

/**
 * Handlers for `parseText`, `listParseOverrides`, `putParseOverride`, `deleteParseOverride` (T403c), keyed by
 * operationId for `AppDeps.handlers`. Override writes are ops through [opApply] (CLAUDE.md rule 8). Wiring
 * (Main.kt, W-5): `handlers += ParseModule(db, sync.opApply, HttpParserClient(PARSER_URL, PARSER_TIMEOUT_MS)).handlers`.
 */
class ParseModule(private val db: Database, opApply: OpApply, private val parser: ParserClient) {
    private val log = LoggerFactory.getLogger(ParseModule::class.java)
    private val overrides = ParseOverrideApi(db, opApply)

    val handlers: Map<String, OperationHandler> = mapOf(
        "parseText" to { parseText(this) },
        "listParseOverrides" to { overrides.list(this) },
        "putParseOverride" to { overrides.put(this) },
        "deleteParseOverride" to { overrides.delete(this) },
    )

    private fun tooLarge() =
        ApiException(HttpStatusCode.PayloadTooLarge, "payload_too_large", "Payload too large", "text is at most $MAX_TEXT characters.")

    private suspend fun parseText(call: ApplicationCall) {
        val body = call.jsonBody()
        val e = Errors()
        e.unknownKeys(body, setOf("text"))
        val node = body.get("text")
        var text: String? = null
        if (node == null) {
            e.add("/text", "required", "text is required.")
        } else if (!node.isTextual) {
            e.add("/text", "invalid_type", "Must be a string.")
        } else {
            val t = node.asText()
            val n = t.codePointCount(0, t.length)
            when {
                n == 0 -> e.add("/text", "invalid_value", "Must not be empty.")
                n > MAX_TEXT -> throw tooLarge()
                t.indexOf('\u0000') >= 0 -> e.add("/text", "invalid_value", "Must not contain U+0000.")
                else -> text = t
            }
        }
        e.throwIfAny()
        val result = try {
            parser.parse(text ?: error("validated above"), call.requestId)
        } catch (x: ParserTooLarge) {
            throw tooLarge()
        } catch (x: ParserUnavailable) {
            log.warn("parser unavailable: {}", x.message)
            if (x.retryAfter) call.response.header("Retry-After", "5")
            throw Problems.unavailable("The parser is not available right now. Try again shortly.")
        }
        val seqs = sortedSetOf<Long>()
        for (s in result.sentences) {
            for (t in s["tokens"]) {
                val q = t["seq"]
                if (q != null && q.isIntegralNumber && q.canConvertToLong()) seqs += q.asLong()
            }
        }
        val entries = if (seqs.isEmpty()) emptyList() else db.tx { dsl -> summaries(dsl, seqs.toLongArray().toTypedArray()) }
        val out = linkedMapOf<String, Any?>("sentences" to result.sentences, "entries" to entries)
        result.parserVersion?.let { out["parser_version"] = it }
        call.respondJson(out)
    }

    /** `DictEntrySummary` per seq the current release holds, ascending by seq, with the caller's status and note. */
    private fun summaries(dsl: DSLContext, seqs: Array<Long>): List<Map<String, Any?>> =
        dsl.fetch(
            "select e.seq, e.headword, e.headword_reading, e.is_common, e.senses->0->'gloss' as glosses, e.pos, e.freq_rank, " +
                "e.retired_release_id is not null as retired, " +
                "coalesce((select ws.status from app.word_status ws where ws.entry_seq = e.seq), 'new') as status, " +
                "(select n.id::text from app.note n where n.entry_seq = e.seq and n.deleted_at is null order by n.created_at, n.id limit 1) as note_id " +
                "from ref.dict_entry e where e.seq = any(?::bigint[]) order by e.seq",
            seqs,
        ).map { r ->
            val glosses = r.get("glosses", String::class.java)
            linkedMapOf(
                "seq" to r.get("seq", Long::class.java),
                "headword" to r.get("headword", String::class.java),
                "headword_reading" to r.get("headword_reading", String::class.java),
                "is_common" to r.get("is_common", Boolean::class.java),
                "glosses" to (if (glosses == null) emptyList() else json.readTree(glosses).map { it["text"].asText() }),
                "pos" to r.get("pos", Array<String>::class.java).toList(),
                "freq_rank" to r.get("freq_rank", Int::class.javaObjectType),
                "retired" to r.get("retired", Boolean::class.java),
                "status" to r.get("status", String::class.java),
                "note_id" to r.get("note_id", String::class.java),
            )
        }
}
