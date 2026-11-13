package kotobaya.sentences

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotobaya.notes.Errors
import kotobaya.notes.gone
import kotobaya.notes.pathId
import kotobaya.server.ApiException
import kotobaya.server.Database
import kotobaya.server.OperationHandler
import kotobaya.server.Problems
import kotobaya.server.json
import kotobaya.server.respondJson
import org.jooq.DSLContext
import org.jooq.Record
import java.util.Base64

private val UUID_RE = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

/**
 * Distinct entries among a sentence's tokens that count as unknown now: class content/other, mapped, effective
 * status `new` (PARSER.md 2, `SentenceFilter`). Computed per request from `sentence.tokens` (no cache, ADR-017).
 * Particles, auxiliaries, names, unmapped tokens and `blacklisted`/`ignored` entries never count.
 */
private const val UNKNOWN =
    "(select count(distinct (t.v->>'seq')::bigint) from jsonb_array_elements(s.tokens) as t(v) " +
        "where jsonb_typeof(t.v->'seq') = 'number' and t.v->>'class' in ('content', 'other') " +
        "and coalesce((select ws.status from app.word_status ws where ws.entry_seq = (t.v->>'seq')::bigint), 'new') = 'new')"

/** Columns 0..11 are the sentence; 12 = unknown count; 13 = media created_at (cursor, microseconds); 14 = segment ordinal. */
private const val COLUMNS = "s.id::text, s.media_id::text, s.segment_id::text, s.ordinal, s.text, s.tokens::text, s.origin, " +
    "s.start_ms, s.end_ms, s.audio_blob_hash, s.image_blob_hash, s.location::text, $UNKNOWN::int as unk, " +
    "to_char(m.created_at at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"'), g.ordinal"

/** Only sentences whose media is not deleted. */
private const val FROM = "from app.sentence s join app.media m on m.id = s.media_id and m.deleted_at is null " +
    "join app.media_segment g on g.id = s.segment_id"

/**
 * Handlers for `listMediaSentences`, `listSentences`, `getSentence`, `listEntryExamples` (T408a), keyed by
 * operationId for `AppDeps.handlers`. Read-only. Wiring (Main.kt, W-5): `handlers += SentencesModule(db).handlers`.
 */
class SentencesModule(private val db: Database) {
    val handlers: Map<String, OperationHandler> = mapOf(
        "listMediaSentences" to { list(this, pathId("media_id")) },
        "listSentences" to { list(this, null) },
        "getSentence" to { get(this) },
        "listEntryExamples" to { examples(this) },
    )

    private suspend fun get(call: ApplicationCall) {
        val id = call.pathId("sentence_id")
        val body = db.tx { dsl ->
            val r = dsl.fetch("select $COLUMNS $FROM where s.id = ?::uuid", id).firstOrNull() ?: return@tx null
            render(listOf(r), spans(dsl, listOf(id))).single()
        } ?: throw Problems.notFound("No such sentence.")
        call.respondJson(body)
    }

