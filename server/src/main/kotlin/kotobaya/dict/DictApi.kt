package kotobaya.dict

import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotobaya.server.ApiException
import kotobaya.server.Database
import kotobaya.server.OperationHandler
import kotobaya.server.Problems
import kotobaya.server.json
import kotobaya.server.respondJson
import org.jooq.DSLContext
import org.jooq.Record
import java.text.Normalizer
import java.util.Base64

/**
 * Handlers for the dictionary operations of T106, keyed by operationId for `AppDeps.handlers`.
 *
 * `listEntryExamples` lives in EntryExamples.kt (F-17). `getDictRelease` / `refreshDictionary` live in DictRelease.kt (W-5a).
 */
fun dictHandlers(db: Database): Map<String, OperationHandler> {
    val api = DictApi(db)
    return mapOf(
        "searchDictionary" to { api.search(this) },
        "getDictEntry" to { api.entry(this) },
        "getKanji" to { api.kanji(this) },
        "listKanjiWords" to { KanjiWordsApi(db).list(this) },
        "listEntryExamples" to { EntryExamplesApi(db).list(this) },
        "getDictRelease" to { DictReleaseApi.get(db, this) },
        "refreshDictionary" to { DictReleaseApi.refresh(db, this) },
    )
}

internal fun validationFailed(pointer: String, code: String, detail: String) = ApiException(
    HttpStatusCode.UnprocessableEntity, "validation_failed", "Validation failed", detail,
    mapOf("errors" to listOf(mapOf("pointer" to pointer, "code" to code, "detail" to detail))),
)

/** Sort position of the last row of a page; `h` ties the cursor to the query it came from. */
internal data class SearchCursor(val g: Int, val f: Int, val s: Long, val h: String)

internal object SearchText {
    private const val NO_RANK = Int.MAX_VALUE

    fun noRank() = NO_RANK

    fun normalise(text: String): String = foldKatakana(Normalizer.normalize(text.trim(), Normalizer.Form.NFKC))

    /** Same fold as the worker's `normalize_form` (katakana to hiragana; ー and the rest stay). */
    fun foldKatakana(text: String): String = buildString(text.length) {
        for (ch in text) {
            val cp = ch.code
            append(if (cp in 0x30A1..0x30F6 || cp == 0x30FD || cp == 0x30FE) (cp - 0x60).toChar() else ch)
        }
    }

    fun isKana(cp: Int) = cp in 0x3041..0x3096 || cp in 0x309D..0x309F || cp in 0x30A1..0x30FA ||
        cp in 0x30FC..0x30FF || cp in 0xFF66..0xFF9F

    fun isKanji(cp: Int) = cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF || cp in 0xF900..0xFAFF ||
        cp in 0x20000..0x2FA1F || cp in 0x30000..0x323AF || cp == 0x3005 || cp == 0x3007

    fun containsKanjiOrKana(q: String): Boolean = q.codePoints().anyMatch { isKana(it) || isKanji(it) }

    fun likeEscape(s: String): String = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}

internal class DictApi(private val db: Database) {
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val b64d = Base64.getUrlDecoder()

    suspend fun search(call: ApplicationCall) {
        val params = call.request.queryParameters
        val raw = params["q"] ?: throw validationFailed("/q", "required", "q is required.")
        val q = Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC)
        if (q.isEmpty()) throw validationFailed("/q", "invalid_value", "q must not be empty.")
        if (q.codePointCount(0, q.length) > 100) throw validationFailed("/q", "invalid_value", "q is at most 100 characters.")
        val commonOnly = when (val v = params["common_only"]) {
            null -> false
            "true" -> true
            "false" -> false
            else -> throw validationFailed("/common_only", "invalid_value", "common_only must be true or false, not \"$v\".")
        }
        val limit = params["limit"]?.let {
            it.toIntOrNull()?.takeIf { n -> n in 1..500 }
                ?: throw validationFailed("/limit", "invalid_value", "limit must be an integer from 1 to 500.")
        } ?: 50
        val hash = Integer.toHexString("$q\u0000$commonOnly".hashCode())
        val cursor = params["cursor"]?.let { decodeCursor(it, hash) }

