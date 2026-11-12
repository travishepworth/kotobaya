package kotobaya.decks

import io.ktor.http.HttpStatusCode
import kotobaya.contracts.queue.StudyDay
import kotobaya.contracts.search.SearchNode
import kotobaya.notes.NoteViews
import kotobaya.search.DefaultSearchQueryParser
import kotobaya.search.sql.Dialect
import kotobaya.search.sql.SearchContext
import kotobaya.search.sql.SearchSqlCompiler
import kotobaya.search.sql.SmartDeckRef
import kotobaya.server.ApiException
import org.jooq.DSLContext

/** Smart decks: a stored card search string, evaluated by core's `SearchSqlCompiler` (Postgres dialect, every term decided in SQL). */
internal object SmartDecks {
    /** Parses [query]; a string that does not parse is `422 invalid_query` with one `errors[]` element per parse error. */
    fun parse(query: String): SearchNode {
        val r = DefaultSearchQueryParser.parse(query)
        val node = r.query
        if (node != null && r.errors.isEmpty()) return node
        val errors = r.errors.map {
            mapOf(
                "pointer" to "/query", "code" to it.code.name.lowercase(),
                "detail" to "Search error ${it.code.name.lowercase()} at ${it.span.start}.", "position" to it.span.start,
            )
        }
        throw ApiException(
            HttpStatusCode.UnprocessableEntity, "invalid_query", "Invalid search query",
            errors.firstOrNull()?.get("detail") as String? ?: "The query does not parse.", mapOf("errors" to errors),
        )
    }

    /**
     * Ids of the live cards the smart deck [deckId] selects, ascending. Other live smart decks resolve `deck:`
     * terms (a stored query that no longer parses is skipped). Not applicable to other deck types.
     */
    fun cardIds(dsl: DSLContext, deckId: String, nowMs: Long, studyDay: StudyDay, hotNoteIds: Set<String> = emptySet()): List<String> {
        val query = dsl.fetchValue("select query from app.deck where id = ?::uuid and type = 'smart' and deleted_at is null", deckId) as String?
            ?: throw ApiException(HttpStatusCode.Conflict, "not_applicable", "Conflict", "Not a live smart deck.")
        val others = dsl.fetch("select name, query from app.deck where type = 'smart' and deleted_at is null and id <> ?::uuid", deckId).mapNotNull {
            val parsed = DefaultSearchQueryParser.parse(it.get(1, String::class.java))
            parsed.query?.takeIf { _ -> parsed.errors.isEmpty() }?.let { n -> SmartDeckRef(it.get(0, String::class.java), n) }
        }
        val ctx = SearchContext(studyDay, nowMs, NoteViews(dsl).leechThreshold(), hotNoteIds, others)
        val sql = SearchSqlCompiler.compile(parse(query), Dialect.POSTGRES, ctx).select()
        return dsl.fetch(sql.sql + " ORDER BY c.id", *sql.params.toTypedArray()).map { it.get(0).toString() }
    }
}
