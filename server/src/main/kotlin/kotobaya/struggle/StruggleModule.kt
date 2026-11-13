package kotobaya.struggle

import com.fasterxml.jackson.databind.node.ObjectNode
import kotobaya.cards.cursor
import kotobaya.cards.pageLimit
import kotobaya.cards.pageOffset
import kotobaya.contracts.fsrs.ManualAction
import kotobaya.contracts.fsrs.Rating
import kotobaya.contracts.fsrs.ReviewType
import kotobaya.contracts.hot.HotCard
import kotobaya.contracts.hot.HotConfig
import kotobaya.contracts.hot.HotReview
import kotobaya.contracts.replay.ReplayReview
import kotobaya.fsrs.FsrsScheduler
import kotobaya.notes.Errors
import kotobaya.review.ReviewSettings
import kotobaya.review.ReviewViews
import kotobaya.server.Database
import kotobaya.server.OperationHandler
import kotobaya.server.respondJson
import kotobaya.struggle.DefaultHotScorer
import kotobaya.struggle.HotHistory
import org.jooq.DSLContext
import org.jooq.Record
import java.time.Instant

private const val DAY_MS = 86_400_000L

/**
 * Handlers for `listHotWords` and `listLeeches` (T501), keyed by operationId for `AppDeps.handlers`.
 *
 * Read-only: nothing is minted, nothing is stored; both lists are computed per request from `review_log` and the
 * card cache. Hot-word scoring is the core [DefaultHotScorer] over [HotHistory] (replay gives each failure's state).
 * Wiring (Main.kt): `handlers += StruggleModule(db).handlers`.
 */
class StruggleModule(private val db: Database, private val clock: () -> Instant = Instant::now) {
    val handlers: Map<String, OperationHandler> = mapOf(
        "listHotWords" to { hotWords(this) },
        "listLeeches" to { leeches(this) },
        "listHotKanji" to { hotKanji(this) },
    )

    /**
     * `listHotKanji`: the hot-word scores of every live vocab note, summed per kanji of the note's entry
     * (`ref.entry_kanji`). Contract reading: `word_count` is the number of hot notes containing the kanji; notes without
     * an entry link contribute nothing (no kanji known). Ordered by score desc, then literal.
     */
    private suspend fun hotKanji(call: io.ktor.server.application.ApplicationCall) {
        val e = Errors()
        val limit = call.request.queryParameters["limit"]?.let { v ->
            v.toIntOrNull()?.takeIf { it in 1..200 } ?: run { e.add("/limit", "out_of_range", "Must be 1 to 200."); null }
        } ?: 20
        e.throwIfAny()
        val now = clock().toEpochMilli()
        val items = db.tx { dsl -> HotWords(dsl, now).kanji(limit) }
        call.respondJson(mapOf("items" to items))
    }

    private suspend fun hotWords(call: io.ktor.server.application.ApplicationCall) {
        val e = Errors()
        val limit = call.request.queryParameters["limit"]?.let { v ->
            v.toIntOrNull()?.takeIf { it in 1..200 } ?: run { e.add("/limit", "out_of_range", "Must be 1 to 200."); null }
        } ?: 20
        e.throwIfAny()
        val now = clock().toEpochMilli()
        val items = db.tx { dsl -> HotWords(dsl, now).list(limit) }
        call.respondJson(mapOf("items" to items))
    }

    private suspend fun leeches(call: io.ktor.server.application.ApplicationCall) {
        val q = call.request.queryParameters
        val e = Errors()
        val limit = pageLimit(q["limit"], e)
        val hash = "leeches"
        val offset = pageOffset(q["cursor"], hash, e)
        e.throwIfAny()
        val body = db.tx { dsl ->
            val settings = ReviewSettings.load(dsl)
            val ids = dsl.fetch(
                "select c.id::text from app.card c join app.note n on n.id = c.note_id " +
                    "where c.deleted_at is null and c.merged_into_id is null and n.deleted_at is null and c.lapses >= ? " +
                    "order by c.lapses desc, c.id limit ? offset ?",
                settings.leechThreshold, limit + 1, offset,
            ).map { it.get(0, String::class.java) }
            val views = ReviewViews(dsl, settings.leechThreshold).cards(ids.take(limit))
            linkedMapOf(
                "items" to ids.take(limit).mapNotNull { views[it]?.json },
                "next_cursor" to if (ids.size > limit) cursor(offset + limit, hash) else null,
            )
        }
        call.respondJson(body)
    }
}

internal class HotWords(private val dsl: DSLContext, private val now: Long) {
    private val config = HotConfig.DEFAULT
    private val since = now - config.windowDays * DAY_MS
    private val leechThreshold by lazy { ReviewSettings.load(dsl).leechThreshold }

