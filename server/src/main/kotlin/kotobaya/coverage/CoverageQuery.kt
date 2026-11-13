package kotobaya.coverage

import org.jooq.DSLContext
import java.math.BigDecimal
import java.math.RoundingMode

/** A `hide` class of the `CoverageHide` parameter, in contract enum order. */
internal enum class HideClass(val wire: String, val posClass: String?) {
    NAMES("names", "name"),
    PARTICLES("particles", "particle"),
    AUXILIARY("auxiliary", "auxiliary"),
    IGNORED("ignored", null),
    UNMAPPED("unmapped", null),
}

private val KNOWN = arrayOf("known", "never_forget")
private val IGNORED_STATUSES = arrayOf("blacklisted", "ignored")
private val CURVE_POINTS = listOf(0, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000)

/** One measured entry: effective status and token weight (occurrence count, or 1 for user/list deck items). */
internal class EntryRow(val seq: Long, val status: String, val tokens: Long)

internal class SegmentRow(
    val id: String,
    val ordinal: Int,
    val title: String,
    val uniqueTotal: Int,
    val uniqueUnknown: Int,
    val tokenTotal: Long,
    val knownTokens: Long,
    val unmapped: Long,
)

/** Unknown and total tokens of one parsed media under the same `hide`, for the difficulty rank. */
internal class MediaLoad(val mediaId: String, val unknown: Long, val total: Long)

/** `known / total * 100` rounded half-up to 2 decimals; 0 when total is 0. */
internal fun pct(part: Long, total: Long, scale: Int = 100): Double =
    if (total == 0L) 0.0 else BigDecimal(part * scale).divide(BigDecimal(total), 2, RoundingMode.HALF_UP).toDouble()

/**
 * SQL reads behind coverage (ADR-017: computed per request, no cache). Status is the effective
 * `coalesce(word_status.status, 'new')`; `pos_class` filters apply per occurrence row. One round trip per section.
 */
internal class CoverageQuery(private val dsl: DSLContext, hide: Set<HideClass>) {
    private val hiddenPos: Array<String> = hide.mapNotNull { it.posClass }.toTypedArray()
    private val hideIgnored = HideClass.IGNORED in hide
    private val hideUnmapped = HideClass.UNMAPPED in hide

    /** Statuses left out of every number except `ignored_count`. */
    private val excluded: Array<String> = if (hideIgnored) IGNORED_STATUSES else emptyArray()

    /** Statuses counted as unknown. */
    private val unknown: Array<String> = if (hideIgnored) arrayOf("new") else arrayOf("new", *IGNORED_STATUSES)

    /** Per-entry token sums of a media (or one segment), all statuses, pos-class filters applied. */
    fun mediaEntries(mediaId: String, segmentId: String?): List<EntryRow> {
        val seg = if (segmentId != null) " and o.segment_id = ?::uuid" else ""
        val args = listOfNotNull(mediaId, segmentId) + listOf(hiddenPos)
        return dsl.fetch(
            "select o.entry_seq, coalesce(ws.status, 'new'), sum(o.count)::bigint from app.occurrence o " +
                "left join app.word_status ws on ws.entry_seq = o.entry_seq " +
                "where o.media_id = ?::uuid$seg and o.pos_class <> all(?::text[]) group by o.entry_seq, ws.status",
            *args.toTypedArray(),
        ).map { EntryRow(it.get(0, Long::class.java), it.get(1, String::class.java), it.get(2, Long::class.java)) }
    }

    /** The live entry items of a user/list deck (SYNC.md 8.5 identities), each weighing one token. */
    fun deckEntries(deckId: String): List<EntryRow> =
        dsl.fetch(
            "select v.eseq, coalesce(ws.status, 'new') from (${kotobaya.decks.visibleItems("?::uuid")}) v " +
                "left join app.word_status ws on ws.entry_seq = v.eseq where v.eseq is not null",
            deckId,
        ).map { EntryRow(it.get(0, Long::class.java), it.get(1, String::class.java), 1L) }

