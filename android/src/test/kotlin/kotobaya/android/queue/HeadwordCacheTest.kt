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
        val cache = HeadwordCache({ generation }) { seq -> reads++; dict[seq] }

        assertEquals("居る", cache.headword(1577980))
        assertEquals("居る", cache.headword(1577980))
        assertEquals(1, reads, "cached within one generation")

        dict[1577980] = "いる" // the new package is installed
        generation = 2
        assertEquals("いる", cache.headword(1577980))
        assertEquals(2, reads)
    }

    @Test
    fun missesAreNotCached() {
        var generation = 1L
        val dict = mutableMapOf<Long, String>()
        val cache = HeadwordCache({ generation }) { dict[it] }
        assertNull(cache.headword(5))
        dict[5] = "あれ" // gap fill delivers it in the same generation
        assertEquals("あれ", cache.headword(5))
    }
}