        val rows = db.tx { dsl -> runSearch(dsl, q, commonOnly, limit, cursor) }
        val page = rows.take(limit)
        val next = if (rows.size > limit) page.last().let { encodeCursor(SearchCursor(it.g, it.f, it.summary.seq, hash)) } else null
        call.respondJson(mapOf("items" to page.map { it.summary.toMap() }, "next_cursor" to next))
    }

    private class Hit(val g: Int, val f: Int, val summary: Summary)

    private class Summary(
        val seq: Long, val headword: String, val reading: String, val common: Boolean, val glosses: List<String>,
        val pos: List<String>, val freq: Int?, val retired: Boolean, val status: String, val noteId: String?,
    ) {
        fun toMap() = linkedMapOf(
            "seq" to seq, "headword" to headword, "headword_reading" to reading, "is_common" to common,
            "glosses" to glosses, "pos" to pos, "freq_rank" to freq, "retired" to retired,
            "status" to status, "note_id" to noteId,
        )
    }

    private fun runSearch(dsl: DSLContext, q: String, commonOnly: Boolean, limit: Int, cursor: SearchCursor?): List<Hit> {
        val latin = !SearchText.containsKanjiOrKana(q)
        val kana: String? = if (latin) Romaji.toHiragana(q) else q
        val binds = mutableListOf<Any?>()
        val branches = mutableListOf<String>()
        if (kana != null) {
            val key = SearchText.foldKatakana(kana)
            val esc = SearchText.likeEscape(key)
            val short = key.codePointCount(0, key.length) <= 2
            // 1-2 characters: exact or prefix (btree); 3+: substring (trigram), still graded exact, prefix, rest.
            branches += "select seq, case when norm = ? then 0 when norm like ? then 1 else 2 end as g " +
                "from ref.dict_entry_form where norm like ?"
            binds += key; binds += "$esc%"; binds += if (short) "$esc%" else "%$esc%"
        }
        if (latin) {
            branches += "select seq, 2 as g from ref.dict_entry_gloss where gloss ilike ?"
            binds += "%${SearchText.likeEscape(q)}%"
        }
        val sql = StringBuilder()
        sql.append("with hits as (select seq, min(g) as g from (")
        sql.append(branches.joinToString(" union all "))
        sql.append(") u group by seq) ")
        sql.append(
            "select e.seq, e.headword, e.headword_reading, e.is_common, e.senses->0->'gloss' as glosses, e.pos, " +
                "e.freq_rank, e.retired_release_id is not null as retired, h.g, " +
                "coalesce((select ws.status from app.word_status ws where ws.entry_seq = e.seq), 'new') as status, " +
                "(select n.id::text from app.note n where n.entry_seq = e.seq and n.deleted_at is null limit 1) as note_id " +
                "from hits h join ref.dict_entry e on e.seq = h.seq where e.retired_release_id is null ",
        )
        if (commonOnly) sql.append("and e.is_common ")
        if (cursor != null) {
            sql.append("and (h.g, coalesce(e.freq_rank, ${SearchText.noRank()}), e.seq) > (?, ?, ?) ")
            binds += cursor.g; binds += cursor.f; binds += cursor.s
        }
        sql.append("order by h.g, coalesce(e.freq_rank, ${SearchText.noRank()}), e.seq limit ?")
        binds += limit + 1
        return dsl.fetch(sql.toString(), *binds.toTypedArray()).map { r ->
            val freq = r.get("freq_rank", Int::class.javaObjectType)
            Hit(
                r.get("g", Int::class.java),
                freq ?: SearchText.noRank(),
                Summary(
                    r.get("seq", Long::class.java), r.get("headword", String::class.java),
                    r.get("headword_reading", String::class.java), r.get("is_common", Boolean::class.java),
                    glossTexts(r.get("glosses", String::class.java)), r.get("pos", Array<String>::class.java).toList(),
                    freq, r.get("retired", Boolean::class.java), r.get("status", String::class.java),
                    r.get("note_id", String::class.java),
                ),
            )
        }
    }

    private fun glossTexts(jsonArray: String?): List<String> =
        if (jsonArray == null) emptyList() else json.readTree(jsonArray).map { it["text"].asText() }

    private fun encodeCursor(c: SearchCursor): String = b64.encodeToString(json.writeValueAsBytes(c))

    private fun decodeCursor(s: String, hash: String): SearchCursor {
        val bad = validationFailed("/cursor", "invalid_value", "Malformed cursor, or the other parameters changed since it was issued.")
        val c = try {
            json.readValue<SearchCursor>(b64d.decode(s))
        } catch (_: Exception) {
            throw bad
        }
        if (c.h != hash) throw bad
        return c
    }

    // ------------------------------------------------------------------ /dict/entries/{seq}

    suspend fun entry(call: ApplicationCall) {
        val seq = call.parameters["seq"]?.toLongOrNull()?.takeIf { it > 0 }
            ?: throw validationFailed("/seq", "invalid_value", "seq must be a positive integer.")
        val body = db.tx { dsl -> loadEntry(dsl, seq) } ?: throw Problems.notFound("No dictionary entry $seq.")
        call.respondJson(body)
    }

    private fun loadEntry(dsl: DSLContext, seq: Long): Map<String, Any?>? {
        val e = dsl.fetchOne("select * from ref.dict_entry where seq = ?", seq) ?: return null
        val freqs = dsl.fetch(
            "select f.list_id, f.rank from ref.entry_freq f join ref.freq_list l on l.id = f.list_id " +
                "where f.seq = ? order by l.priority",
            seq,
        ).map { mapOf("list_id" to it.get("list_id", String::class.java), "rank" to it.get("rank", Int::class.java)) }
        val pitch = dsl.fetch("select reading, positions from ref.entry_pitch where seq = ? order by reading", seq)
            .map { mapOf("reading" to it.get("reading", String::class.java), "positions" to it.get("positions", Array<Short>::class.java).map { p -> p.toInt() }) }
        val kanjiForms = json.readTree(e.get("kanji_forms", String::class.java))
        val kanji = LinkedHashSet<String>()
        for (f in kanjiForms) {
            val text = f["text"].asText()
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                if (SearchText.isKanji(cp) && cp != 0x3005 && cp != 0x3007) kanji += String(Character.toChars(cp))
                i += Character.charCount(cp)
            }
        }
        val (status, noteId) = overlay(dsl, seq = seq, literal = null)
        return linkedMapOf(
            "seq" to seq,
            "language" to e.get("language", String::class.java),
            "headword" to e.get("headword", String::class.java),
            "headword_reading" to e.get("headword_reading", String::class.java),
            "is_common" to e.get("is_common", Boolean::class.java),
            "kanji_forms" to kanjiForms,
            "readings" to json.readTree(e.get("readings", String::class.java)),
            "senses" to json.readTree(e.get("senses", String::class.java)),
            "pos" to e.get("pos", Array<String>::class.java).toList(),
            "misc" to e.get("misc", Array<String>::class.java).toList(),
            "freq_rank" to e.get("freq_rank", Int::class.javaObjectType),
            "retired" to (e.get("retired_release_id") != null),
            "freqs" to freqs,
            "pitch" to pitch,
            "kanji" to kanji.toList(),
            "status" to status,
            "note_id" to noteId,
        )
    }

    private fun overlay(dsl: DSLContext, seq: Long?, literal: String?): Pair<String, String?> {
        if (literal != null) {
            val id = dsl.fetchValue("select id::text from app.note where kanji_literal = ? and deleted_at is null limit 1", literal) as String?
            return "new" to id
        }
        val status = dsl.fetchOptional("select status from app.word_status where entry_seq = ?", seq)
            .map { it.get(0, String::class.java) }.orElse("new")
        val id = dsl.fetchValue("select id::text from app.note where entry_seq = ? and deleted_at is null limit 1", seq) as String?
        return status to id
    }

    // ------------------------------------------------------------------ /kanji/{literal}

    suspend fun kanji(call: ApplicationCall) {
        val literal = call.parameters["literal"].orEmpty()
        if (literal.isEmpty() || literal.codePointCount(0, literal.length) != 1) {
            throw validationFailed("/literal", "invalid_value", "literal must be exactly one character.")
        }
        val body = db.tx { dsl ->
            val k: Record = dsl.fetchOne("select * from ref.kanji where literal = ?", literal) ?: return@tx null
            linkedMapOf(
                "literal" to literal,
                "on_readings" to k.get("on_readings", Array<String>::class.java).toList(),
                "kun_readings" to k.get("kun_readings", Array<String>::class.java).toList(),
                "nanori" to k.get("nanori", Array<String>::class.java).toList(),
                "meanings" to k.get("meanings", Array<String>::class.java).toList(),
                "stroke_count" to k.get("stroke_count", Int::class.javaObjectType),
                "grade" to k.get("grade", Int::class.javaObjectType),
                "jlpt" to k.get("jlpt", Int::class.javaObjectType),
                "freq_rank" to k.get("freq_rank", Int::class.javaObjectType),
                "radical" to k.get("radical", String::class.java),
                "components" to k.get("components", Array<String>::class.java).toList(),
                "note_id" to overlay(dsl, null, literal).second,
            )
        } ?: throw Problems.notFound("No kanji $literal.")
        call.respondJson(body)
    }
}
