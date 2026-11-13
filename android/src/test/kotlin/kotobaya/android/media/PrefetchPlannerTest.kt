package kotobaya.android.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PrefetchPlannerTest {
    private val day = 86_400_000L
    private val now = 1_000 * day

    private fun cached(h: String, size: Long, used: Long?, reason: BlobReason = BlobReason.CARD) =
        CacheEntry(h, size, BlobState.CACHED, reason, 0, used)

    private fun wanted(h: String, size: Long?) = CacheEntry(h, size, BlobState.WANTED, BlobReason.CARD, 0, null)

    @Test
    fun windowSelectsByDays() {
        val needs = listOf(AudioNeed("a", now - day), AudioNeed("b", now + day), AudioNeed("c", now + 3 * day))
        val p = PrefetchPlanner.plan(needs, emptyList(), now, 2, 1000)
        assertEquals(listOf("a", "b"), p.want)
        assertEquals(listOf("a", "b", "c"), PrefetchPlanner.plan(needs, emptyList(), now, 4, 1000).want)
    }

    @Test
    fun budgetRespectedAndLruEvictionOrder() {
        val cache = listOf(cached("old", 40, 1), cached("never", 40, null), cached("new", 40, 9), wanted("n", 50))
        val p = PrefetchPlanner.plan(listOf(AudioNeed("n", now)), cache, now, 2, 150)
        // used 120 + 50 > 150: evict least recently used first (never used, then 1), one is not enough for 120+50-40=130 ok
        assertEquals(listOf("never"), p.evict)
        assertEquals(listOf("n"), p.want)
        val p2 = PrefetchPlanner.plan(listOf(AudioNeed("n", now)), cache, now, 2, 100)
        assertEquals(listOf("never", "old"), p2.evict)
    }

    @Test
    fun protectedBlobsNeverEvicted() {
        val cache = listOf(cached("p", 80, null), wanted("n", 50))
        val needs = listOf(AudioNeed("p", now), AudioNeed("n", now + 1))
        val p = PrefetchPlanner.plan(needs, cache, now, 2, 100)
        assertTrue(p.evict.isEmpty())
        assertTrue(p.want.isEmpty())
        assertEquals(listOf(Skipped("n", SkipReason.BUDGET_EXHAUSTED)), p.skipped)
    }

    @Test
    fun overBudgetSingleBlobSkippedWithReason() {
        val p = PrefetchPlanner.plan(listOf(AudioNeed("big", now)), listOf(wanted("big", 500)), now, 2, 100)
        assertEquals(listOf(Skipped("big", SkipReason.TOO_LARGE_FOR_BUDGET)), p.skipped)
        assertTrue(p.want.isEmpty())
    }

    @Test
    fun shrunkBudgetShedsLruButKeepsProtectedAndNonCard() {
        val cache = listOf(
            cached("p", 50, 5), cached("x", 50, 1), cached("cov", 50, 1, BlobReason.COVER),
        )
        val p = PrefetchPlanner.plan(listOf(AudioNeed("p", now)), cache, now, 2, 100)
        assertEquals(listOf("x"), p.evict)
    }
}
