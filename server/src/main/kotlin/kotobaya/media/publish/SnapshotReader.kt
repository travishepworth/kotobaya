package kotobaya.media.publish

import com.github.luben.zstd.ZstdInputStream
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.sync.DefaultCanonicalJson
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/*
 * Reader for the C-11 snapshot archives (contracts/db/SNAPSHOT.md): `kotobaya-media-content` and
 * `kotobaya-deck-items`, format_version 1. Streams the zstd JSON Lines line by line, checks every rule of
 * SNAPSHOT.md 2, 3.3, 4.2 (the same list as contracts/db/media/validate.py) and hands each record to an
 * optional sink. Readers ignore unknown members (SNAPSHOT.md 6), so a later minor producer revision may add
 * optional members to the header or records.
 *
 * Deliberately free of server types: it depends only on the shared core's JSON model and zstd-jni, so a
 * device installer can reuse it as is.
 */

/** The archive breaks SNAPSHOT.md; nothing of it may be installed. */
class SnapshotInvalid(message: String) : RuntimeException(message)

data class ContentHeader(
    val mediaId: String,
    val contentVersion: Int,
    val segments: Int,
    val sentences: Int,
    val occurrences: Int,
    val grammarSpans: Int,
)

data class SegmentRecord(
    val id: String,
    val ordinal: Int,
    val kind: String,
    val title: String,
    val sentenceCount: Int,
    val tokenCount: Int,
    val unmappedTokenCount: Int,
)

data class SentenceRecord(
    val id: String,
    val segmentId: String,
    val ordinal: Int,
    val text: String,
    /** The `tokens` JSON array as stored in `app.sentence.tokens`. */
    val tokens: JsonArray,
    val origin: String,
    val startMs: Int?,
    val endMs: Int?,
    val audioBlobHash: String?,
    val imageBlobHash: String?,
    /** The `location` JSON object, or null. */
    val location: JsonObject?,
)

data class OccurrenceRecord(
    val segmentId: String,
    val entrySeq: Long,
    val count: Int,
    val posClass: String,
    val sentenceIds: List<String>,
)

data class GrammarSpanRecord(
    val sentenceId: String,
    val grammarPointId: String,
    val startOffset: Int,
    val endOffset: Int,
    val ruleIndex: Int,
)

/** Receives the records of a content archive in archive order (only after they passed the per-record checks). */
interface ContentSink {
    fun header(h: ContentHeader) = Unit
    fun segment(r: SegmentRecord) = Unit
    fun sentence(r: SentenceRecord) = Unit
    fun occurrence(r: OccurrenceRecord) = Unit
    fun grammarSpan(r: GrammarSpanRecord) = Unit
}

data class ContentSummary(
    val header: ContentHeader,
    val segments: List<SegmentRecord>,
    val sentenceCount: Int,
    /** Every sentence id of the archive. */
    val sentenceIds: Set<String>,
    /** entry_seq -> sum of `count` over all segments (SNAPSHOT.md 5). */
    val entryTotals: Map<Long, Long>,
    val tokens: Long,
    val unmappedTokens: Long,
    /** Distinct `grammar_point_id`s of the detected spans. */
    val grammarPointIds: Set<String>,
)

data class DeckHeader(val deckId: String, val deckType: String, val contentVersion: Int, val items: Int)

data class DeckItemRecord(val entrySeq: Long, val position: Long, val occurrenceCount: Int)

interface DeckItemSink {
    fun header(h: DeckHeader) = Unit
    fun item(r: DeckItemRecord) = Unit
}

data class DeckSummary(val header: DeckHeader, val items: Map<Long, Int>)

