package kotobaya.grammar

import io.ktor.server.application.ApplicationCall
import kotobaya.notes.Errors
import kotobaya.notes.gone
import kotobaya.notes.pathId
import kotobaya.server.Database
import kotobaya.server.Problems
import kotobaya.server.json
import kotobaya.server.respondJson
import org.jooq.DSLContext
import org.jooq.Record
import java.util.Base64

/** Same definition as `SentencesModule` (PARSER.md 2, `SentenceFilter`): distinct mapped content/other entries with status `new`. */
internal const val UNKNOWN =
    "(select count(distinct (t.v->>'seq')::bigint) from jsonb_array_elements(s.tokens) as t(v) " +
        "where jsonb_typeof(t.v->'seq') = 'number' and t.v->>'class' in ('content', 'other') " +
        "and coalesce((select ws.status from app.word_status ws where ws.entry_seq = (t.v->>'seq')::bigint), 'new') = 'new')"

internal const val COLUMNS = "s.id::text, s.media_id::text, s.segment_id::text, s.ordinal, s.text, s.tokens::text, s.origin, " +
    "s.start_ms, s.end_ms, s.audio_blob_hash, s.image_blob_hash, s.location::text, $UNKNOWN::int"

private const val FROM = "from app.sentence s join app.media m on m.id = s.media_id and m.deleted_at is null"

/** `listGrammarExamples`: sentences with a live span of the point, manual first, then i+0, then fewest unknown words. */
internal class GrammarExamplesApi(private val db: Database) {
    suspend fun list(call: ApplicationCall) {
        val id = call.pathId("grammar_point_id")
        val q = call.request.queryParameters
        val e = Errors()
        val origin = q["origin"]?.also { if (it != "manual" && it != "detected") e.add("/origin", "invalid_value", "Must be manual or detected.") }
        val where = mutableListOf<String>()
        when (q["filter"]) {
            null, "all" -> Unit
            "i+1", "i 1" -> where += "$UNKNOWN = 1"
            "i+0", "i 0" -> where += "$UNKNOWN = 0"
            else -> e.add("/filter", "invalid_value", "Must be one of all, i+0, i+1.")
        }
        val limit = q["limit"]?.let { v ->
            v.toIntOrNull()?.takeIf { it in 1..500 } ?: run { e.add("/limit", "out_of_range", "Must be 1 to 500."); null }
        } ?: 50
        val hash = (where + listOfNotNull(origin)).hashCode().toString(16)
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
        val body = db.tx { dsl ->
            val g = dsl.fetchOptional(
                "select to_char(deleted_at at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"') from app.grammar_point where id = ?::uuid", id,
            ).orElse(null) ?: throw Problems.notFound("No such grammar point.")
            g.get(0, String::class.java)?.let { throw gone("grammar point", it) }
            val live = "gs.sentence_id = s.id and gs.grammar_point_id = ?::uuid and gs.deleted_at is null"
            val originClause = if (origin != null) " and gs.origin = '$origin'" else ""
            val sql = "select $COLUMNS $FROM where exists (select 1 from app.sentence_grammar_span gs where $live$originClause)" +
                where.joinToString("") { " and $it" } +
                " order by (exists (select 1 from app.sentence_grammar_span gm where gm.sentence_id = s.id and gm.grammar_point_id = ?::uuid " +
                "and gm.deleted_at is null and gm.origin = 'manual')) desc, ($UNKNOWN = 0) desc, $UNKNOWN, s.id " +
                "limit ${limit + 1} offset $offset"
            val rows = dsl.fetch(sql, id, id)
            val shown = rows.take(limit)
            val next = if (rows.size > limit) Base64.getUrlEncoder().withoutPadding().encodeToString("${offset + limit}|$hash".toByteArray()) else null
            mapOf("items" to render(shown, spans(dsl, shown.map { it.get(0, String::class.java) })), "next_cursor" to next)
        }
        call.respondJson(body)
    }

    internal fun spans(dsl: DSLContext, ids: List<String>): Map<String, List<Map<String, Any?>>> {
        if (ids.isEmpty()) return emptyMap()
        return dsl.fetch(
            "select gs.sentence_id::text, gs.grammar_point_id::text, gs.start_offset, gs.end_offset, gs.origin " +
                "from app.sentence_grammar_span gs join app.grammar_point p on p.id = gs.grammar_point_id and p.deleted_at is null " +
                "where gs.sentence_id = any(?::uuid[]) and gs.deleted_at is null " +
                "order by gs.start_offset, gs.end_offset, gs.grammar_point_id, gs.origin",
            ids.toTypedArray(),
        ).groupBy({ it.get(0, String::class.java) }) {
            linkedMapOf(
                "grammar_point_id" to it.get(1, String::class.java),
                "start_offset" to it.get(2, Int::class.java),
                "end_offset" to it.get(3, Int::class.java),
                "origin" to it.get(4, String::class.java),
            )
        }
    }

    internal fun render(rows: List<Record>, spans: Map<String, List<Map<String, Any?>>>): List<Map<String, Any?>> = rows.map { r ->
        val id = r.get(0, String::class.java)
        linkedMapOf(
            "id" to id,
            "media_id" to r.get(1, String::class.java),
            "segment_id" to r.get(2, String::class.java),
            "ordinal" to r.get(3, Int::class.java),
            "text" to r.get(4, String::class.java),
            "tokens" to json.readTree(r.get(5, String::class.java)),
            "origin" to r.get(6, String::class.java),
            "start_ms" to r.get(7, Int::class.javaObjectType),
            "end_ms" to r.get(8, Int::class.javaObjectType),
            "audio_blob_hash" to r.get(9, String::class.java),
            "image_blob_hash" to r.get(10, String::class.java),
            "location" to r.get(11, String::class.java)?.let { json.readTree(it) },
            "unknown_count" to r.get(12, Int::class.java),
            "grammar_spans" to (spans[id] ?: emptyList()),
        )
    }
}
