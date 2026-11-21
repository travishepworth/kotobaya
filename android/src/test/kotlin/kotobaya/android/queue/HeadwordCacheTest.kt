package kotobaya.android.queue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** CORE-28d: a new dictionary package (ADR-046 (a) headword いる instead of 居る) is seen without a restart. */
class HeadwordCacheTest {
    @Test
    fun aNewGenerationDropsCachedHeadwords() {
        var generation = 1L
        val dict = mutableMapOf(1577980L to "居る")
        var reads = 0
        val cache = HeadwordCache({ generation }, lookup = { seq -> reads++; dict[seq] })

        assertEquals("居る", cache.headword(1577980))
        assertEquals("居る", cache.headword(1577980))
        assertEquals(1, reads, "cached within one generation")

        dict[1577980] = "いる" // the new package is installed
        generation = 2
        assertEquals("いる", cache.headword(1577980))
        assertEquals(2, reads)
    }

    @Test
    fun aMissIsCachedForItsGenerationOnly() {
        var generation = 1L
        val dict = mutableMapOf<Long, String>()
        var reads = 0
        val cache = HeadwordCache({ generation }, lookup = { reads++; dict[it] })
        assertNull(cache.headword(5))
        assertNull(cache.headword(5))
        assertEquals(1, reads, "PERF-LF: a miss is not read again in the same generation")
        dict[5] = "あれ" // a gap fill delivers it: every sidecar write bumps the dictionary generation
        generation = 2
        assertEquals("あれ", cache.headword(5))
    }

    @Test
    fun prefetchReadsOnceInOneBatchAndServesHitsAndMisses() {
        var generation = 1L
        val dict = mapOf(1L to "一", 2L to "二")
        val batches = ArrayList<Collection<Long>>()
        var single = 0
        val cache = HeadwordCache({ generation }, { single++; dict[it] }) { seqs -> batches += seqs.toList(); seqs.mapNotNull { s -> dict[s]?.let { s to it } }.toMap() }
        cache.prefetch(listOf(1L, 2L, 3L, 1L))
        cache.prefetch(listOf(1L, 2L, 3L))
        assertEquals(listOf<Collection<Long>>(listOf(1L, 2L, 3L)), batches, "one batch, nothing read again")
        assertEquals("一", cache.headword(1))
        assertNull(cache.headword(3))
        assertEquals(0, single)
        generation = 2
        cache.prefetch(listOf(1L))
        assertEquals(2, batches.size, "a new generation reads again")
    }
}
