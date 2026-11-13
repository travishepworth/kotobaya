package kotobaya.confusables

/** Why two notes are confusable; [wire] is the `reason` column value. */
internal enum class Reason(val wire: String) { HOMOPHONE("homophone"), LOOK_ALIKE("look_alike"), MEANING("meaning") }

/** An unordered pair with one row per reason; [a] < [b] always (lowercase uuid text order, as `app.confusable` requires). */
internal data class Pair3(val a: String, val b: String, val reason: Reason) {
    companion object {
        fun of(x: String, y: String, reason: Reason): Pair3 = if (x < y) Pair3(x, y, reason) else Pair3(y, x, reason)
    }
}

/** A live vocab note and the dictionary data of its entry. [senses] is the English glosses per sense, in order. */
internal data class VocabNote(
    val noteId: String,
    val entrySeq: Long,
    val headword: String,
    val reading: String,
    val senses: List<List<String>>,
)

/** A live kanji note. */
internal data class KanjiNote(val noteId: String, val literal: String)

/**
 * Pure suggestion scoring (T502): which pairs of the user's own notes look confusable, and why. No I/O, so the rules
 * are testable on their own; [ConfusableSuggester] feeds it from the database.
 */
internal object ConfusableScoring {
    /** A shared key held by more notes than this is too generic to be a real confusion. */
    const val MAX_BUCKET = 12
    const val MAX_MEANING_BUCKET = 6

    /** Two kanji look alike when they share at least this many KRADFILE components... */
    const val MIN_SHARED_COMPONENTS = 2

    /** ...and the shared part is at least this share of all their components. */
    const val MIN_JACCARD = 0.5

    fun suggest(vocab: List<VocabNote>, kanji: List<KanjiNote>, components: Map<String, Set<String>>): Set<Pair3> =
        homophones(vocab) + lookAlikeKanji(kanji, components) + lookAlikeVocab(vocab, components) + meanings(vocab)

    /** Different entries with the same primary reading (katakana folded to hiragana). */
    fun homophones(vocab: List<VocabNote>): Set<Pair3> =
        pairsOfBuckets(vocab.groupBy { foldKana(it.reading) }.filterKeys { it.isNotEmpty() }, MAX_BUCKET, Reason.HOMOPHONE)

    /** Different entries sharing a normalised English gloss among their first two senses. */
    fun meanings(vocab: List<VocabNote>): Set<Pair3> {
        val buckets = HashMap<String, MutableList<VocabNote>>()
        for (v in vocab) for (k in glossKeywords(v)) buckets.getOrPut(k) { mutableListOf() } += v
        return pairsOfBuckets(buckets, MAX_MEANING_BUCKET, Reason.MEANING)
    }

    /** Kanji notes whose components overlap enough: 待/持/特 share 土 and 寸. */
    fun lookAlikeKanji(kanji: List<KanjiNote>, components: Map<String, Set<String>>): Set<Pair3> {
        val byComponent = HashMap<String, MutableList<KanjiNote>>()
        for (k in kanji) for (c in components[k.literal].orEmpty()) byComponent.getOrPut(c) { mutableListOf() } += k
        val out = LinkedHashSet<Pair3>()
        for (bucket in byComponent.values) {
            if (bucket.size > 40) continue
            for (i in bucket.indices) for (j in i + 1 until bucket.size) {
                val x = bucket[i]
                val y = bucket[j]
                if (x.noteId != y.noteId && looksAlike(x.literal, y.literal, components)) out += Pair3.of(x.noteId, y.noteId, Reason.LOOK_ALIKE)
            }
        }
        return out
    }

    /**
     * Vocab notes whose headwords have the same length, are identical except at exactly one kanji, and that kanji
     * pair look alike (待つ / 持つ).
     */
    fun lookAlikeVocab(vocab: List<VocabNote>, components: Map<String, Set<String>>): Set<Pair3> {
        val buckets = HashMap<String, MutableList<Triple<VocabNote, Int, String>>>()
        for (v in vocab) {
            val cps = v.headword.codePoints().toArray()
            for ((i, cp) in cps.withIndex()) {
                if (!isKanji(cp)) continue
                val skeleton = StringBuilder()
                for ((j, o) in cps.withIndex()) skeleton.appendCodePoint(if (i == j) 0x2E else o)
                buckets.getOrPut(skeleton.toString()) { mutableListOf() } += Triple(v, i, String(Character.toChars(cp)))
            }
        }
        val out = LinkedHashSet<Pair3>()
        for (bucket in buckets.values) {
            if (bucket.size > MAX_BUCKET) continue
            for (i in bucket.indices) for (j in i + 1 until bucket.size) {
                val (x, _, kx) = bucket[i]
                val (y, _, ky) = bucket[j]
                if (x.noteId != y.noteId && kx != ky && looksAlike(kx, ky, components)) out += Pair3.of(x.noteId, y.noteId, Reason.LOOK_ALIKE)
            }
        }
        return out
    }

    fun looksAlike(x: String, y: String, components: Map<String, Set<String>>): Boolean {
        val a = components[x].orEmpty()
        val b = components[y].orEmpty()
        val shared = a.intersect(b).size
        val union = a.union(b).size
        return shared >= MIN_SHARED_COMPONENTS && union > 0 && shared.toDouble() / union >= MIN_JACCARD
    }

    private fun pairsOfBuckets(buckets: Map<String, List<VocabNote>>, cap: Int, reason: Reason): Set<Pair3> {
        val out = LinkedHashSet<Pair3>()
        for (bucket in buckets.values) {
            if (bucket.size > cap) continue
            for (i in bucket.indices) for (j in i + 1 until bucket.size) {
                val x = bucket[i]
                val y = bucket[j]
                if (x.noteId != y.noteId && x.entrySeq != y.entrySeq) out += Pair3.of(x.noteId, y.noteId, reason)
            }
        }
        return out
    }

    internal fun glossKeywords(v: VocabNote): Set<String> =
        v.senses.take(2).flatten().mapNotNull { normalizeGloss(it) }.toSet()

    private val PAREN = Regex("\\([^)]*\\)")
    private val SPACES = Regex("\\s+")
    private val LEADING = Regex("^(to|a|an|the)\\s+")

    internal fun normalizeGloss(g: String): String? {
        val s = g.lowercase().replace(PAREN, " ").trim().replace(SPACES, " ").replace(LEADING, "").trim()
        return s.takeIf { it.length >= 2 }
    }

    internal fun foldKana(s: String): String = buildString {
        for (c in s) append(if (c in 'ァ'..'ヶ') (c.code - 0x60).toChar() else c)
    }

    private fun isKanji(cp: Int): Boolean = cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0x20000..0x2FA1F || cp == 0x3005
}
