package kotobaya.android.queue

import java.util.concurrent.ConcurrentHashMap

/**
 * Dictionary headwords by entry seq for the queue loader, valid for one dictionary [generation] (a package install
 * or a gap-fill write changes it). A new package can change a headword (ADR-046 (a): 居る → いる), so a stale
 * kanji headword must not outlive it: it would make the loader require and mint a kanji note (居) that the
 * new package no longer asks for, and that note would stay. Misses are not cached.
 */
class HeadwordCache(private val generation: () -> Long, private val lookup: (Long) -> String?) {
    private class Slot(val generation: Long) {
        val headwords = ConcurrentHashMap<Long, String>()
    }

    @Volatile private var slot = Slot(Long.MIN_VALUE)

    /** The headword of entry [seq] under the current dictionary, or null when it is not available. */
    fun headword(seq: Long): String? {
        val g = generation()
        var s = slot
        if (s.generation != g) synchronized(this) {
            s = slot
            if (s.generation != g) {
                s = Slot(g)
                slot = s
            }
        }
        s.headwords[seq]?.let { return it }
        // A value read while the generation moves on lands in the old slot only, which is dropped.
        return lookup(seq)?.also { s.headwords[seq] = it }
    }
}
