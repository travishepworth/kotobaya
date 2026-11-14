package kotobaya.android.search

import app.cash.sqldelight.db.SqlDriver
import kotobaya.android.dict.InstalledDictionary
import kotobaya.android.net.ApiClient
import kotobaya.android.net.ApiError
import kotobaya.android.queue.QueueSettings
import kotobaya.android.queue.rolloverMs
import kotobaya.android.queue.studyDayOf
import kotobaya.contracts.search.SearchError
import kotobaya.db.app.AppDatabase
import kotobaya.search.DefaultSearchQueryParser
import kotobaya.search.sql.Dialect
import kotobaya.search.sql.DictFormRow
import kotobaya.search.sql.SqlFragment
import kotobaya.search.sql.ResidualRow
import kotobaya.search.sql.SearchContext
import kotobaya.search.sql.SearchSqlCompiler
import org.json.JSONException
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.sync.DefaultCanonicalJson
import java.net.URLEncoder

/** One card in "My cards" results. [entrySeq] null: the note has no dictionary entry. */
data class CardHit(
    val cardId: String,
    val noteId: String,
    val kind: String,
    val state: String,
    val suspended: Boolean,
    val entrySeq: Long?,
    val title: String,
)

sealed interface CardSearchOutcome {
    data class Results(val hits: List<CardHit>, val fromServer: Boolean = false) : CardSearchOutcome

    /** The parser rejected the query; [errors] are the parser's own. */
    data class ParseError(val errors: List<SearchError>) : CardSearchOutcome

    /** Server fallback failed (offline, unreachable, invalid query on the server, ...). */
    data class Failed(val offline: Boolean) : CardSearchOutcome
}

private const val EXPRESSION_FIELD = "expression"

/** Display text of a note: its `expression` field, else the first non-empty string field (key order). */
internal fun noteTitle(fieldsJson: String): String {
    val fields = (DefaultCanonicalJson.decode(fieldsJson).value as? JsonObject)?.members.orEmpty()
    fun text(key: String) = (fields[key] as? JsonString)?.value.orEmpty()
    val preferred = text(EXPRESSION_FIELD)
    if (preferred.isNotEmpty()) return preferred
    return fields.keys.sorted().map(::text).firstOrNull { it.isNotEmpty() }.orEmpty()
}

/**
 * "My cards" over the local replica: core's [DefaultSearchQueryParser] and [SearchSqlCompiler] (SQLite dialect,
 * residual predicate applied). Nothing here interprets the query language.
 * Not covered: `is:hot` (no hot set is passed) and live smart-deck terms (`deck:` matches stored membership only).
 */
