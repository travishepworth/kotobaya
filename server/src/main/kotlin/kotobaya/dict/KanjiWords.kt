package kotobaya.dict

import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.server.application.ApplicationCall
import kotobaya.server.Database
import kotobaya.server.Problems
import kotobaya.server.json
import kotobaya.server.respondJson
import java.util.Base64
import java.util.UUID

/** Sort key of the last row of a page, tied to the query (`l`, `d`, `m`) it was issued for. */
internal data class KanjiWordsCursor(val o: Int, val f: Int, val s: Long, val l: String, val d: String?, val m: Boolean)

/** `listKanjiWords` (FEAT-1): live entries containing a kanji, by frequency, with the caller's deck membership. */
internal class KanjiWordsApi(private val db: Database) {
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val b64d = Base64.getUrlDecoder()

    private class Row(val item: Map<String, Any?>, val occ: Int, val f: Int)

    suspend fun list(call: ApplicationCall) {
        val literal = call.parameters["literal"].orEmpty()
        if (literal.isEmpty() || literal.codePointCount(0, literal.length) != 1) {
            throw validationFailed("/literal", "invalid_value", "literal must be exactly one character.")
        }
        val params = call.request.queryParameters
        val deckId = params["deck_id"]?.let {
            try {
                UUID.fromString(it).toString()
            } catch (_: IllegalArgumentException) {
                throw validationFailed("/deck_id", "invalid_value", "deck_id must be a UUID.")
            }
        }
        val mine = when (val v = params["mine"]) {
            null, "false" -> false
            "true" -> true
            else -> throw validationFailed("/mine", "invalid_value", "mine must be true or false, not \"$v\".")
        }
        val limit = params["limit"]?.let {
            it.toIntOrNull()?.takeIf { n -> n in 1..200 }
                ?: throw validationFailed("/limit", "invalid_value", "limit must be an integer from 1 to 200.")
        } ?: 50
        val cursor = params["cursor"]?.let { decode(it, literal, deckId, mine) }

        val rows = db.tx { dsl ->
            if (deckId != null &&
                dsl.fetchOne("select 1 from app.deck where id = ?::uuid and deleted_at is null", deckId) == null
            ) {
                throw Problems.notFound("No deck $deckId.")
            }
            val no = SearchText.noRank()
            val binds = mutableListOf<Any?>()
            val sql = StringBuilder()
            val occExpr = if (deckId != null) "di.occurrence_count" else "0"
            sql.append(
                "select e.seq, e.headword, e.headword_reading, e.is_common, e.senses->0->'gloss' as glosses, e.pos, " +
                    "e.freq_rank, e.retired_release_id is not null as retired, " +
                    "coalesce((select ws.status from app.word_status ws where ws.entry_seq = e.seq), 'new') as status, " +
                    "(select n.id::text from app.note n where n.entry_seq = e.seq and n.deleted_at is null limit 1) as note_id, " +
                    "$occExpr as occ, coalesce(e.freq_rank, $no) as f, " +
                    "(select coalesce(json_agg(json_build_object('deck_id', d.id, 'name', d.name, 'type', d.type, " +
                    "'occurrence_count', x.occurrence_count) order by d.name collate \"C\", d.id), '[]'::json)::text " +
                    "from app.deck_item x join app.deck d on d.id = x.deck_id " +
                    "where x.entry_seq = e.seq and x.deleted_at is null and d.deleted_at is null) as decks " +
                    "from ref.entry_kanji k join ref.dict_entry e on e.seq = k.seq ",
            )
            if (deckId != null) {
                sql.append("join app.deck_item di on di.entry_seq = e.seq and di.deck_id = ?::uuid and di.deleted_at is null ")
                binds += deckId
            }
            sql.append("where k.literal = ? and e.retired_release_id is null ")
            binds += literal
            if (mine) {
                sql.append(
                    "and (exists (select 1 from app.deck_item x join app.deck d on d.id = x.deck_id " +
                        "where x.entry_seq = e.seq and x.deleted_at is null and d.deleted_at is null) " +
                        "or exists (select 1 from app.note n where n.entry_seq = e.seq and n.deleted_at is null and n.type = 'vocab')) ",
                )
            }
            if (cursor != null) {
                if (deckId != null) {
                    sql.append("and (-di.occurrence_count, coalesce(e.freq_rank, $no), e.seq) > (?, ?, ?) ")
                    binds += -cursor.o
                } else {
                    sql.append("and (coalesce(e.freq_rank, $no), e.seq) > (?, ?) ")
                }
                binds += cursor.f
                binds += cursor.s
            }
            val order = if (deckId != null) "di.occurrence_count desc, " else ""
            sql.append("order by $order coalesce(e.freq_rank, $no), e.seq limit ?")
            binds += limit + 1
            dsl.fetch(sql.toString(), *binds.toTypedArray()).map { r ->
                val item = linkedMapOf<String, Any?>(
                    "seq" to r.get("seq", Long::class.java),
                    "headword" to r.get("headword", String::class.java),
                    "headword_reading" to r.get("headword_reading", String::class.java),
                    "is_common" to r.get("is_common", Boolean::class.java),
                    "glosses" to r.get("glosses", String::class.java).let { g ->
                        if (g == null) emptyList() else json.readTree(g).map { it["text"].asText() }
                    },
                    "pos" to r.get("pos", Array<String>::class.java).toList(),
                    "freq_rank" to r.get("freq_rank", Int::class.javaObjectType),
                    "retired" to r.get("retired", Boolean::class.java),
                    "status" to r.get("status", String::class.java),
                    "note_id" to r.get("note_id", String::class.java),
                    "decks" to json.readTree(r.get("decks", String::class.java)),
                )
                Row(item, r.get("occ", Int::class.java), r.get("f", Int::class.java))
            }
        }
        val page = rows.take(limit)
        val next = if (rows.size > limit) {
            val last = page.last()
            b64.encodeToString(json.writeValueAsBytes(KanjiWordsCursor(last.occ, last.f, last.item["seq"] as Long, literal, deckId, mine)))
        } else {
            null
        }
        call.respondJson(mapOf("items" to page.map { it.item }, "next_cursor" to next))
    }

    private fun decode(s: String, literal: String, deckId: String?, mine: Boolean): KanjiWordsCursor {
        val bad = validationFailed("/cursor", "invalid_value", "Malformed cursor, or the other parameters changed since it was issued.")
        val c = try {
            json.readValue<KanjiWordsCursor>(b64d.decode(s))
        } catch (_: Exception) {
            throw bad
        }
        if (c.l != literal || c.d != deckId || c.m != mine) throw bad
        return c
    }
}