    fun list(limit: Int): List<Map<String, Any?>> =
        scoredNotes().take(limit).map { render(it.noteId, it.score, it.failures) }

    fun kanji(limit: Int): List<Map<String, Any?>> {
        val hot = scoredNotes()
        if (hot.isEmpty()) return emptyList()
        val rows = dsl.fetch(
            "select n.id::text, k.literal from app.note n join ref.entry_kanji k on k.seq = n.entry_seq " +
                "where n.id = any(?::uuid[])",
            hot.map { it.noteId }.toTypedArray(),
        )
        val scoreOf = hot.associate { it.noteId to it.score }
        val score = HashMap<String, Double>()
        val count = HashMap<String, Int>()
        for (r in rows) {
            val lit = r.get(1, String::class.java)
            score.merge(lit, scoreOf.getValue(r.get(0, String::class.java)), Double::plus)
            count.merge(lit, 1, Int::plus)
        }
        val top = score.entries.filter { it.value > 0.0 }
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key }).take(limit)
        if (top.isEmpty()) return emptyList()
        val lits = top.map { it.key }.toTypedArray()
        val meanings = dsl.fetch("select literal, meanings from ref.kanji where literal = any(?::text[])", lits)
            .associate { it.get(0, String::class.java) to it.get(1, Array<String>::class.java).toList() }
        val notes = dsl.fetch(
            "select distinct on (kanji_literal) kanji_literal, id::text from app.note " +
                "where type = 'kanji' and deleted_at is null and merged_into_id is null and kanji_literal = any(?::text[]) " +
                "order by kanji_literal, created_at, id",
            lits,
        ).associate { it.get(0, String::class.java) to it.get(1, String::class.java) }
        return top.map {
            linkedMapOf(
                "literal" to it.key, "score" to it.value, "word_count" to count.getValue(it.key),
                "meanings" to meanings[it.key].orEmpty(), "note_id" to notes[it.key],
            )
        }
    }

    private class Scored(val noteId: String, val score: Double, val failures: Int)

    /** Every hot live vocab note, hottest first (ties by note id). */
    private fun scoredNotes(): List<Scored> {
        // Live cards of live vocab notes, by note.
        val cardRows = dsl.fetch(
            "select c.id::text, c.note_id::text, c.difficulty, c.reps, c.lapses, c.preset_id::text " +
                "from app.card c join app.note n on n.id = c.note_id " +
                "where n.type = 'vocab' and n.deleted_at is null and c.deleted_at is null and c.merged_into_id is null",
        )
        if (cardRows.isEmpty()) return emptyList()
        val cardsByNote = cardRows.groupBy { it.get(1, String::class.java) }
        val presetOf = cardRows.associate { it.get(0, String::class.java) to it.get(5, String::class.java) }

        // Cards with an Again in the window: only these can make a note hot, so only their history is replayed.
        val failed = dsl.fetch(
            "select distinct coalesce(c.merged_into_id, c.id)::text from app.review_log r join app.card c on c.id = r.card_id " +
                "where r.undone_at is null and r.review_type in ('scheduled', 'learning') and r.rating = 1 " +
                "and (extract(epoch from r.reviewed_at) * 1000)::bigint > ?",
            since,
        ).map { it.get(0, String::class.java) }.filter { it in presetOf }
        if (failed.isEmpty()) return emptyList()

        val presets = ReviewViews.loadPresets(dsl)
        val history = loadHistory(failed)
        val hotByCard = failed.associateWith { id ->
            val params = FsrsScheduler.paramsOf(ReviewViews.effectivePreset(presetOf.getValue(id), presets))
            HotHistory.hotReviews(history[id].orEmpty(), params)
        }

        val scored = cardsByNote.mapNotNull { (noteId, rows) ->
            val reviews = rows.flatMap { hotByCard[it.get(0, String::class.java)].orEmpty() }
            if (reviews.isEmpty()) return@mapNotNull null
            val cards = rows.map {
                HotCard(it.get(0, String::class.java), (it.get(2) as Number?)?.toDouble(), (it.get(3) as Number).toInt(), (it.get(4) as Number).toInt())
            }
            val s = DefaultHotScorer.scoreNote(reviews, cards, now, config)
            if (!s.hot || s.score <= 0.0) return@mapNotNull null
            Scored(noteId, s.score, failuresIn(reviews))
        }.sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.noteId })
        return scored
    }

    private fun failuresIn(reviews: List<HotReview>): Int =
        reviews.count { it.rating == Rating.AGAIN && it.reviewedAt > since && it.reviewedAt <= now }

    /** Every `review_log` row of each survivor card (and its merge losers), by survivor id. */
    internal fun loadHistory(survivors: List<String>): Map<String, List<ReplayReview>> {
        val out = HashMap<String, MutableList<ReplayReview>>()
        // Survivors and their merge losers by card id (card pk and merged_into index), then review_log by card_id index. Filtering
        // on `coalesce(merged_into_id, id) = any(...)` instead cannot use an index and scanned every review row per survivor.
        val survivorOf = HashMap<String, String>()
        val ids = survivors.toTypedArray()
        for (r in dsl.fetch(
            "select id::text, coalesce(merged_into_id, id)::text from app.card where id = any(?::uuid[]) or merged_into_id = any(?::uuid[])",
            ids, ids,
        )) survivorOf[r.get(0, String::class.java)] = r.get(1, String::class.java)
        val rows = dsl.fetch(
            "select r.card_id::text, r.id::text, r.card_id::text, r.hlc::text, " +
                "(extract(epoch from r.reviewed_at) * 1000)::bigint, r.utc_offset_min, r.review_type, r.rating, r.manual_action, " +
                "(extract(epoch from r.manual_due_at) * 1000)::bigint, r.undone_hlc::text, r.import_source " +
                "from app.review_log r where r.undone_at is null and r.card_id = any(?::uuid[])",
            survivorOf.keys.toTypedArray(),
        )
        for (r in rows) {
            val survivor = survivorOf.getValue(r.get(0, String::class.java))
            out.getOrPut(survivor) { mutableListOf() } += replayReview(r, survivor)
        }
        return out
    }

    private fun replayReview(r: Record, survivor: String): ReplayReview {
        val type = r.get(6, String::class.java)
        val action = r.get(8, String::class.java)
        return ReplayReview(
            id = r.get(1, String::class.java),
            cardId = survivor,
            hlc = r.get(3, String::class.java),
            reviewedAt = (r.get(4) as Number).toLong(),
            utcOffsetMin = (r.get(5) as Number?)?.toInt(),
            reviewType = ReviewType.entries.first { it.wire == type },
            rating = (r.get(7) as Number?)?.toInt()?.let { v -> Rating.entries.first { it.value == v } },
            manualAction = action?.let { a -> ManualAction.entries.first { it.wire == a } },
            manualDueAt = (r.get(9) as Number?)?.toLong(),
            undoneHlc = r.get(10, String::class.java),
            importSource = r.get(11, String::class.java),
        )
    }

    private fun render(noteId: String, score: Double, failures: Int): Map<String, Any?> {
        val ids = dsl.fetch(
            "select c.id::text from app.card c where c.note_id = ?::uuid and c.deleted_at is null and c.merged_into_id is null " +
                "order by c.created_at, c.id",
            noteId,
        ).map { it.get(0, String::class.java) }
        val views = ReviewViews(dsl, leechThreshold).cards(ids)
        val cards = ids.mapNotNull { views[it]?.json }
        val note = checkNotNull(cards.firstOrNull()?.get("note")) { "hot note $noteId without cards" }
        val seq = note["entry_seq"]?.takeUnless { it.isNull }?.asLong()
        return linkedMapOf(
            "note_id" to noteId,
            "entry_seq" to seq,
            "score" to score,
            "failures_14d" to failures,
            "entry" to seq?.let { entrySummary(it, noteId) },
            "note" to note,
            "cards" to cards.map { it.deepCopy().apply { remove("note") } },
        )
    }

    private fun entrySummary(seq: Long, noteId: String): Map<String, Any?>? {
        val r = dsl.fetchOne(
            "select e.seq, e.headword, e.headword_reading, e.is_common, e.senses->0->'gloss' as glosses, e.pos, e.freq_rank, " +
                "e.retired_release_id is not null as retired, " +
                "coalesce((select ws.status from app.word_status ws where ws.entry_seq = e.seq), 'new') as status " +
                "from ref.dict_entry e where e.seq = ?",
            seq,
        ) ?: return null
        val glosses = r.get("glosses", String::class.java)
        return linkedMapOf(
            "seq" to seq,
            "headword" to r.get("headword", String::class.java),
            "headword_reading" to r.get("headword_reading", String::class.java),
            "is_common" to r.get("is_common", Boolean::class.java),
            "glosses" to (if (glosses == null) emptyList() else kotobaya.server.json.readTree(glosses).map { it["text"].asText() }),
            "pos" to r.get("pos", Array<String>::class.java).toList(),
            "freq_rank" to r.get("freq_rank", Int::class.javaObjectType),
            "retired" to r.get("retired", Boolean::class.java),
            "status" to r.get("status", String::class.java),
            "note_id" to noteId,
        )
    }
}
