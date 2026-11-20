package kotobaya.android.search

import app.cash.sqldelight.db.SqlDriver
import kotobaya.android.dict.InstalledDictionary
import kotobaya.android.net.ApiClient
import kotobaya.android.net.ApiError
import kotobaya.android.shell.api.actions.CardQueryResult
import kotobaya.contracts.search.SearchError
import kotobaya.db.app.AppDatabase
import kotobaya.search.DefaultSearchQueryParser
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
    /** The note's `reading` / `meaning` fields, else the dictionary's (see [withLabels]); never a placeholder. */
    val reading: String = "",
    val meaning: String = "",
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

private fun fieldText(fieldsJson: String, key: String): String {
    val fields = (DefaultCanonicalJson.decode(fieldsJson).value as? JsonObject)?.members.orEmpty()
    return (fields[key] as? JsonString)?.value.orEmpty().replace(Regex("<[^>]*>"), "").trim()
}

/** Fills what the note's fields leave empty (title, reading, meaning) from the dictionary entry the note links to. */
internal fun CardHit.withLabels(labels: Map<Long, EntryLabel>): CardHit {
    val l = entrySeq?.let(labels::get) ?: return this
    return copy(
        title = title.ifEmpty { l.headword },
        reading = reading.ifEmpty { l.reading },
        meaning = meaning.ifEmpty { l.gloss },
    )
}

/**
 * "My cards" over the local replica: the selection of [ReplicaCardQuery] (core parser and compiler, every term
 * evaluated on the device, the same selection `listCards` and `bulkCards` use), newest first. Nothing here
 * interprets the query language.
 */
class LocalCardSearch(
    private val driver: SqlDriver,
    db: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    /** L-7 steps 1-2 for the C-24 dictionary-form lookup; null (or nothing installed or cached): bare terms match note text only. */
    dictionary: InstalledDictionary? = null,
) {
    private val selection = ReplicaCardQuery(driver, db, dictionary)

    fun search(query: String): CardSearchOutcome {
        val parsed = DefaultSearchQueryParser.parse(query)
        if (parsed.query == null || parsed.errors.isNotEmpty()) return CardSearchOutcome.ParseError(parsed.errors)
        return when (val r = selection.select(query, clock())) {
            is CardQueryResult.Ids -> CardSearchOutcome.Results(details(r.ids))
            // unreachable: the parse above already failed for these, and ReplicaCardQuery never forwards
            is CardQueryResult.Invalid, is CardQueryResult.Forward -> CardSearchOutcome.ParseError(parsed.errors)
        }
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
                    fieldText(c.getString(7).orEmpty(), "reading"), fieldText(c.getString(7).orEmpty(), "meaning"),
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
                note?.optJSONObject("fields")?.toString()?.let { fieldText(it, "reading") }.orEmpty(),
                note?.optJSONObject("fields")?.toString()?.let { fieldText(it, "meaning") }.orEmpty(),
            )
        }
        CardSearchOutcome.Results(hits, fromServer = true)
    } catch (e: ApiError) {
        CardSearchOutcome.Failed(offline = e is ApiError.Offline || e is ApiError.Unreachable)
    } catch (e: JSONException) {
        CardSearchOutcome.Failed(offline = false)
    }
}
