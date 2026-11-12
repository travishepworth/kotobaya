package kotobaya.imports

import com.fasterxml.jackson.databind.JsonNode

/**
 * T207a's `ImportPlan.to_json()`, schema version 1 (worker/src/kotobaya_worker/importers/jpdb_history/plan.py).
 *
 * The worker decides everything that depends on the export and the dictionary (mapping, groups, de-duplication,
 * grades, review ids, per-record buckets); [JpdbHistoryApply] decides what depends on the live database and
 * writes it as ops. Parsing is strict: a plan this class cannot read fails the job instead of importing less.
 */
internal data class JpdbPlan(
    val importReviews: Boolean,
    val importWordStatus: Boolean,
    val vocabGroups: List<VocabGroup>,
    val kanjiNotes: List<KanjiNote>,
    val kanjiGroups: List<KanjiGroup>,
    val unmapped: List<JsonNode>,
    val kanjiUnmapped: List<JsonNode>,
    val counts: Counts,
) {
    data class Review(
        val id: String,
        val reviewedAt: Long,
        val rating: Int,
        val button: String?,
    )

    data class VocabGroup(val entrySeq: Long, val carded: Boolean, val cardKind: String, val reviews: List<Review>)

    data class KanjiNote(val character: String, val fields: Map<String, String>)

    data class KanjiGroup(
        val character: String,
        val cardKind: String,
        val suspendedOnCreate: Boolean,
        val rowCount: Int,
        val reviews: List<Review>,
    )

    /** `RecordCounts` of the plan: the per-record buckets that do not depend on the database. */
    data class Counts(
        val vocabularyTotal: Int,
        val vocabularyCarded: Int,
        val vocabularyStatusOnly: Int,
        val vocabularyEnJpIgnored: Int,
        val kanjiTotal: Int,
        val kanjiCarded: Int,
        val reviewsTotal: Int,
        val reviewsRating: Int,
        val reviewsSkippedRows: Int,
        val reviewsUnknownGrade: Int,
        val statusEventsUnknown: Int,
        val statusEventsKnown: Int,
        val reviewsDuplicateDropped: Int,
    )

    companion object {
        const val VERSION = 1

        fun parse(root: JsonNode): JpdbPlan {
            val version = root.path("version")
            require(version.isInt && version.asInt() == VERSION) { "unsupported jpdb import plan version $version" }
            val options = root.req("options")
            return JpdbPlan(
                importReviews = options.req("import_reviews").bool(),
                importWordStatus = options.req("import_word_status").bool(),
                vocabGroups = root.req("vocab_groups").map { g ->
                    val fate = g.req("fate").str()
                    require(fate == "carded" || fate == "status_only") { "unknown vocab group fate $fate" }
                    VocabGroup(g.req("entry_seq").long(), fate == "carded", g.req("card_kind").str(), g.req("reviews").map(::review))
                },
                kanjiNotes = root.req("kanji_notes").map { n ->
                    val fields = linkedMapOf<String, String>()
                    n.req("fields").fields().forEach { (k, v) -> fields[k] = v.str() }
                    KanjiNote(n.req("character").str(), fields)
                },
                kanjiGroups = root.req("kanji_groups").map { g ->
                    val kind = g.req("card_kind").str()
                    require(kind == "keyword" || kind == "writing") { "unknown kanji card kind $kind" }
                    KanjiGroup(g.req("character").str(), kind, g.req("suspended_on_create").bool(), g.req("row_count").int(), g.req("reviews").map(::review))
                },
                unmapped = root.req("unmapped").toList(),
                kanjiUnmapped = root.req("kanji_unmapped").toList(),
                counts = root.req("counts").let { c ->
                    Counts(
                        vocabularyTotal = c.req("vocabulary_total").int(),
                        vocabularyCarded = c.req("vocabulary_carded").int(),
                        vocabularyStatusOnly = c.req("vocabulary_status_only").int(),
                        vocabularyEnJpIgnored = c.req("vocabulary_en_jp_ignored").int(),
                        kanjiTotal = c.req("kanji_total").int(),
                        kanjiCarded = c.req("kanji_carded").int(),
                        reviewsTotal = c.req("reviews_total").int(),
                        reviewsRating = c.req("reviews_rating").int(),
                        reviewsSkippedRows = c.req("reviews_skipped_rows").int(),
                        reviewsUnknownGrade = c.req("reviews_unknown_grade").int(),
                        statusEventsUnknown = c.req("status_events_unknown").int(),
                        statusEventsKnown = c.req("status_events_known").int(),
                        reviewsDuplicateDropped = c.req("reviews_duplicate_dropped").int(),
                    )
                },
            )
        }

        private fun review(r: JsonNode): Review {
            require(r.req("review_type").str() == "scheduled" && r.req("import_source").str() == "jpdb") { "unexpected planned review $r" }
            val button = r.req("button")
            return Review(r.req("id").str(), r.req("reviewed_at").long(), r.req("rating").int(), if (button.isNull) null else button.str())
        }

        private fun JsonNode.req(name: String): JsonNode =
            get(name) ?: throw IllegalArgumentException("jpdb import plan: missing '$name'")

        private fun JsonNode.str(): String { require(isTextual) { "jpdb import plan: expected a string, got $this" }; return asText() }
        private fun JsonNode.bool(): Boolean { require(isBoolean) { "jpdb import plan: expected a boolean, got $this" }; return asBoolean() }
        private fun JsonNode.long(): Long { require(isIntegralNumber && canConvertToLong()) { "jpdb import plan: expected an integer, got $this" }; return asLong() }
        private fun JsonNode.int(): Int { require(isIntegralNumber && canConvertToInt()) { "jpdb import plan: expected an integer, got $this" }; return asInt() }
    }
}
