package kotobaya.coverage

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotobaya.notes.Errors
import kotobaya.notes.gone
import kotobaya.notes.pathId
import kotobaya.server.ApiException
import kotobaya.server.Database
import kotobaya.server.OperationHandler
import kotobaya.server.Problems
import kotobaya.server.formatInstant
import kotobaya.server.respondJson
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import java.time.Instant

private val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

/**
 * Handlers for `getMediaCoverage` and `getDeckCoverage` (T404c), keyed by operationId for `AppDeps.handlers`.
 *
 * Read-only; computed per request from `occurrence`, `media_segment`, `deck_item` and `word_status` (ADR-017), so
 * every response reflects the current word statuses and `sentence.unknown_count` stays NULL. Wiring (Main.kt, W-5):
 * `handlers += CoverageModule(db).handlers`.
 */
class CoverageModule(private val db: Database, private val clock: () -> Instant = Instant::now) {
    private val log = LoggerFactory.getLogger(CoverageModule::class.java)

    val handlers: Map<String, OperationHandler> = mapOf(
        "getMediaCoverage" to { media(this) },
        "getDeckCoverage" to { deck(this) },
    )

    private suspend fun media(call: ApplicationCall) {
        val id = call.pathId("media_id")
        val hide = call.hide()
        val segmentId = call.request.queryParameters["segment_id"]?.let { v ->
            if (UUID_RE.matches(v)) v.lowercase() else throw Problems.notFound("No such segment of this media.")
        }
        val started = System.nanoTime()
        val body = db.tx { dsl -> mediaCoverage(dsl, id, segmentId, hide) }
        log.debug("coverage media={} segment={} took {} ms", id, segmentId, (System.nanoTime() - started) / 1_000_000)
        call.respondJson(body)
    }

    private suspend fun deck(call: ApplicationCall) {
        val id = call.pathId("deck_id")
        val hide = call.hide()
        val body = db.tx { dsl ->
            val row = dsl.fetchOne(
                "select type, media_id::text, to_char(deleted_at at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"') " +
                    "from app.deck where id = ?::uuid",
                id,
            ) ?: throw Problems.notFound("No such deck.")
            val type = row.get(0, String::class.java)
            row.get(2, String::class.java)?.let { throw gone("deck", it) }
            when (type) {
                "smart" -> throw ApiException(HttpStatusCode.Conflict, "not_applicable", "Not applicable", "A smart deck has no items to measure.")
                "media" -> mediaCoverage(dsl, row.get(1, String::class.java), null, hide)
                else -> {
                    val q = CoverageQuery(dsl, hide)
                    render(id, null, hide, q.summarise(q.deckEntries(id), 0L), emptyList(), null)
                }
            }
        }
        call.respondJson(body)
    }

    private fun mediaCoverage(dsl: DSLContext, mediaId: String, segmentId: String?, hide: Set<HideClass>): Map<String, Any?> {
        val row = dsl.fetchOne(
            "select id::text, content_version, to_char(deleted_at at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"') " +
                "from app.media where id = ?::uuid",
            mediaId,
        ) ?: throw Problems.notFound("No such media.")
        row.get(2, String::class.java)?.let { throw gone("media", it) }
        val id = row.get(0, String::class.java)
        val q = CoverageQuery(dsl, hide)
        val segments = q.segments(id, segmentId)
        if (segmentId != null && segments.isEmpty()) throw Problems.notFound("No such segment of this media.")
        if (row.get(1, Int::class.java) == 0) {
            // Not parsed yet: all-zero numbers (contract), whatever rows may exist.
            val zeros = segments.map { SegmentRow(it.id, it.ordinal, it.title, 0, 0, 0, 0, 0) }
            return render(id, segmentId, hide, q.summarise(emptyList(), 0L), zeros, null)
        }
        val summary = q.summarise(q.mediaEntries(id, segmentId), segments.sumOf { it.unmapped })
        val difficulty = if (segmentId != null || summary.totalTokens == 0L) null else difficulty(q, id, summary)
        return render(id, segmentId, hide, summary, segments, difficulty)
    }

    /** Rank 1 = easiest; ties share a rank (1 + number of media strictly easier). Compared exactly, not rounded. */
    private fun difficulty(q: CoverageQuery, mediaId: String, s: Summary): Map<String, Any> {
        val others = q.mediaLoads().filter { it.mediaId != mediaId }
        val easier = others.count { it.unknown * s.totalTokens < s.unknownTokens * it.total }
        return linkedMapOf(
            "unknown_per_1000" to pct(s.unknownTokens, s.totalTokens, scale = 1000),
            "rank" to easier + 1,
            "of" to others.size + 1,
        )
    }

    private fun render(
        deckId: String,
        segmentId: String?,
        hide: Set<HideClass>,
        s: Summary,
        segments: List<SegmentRow>,
        difficulty: Map<String, Any>?,
    ): Map<String, Any?> = linkedMapOf(
        "deck_id" to deckId,
        "segment_id" to segmentId,
        "hide" to HideClass.entries.filter { it in hide }.map { it.wire },
        "unique" to linkedMapOf(
            "total" to s.uniqueKnown + s.uniqueLearning + s.uniqueUnknown,
            "known" to s.uniqueKnown,
            "learning" to s.uniqueLearning,
            "unknown" to s.uniqueUnknown,
        ),
        "tokens" to linkedMapOf(
            "total" to s.totalTokens,
            "known" to s.knownTokens,
            "learning" to s.learningTokens,
            "unknown" to s.unknownTokens,
            "unmapped" to s.unmapped,
            "known_pct" to pct(s.knownTokens, s.totalTokens),
        ),
        "ignored_count" to s.ignoredCount,
        "curve" to s.curve.map { (n, p) -> linkedMapOf("words" to n, "known_pct" to p) },
        "segments" to segments.map {
            linkedMapOf(
                "segment_id" to it.id,
                "ordinal" to it.ordinal,
                "title" to it.title,
                "unique_total" to it.uniqueTotal,
                "unique_unknown" to it.uniqueUnknown,
                "token_total" to it.tokenTotal,
                "known_pct" to pct(it.knownTokens, it.tokenTotal),
            )
        },
        "difficulty" to difficulty,
        "computed_at" to formatInstant(clock()),
    )
}

/** `hide`: absent = `ignored`; empty = nothing; else comma-separated contract classes (unknown ones are 422). */
private fun ApplicationCall.hide(): Set<HideClass> {
    val raw = request.queryParameters.getAll("hide") ?: return setOf(HideClass.IGNORED)
    val parts = raw.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
    val classes = parts.map { p -> HideClass.entries.firstOrNull { it.wire == p } }
    if (classes.any { it == null }) {
        Errors().apply { add("/hide", "invalid_value", "Comma-separated: ${HideClass.entries.joinToString(", ") { it.wire }}.") }.throwIfAny()
    }
    return classes.filterNotNull().toSet()
}
