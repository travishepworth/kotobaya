package kotobaya.cards

import kotobaya.decks.SmartDecks
import kotobaya.review.ReviewSettings
import kotobaya.review.rolloverMs
import kotobaya.review.studyDayOf
import kotobaya.search.DefaultSearchQueryParser
import kotobaya.search.sql.CompiledSearch
import kotobaya.search.sql.Dialect
import kotobaya.search.sql.SearchContext
import kotobaya.search.sql.SearchSqlCompiler
import kotobaya.search.sql.SmartDeckRef
import org.jooq.DSLContext

/**
 * The card search language over Postgres: parse (`422 invalid_query` with the error offset), compile with a
 * tz-aware context, select. In the Postgres dialect every term is decided in SQL, so `CompiledSearch.accepts` is
 * always true and no row needs a residual check; a compiler that said otherwise is refused here rather than
 * acted on (T108b: callers keep only rows `accepts` approves, bulk actions apply it before acting).
 */
internal object CardSearch {
    fun compile(dsl: DSLContext, query: String?, nowMs: Long, settings: ReviewSettings = ReviewSettings.load(dsl)): CompiledSearch {
        val node = SmartDecks.parse(query ?: "")
        val today = studyDayOf(nowMs, settings.timezone, settings.dayRolloverHour)
        val smart = dsl.fetch("select name, query from app.deck where type = 'smart' and deleted_at is null").mapNotNull {
            val parsed = DefaultSearchQueryParser.parse(it.get(1, String::class.java))
            parsed.query?.takeIf { _ -> parsed.errors.isEmpty() }?.let { n -> SmartDeckRef(it.get(0, String::class.java), n) }
        }
        val ctx = SearchContext(
            studyDay = today.day,
            nowMs = nowMs,
            leechThreshold = settings.leechThreshold,
            // is:hot needs the HotScorer inputs (not loaded here): it matches nothing until a ticket supplies them.
            smartDecks = smart,
            dayStart = { offset -> rolloverMs(settings.timezone, settings.dayRolloverHour, today.date.plusDays(offset.toLong())) },
        )
        val compiled = SearchSqlCompiler.compile(node, Dialect.POSTGRES, ctx)
        check(!compiled.residual) { "the Postgres dialect must decide every term in SQL" }
        return compiled
    }

    /** Ids of up to [limit] matching cards, ascending by id. */
    fun ids(dsl: DSLContext, compiled: CompiledSearch, limit: Int): List<String> {
        val sql = compiled.select()
        return dsl.fetch("${sql.sql} ORDER BY c.id LIMIT $limit", *sql.params.toTypedArray()).map { it.get(0).toString() }
    }
}