object SnapshotReader {
    const val CONTENT_FORMAT = "kotobaya-media-content"
    const val DECK_FORMAT = "kotobaya-deck-items"
    const val MAX_LINE_BYTES = 16 * 1024 * 1024
    const val MAX_DECOMPRESSED_BYTES = 4L * 1024 * 1024 * 1024
    const val MAX_SEGMENTS = 10_000
    const val MAX_SENTENCES = 5_000_000
    const val MAX_OCCURRENCES = 50_000_000
    const val MAX_SPANS = 50_000_000
    const val MAX_ITEMS = 1_000_000
    private const val MAX_SAFE = 9_007_199_254_740_991L
    private const val INT4 = 2_147_483_647L
    private val CONTENT_ORDER = listOf("header", "segment", "sentence", "occurrence", "grammar_span")
    private val SEGMENT_KINDS = setOf("episode", "chapter", "volume", "page", "whole")
    private val POS_CLASSES = setOf("content", "particle", "auxiliary", "name", "other")
    private val UUID_RE = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private val SHA_RE = Regex("^[0-9a-f]{64}$")

    // ------------------------------------------------------------------ media content (SNAPSHOT.md 3)

    private class SentInfo(val segIdx: Int, val ordinal: Int, val utf16Len: Int, val position: Int)

    /** Per segment: the aggregation of its `media` sentences' tokens (rule 7). */
    private class Agg {
        var hasSentences = false
        var unmapped = 0L
        val counts = HashMap<Long, Long>()
        val classes = HashMap<Long, HashMap<String, Int>>()
        val where = HashMap<Long, MutableList<String>>()
    }

    /** Validates the zstd [compressed] content archive and streams its records to [sink]. */
    fun readContent(compressed: InputStream, sink: ContentSink? = null): ContentSummary =
        ZstdInputStream(compressed).use { readContentText(it, sink) }

