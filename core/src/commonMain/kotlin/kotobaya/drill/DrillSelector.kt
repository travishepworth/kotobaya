package kotobaya.drill

import kotobaya.fsrs.FsrsScheduler

/** A live card the selector may pick: its id, its note and its lapse count. */
public data class DrillCandidate(val cardId: String, val noteId: String, val lapses: Int)

/** A note with its hot score (higher = hotter). */
public data class ScoredNote(val noteId: String, val score: Double)

/** A confirmed confusable pair of notes (order and duplicates, e.g. one row per reason, do not matter). */
public data class NotePair(val noteA: String, val noteB: String)

/**
 * Picks the cards of a drill session (T503) from struggle sources. Pure and deterministic: the same inputs and
 * `seed` always give the same card ids in the same order; the seed only breaks ties and orders cards within a note.
 * Every function returns at most `limit` distinct card ids and an empty list when the source has nothing.
 */
public object DrillSelector {
    /** Cards of the hot [notes], hottest note first (ties by seeded order); a note's cards stay together. */
    public fun hotWords(notes: List<ScoredNote>, cards: List<DrillCandidate>, limit: Int, seed: Long): List<String> {
        val byNote = cards.groupBy { it.noteId }
        val ordered = notes.sortedWith(
            compareByDescending<ScoredNote> { it.score }.thenBy { mix(seed, it.noteId) }.thenBy { it.noteId },
        )
        return take(ordered.flatMap { inNote(byNote[it.noteId].orEmpty(), seed) }, limit)
    }

    /** Cards with `lapses >= threshold`, most lapses first (ties by seeded order). */
    public fun leeches(cards: List<DrillCandidate>, threshold: Int, limit: Int, seed: Long): List<String> =
        take(
            cards.filter { it.lapses >= threshold }
                .sortedWith(compareByDescending<DrillCandidate> { it.lapses }.thenBy { mix(seed, it.cardId) }.thenBy { it.cardId }),
            limit,
        )

    /** The cards of the single note [noteId], in seeded order. */
    public fun word(noteId: String, cards: List<DrillCandidate>, limit: Int, seed: Long): List<String> =
        take(inNote(cards.filter { it.noteId == noteId }, seed), limit)

    /**
     * Cards of the [pairs], partners adjacent (all cards of one note, then all of its partner's). With [onlyNoteId],
     * only pairs involving that note, and that note's cards come first. Pairs whose notes have no cards are skipped,
     * a card appearing via several pairs is emitted once, and no pairs means an empty list.
     */
    public fun confusables(
        pairs: List<NotePair>,
        cards: List<DrillCandidate>,
        onlyNoteId: String?,
        limit: Int,
        seed: Long,
    ): List<String> {
        val byNote = cards.groupBy { it.noteId }
        val distinct = pairs.filter { it.noteA != it.noteB }
            .map { if (it.noteA < it.noteB) it else NotePair(it.noteB, it.noteA) }
            .distinct()
            .filter { onlyNoteId == null || it.noteA == onlyNoteId || it.noteB == onlyNoteId }
            .sortedWith(compareBy<NotePair> { mix(seed, it.noteA + "|" + it.noteB) }.thenBy { it.noteA }.thenBy { it.noteB })
        val out = ArrayList<DrillCandidate>()
        for (p in distinct) {
            val (first, second) = if (onlyNoteId == p.noteB) p.noteB to p.noteA else p.noteA to p.noteB
            out += inNote(byNote[first].orEmpty(), seed)
            out += inNote(byNote[second].orEmpty(), seed)
        }
        return take(out, limit)
    }

    private fun inNote(cards: List<DrillCandidate>, seed: Long): List<DrillCandidate> =
        cards.sortedWith(compareBy<DrillCandidate> { mix(seed, it.cardId) }.thenBy { it.cardId })

    private fun take(cards: List<DrillCandidate>, limit: Int): List<String> =
        cards.map { it.cardId }.distinct().take(limit.coerceAtLeast(0))

    /** Seeded hash of [s] (FNV-1a over UTF-16 units, finished with SplitMix64); identical on every platform. */
    private fun mix(seed: Long, s: String): Long {
        var h = seed xor 0x3c79ac492ba7b653L
        for (c in s) h = (h xor c.code.toLong()) * 0x100000001b3L
        return FsrsScheduler.splitMix64(h)
    }
}
