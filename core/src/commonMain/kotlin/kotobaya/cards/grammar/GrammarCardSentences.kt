package kotobaya.cards.grammar

import kotobaya.contracts.render.ClozeSentence
import kotobaya.contracts.Uuid

/** One example sentence that carries a span of the grammar point under review. */
public data class GrammarCandidate(
    /** `sentence.id`. */
    public val sentenceId: Uuid,
    /** Sentence text. */
    public val text: String,
    /** UTF-16 start of the span, inclusive. */
    public val start: Int,
    /** UTF-16 end of the span, exclusive. */
    public val end: Int,
    /** Number of distinct not-yet-known words in the sentence (0 = i+0). */
    public val unknownCount: Int,
    /** English translation, if any. */
    public val translation: String? = null,
)

/**
 * Chooses the example sentence for a grammar card review (SPEC "Grammar review": rotate on each review, prefer i+0)
 * and turns it into the [ClozeSentence] the card renderer takes. Pure and deterministic.
 */
public object GrammarCardSentences {
    /** True when `[start, end)` is a non-empty range of [text] that does not cut a surrogate pair. */
    public fun isValidSpan(text: String, start: Int, end: Int): Boolean {
        if (start < 0 || start >= end || end > text.length) return false
        fun splits(i: Int) = i in 1 until text.length && text[i - 1].isHighSurrogate() && text[i].isLowSurrogate()
        return !splits(start) && !splits(end)
    }

    /**
     * Picks one candidate, or null if none has a valid span.
     *
     * Invalid spans are dropped. With two or more usable candidates the [lastShown] sentence is excluded, so two
     * consecutive reviews never show the same sentence. Among the rest the lowest [GrammarCandidate.unknownCount]
     * wins (i+0 before i+1 before the rest); ties are broken by [rotation] (e.g. the card's review count) over the
     * tied candidates in a stable order (sentence id), so successive reviews cycle through them.
     */
    public fun pick(candidates: List<GrammarCandidate>, lastShown: Uuid?, rotation: Int = 0): GrammarCandidate? {
        val usable = candidates.filter { isValidSpan(it.text, it.start, it.end) }
            .distinctBy { Triple(it.sentenceId, it.start, it.end) }
        if (usable.isEmpty()) return null
        val pool = if (usable.map { it.sentenceId }.distinct().size >= 2) usable.filter { it.sentenceId != lastShown } else usable
        val best = pool.minOf { it.unknownCount }
        val tier = pool.filter { it.unknownCount == best }.sortedBy { it.sentenceId.toString() }
        return tier[((rotation % tier.size) + tier.size) % tier.size]
    }

    /** The renderer input for [c]. */
    public fun toCloze(c: GrammarCandidate): ClozeSentence = ClozeSentence(c.sentenceId, c.text, c.start, c.end, c.translation)
}
