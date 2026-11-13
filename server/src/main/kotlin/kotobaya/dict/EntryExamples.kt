package kotobaya.dict

import io.ktor.server.application.ApplicationCall
import kotobaya.server.Database
import kotobaya.server.Problems
import kotobaya.server.json
import kotobaya.server.respondJson
import org.jooq.DSLContext

/** `listEntryExamples`: media sentences (through `occurrence.sentence_ids`) and Tatoeba pairs. Empty lists are fine. */
internal class EntryExamplesApi(private val db: Database) {
    suspend fun list(call: ApplicationCall) {
        val seq = call.parameters["seq"]?.toLongOrNull()?.takeIf { it > 0 }
            ?: throw validationFailed("/seq", "invalid_value", "seq must be a positive integer.")
        val limit = call.request.queryParameters["limit"]?.let {
            it.toIntOrNull()?.takeIf { n -> n in 1..50 }
                ?: throw validationFailed("/limit", "invalid_value", "limit must be an integer from 1 to 50.")
        } ?: 10
        val body = db.tx { dsl ->
            if (!dsl.fetchExists(dsl.selectOne().from("ref.dict_entry").where("seq = ?", seq))) {
                null
            } else {
                linkedMapOf("media" to media(dsl, seq, limit), "tatoeba" to tatoeba(dsl, seq, limit))
            }
        } ?: throw Problems.notFound("No dictionary entry $seq.")
        call.respondJson(body)
    }

    private fun tatoeba(dsl: DSLContext, seq: Long, limit: Int): List<Map<String, Any?>> =
        dsl.fetch(
            "select s.id, s.text, s.translation from ref.example_sentence_entry l " +
                "join ref.example_sentence s on s.id = l.sentence_id where l.seq = ? " +
                "order by char_length(s.text), s.id limit ?",
            seq, limit,
        ).map {
            linkedMapOf("id" to it.get("id", Long::class.java), "text" to it.get("text", String::class.java), "translation" to it.get("translation", String::class.java))
        }

    private fun media(dsl: DSLContext, seq: Long, limit: Int): List<Map<String, Any?>> {
        val rows = dsl.fetch(
            "select s.* from app.sentence s join app.media m on m.id = s.media_id " +
                "where m.deleted_at is null and s.origin = 'media' and s.id in " +
                "(select unnest(sentence_ids) from app.occurrence where entry_seq = ?) " +
                "order by s.unknown_count asc nulls last, m.created_at desc, s.id limit ?",
            seq, limit,
        )
        if (rows.isEmpty()) return emptyList()
        val ids = rows.map { it.get("id", java.util.UUID::class.java) }.toTypedArray()
        val spans = dsl.fetch(
            "select sentence_id, grammar_point_id, start_offset, end_offset, origin from app.sentence_grammar_span g " +
                "where g.sentence_id = any(?) and g.deleted_at is null " +
                "and exists (select 1 from app.grammar_point p where p.id = g.grammar_point_id and p.deleted_at is null) " +
                "order by g.sentence_id, g.start_offset, g.end_offset, g.grammar_point_id, g.origin",
            ids,
        ).groupBy({ it.get("sentence_id", java.util.UUID::class.java) }) {
            linkedMapOf<String, Any?>(
                "grammar_point_id" to it.get("grammar_point_id", java.util.UUID::class.java).toString(),
                "start_offset" to it.get("start_offset", Int::class.java),
                "end_offset" to it.get("end_offset", Int::class.java),
                "origin" to it.get("origin", String::class.java),
            )
        }
        return rows.map { r ->
            val id = r.get("id", java.util.UUID::class.java)
            linkedMapOf(
                "id" to id.toString(),
                "media_id" to r.get("media_id", java.util.UUID::class.java).toString(),
                "segment_id" to r.get("segment_id", java.util.UUID::class.java).toString(),
                "ordinal" to r.get("ordinal", Int::class.java),
                "text" to r.get("text", String::class.java),
                "tokens" to json.readTree(r.get("tokens", String::class.java)),
                "origin" to r.get("origin", String::class.java),
                "start_ms" to r.get("start_ms", Int::class.javaObjectType),
                "end_ms" to r.get("end_ms", Int::class.javaObjectType),
                "audio_blob_hash" to r.get("audio_blob_hash", String::class.java),
                "image_blob_hash" to r.get("image_blob_hash", String::class.java),
                "location" to r.get("location", String::class.java)?.let { json.readTree(it) },
                "unknown_count" to (r.get("unknown_count", Int::class.javaObjectType) ?: 0),
                "grammar_spans" to (spans[id] ?: emptyList()),
            )
        }
    }
}