    /** As [readContent] for the decompressed JSON Lines text. */
    fun readContentText(text: InputStream, sink: ContentSink? = null): ContentSummary {
        val lines = LineReader(text)
        val head = lines.next()?.let { record(it, 1) } ?: invalid("empty archive")
        if (head.kind != "header") invalid("line 1: first line is not the header")
        val h = contentHeader(head.obj)
        sink?.header(h)

        val segs = ArrayList<SegmentRecord>()
        val segIdx = HashMap<String, Int>()
        val aggs = ArrayList<Agg>()
        val sents = HashMap<String, SentInfo>()
        val nextOrdinal = ArrayList<Int>()
        var lastSentKey = -1L to -1L
        val entryTotals = HashMap<Long, Long>()
        val occBySeg = ArrayList<HashMap<Long, OccurrenceRecord>>()
        var lastOccKey = -1 to 0L
        val gpIds = HashSet<String>()
        var lastSpan: List<Comparable<*>>? = null
        val seen = IntArray(CONTENT_ORDER.size)
        var rank = 0
        var segmentsClosed = false

        fun closeSegments() {
            if (segmentsClosed) return
            segmentsClosed = true
            if (segs.isEmpty()) invalid("at least one segment is required")
        }

        while (true) {
            val text = lines.next() ?: break
            val n = lines.lineNumber
            val r = record(text, n)
            val k = CONTENT_ORDER.indexOf(r.kind)
            if (k < 0) invalid("line $n: unknown record '${r.kind}'")
            if (k == 0) invalid("line $n: second header")
            if (k < rank) invalid("line $n: records not in section order segment, sentence, occurrence, grammar_span")
            rank = k
            seen[k]++
            val limit = when (k) { 1 -> h.segments; 2 -> h.sentences; 3 -> h.occurrences; else -> h.grammarSpans }
            if (seen[k] > limit) invalid("line $n: more ${r.kind} records than header counts.${r.kind} = $limit")
            val o = r.obj
            when (k) {
                1 -> {
                    val s = SegmentRecord(
                        id = uuid(o, "id", n),
                        ordinal = int(o, "ordinal", n, 0, 9999).toInt(),
                        kind = enumOf(o, "kind", n, SEGMENT_KINDS),
                        title = text(o, "title", n, maxCodePoints = 300),
                        sentenceCount = int(o, "sentence_count", n, 0, INT4).toInt(),
                        tokenCount = int(o, "token_count", n, 0, INT4).toInt(),
                        unmappedTokenCount = int(o, "unmapped_token_count", n, 0, INT4).toInt(),
                    )
                    if (s.ordinal != segs.size) invalid("line $n: segment ordinals are not 0..k-1 in order")
                    if (segIdx.put(s.id, segs.size) != null) invalid("line $n: duplicate segment id ${s.id}")
                    segs += s
                    aggs += Agg()
                    nextOrdinal += 0
                    occBySeg += HashMap()
                    sink?.segment(s)
                }
                2 -> {
                    closeSegments()
                    val s = sentence(o, n)
                    val si = segIdx[s.segmentId] ?: invalid("line $n: sentence ${s.id}: unknown segment_id")
                    if (sents.containsKey(s.id) || segIdx.containsKey(s.id)) invalid("line $n: duplicate sentence id ${s.id}")
                    val key = si.toLong() to s.ordinal.toLong()
                    if (key.first < lastSentKey.first || (key.first == lastSentKey.first && key.second <= lastSentKey.second)) {
                        invalid("line $n: sentence ${s.id} out of order (segment ordinal, ordinal)")
                    }
                    lastSentKey = key
                    if (s.ordinal != nextOrdinal[si]) invalid("line $n: segment $si: sentence ordinals are not 0..n-1")
                    nextOrdinal[si] = s.ordinal + 1
                    if ((s.startMs == null) != (s.endMs == null)) invalid("line $n: sentence ${s.id}: start_ms and end_ms must both be set or both null")
                    if (s.startMs != null && s.endMs != null && s.endMs < s.startMs) invalid("line $n: sentence ${s.id}: end_ms < start_ms")
                    val n16 = s.text.length
                    var prevEnd = 0L
                    val agg = aggs[si]
                    agg.hasSentences = true
                    for (t in s.tokens.items) {
                        val tok = t as? JsonObject ?: invalid("line $n: sentence ${s.id}: a token is not an object")
                        val st = intOrNull(tok.members["s"])
                        val en = intOrNull(tok.members["e"])
                        if (st == null || en == null || !(prevEnd <= st && st < en && en <= n16)) {
                            invalid("line $n: sentence ${s.id}: token offsets not ascending, non-overlapping UTF-16 [s, e) inside text")
                        }
                        prevEnd = en
                        val seqV = tok.members["seq"]
                        val seq = if (seqV == null || seqV == JsonNull) null else intOrNull(seqV)?.takeIf { it >= 1 }
                            ?: invalid("line $n: sentence ${s.id}: token seq must be null or a positive integer")
                        if (s.origin != "media") continue
                        if (seq == null) {
                            agg.unmapped++
                            continue
                        }
                        agg.counts.merge(seq, 1L, Long::plus)
                        val cls = (tok.members["class"] as? JsonString)?.value ?: "content"
                        agg.classes.getOrPut(seq) { HashMap() }.merge(cls, 1, Int::plus)
                        val lst = agg.where.getOrPut(seq) { ArrayList(2) }
                        if ((lst.isEmpty() || lst.last() != s.id) && lst.size < 20) lst += s.id
                    }
                    sents[s.id] = SentInfo(si, s.ordinal, n16, sents.size)
                    sink?.sentence(s)
                }
                3 -> {
                    closeSegments()
                    val oc = OccurrenceRecord(
                        segmentId = uuid(o, "segment_id", n),
                        entrySeq = int(o, "entry_seq", n, 1, MAX_SAFE),
                        count = int(o, "count", n, 1, INT4).toInt(),
                        posClass = enumOf(o, "pos_class", n, POS_CLASSES),
                        sentenceIds = uuidList(o, "sentence_ids", n),
                    )
                    val si = segIdx[oc.segmentId] ?: invalid("line $n: occurrence ${oc.entrySeq}: unknown segment_id")
                    val key = si to oc.entrySeq
                    if (key.first < lastOccKey.first || (key.first == lastOccKey.first && key.second <= lastOccKey.second)) {
                        invalid("line $n: occurrence (${key.first}, ${key.second}) duplicate or out of order")
                    }
                    lastOccKey = key
                    var prevOrd = -1
                    for (sid in oc.sentenceIds) {
                        val info = sents[sid]
                        if (info == null || info.segIdx != si) invalid("line $n: occurrence (${key.first}, ${key.second}): sentence $sid not in this segment")
                        if (info.ordinal < prevOrd) invalid("line $n: occurrence (${key.first}, ${key.second}): sentence_ids not in ordinal order")
                        prevOrd = info.ordinal
                    }
                    occBySeg[si][oc.entrySeq] = oc
                    entryTotals.merge(oc.entrySeq, oc.count.toLong(), Long::plus)
                    sink?.occurrence(oc)
                }
                else -> {
                    closeSegments()
                    val g = GrammarSpanRecord(
                        sentenceId = uuid(o, "sentence_id", n),
                        grammarPointId = uuid(o, "grammar_point_id", n),
                        startOffset = int(o, "start_offset", n, 0, INT4).toInt(),
                        endOffset = int(o, "end_offset", n, 1, INT4).toInt(),
                        ruleIndex = int(o, "rule_index", n, 0, 32767).toInt(),
                    )
                    val info = sents[g.sentenceId] ?: invalid("line $n: grammar_span: sentence ${g.sentenceId} not in the archive")
                    if (!(g.startOffset < g.endOffset && g.endOffset <= info.utf16Len)) invalid("line $n: grammar_span on ${g.sentenceId}: offsets outside the sentence")
                    val key = listOf<Comparable<*>>(info.position, g.startOffset, g.endOffset, g.grammarPointId)
                    if (lastSpan != null && compareKeys(key, lastSpan) <= 0) invalid("line $n: grammar_span on ${g.sentenceId}: duplicate or out of order")
                    lastSpan = key
                    gpIds += g.grammarPointId
                    sink?.grammarSpan(g)
                }
            }
        }
        closeSegments()
        val expected = listOf(h.segments, h.sentences, h.occurrences, h.grammarSpans)
        for (k in 1..4) {
            if (seen[k] != expected[k - 1]) invalid("header counts.${CONTENT_ORDER[k]} = ${expected[k - 1]} but ${seen[k]} records")
        }
        segs.forEachIndexed { i, seg ->
            if (seg.sentenceCount != nextOrdinal[i]) invalid("segment $i: sentence_count ${seg.sentenceCount} but ${nextOrdinal[i]} sentences")
            val occs = occBySeg[i]
            val total = occs.values.sumOf { it.count.toLong() }
            if (seg.unmappedTokenCount > seg.tokenCount) invalid("segment $i: unmapped_token_count > token_count")
            if (seg.tokenCount.toLong() != total + seg.unmappedTokenCount) {
                invalid("segment $i: token_count ${seg.tokenCount} != sum(occurrence.count) $total + unmapped_token_count ${seg.unmappedTokenCount}")
            }
            val agg = aggs[i]
            if (!agg.hasSentences) return@forEachIndexed
            if (occs.keys != agg.counts.keys) invalid("segment $i: occurrence entries ${occs.keys.sorted()} differ from token seqs ${agg.counts.keys.sorted()}")
            for ((seq, oc) in occs) {
                if (oc.count.toLong() != agg.counts[seq]) invalid("segment $i: occurrence $seq count ${oc.count} != ${agg.counts[seq]} tokens")
                if (oc.sentenceIds != agg.where[seq]) invalid("segment $i: occurrence $seq sentence_ids are not the first 20 containing sentences")
                val cls = agg.classes.getValue(seq)
                if ((cls[oc.posClass] ?: 0) != cls.values.max()) invalid("segment $i: occurrence $seq pos_class is not a most frequent token class")
            }
            if (seg.unmappedTokenCount.toLong() != agg.unmapped) {
                invalid("segment $i: unmapped_token_count ${seg.unmappedTokenCount} != ${agg.unmapped} tokens with seq null")
            }
        }
        return ContentSummary(
            header = h,
            segments = segs,
            sentenceCount = sents.size,
            sentenceIds = sents.keys,
            entryTotals = entryTotals,
            tokens = segs.sumOf { it.tokenCount.toLong() },
            unmappedTokens = segs.sumOf { it.unmappedTokenCount.toLong() },
            grammarPointIds = gpIds,
        )
    }