    private suspend fun list(call: ApplicationCall, fixedMedia: String?) {
        val q = call.request.queryParameters
        val e = Errors()
        val where = mutableListOf<String>()
        val args = mutableListOf<Any>()
        fun uuid(param: String): String? = q[param]?.let { v ->
            if (UUID_RE.matches(v)) v else { e.add("/$param", "invalid_value", "Must be a lowercase UUID."); null }
        }
        val mediaId = fixedMedia ?: uuid("media_id")
        mediaId?.let { where += "s.media_id = ?::uuid"; args += it }
        when (val f = q["filter"]) {
            null, "all" -> Unit
            "i+1", "i 1" -> where += "$UNKNOWN = 1"
            "i+0", "i 0" -> where += "$UNKNOWN = 0"
            else -> e.add("/filter", "invalid_value", "Must be one of all, i+0, i+1.")
        }
        uuid("segment_id")?.let { where += "s.segment_id = ?::uuid"; args += it }
        q["q"]?.let { v ->
            val n = v.codePointCount(0, v.length)
            if (n in 1..200) { where += "strpos(s.text, ?) > 0"; args += v } else e.add("/q", "invalid_value", "Between 1 and 200 characters.")
        }
        q["entry_seq"]?.let { v ->
            val n = v.toLongOrNull()
            if (n != null && n >= 1) { where += "s.tokens @> ?::jsonb"; args += """[{"seq":$n}]""" } else e.add("/entry_seq", "invalid_value", "Must be an integer of at least 1.")
        }
        uuid("grammar_point_id")?.let {
            where += "exists (select 1 from app.sentence_grammar_span gs join app.grammar_point p on p.id = gs.grammar_point_id " +
                "and p.deleted_at is null where gs.sentence_id = s.id and gs.grammar_point_id = ?::uuid and gs.deleted_at is null)"
            args += it
        }
        val limit = q["limit"]?.let { v ->
            v.toIntOrNull()?.takeIf { it in 1..500 } ?: run { e.add("/limit", "out_of_range", "Must be 1 to 500."); null }
        } ?: 50
        val hash = (where + args.map { it.toString() }).hashCode().toString(16)
        q["cursor"]?.let { c ->
            val p = runCatching { String(Base64.getUrlDecoder().decode(c), Charsets.UTF_8).split('|') }.getOrNull()
            val ok = p != null && p.size == 5 && p[4] == hash && UUID_RE.matches(p[1]) &&
                p[2].toIntOrNull() != null && p[3].toIntOrNull() != null &&
                runCatching { java.time.OffsetDateTime.parse(p[0]) }.isSuccess
            if (!ok || p == null) {
                e.add("/cursor", "invalid_value", "Malformed or foreign cursor.")
            } else {
                where += "(m.created_at < ?::timestamptz or (m.created_at = ?::timestamptz and (m.id > ?::uuid or " +
                    "(m.id = ?::uuid and (g.ordinal, s.ordinal) > (?::int, ?::int)))))"
                args += p[0]; args += p[0]; args += p[1]; args += p[1]; args += p[2].toInt(); args += p[3].toInt()
            }
        }
        e.throwIfAny()

        val body = db.tx { dsl ->
            if (fixedMedia != null) {
                val m = dsl.fetch("select to_char(deleted_at at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"') from app.media where id = ?::uuid", fixedMedia)
                    .firstOrNull() ?: throw Problems.notFound("No such media.")
                m.get(0, String::class.java)?.let { throw gone("media", it) }
            }
            val sql = "select $COLUMNS $FROM" + (if (where.isEmpty()) "" else " where " + where.joinToString(" and ")) +
                " order by m.created_at desc, m.id, g.ordinal, s.ordinal limit ${limit + 1}"
            val rows = dsl.fetch(sql, *args.toTypedArray())
            val shown = rows.take(limit)
            val next = if (rows.size > limit) {
                val l = shown.last()
                Base64.getUrlEncoder().withoutPadding().encodeToString(
                    "${l.get(13, String::class.java)}|${l.get(1, String::class.java)}|${l.get(14, Int::class.java)}|${l.get(3, Int::class.java)}|$hash".toByteArray(),
                )
            } else {
                null
            }
            mapOf("items" to render(shown, spans(dsl, shown.map { it.get(0, String::class.java) })), "next_cursor" to next)
        }
        call.respondJson(body)
    }

    private suspend fun examples(call: ApplicationCall) {
        val q = call.request.queryParameters
        val seq = call.parameters["seq"]?.toLongOrNull()?.takeIf { it > 0 }
            ?: throw invalid("/seq", "seq must be a positive integer.")
        val limit = q["limit"]?.let { v -> v.toIntOrNull()?.takeIf { it in 1..50 } ?: throw invalid("/limit", "Must be 1 to 50.") } ?: 10
        val body = db.tx { dsl ->
            if (!dsl.fetchExists(dsl.selectOne().from("ref.dict_entry").where("seq = ?", seq))) return@tx null
            val rows = dsl.fetch(
                "select $COLUMNS $FROM where s.id in (select unnest(o.sentence_ids) from app.occurrence o where o.entry_seq = ?) " +
                    "order by unk, m.created_at desc, s.id limit ?",
                seq, limit,
            )
            val tatoeba = dsl.fetch(
                "select es.id, es.text, es.translation from ref.example_sentence es " +
                    "join ref.example_sentence_entry ee on ee.sentence_id = es.id where ee.seq = ? " +
                    "order by char_length(es.text), es.id limit ?",
                seq, limit,
            ).map { linkedMapOf("id" to it.get(0, Long::class.java), "text" to it.get(1, String::class.java), "translation" to it.get(2, String::class.java)) }
            mapOf("media" to render(rows, spans(dsl, rows.map { it.get(0, String::class.java) })), "tatoeba" to tatoeba)
        } ?: throw Problems.notFound("No dictionary entry $seq.")
        call.respondJson(body)
    }

    private fun invalid(pointer: String, detail: String) = ApiException(
        HttpStatusCode.UnprocessableEntity, "validation_failed", "Validation failed", detail,
        mapOf("errors" to listOf(mapOf("pointer" to pointer, "code" to "invalid_value", "detail" to detail))),
    )

    /** Live spans (detected, and manual not deleted) whose grammar point is live, by start offset, per sentence id. */
    private fun spans(dsl: DSLContext, ids: List<String>): Map<String, List<Map<String, Any?>>> {
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

    private fun render(rows: List<Record>, spans: Map<String, List<Map<String, Any?>>>): List<Map<String, Any?>> = rows.map { r ->
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
