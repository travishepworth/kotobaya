package kotobaya.drill

import kotobaya.contracts.fsrs.Rating
import kotobaya.contracts.fsrs.ReviewType
import kotobaya.replay.DAY
import kotobaya.replay.DefaultReplayEngine
import kotobaya.replay.P0
import kotobaya.replay.T0
import kotobaya.replay.rated
import kotobaya.replay.uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DrillSelectorTest {
    private fun c(card: String, note: String, lapses: Int = 0) = DrillCandidate(card, note, lapses)

    private val cards = listOf(
        c("c1", "n1", 9), c("c2", "n1", 2), c("c3", "n2", 8), c("c4", "n3", 0), c("c5", "n3", 8), c("c6", "n4", 1),
    )

    @Test
    fun leechesAreMostLapsesFirstAndThresholdBounded() {
        val out = DrillSelector.leeches(cards, 8, 10, 1)
        assertEquals("c1", out.first())
        assertEquals(setOf("c3", "c5"), out.drop(1).toSet())
        assertEquals(listOf("c1"), DrillSelector.leeches(cards, 8, 1, 1))
        assertEquals(emptyList(), DrillSelector.leeches(cards, 10, 10, 1))
        assertEquals(emptyList(), DrillSelector.leeches(emptyList(), 8, 10, 1))
    }

    @Test
    fun hotWordsAreHottestFirstWithNotesTogether() {
        val hot = listOf(ScoredNote("n3", 1.0), ScoredNote("n1", 5.0), ScoredNote("nX", 9.0))
        val out = DrillSelector.hotWords(hot, cards, 10, 7)
        assertEquals(setOf("c1", "c2"), out.take(2).toSet())
        assertEquals(setOf("c4", "c5"), out.drop(2).toSet())
        assertEquals(4, out.size)
        assertEquals(out.take(3), DrillSelector.hotWords(hot, cards, 3, 7))
        assertEquals(emptyList(), DrillSelector.hotWords(emptyList(), cards, 10, 7))
        assertEquals(emptyList(), DrillSelector.hotWords(hot, emptyList(), 10, 7))
    }

    @Test
    fun wordSelectsOnlyThatNote() {
        assertEquals(setOf("c1", "c2"), DrillSelector.word("n1", cards, 10, 3).toSet())
        assertEquals(1, DrillSelector.word("n1", cards, 1, 3).size)
        assertEquals(emptyList(), DrillSelector.word("nope", cards, 10, 3))
        assertEquals(emptyList(), DrillSelector.word("n1", emptyList(), 10, 3))
    }

    @Test
    fun confusablePairsAreAdjacentDedupedAndSkipGracefully() {
        val pairs = listOf(NotePair("n2", "n1"), NotePair("n1", "n2"), NotePair("n3", "ghost"), NotePair("ghost", "ghost2"))
        val out = DrillSelector.confusables(pairs, cards, null, 10, 5)
        assertEquals(setOf("c1", "c2", "c3", "c4", "c5"), out.toSet())
        assertEquals(5, out.size)
        val only = DrillSelector.confusables(pairs, cards, "n2", 10, 5)
        assertEquals("c3", only.first())
        assertEquals(setOf("c3", "c1", "c2"), only.toSet())
        assertEquals(emptyList(), DrillSelector.confusables(emptyList(), cards, null, 10, 5))
        assertEquals(emptyList(), DrillSelector.confusables(pairs, cards, "n4", 10, 5))
    }

    @Test
    fun orderIsDeterministicPerSeed() {
        val many = (1..30).map { c("c$it", "n${it % 5}", 8) }
        assertEquals(DrillSelector.leeches(many, 8, 30, 42), DrillSelector.leeches(many, 8, 30, 42))
        assertEquals(DrillSelector.leeches(many, 8, 30, 42).sorted(), DrillSelector.leeches(many, 8, 30, 43).sorted())
        assertNotEquals(DrillSelector.leeches(many, 8, 30, 42), DrillSelector.leeches(many, 8, 30, 43))
    }

    @Test
    fun interleavedDrillReviewsDoNotChangeTheSchedule() {
        val real = listOf(
            rated(uuid(1, 1), T0, Rating.GOOD, ReviewType.LEARNING),
            rated(uuid(1, 2), T0 + DAY, Rating.AGAIN),
            rated(uuid(1, 3), T0 + 3 * DAY, Rating.GOOD),
            rated(uuid(1, 4), T0 + 9 * DAY, Rating.EASY),
        )
        val drills = listOf(
            rated(uuid(2, 1), T0 + DAY / 2, Rating.AGAIN, ReviewType.DRILL),
            rated(uuid(2, 2), T0 + 2 * DAY, Rating.EASY, ReviewType.DRILL),
            rated(uuid(2, 3), T0 + 3 * DAY + 5, Rating.AGAIN, ReviewType.CRAM),
            rated(uuid(2, 4), T0 + 20 * DAY, Rating.AGAIN, ReviewType.DRILL),
        )
        val without = DefaultReplayEngine.replay(real, P0)
        val with = DefaultReplayEngine.replay((real + drills).shuffled(kotlin.random.Random(1)), P0)
        assertEquals(without, with)
        assertEquals(4, with.schedule.reps)
    }
}
