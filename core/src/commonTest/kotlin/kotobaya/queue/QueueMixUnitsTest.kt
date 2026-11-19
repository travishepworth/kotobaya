package kotobaya.queue

import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.fsrs.ReviewType
import kotobaya.contracts.queue.QueueInput
import kotobaya.contracts.queue.QueueItemKind
import kotobaya.contracts.queue.QueueLimits
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CORE-28: QueueBuilder rule 5 on units (ADR-040): the mix never splits a unit, and "open group first"
 * continues a started unit after a rebuild. Reviews 1..n are due `past + n`; 学 = 51, 校 = 52; 学校 = 101,
 * たべる = 102.
 */
class QueueMixUnitsTest {
    private val past = NOW - 2 * HOUR

    private fun lim(newPerDay: Int = 4, keepGoing: Boolean = false): QueueLimits =
        QueueLimits(newPerDay, 200, 0, 0, true, 0, keepGoing = keepGoing, keepGoingNewBatch = 10)

    private fun reviews(n: Int) = (1..n).map { review(it, past + it) }

    private val units = listOf(
        card(101, created = 1, exprKanji = listOf("学", "校")),
        card(102, created = 2),
        card(51, base = NoteBaseType.KANJI, kanji = "学", created = 3),
        card(52, base = NoteBaseType.KANJI, kanji = "校", created = 4),
    )

    @Test
    fun theRuleFiveUnitExampleAndItsRebuild() {
        val sim = QueueSim(input(reviews(4) + units, limits = lim()))
        assertEquals("R1 N51 N52 N101 R2 R3 N102 R4", sim.build().shape())
        sim.answer(1, Ans.GOOD)
        sim.answer(51, Ans.GOOD)
        assertEquals("N52 N101 R2 R3 N102 R4", sim.build().shape())
    }

    @Test
    fun aDueLearningCardMayComeBetweenAndTheGroupContinuesAfterIt() {
        val sim = QueueSim(input(reviews(4) + units + learning(900, NOW + 20 * 60_000L), limits = lim()))
        sim.answer(1, Ans.GOOD)
        sim.answer(51, Ans.GOOD)
        // 20 minutes later the other learning card is due; 学 (10 min step) too.
        sim.input = sim.input.copy(now = NOW + 20 * 60_000L + 5_000L)
        assertEquals("L51 L900 N52 N101 R2 R3 N102 R4", sim.build().shape())
    }

    @Test
    fun theOpenGroupIsNotPulledAfterAReview() {
        val sim = QueueSim(input(reviews(8) + units, limits = lim()))
        sim.answer(1, Ans.GOOD)
        sim.answer(51, Ans.GOOD)
        assertEquals("N52 N101", sim.build().shape().split(" ").take(2).joinToString(" "))
        // A review answered after 学 (s = 1): the stride decides, rRem 6 > s * gRem = 3 → a review first.
        sim.answer(2, Ans.GOOD)
        assertEquals("R3", sim.build().shape().split(" ").first())
    }

    @Test
    fun theOpenGroupIsNotPulledAfterADayRollover() {
        val sim = QueueSim(input(reviews(4) + units, limits = lim()))
        sim.answer(51, Ans.EASY)
        assertEquals("N52 N101", sim.build().shape().split(" ").take(2).joinToString(" "))
        sim.nextDay()
        // Reviews are still due (they were due before the day); today is empty: a fresh day starts with a review.
        assertEquals("R1", sim.build().shape().split(" ").first())
    }

    @Test
    fun theOpenGroupIsNotPulledWhenTheLastIntroducedCardIsAVocabCard() {
        val sim = QueueSim(input(reviews(8) + units + card(103, created = 0), limits = lim(newPerDay = 5)))
        sim.answer(103, Ans.GOOD)
        // last = 103 (vocab), s = 0, rRem 8, gRem 4: 0 >= 8 no → a review.
        assertEquals("R1", sim.build().shape().split(" ").first())
    }

    @Test
    fun keepGoingOpenGroupThenReviewsThenTheRest() {
        val sim = QueueSim(input(reviews(3) + units, limits = lim(newPerDay = 0, keepGoing = true)))
        assertEquals("R1 R2 R3 N51 N52 N101 N102", sim.build().shape())
        sim.answer(51, Ans.GOOD)
        assertEquals("N52 N101 R1 R2 R3 N102", sim.build().shape())
    }

    @Test
    fun noReviewFallsBetweenTheCardsOfAUnit() {
        for (seed in 0 until 300) {
            val i = randomInput(seed)
            val q = DefaultReviewLoop.evaluate(i).queue
            val byId = i.candidates.associateBy { it.cardId }
            // Each kanji literal belongs to one word here: from a NEW kanji item to its word, only NEW items or learning.
            val kinds = q.items.map { it.kind }
            for ((x, item) in q.items.withIndex()) {
                val c = byId.getValue(item.cardId)
                if (item.kind != QueueItemKind.NEW || c.noteBaseType != NoteBaseType.KANJI) continue
                val w = q.items.indexOfFirst { byId.getValue(it.cardId).expressionKanji.contains(c.kanjiLiteral) }
                if (w > x) assertTrue(kinds.subList(x, w).none { it == QueueItemKind.REVIEW }, "seed $seed: ${q.shape()}")
            }
        }
    }

    @Test
    fun answeringTheHeadAndRebuildingYieldsTheTail() {
        // M4 with units: one card per note, words drawn as units, answers due after today.
        for (seed in 0 until 300) {
            val sim = QueueSim(randomInput(seed))
            var q = sim.eval().queue
            while (q.items.isNotEmpty()) {
                sim.answer(q.cards().first(), Ans.EASY)
                val next = sim.eval().queue
                assertEquals(q.items.drop(1), next.items, "seed $seed")
                q = next
            }
        }
    }

    /** Deckless words, each with its own kanji (created after every word), reviews, and some answers today. */
    private fun randomInput(seed: Int): QueueInput {
        val r = Random(seed)
        val pool = (0 until 30).map { "k$it" }.shuffled(r).iterator()
        val words = (1..r.nextInt(0, 10)).map { n -> card(100 + n, created = r.nextLong(0, 5), exprKanji = (0 until r.nextInt(0, 4)).map { pool.next() }) }
        var id = 300
        val kanji = words.flatMap { w -> w.expressionKanji.map { l -> card(id++, base = NoteBaseType.KANJI, kanji = l, created = 10) } }
        var t = TOKYO_DAY.startMs
        val today = (1..r.nextInt(0, 6)).map {
            val intro = r.nextBoolean()
            today(5000 + it, type = if (intro) ReviewType.LEARNING else ReviewType.SCHEDULED, introducedNew = intro, countsAsReview = !intro, at = ++t)
        }
        return input(
            reviews(r.nextInt(0, 20)) + words + kanji,
            limits = lim(newPerDay = r.nextInt(0, 20)).copy(reviewsUnlimited = r.nextBoolean(), keepGoingNewBatch = Int.MAX_VALUE),
            today = today,
        ).let { if (r.nextInt(4) == 0) it.copy(limits = it.limits.copy(keepGoing = true)) else it }
    }
}