    private fun contentHeader(o: JsonObject): ContentHeader {
        val format = str(o, "format", 1)
        if (format != CONTENT_FORMAT) invalid("line 1: unknown format '$format'")
        val version = int(o, "format_version", 1, Long.MIN_VALUE, Long.MAX_VALUE)
        if (version != 1L) invalid("line 1: unsupported format_version $version")
        val counts = o.members["counts"] as? JsonObject ?: invalid("line 1: counts must be an object")
        return ContentHeader(
            mediaId = uuid(o, "media_id", 1),
            contentVersion = int(o, "content_version", 1, 1, INT4).toInt(),
            segments = int(counts, "segment", 1, 1, MAX_SEGMENTS.toLong()).toInt(),
            sentences = int(counts, "sentence", 1, 0, MAX_SENTENCES.toLong()).toInt(),
            occurrences = int(counts, "occurrence", 1, 0, MAX_OCCURRENCES.toLong()).toInt(),
            grammarSpans = int(counts, "grammar_span", 1, 0, MAX_SPANS.toLong()).toInt(),
        )
    }

    private fun sentence(o: JsonObject, n: Int): SentenceRecord {
        val tokens = o.members["tokens"] as? JsonArray ?: invalid("line $n: tokens must be an array")
        if (tokens.items.any { it !is JsonObject }) invalid("line $n: every token must be an object")
        val loc = when (val v = member(o, "location", n)) {
            JsonNull -> null
            is JsonObject -> v
            else -> invalid("line $n: location must be an object or null")
        }
        return SentenceRecord(
            id = uuid(o, "id", n),
            segmentId = uuid(o, "segment_id", n),
            ordinal = int(o, "ordinal", n, 0, INT4).toInt(),
            text = text(o, "text", n, minCodePoints = 1),
            tokens = tokens,
            origin = enumOf(o, "origin", n, setOf("media", "generated")),
            startMs = nullableInt(o, "start_ms", n),
            endMs = nullableInt(o, "end_ms", n),
            audioBlobHash = nullableSha(o, "audio_blob_hash", n),
            imageBlobHash = nullableSha(o, "image_blob_hash", n),
            location = loc,
        )
    }

