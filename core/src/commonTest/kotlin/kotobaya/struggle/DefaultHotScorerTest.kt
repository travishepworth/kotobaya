package kotobaya.struggle

import kotobaya.contracts.Reserved
import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.fsrs.Rating
import kotobaya.contracts.hot.HotCard
import kotobaya.contracts.hot.HotConfig
import kotobaya.contracts.hot.HotReview
import kotobaya.contracts.hot.HotScore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultHotScorerTest {
    private val cfg = HotConfig.DEFAULT
    private val now = 1_800_000_000_000L
    private val day = Reserved.DAY_MS

    private fun fail(agoDays: Double, state: CardState = CardState.REVIEW, card: String = "c1") =
        HotReview(card, now - (agoDays * day).toLong(), Rating.AGAIN, state)

    private fun ok(agoDays: Double, card: String = "c1") = HotReview(card, now - (agoDays * day).toLong(), Rating.GOOD, CardState.REVIEW)
    private fun card(d: Double? = 7.0, reps: Int = 5, lapses: Int = 1) = HotCard("c1", d, reps, lapses)
    private fun score(r: List<HotReview>, c: List<HotCard> = listOf(card())) = DefaultHotScorer.scoreNote(r, c, now, cfg)

    @Test
    fun contractExample() {
        val s = score(listOf(fail(0.0)))
        assertEquals(4.2, s.score, 1e-9)
        assertTrue(s.hot)
    }

    @Test
    fun noFailuresScoresZero() {
        val s = score(listOf(ok(1.0)))
        assertEquals(0.0, s.score)
        assertFalse(s.hot)
    }

    @Test
    fun eachFactorMovesTheScoreTheRightWay() {
        val base = score(listOf(fail(1.0))).score
        assertTrue(score(listOf(fail(1.0), fail(1.0))).score > base, "more failures")
        assertTrue(score(listOf(fail(1.0, CardState.LEARNING))).score < base, "relearning failure weighs more")
        assertTrue(score(listOf(fail(1.0)), listOf(card(d = 9.0))).score > base, "difficulty")
        assertTrue(score(listOf(fail(1.0)), listOf(card(lapses = 4))).score > base, "lapse rate")
    }

    @Test
    fun olderFailuresWeighLessAndLeaveTheWindow() {
        val s0 = score(listOf(fail(0.0))).decayedFailures
        val s7 = score(listOf(fail(7.0))).decayedFailures
        val s13 = score(listOf(fail(13.0))).decayedFailures
        assertEquals(1.0, s0, 1e-9)
        assertEquals(0.5, s7, 1e-9)
        assertTrue(s13 < s7)
        assertEquals(0.0, score(listOf(fail(15.0))).score)
    }

    @Test
    fun recoveredWordDropsOff() {
        val struggling = score(listOf(fail(1.0), ok(0.5)))
        assertTrue(struggling.hot, "one success after the failure is not recovery")
        val recovered = score(listOf(fail(1.0), ok(0.5), ok(0.2)))
        assertFalse(recovered.hot)
        assertTrue(recovered.score > 0.0)
        assertTrue(score(listOf(fail(1.0), ok(0.5), ok(0.2), fail(0.1))).hot, "a new failure restarts it")
    }

    @Test
    fun hotKanjiNeedsTwoWordsSharingIt() {
        val a = score(listOf(fail(0.0)))
        val b = score(listOf(fail(0.0)))
        val kanji = DefaultHotScorer.scoreKanji(listOf(a, b), cfg)
        assertEquals(2.0, kanji.score, 1e-9)
        assertFalse(kanji.hot, "2.0 < threshold 2.5")
        val c = score(listOf(fail(0.0)))
        assertTrue(DefaultHotScorer.scoreKanji(listOf(a, b, c), cfg).hot)
        val single = HotScore(5.0, true, 5.0)
        assertFalse(DefaultHotScorer.scoreKanji(listOf(single, HotScore(0.0, false, 0.0)), cfg).hot, "one word alone is not a kanji problem")
    }

    @Test
    fun leechRule() {
        assertFalse(Leeches.isLeech(7))
        assertTrue(Leeches.isLeech(8))
        assertTrue(Leeches.isLeech(3, 3))
    }
}
