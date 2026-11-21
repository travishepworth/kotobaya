package kotobaya.android.queue

import java.util.concurrent.ConcurrentHashMap

/**
 * Dictionary headwords by entry seq for the queue loader, valid for one dictionary [generation] (a package install
 * or a gap-fill write changes it). A new package can change a headword (ADR-046 (a): 居る → いる), so a stale
 * kanji headword must not outlive it: it would make the loader require and mint a kanji note (居) that the
 * new package no longer asks for, and that note would stay.
 *
 * PERF-LF: one process-wide instance serves every loader (native screens and the shell's `getReviewQueue` /
 * `getStatsSummary`); [prefetch] fills it for a whole build with batched reads ([batch]), and misses are cached too
 * (for the generation only: a gap fill that adds the entry changes the generation), so a build after the first reads
 * nothing from the dictionary.
 */
class HeadwordCache(
    private val generation: () -> Long,
    private val lookup: (Long) -> String?,
    /** Many headwords at once (an absent key is a miss); default: [lookup] one by one. */
    private val batch: (Collection<Long>) -> Map<Long, String> = { seqs -> seqs.mapNotNull { s -> lookup(s)?.let { s to it } }.toMap() },
) {
    private class Slot(val generation: Long) {
        val headwords = ConcurrentHashMap<Long, String>()
        val misses: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    }

    @Volatile private var slot = Slot(Long.MIN_VALUE)

    private fun current(): Slot {
        val g = generation()
        var s = slot
        if (s.generation != g) synchronized(this) {
            s = slot
            if (s.generation != g) {
                s = Slot(g)
                slot = s
            }
        }
        return s
    }

    /** Loads every headword of [seqs] not known yet, in batches. */
    fun prefetch(seqs: Collection<Long>) {
        val s = current()
        val need = seqs.filter { !s.headwords.containsKey(it) && it !in s.misses }.distinct()
        if (need.isEmpty()) return
        // A value read while the generation moves on lands in the old slot only, which is dropped.
        val found = batch(need)
        for (seq in need) found[seq]?.let { s.headwords[seq] = it } ?: s.misses.add(seq)
    }

    /** The headword of entry [seq] under the current dictionary, or null when it is not available. */
    fun headword(seq: Long): String? {
        val s = current()
        s.headwords[seq]?.let { return it }
        if (seq in s.misses) return null
        val h = lookup(seq)
        if (h != null) s.headwords[seq] = h else s.misses.add(seq)
        return h
    }
}