    // ------------------------------------------------------------------ deck items (SNAPSHOT.md 4)

    fun readDeckItems(compressed: InputStream, sink: DeckItemSink? = null): DeckSummary =
        ZstdInputStream(compressed).use { readDeckItemsText(it, sink) }

    fun readDeckItemsText(text: InputStream, sink: DeckItemSink? = null): DeckSummary {
        val lines = LineReader(text)
        val head = lines.next()?.let { record(it, 1) } ?: invalid("empty archive")
        if (head.kind != "header") invalid("line 1: first line is not the header")
        val o = head.obj
        val format = str(o, "format", 1)
        if (format != DECK_FORMAT) invalid("line 1: unknown format '$format'")
        val version = int(o, "format_version", 1, Long.MIN_VALUE, Long.MAX_VALUE)
        if (version != 1L) invalid("line 1: unsupported format_version $version")
        val counts = o.members["counts"] as? JsonObject ?: invalid("line 1: counts must be an object")
        val h = DeckHeader(
            deckId = uuid(o, "deck_id", 1),
            deckType = enumOf(o, "deck_type", 1, setOf("media", "list")),
            contentVersion = int(o, "content_version", 1, 1, INT4).toInt(),
            items = int(counts, "item", 1, 0, MAX_ITEMS.toLong()).toInt(),
        )
        sink?.header(h)
        val media = h.deckType == "media"
        val items = LinkedHashMap<Long, Int>()
        var prev: DeckItemRecord? = null
        while (true) {
            val line = lines.next() ?: break
            val n = lines.lineNumber
            val r = record(line, n)
            when (r.kind) {
                "item" -> Unit
                "header" -> invalid("line $n: second header")
                else -> invalid("line $n: unknown record '${r.kind}'")
            }
            if (items.size >= h.items) invalid("line $n: more items than header counts.item = ${h.items}")
            val it = DeckItemRecord(
                entrySeq = int(r.obj, "entry_seq", n, 1, MAX_SAFE),
                position = int(r.obj, "position", n, 0, MAX_SAFE),
                occurrenceCount = int(r.obj, "occurrence_count", n, 0, INT4).toInt(),
            )
            if (items.put(it.entrySeq, it.occurrenceCount) != null) invalid("line $n: duplicate entry_seq ${it.entrySeq}")
            val p = prev
            if (p != null && (it.position < p.position || (it.position == p.position && it.entrySeq < p.entrySeq))) {
                invalid("line $n: items not ordered by (position, entry_seq)")
            }
            if (media) {
                if (it.position != items.size.toLong()) invalid("line $n: media deck: positions are not 1..n")
                if (it.occurrenceCount < 1) invalid("line $n: media deck: occurrence_count must be >= 1")
                if (p != null && (it.occurrenceCount > p.occurrenceCount || (it.occurrenceCount == p.occurrenceCount && it.entrySeq < p.entrySeq))) {
                    invalid("line $n: media deck: positions do not rank by occurrence_count desc, entry_seq asc")
                }
            } else if (it.occurrenceCount != 0) {
                invalid("line $n: list deck: occurrence_count must be 0")
            }
            prev = it
            sink?.item(it)
        }
        if (items.size != h.items) invalid("header counts.item = ${h.items} but ${items.size} records")
        return DeckSummary(h, items)
    }

