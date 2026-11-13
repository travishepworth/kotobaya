package kotobaya.review

import io.ktor.server.application.ApplicationCall
import kotobaya.contracts.fsrs.Scheduler
import kotobaya.contracts.search.SearchNode
import kotobaya.decks.SmartDecks
import kotobaya.drill.DrillSelector
import kotobaya.drill.DrillSources
import kotobaya.notes.Errors
import kotobaya.notes.jsonBody
import kotobaya.search.DefaultSearchQueryParser
import kotobaya.search.sql.Dialect
import kotobaya.search.sql.SearchContext
import kotobaya.search.sql.SearchSqlCompiler
import kotobaya.search.sql.SmartDeckRef
import kotobaya.server.Database
import kotobaya.server.Uuid7
import kotobaya.server.respondJson
import org.jooq.DSLContext
import java.time.Instant

private val DRILL_KEYS = setOf("source", "entry_seq", "query", "card_ids", "mode", "limit")

/**
 * `createDrillSession`: an ephemeral list of cards; nothing is stored and no op is emitted. The answers go to
 * `POST /review/answer` with `mode = drill|cram`, which logs them without touching the schedule.
 *
 * Struggle sources (T503) are picked by the core `DrillSelector` (seeded by the session id) over inputs loaded by
 * `DrillSources`: `hot_words`, `leeches`, `confusables` (confirmed pairs only; none means an empty list).
 * `cards` and `query` are served here directly. The request schema has no single-word source, so none is exposed.
 */
internal class DrillApi(private val db: Database, private val scheduler: Scheduler, private val clock: () -> Instant) {
    suspend fun create(call: ApplicationCall) {
        val body = call.jsonBody()
        val e = Errors()
        e.unknownKeys(body, DRILL_KEYS)
        val source = e.enum(body, "source", setOf("hot_words", "leeches", "confusables", "query", "cards"), required = true)
        val mode = e.enum(body, "mode", setOf("drill", "cram")) ?: "drill"
        val limit = body.get("limit")?.let { e.long(it, "/limit", 1, 500)?.toInt() } ?: 20
        val entrySeq = body.get("entry_seq")?.let { e.long(it, "/entry_seq", 1) }
        val query = e.string(body, "query", 1, 2000)
        val cardIds = body.get("card_ids")?.let { n ->
            if (!n.isArray || n.size() < 1 || n.size() > 500) {
                e.add("/card_ids", "invalid_value", "An array of 1 to 500 ids.")
                null
            } else {
                n.mapIndexedNotNull { i, x -> e.uuidValue(x, "/card_ids/$i") }.also {
                    if (it.toSet().size != it.size) e.add("/card_ids", "invalid_value", "Ids must be unique.")
                }
            }
        }
        if (source == "query" && query == null && !body.has("query")) e.add("/query", "required", "query is required for source query.")
        if (source == "cards" && cardIds == null && !body.has("card_ids")) e.add("/card_ids", "required", "card_ids is required for source cards.")
        e.throwIfAny()
        val nowMs = clock().toEpochMilli()
        val out = db.tx { dsl ->
            val settings = ReviewSettings.load(dsl)
            val sessionId = Uuid7.generate().toString()
            val seed = sessionId.fold(0L) { h, ch -> h * 31 + ch.code }
            val sources = DrillSources(dsl, nowMs)
            val ids = when (source) {
                "cards" -> live(dsl, checkNotNull(cardIds)).take(limit)
                "leeches" -> DrillSelector.leeches(sources.leechCandidates(settings.leechThreshold), settings.leechThreshold, limit, seed)
                "hot_words" -> {
                    val hot = sources.hotNotes()
                    DrillSelector.hotWords(hot, sources.candidatesOf(hot.map { it.noteId }), limit, seed)
                }
                "confusables" -> {
                    val only = entrySeq?.let { sources.noteOfEntry(it) }
                    val pairs = sources.confirmedPairs().filter { p -> only == null || p.noteA in only || p.noteB in only }
                    val cards = sources.candidatesOf(pairs.flatMap { listOf(it.noteA, it.noteB) }.toSet())
                    DrillSelector.confusables(pairs, cards, only?.singleOrNull(), limit, seed)
                }
                else -> byQuery(dsl, checkNotNull(query), settings, nowMs).let { live(dsl, it) }.take(limit)
            }
            val views = ReviewViews(dsl, settings.leechThreshold)
            mapOf(
                "id" to sessionId,
                "mode" to mode,
                "items" to views.items(ids, ids.associateWith { mode }, nowMs, scheduler),
            )
        }
        call.respondJson(out)
    }

    /** [ids] that are live cards of live notes, in the given order. */
    private fun live(dsl: DSLContext, ids: List<String>): List<String> {
        val ok = dsl.fetch(
            "select c.id::text from app.card c join app.note n on n.id = c.note_id " +
                "where c.id = any(?::uuid[]) and c.deleted_at is null and c.merged_into_id is null and n.deleted_at is null",
            ids.toTypedArray(),
        ).map { it.get(0, String::class.java) }.toSet()
        return ids.filter { it in ok }
    }

    /** Cards matching the search string, most lapses first (`-lapses`). */
    private fun byQuery(dsl: DSLContext, query: String, settings: ReviewSettings, nowMs: Long): List<String> {
        val node: SearchNode = SmartDecks.parse(query)
        val studyDay = studyDayOf(nowMs, settings.timezone, settings.dayRolloverHour)
        val smart = dsl.fetch("select name, query from app.deck where type = 'smart' and deleted_at is null").mapNotNull {
            val parsed = DefaultSearchQueryParser.parse(it.get(1, String::class.java))
            parsed.query?.takeIf { _ -> parsed.errors.isEmpty() }?.let { n -> SmartDeckRef(it.get(0, String::class.java), n) }
        }
        val ctx = SearchContext(
            studyDay.day, nowMs, settings.leechThreshold, emptySet(), smart,
            dayStart = { off -> rolloverMs(settings.timezone, settings.dayRolloverHour, studyDay.date.plusDays(off.toLong())) },
        )
        val sql = SearchSqlCompiler.compile(node, Dialect.POSTGRES, ctx).select()
        return dsl.fetch(sql.sql + " ORDER BY c.lapses DESC, c.id", *sql.params.toTypedArray()).map { it.get(0).toString() }
    }
}