class LocalCardSearch(
    private val driver: SqlDriver,
    private val db: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    /** The offline dictionary for the C-24 dictionary-form lookup; null (or not installed): bare terms match note text only. */
    private val dictionary: InstalledDictionary? = null,
    /** Cap of distinct entries one pattern may match before the dictionary branch is dropped (overridable in tests). */
    private val dictFormsMaxSeqs: Int = DICT_FORMS_MAX_SEQS,
) {
    fun search(query: String): CardSearchOutcome {
        val parsed = DefaultSearchQueryParser.parse(query)
        val node = parsed.query ?: return CardSearchOutcome.ParseError(parsed.errors)
        val now = clock()
        val st = QueueSettings.from(db.localQueueQueries.schedulerSettings().executeAsList().associate { it.key to it.value_ })
        val today = studyDayOf(now, st.timezone, st.dayRolloverHour)
        val ctx = SearchContext(
            studyDay = today.day,
            nowMs = now,
            leechThreshold = st.leechThreshold,
            dayStart = { off -> rolloverMs(st.timezone, st.dayRolloverHour, today.date.plusDays(off.toLong())) },
            dictForms = dictForms(),
        )
        val compiled = SearchSqlCompiler.compile(node, Dialect.SQLITE, ctx)
        val f = compiled.select()
        val ids = driver.rows(f.sql, f.params) { c ->
            val id = c.getString(0)!!
            if (!compiled.residual) {
                id
            } else {
                val probes = compiled.probes.indices.map { (c.getLong(7 + it) ?: 0L) != 0L }
                val row = ResidualRow(
                    c.getString(1)!!, c.getString(2)!!, c.getString(3)!!, c.getString(4)!!, c.getString(5)!!, c.getString(6)!!, probes,
                )
                if (compiled.accepts(row)) id else null
            }
        }.filterNotNull()
        return CardSearchOutcome.Results(details(ids))
    }

    /**
     * C-24 callback over the installed dictionary (a separate SQLite file, read off the main thread by the caller), or null
     * when none is installed. Perf: rows are intersected with the distinct entry seqs of the collection's notes while the
     * cursor is read, and a pattern that still matches more than [DICT_FORMS_MAX_SEQS] entries (a one-character kana such
     * as る) is treated as too broad to be a dictionary-form lookup: the dictionary branch is dropped for it (the note's own
     * text still matches), which keeps the bound seq list the compiler builds small.
     */
    private fun dictForms(): ((SqlFragment) -> List<DictFormRow>)? {
        val dict = dictionary?.takeIf { it.isAvailable() } ?: return null
        val collection: Set<Long> by lazy {
            driver.rows("SELECT DISTINCT entry_seq FROM note WHERE entry_seq IS NOT NULL AND deleted_at IS NULL") { it.getLong(0) ?: 0L }.toHashSet()
        }
        return { f ->
            val rows = dict.formRows(f.sql, f.params.map { it.toString() }) { it in collection }.orEmpty()
            if (rows.mapTo(HashSet()) { it.seq }.size > dictFormsMaxSeqs) emptyList() else rows
        }
    }

    private companion object {
        const val DICT_FORMS_MAX_SEQS = 3000
    }

    private fun details(ids: List<String>): List<CardHit> {
        val out = ArrayList<Triple<Long, String, CardHit>>()
        for (chunk in ids.chunked(400)) {
            driver.rows(
                "SELECT c.id, c.note_id, c.kind, c.state, c.suspended, c.created_at, n.entry_seq, n.fields " +
                    "FROM card c JOIN note n ON n.id = c.note_id WHERE c.id IN (${placeholders(chunk.size)})",
                chunk,
            ) { c ->
                val hit = CardHit(
                    c.getString(0)!!, c.getString(1)!!, c.getString(2)!!, c.getString(3)!!, (c.getLong(4) ?: 0L) != 0L,
                    c.getLong(6), noteTitle(c.getString(7).orEmpty()),
                )
                out += Triple(c.getLong(5) ?: 0L, hit.cardId, hit)
            }
        }
        return out.sortedWith(compareByDescending<Triple<Long, String, CardHit>> { it.first }.thenBy { it.second }).map { it.third }
    }
}

/** `GET /cards?query=` (listCards), the fallback when the replica is not usable. First page only. */
class ApiCardSearch(private val api: ApiClient) {
    suspend fun search(query: String, limit: Int = 100): CardSearchOutcome = try {
        val q = URLEncoder.encode(query, "UTF-8")
        val items = api.get("/cards?query=$q&limit=$limit").body.optJSONArray("items")
        val hits = (0 until (items?.length() ?: 0)).map { i ->
            val c = items!!.getJSONObject(i)
            val note = c.optJSONObject("note")
            CardHit(
                c.getString("id"), c.getString("note_id"), c.getString("kind"), c.getString("state"),
                c.optBoolean("suspended"), note?.takeIf { !it.isNull("entry_seq") }?.getLong("entry_seq"),
                note?.optJSONObject("fields")?.toString()?.let(::noteTitle).orEmpty(),
            )
        }
        CardSearchOutcome.Results(hits, fromServer = true)
    } catch (e: ApiError) {
        CardSearchOutcome.Failed(offline = e is ApiError.Offline || e is ApiError.Unreachable)
    } catch (e: JSONException) {
        CardSearchOutcome.Failed(offline = false)
    }
}