    /** SNAPSHOT.md 5: a media deck's items are exactly the media's entries with summed counts. */
    fun checkPair(content: ContentSummary, deck: DeckSummary) {
        val dh = deck.header
        if (dh.deckType != "media") invalid("pair: deck_type must be media")
        if (dh.deckId != content.header.mediaId) invalid("pair: deck_id != media_id")
        if (dh.contentVersion != content.header.contentVersion) invalid("pair: content_version differs")
        val expected = content.entryTotals
        if (expected.size != deck.items.size || expected.any { (seq, total) -> deck.items[seq]?.toLong() != total }) {
            invalid("pair: deck items are not exactly the content's entries with summed occurrence counts")
        }
    }

    // ------------------------------------------------------------------ lines and values

    private class Rec(val kind: String, val obj: JsonObject)

    private fun record(text: String, n: Int): Rec {
        val d = DefaultCanonicalJson.decode(text)
        val v = d.value ?: invalid("line $n: not JSON: ${d.error?.message}")
        val o = v as? JsonObject ?: invalid("line $n: not an object")
        checkNoNul(o, n)
        val kind = (o.members["record"] as? JsonString)?.value ?: invalid("line $n: record must be a string")
        return Rec(kind, o)
    }

    private fun checkNoNul(v: JsonValue, n: Int) {
        when (v) {
            is JsonString -> if (v.value.indexOf('\u0000') >= 0) invalid("line $n: U+0000 in a string")
            is JsonArray -> v.items.forEach { checkNoNul(it, n) }
            is JsonObject -> v.members.forEach { (k, x) ->
                if (k.indexOf('\u0000') >= 0) invalid("line $n: U+0000 in a member name")
                checkNoNul(x, n)
            }
            else -> Unit
        }
    }

    private fun member(o: JsonObject, name: String, n: Int): JsonValue = o.members[name] ?: invalid("line $n: missing member '$name'")

    private fun str(o: JsonObject, name: String, n: Int): String =
        (member(o, name, n) as? JsonString)?.value ?: invalid("line $n: $name must be a string")

    private fun text(o: JsonObject, name: String, n: Int, minCodePoints: Int = 0, maxCodePoints: Int = Int.MAX_VALUE): String {
        val s = str(o, name, n)
        val cp = s.codePointCount(0, s.length)
        if (cp < minCodePoints || cp > maxCodePoints) invalid("line $n: $name length $cp outside $minCodePoints..$maxCodePoints")
        return s
    }

    private fun uuid(o: JsonObject, name: String, n: Int): String =
        str(o, name, n).also { if (!UUID_RE.matches(it)) invalid("line $n: $name is not a canonical lowercase UUID") }

    private fun enumOf(o: JsonObject, name: String, n: Int, allowed: Set<String>): String =
        str(o, name, n).also { if (it !in allowed) invalid("line $n: $name '$it' not one of $allowed") }

