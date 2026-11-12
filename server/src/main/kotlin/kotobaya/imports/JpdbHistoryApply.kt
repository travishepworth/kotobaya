package kotobaya.imports

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotobaya.contracts.sync.OpType
import kotobaya.notes.NoteViews
import kotobaya.notes.payload
import kotobaya.server.Uuid7
import kotobaya.server.json
import kotobaya.sync.OpApply
import kotobaya.sync.OpTx
import org.slf4j.LoggerFactory

/** Progress and cancellation of one import run; both are checked between batches. */
interface ImportProgress {
    /** [fraction] in 0..1 of the apply phase. */
    fun report(fraction: Double, message: String)

    /** True once the job was cancelled (or is no longer ours); the run stops before the next batch. */
    fun cancelled(): Boolean

    companion object {
        val None: ImportProgress = object : ImportProgress {
            override fun report(fraction: Double, message: String) = Unit
            override fun cancelled() = false
        }
    }
}

/** The run stopped at a batch boundary because the job was cancelled; committed batches stay (a re-run resumes). */
class ImportCancelled : RuntimeException("jpdb history import cancelled")

/**
 * Applies a T207a [JpdbPlan] as server ops (CLAUDE.md rule 8) and returns the full `JobResultJpdbHistory`
 * (contracts/api/openapi.yaml `importJpdbHistory`, C-4; ADR-008, ADR-009).
 *
 * - Ops: `note.created` (origin `jpdb_import`), `card.created` (only the planned kind; `suspended = true` in the
 *   payload for writing / known-only kanji cards, never a separate `card.suspended`), `review.logged` with the
 *   plan's deterministic ids (`import_source = jpdb`, `utc_offset_min = null`), `word.status_set` (origin
 *   `jpdb_import`). The card cache is folded by the [OpApply]'s `CardCacheHook` in the same transaction as each
 *   review (SYNC.md 9.2/9.3), so cards have due dates when the run ends; reviews are emitted in group order
 *   (ascending `reviewed_at`), i.e. incremental folds on a fresh card.
 * - Batches: one op transaction per [batchUnits] notes / statuses. Every existence check runs inside the batch's
 *   transaction, under the sync lock, so a concurrent device add is either seen (reused) or arrives later and goes
 *   through the SYNC.md 8.1 merge (the import's note already holds the reference and survives).
 * - Idempotent and resumable: live notes are found by `entry_seq` / `kanji_literal`, live cards by `(note, kind)`,
 *   reviews by id, so a re-run (or a run after a crash or cancel) writes only what is missing and counts the rest as
 *   `notes_existing`, `reviews_already_imported`, ...
 *
 * Beyond the contract's members the result carries `reviews_id_collisions` (a subset of
 * `reviews_already_imported`): planned reviews whose id is already used by a review of another card — the known
 * review-id v1 collision (one vid text-mapped to two seqs, same timestamp, grade and occurrence). They are counted,
 * logged and never emitted (the op would be a no-op on `review_log` anyway).
 *
 * A kanji whose character contains U+0000 cannot be a `kanji_literal` (SYNC.md 2.1): its groups are skipped and
 * moved to `kanji_unmapped` (rows) and `reviews_skipped` (their rating events). U+0000 is stripped from field text.
 */
class JpdbHistoryApply(private val opApply: OpApply, private val batchUnits: Int = 200) {
    private val log = LoggerFactory.getLogger("kotobaya.imports.jpdb")

    /** Parses [planJson] (T207a schema v1) and applies it. Blocking; call from an IO thread. */
    fun apply(planJson: JsonNode, progress: ImportProgress = ImportProgress.None): ObjectNode =
        apply(JpdbPlan.parse(planJson), progress)

    internal fun apply(plan: JpdbPlan, progress: ImportProgress): ObjectNode {
        val c = plan.counts
        val planned = plan.vocabGroups.sumOf { it.reviews.size } + plan.kanjiGroups.sumOf { it.reviews.size }
        check(planned == c.reviewsRating) { "jpdb import plan lists $planned reviews but counts ${c.reviewsRating} rating events" }
        val run = Run()

        val units = mutableListOf<(OpTx) -> Unit>()
        if (plan.importReviews) {
            plan.vocabGroups.filter { it.carded }.forEach { g -> units += { tx -> run.vocab(tx, g) } }
            val groupsByChar = plan.kanjiGroups.groupBy { it.character }
            val noted = plan.kanjiNotes.map { it.character }.toSet()
            groupsByChar.keys.firstOrNull { it !in noted }?.let { error("jpdb import plan: kanji group '$it' has no kanji note") }
            plan.kanjiNotes.forEach { n -> units += { tx -> run.kanji(tx, n, groupsByChar[n.character].orEmpty()) } }
        }
        if (plan.importWordStatus) {
            plan.vocabGroups.filter { !it.carded }.forEach { g -> units += { tx -> run.status(tx, g.entrySeq) } }
        }

        val chunks = units.chunked(batchUnits)
        chunks.forEachIndexed { i, chunk ->
            if (progress.cancelled()) throw ImportCancelled()
            opApply.transactionBlocking(null) { tx -> chunk.forEach { it(tx) } }
            progress.report((i + 1).toDouble() / chunks.size, "applied ${i + 1} of ${chunks.size} batches")
        }
        if (chunks.isEmpty()) progress.report(1.0, "nothing to write")

        return result(plan, run)
    }