    /** Segment summaries in `ordinal` order (one row when [segmentId] is set; empty if it is not a segment of the media). */
    fun segments(mediaId: String, segmentId: String?): List<SegmentRow> {
        val seg = if (segmentId != null) " and s.id = ?::uuid" else ""
        // bind order follows the SQL text: five filter arrays, the subquery pos-class array, then the ids
        val args = listOf(excluded, excluded, unknown, excluded, KNOWN, hiddenPos, mediaId) + listOfNotNull(segmentId)
        return dsl.fetch(
            "select s.id::text, s.ordinal, s.title, s.unmapped_token_count, " +
                "count(o.entry_seq) filter (where o.st <> all(?::text[])), " +
                "count(o.entry_seq) filter (where o.st <> all(?::text[]) and o.st = any(?::text[])), " +
                "coalesce(sum(o.count) filter (where o.st <> all(?::text[])), 0)::bigint, " +
                "coalesce(sum(o.count) filter (where o.st = any(?::text[])), 0)::bigint " +
                "from app.media_segment s left join (select oc.segment_id, oc.entry_seq, oc.count, coalesce(ws.status, 'new') as st " +
                "from app.occurrence oc left join app.word_status ws on ws.entry_seq = oc.entry_seq " +
                "where oc.pos_class <> all(?::text[])) o on o.segment_id = s.id " +
                "where s.media_id = ?::uuid$seg group by s.id order by s.ordinal",
            *args.toTypedArray(),
        ).map {
            val unmapped = if (hideUnmapped) 0L else it.get(3, Long::class.java)
            SegmentRow(
                id = it.get(0, String::class.java),
                ordinal = it.get(1, Int::class.java),
                title = it.get(2, String::class.java),
                uniqueTotal = it.get(4, Int::class.java),
                uniqueUnknown = it.get(5, Int::class.java),
                tokenTotal = it.get(6, Long::class.java) + unmapped,
                knownTokens = it.get(7, Long::class.java),
                unmapped = unmapped,
            )
        }
    }

    /** Unknown and total tokens of every live parsed media with a non-zero total, under this `hide`. */
    fun mediaLoads(): List<MediaLoad> =
        dsl.fetch(
            "with live as (select id from app.media where deleted_at is null and content_version > 0), " +
                "occ as (select o.media_id, o.count, coalesce(ws.status, 'new') as st from app.occurrence o " +
                "join live on live.id = o.media_id left join app.word_status ws on ws.entry_seq = o.entry_seq " +
                "where o.pos_class <> all(?::text[])), " +
                "a as (select media_id, coalesce(sum(count) filter (where st = any(?::text[])), 0) as u, " +
                "coalesce(sum(count) filter (where st <> all(?::text[])), 0) as t from occ group by media_id), " +
                "n as (select s.media_id, sum(s.unmapped_token_count) as n from app.media_segment s join live on live.id = s.media_id group by s.media_id) " +
                "select coalesce(a.media_id, n.media_id)::text, coalesce(a.u, 0)::bigint, " +
                "(coalesce(a.t, 0) + case when ? then 0 else coalesce(n.n, 0) end)::bigint " +
                "from a full join n on n.media_id = a.media_id",
            hiddenPos, unknown, excluded, hideUnmapped,
        ).map { MediaLoad(it.get(0, String::class.java), it.get(1, Long::class.java), it.get(2, Long::class.java)) }
            .filter { it.total > 0 }

    /** The `Coverage` numbers (without ids, segments and difficulty) for [entries] plus [unmapped] tokens. */
    fun summarise(entries: List<EntryRow>, unmapped: Long): Summary {
        val ignoredCount = entries.count { it.status in IGNORED_STATUSES }
        val counted = entries.filter { it.status !in excluded }
        val known = counted.filter { it.status in KNOWN }
        val learning = counted.filter { it.status == "learning" }
        val unknownRows = counted.filter { it.status in unknown }
            .sortedWith(compareByDescending<EntryRow> { it.tokens }.thenBy { it.seq })
        val knownTokens = known.sumOf { it.tokens }
        val unknownTokens = unknownRows.sumOf { it.tokens }
        val total = knownTokens + learning.sumOf { it.tokens } + unknownTokens + unmapped

        val curve = mutableListOf<Pair<Int, Double>>()
        var learned = 0L
        var taken = 0
        for (n in CURVE_POINTS.filter { it < unknownRows.size } + unknownRows.size) {
            while (taken < n) learned += unknownRows[taken++].tokens
            curve += n to pct(knownTokens + learned, total)
        }
        return Summary(
            uniqueKnown = known.size,
            uniqueLearning = learning.size,
            uniqueUnknown = unknownRows.size,
            knownTokens = knownTokens,
            learningTokens = learning.sumOf { it.tokens },
            unknownTokens = unknownTokens,
            unmapped = unmapped,
            totalTokens = total,
            ignoredCount = ignoredCount,
            curve = curve,
        )
    }
}

internal class Summary(
    val uniqueKnown: Int,
    val uniqueLearning: Int,
    val uniqueUnknown: Int,
    val knownTokens: Long,
    val learningTokens: Long,
    val unknownTokens: Long,
    val unmapped: Long,
    val totalTokens: Long,
    val ignoredCount: Int,
    val curve: List<Pair<Int, Double>>,
)