    private fun intOrNull(v: JsonValue?): Long? {
        val d = (v as? JsonNumber)?.value ?: return null
        if (d != Math.rint(d) || kotlin.math.abs(d) > MAX_SAFE) return null
        return d.toLong()
    }

    private fun int(o: JsonObject, name: String, n: Int, min: Long, max: Long): Long {
        val v = intOrNull(member(o, name, n)) ?: invalid("line $n: $name must be an integer")
        if (v < min || v > max) invalid("line $n: $name $v outside $min..$max")
        return v
    }

    private fun nullableInt(o: JsonObject, name: String, n: Int): Int? =
        if (member(o, name, n) == JsonNull) null else int(o, name, n, 0, INT4).toInt()

    private fun nullableSha(o: JsonObject, name: String, n: Int): String? =
        if (member(o, name, n) == JsonNull) null else str(o, name, n).also { if (!SHA_RE.matches(it)) invalid("line $n: $name is not a SHA-256") }

    private fun uuidList(o: JsonObject, name: String, n: Int): List<String> {
        val a = member(o, name, n) as? JsonArray ?: invalid("line $n: $name must be an array")
        if (a.items.size > 20) invalid("line $n: $name has more than 20 items")
        val out = a.items.map { (it as? JsonString)?.value?.takeIf(UUID_RE::matches) ?: invalid("line $n: $name items must be UUIDs") }
        if (out.toSet().size != out.size) invalid("line $n: $name items are not unique")
        return out
    }

    @Suppress("UNCHECKED_CAST")
    private fun compareKeys(a: List<Comparable<*>>, b: List<Comparable<*>>): Int {
        for (i in a.indices) {
            val c = (a[i] as Comparable<Any>).compareTo(b[i] as Any)
            if (c != 0) return c
        }
        return 0
    }

    private fun invalid(message: String): Nothing = throw SnapshotInvalid(message)

    /**
     * SNAPSHOT.md 2.2: UTF-8 without BOM, every line LF-terminated (the last included), no empty line, no CR, no
     * leading/trailing whitespace, at most [MAX_LINE_BYTES] per line; at most [MAX_DECOMPRESSED_BYTES] in total.
     */
    private class LineReader(private val input: InputStream) {
        private val buf = ByteArray(1 shl 16)
        private var pos = 0
        private var lim = 0
        private var total = 0L
        private var line = ByteArray(1 shl 12)
        var lineNumber = 0
            private set
        private val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)

        fun next(): String? {
            var len = 0
            while (true) {
                if (pos == lim) {
                    lim = input.read(buf)
                    pos = 0
                    if (lim <= 0) {
                        lim = 0
                        if (len == 0) return null
                        throw SnapshotInvalid("line ${lineNumber + 1}: not terminated by LF (truncated?)")
                    }
                    total += lim
                    if (total > MAX_DECOMPRESSED_BYTES) throw SnapshotInvalid("decompressed size exceeds $MAX_DECOMPRESSED_BYTES bytes")
                }
                val b = buf[pos++]
                if (b == '\n'.code.toByte()) break
                if (len == MAX_LINE_BYTES) throw SnapshotInvalid("line ${lineNumber + 1} longer than $MAX_LINE_BYTES bytes")
                if (len == line.size) line = line.copyOf(minOf(line.size * 2, MAX_LINE_BYTES))
                line[len++] = b
            }
            lineNumber++
            if (lineNumber == 1 && len >= 3 && line[0] == 0xEF.toByte() && line[1] == 0xBB.toByte() && line[2] == 0xBF.toByte()) {
                throw SnapshotInvalid("byte order mark")
            }
            val s = try {
                decoder.reset().decode(ByteBuffer.wrap(line, 0, len)).toString()
            } catch (e: CharacterCodingException) {
                throw SnapshotInvalid("line $lineNumber: not UTF-8")
            }
            if (s.isEmpty() || s.indexOf('\r') >= 0 || s.first().isWhitespace() || s.last().isWhitespace()) {
                throw SnapshotInvalid("line $lineNumber: empty, padded or CR")
            }
            return s
        }
    }
}