    /** Write-side counters of one run. */
    private inner class Run {
        var notesCreated = 0
        var notesExisting = 0
        var cardsCreated = 0
        var kanjiNotesCreated = 0
        var kanjiNotesExisting = 0
        var kanjiCardsCreated = 0
        var kanjiCardsSuspended = 0
        var reviewsImported = 0
        var reviewsAlready = 0
        var collisions = 0
        var wordStatusesSet = 0
        var nulKanjiRows = 0
        var nulKanjiRatings = 0
        val nulKanjiUnmapped = mutableListOf<JsonNode>()

        /** review id → card it was logged on, for every id this run emitted or found. */
        private val seen = HashMap<String, String>()

        fun vocab(tx: OpTx, g: JpdbPlan.VocabGroup) {
            val views = NoteViews(tx.dsl)
            val noteId = views.liveHolder("entry_seq", g.entrySeq)?.also { notesExisting++ } ?: run {
                val id = Uuid7.generate().toString()
                val p = json.createObjectNode().put("id", id).put("note_type_id", VOCAB_TYPE).put("entry_seq", g.entrySeq)
                p.set<JsonNode>("fields", json.createObjectNode())
                p.put("origin", ORIGIN)
                tx.emit(OpType.NOTE_CREATED, payload(p))
                notesCreated++
                id
            }
            val cardId = ensureCard(tx, noteId, g.cardKind, suspended = false) { cardsCreated++ }
            reviews(tx, cardId, g.reviews)
        }

        fun kanji(tx: OpTx, n: JpdbPlan.KanjiNote, groups: List<JpdbPlan.KanjiGroup>) {
            if (n.character.contains('\u0000')) {
                // Not a storable kanji_literal (SYNC 2.1): skipped and counted, never silently dropped.
                for (g in groups) {
                    nulKanjiRows += g.rowCount
                    nulKanjiRatings += g.reviews.size
                    repeat(g.rowCount) { i ->
                        nulKanjiUnmapped.add(json.createObjectNode().put("character", n.character).put("card_kind", g.cardKind)
                            .put("review_count", if (i == 0) g.reviews.size else 0))
                    }
                }
                log.warn("jpdb import: skipped kanji with U+0000 in its character ({} groups)", groups.size)
                return
            }
            val views = NoteViews(tx.dsl)
            val noteId = views.liveHolder("kanji_literal", n.character)?.also { kanjiNotesExisting++ } ?: run {
                val id = Uuid7.generate().toString()
                val fields = json.createObjectNode()
                n.fields.forEach { (k, v) -> fields.put(k, v.replace("\u0000", "")) }
                val p = json.createObjectNode().put("id", id).put("note_type_id", KANJI_TYPE).put("kanji_literal", n.character)
                p.set<JsonNode>("fields", fields)
                p.put("origin", ORIGIN)
                tx.emit(OpType.NOTE_CREATED, payload(p))
                kanjiNotesCreated++
                id
            }
            for (g in groups) {
                val cardId = ensureCard(tx, noteId, g.cardKind, g.suspendedOnCreate) {
                    kanjiCardsCreated++
                    if (g.suspendedOnCreate) kanjiCardsSuspended++
                }
                reviews(tx, cardId, g.reviews)
            }
        }

        fun status(tx: OpTx, seq: Long) {
            // Any live override (any status, any origin) wins: a re-run never overwrites the user's choice.
            val has = tx.dsl.fetchExists(tx.dsl.selectOne().from("app.word_status").where("entry_seq = ? and override_status is not null", seq))
            if (has) return
            tx.emit(
                OpType.WORD_STATUS_SET,
                payload(json.createObjectNode().put("entry_seq", seq).put("status", "known").put("origin", ORIGIN)),
            )
            wordStatusesSet++
        }

        /** The live card of [kind] on [noteId] (an existing one keeps its `suspended` state), else a new one. */
        private fun ensureCard(tx: OpTx, noteId: String, kind: String, suspended: Boolean, created: () -> Unit): String {
            tx.dsl.fetchOptional(
                "select id::text from app.card where note_id = ?::uuid and kind = ? and deleted_at is null", noteId, kind,
            ).map { it.get(0, String::class.java) }.orElse(null)?.let { return it }
            val id = Uuid7.generate().toString()
            val p = json.createObjectNode().put("id", id).put("note_id", noteId).put("kind", kind)
            if (suspended) p.put("suspended", true)
            tx.emit(OpType.CARD_CREATED, payload(p))
            created()
            return id
        }

        private fun reviews(tx: OpTx, cardId: String, reviews: List<JpdbPlan.Review>) {
            if (reviews.isEmpty()) return
            // id -> the live card (merge survivor) its row feeds, or "" when that card or its note is tombstoned.
            val existing = HashMap<String, String>()
            reviews.map { it.id }.chunked(1000).forEach { ids ->
                tx.dsl.fetch(
                    "select r.id::text, case when s.deleted_at is null and n.deleted_at is null then s.id::text else '' end " +
                        "from app.review_log r join app.card c on c.id = r.card_id " +
                        "join app.card s on s.id = coalesce(c.merged_into_id, c.id) join app.note n on n.id = s.note_id " +
                        "where r.id = any(?::uuid[])",
                    ids.toTypedArray(),
                ).forEach { existing[it.get(0, String::class.java)] = it.get(1, String::class.java) }
            }
            for (r in reviews) {
                val owner = seen[r.id] ?: existing[r.id]
                if (owner != null) {
                    reviewsAlready++
                    // A row on a tombstoned card (the user deleted the note) is simply already imported; a row
                    // feeding another live card is the id collision.
                    if (owner.isNotEmpty() && owner != cardId) {
                        collisions++
                        log.warn("jpdb import: review id {} is already used by card {}, not logged on card {}", r.id, owner, cardId)
                    }
                    seen.putIfAbsent(r.id, owner)
                    continue
                }
                val p = json.createObjectNode().put("id", r.id).put("card_id", cardId).put("reviewed_at", r.reviewedAt)
                p.putNull("utc_offset_min")
                p.put("review_type", "scheduled").put("rating", r.rating)
                if (r.button == null) p.putNull("button") else p.put("button", r.button)
                p.putNull("duration_ms")
                p.put("import_source", "jpdb")
                tx.emit(OpType.REVIEW_LOGGED, payload(p))
                reviewsImported++
                seen[r.id] = cardId
            }
        }
    }

    private fun result(plan: JpdbPlan, run: Run): ObjectNode {
        val c = plan.counts
        val ratingsNotWritten = if (plan.importReviews) run.nulKanjiRatings else c.reviewsRating
        val r = json.createObjectNode()
        r.put("vocabulary_total", c.vocabularyTotal)
        r.put("vocabulary_carded", c.vocabularyCarded)
        r.put("vocabulary_status_only", c.vocabularyStatusOnly)
        r.put("vocabulary_en_jp_ignored", c.vocabularyEnJpIgnored)
        r.put("notes_created", run.notesCreated)
        r.put("notes_existing", run.notesExisting)
        r.put("cards_created", run.cardsCreated)
        r.put("kanji_total", c.kanjiTotal)
        r.put("kanji_carded", c.kanjiCarded - run.nulKanjiRows)
        r.put("kanji_notes_created", run.kanjiNotesCreated)
        r.put("kanji_notes_existing", run.kanjiNotesExisting)
        r.put("kanji_cards_created", run.kanjiCardsCreated)
        r.put("kanji_cards_suspended", run.kanjiCardsSuspended)
        r.set<JsonNode>("kanji_unmapped", json.createArrayNode().addAll(plan.kanjiUnmapped).addAll(run.nulKanjiUnmapped))
        r.put("reviews_total", c.reviewsTotal)
        r.put("reviews_imported", run.reviewsImported)
        r.put("reviews_already_imported", run.reviewsAlready)
        r.put("reviews_skipped", c.reviewsSkippedRows + ratingsNotWritten)
        r.put("reviews_unknown_grade", c.reviewsUnknownGrade)
        r.put("reviews_duplicate_dropped", c.reviewsDuplicateDropped)
        r.put("status_events_unknown", c.statusEventsUnknown)
        r.put("status_events_known", c.statusEventsKnown)
        r.put("word_statuses_set", run.wordStatusesSet)
        r.set<JsonNode>("unmapped", json.createArrayNode().addAll(plan.unmapped))
        r.put("reviews_id_collisions", run.collisions)
        checkSums(r)
        return r
    }

    companion object {
        const val VOCAB_TYPE = "00000000-0000-7000-8000-000000000101"
        const val KANJI_TYPE = "00000000-0000-7000-8000-000000000102"
        const val ORIGIN = "jpdb_import"

        /** The three sums of `JobResultJpdbHistory`; a result that breaks one fails the job instead of hiding records. */
        fun checkSums(r: JsonNode) {
            fun n(k: String) = r.path(k).asLong()
            check(n("vocabulary_total") == n("vocabulary_carded") + n("vocabulary_status_only") + n("vocabulary_en_jp_ignored") + r.path("unmapped").size()) {
                "vocabulary buckets do not sum to vocabulary_total: $r"
            }
            check(n("kanji_total") == n("kanji_carded") + r.path("kanji_unmapped").size()) { "kanji buckets do not sum to kanji_total: $r" }
            val events = listOf(
                "reviews_imported", "reviews_already_imported", "reviews_skipped", "reviews_unknown_grade",
                "status_events_unknown", "status_events_known", "reviews_duplicate_dropped",
            ).sumOf(::n)
            check(n("reviews_total") == events) { "event buckets do not sum to reviews_total: $r" }
        }
    }
}
