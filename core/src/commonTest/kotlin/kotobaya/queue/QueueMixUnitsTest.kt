package kotobaya.queue

import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.fsrs.ReviewType
import kotobaya.contracts.queue.QueueInput
import kotobaya.contracts.queue.QueueItemKind
import kotobaya.contracts.queue.QueueLimits
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
    fun noReviewFallsBetweenTheCardsOfAUnitAndNoWordPrecedesItsKanji() {
        for (keepGoing in listOf(false, true)) {
            for (seed in 0 until 400) {
                val i = QueueRandom.input(seed, keepGoing)
                val q = DefaultReviewLoop.evaluate(i).queue
                val byId = i.candidates.associateBy { it.cardId }
                val pos = q.items.withIndex().associate { (x, it) -> it.cardId to x }
                for ((x, item) in q.items.withIndex()) {
                    val c = byId.getValue(item.cardId)
                    if (item.kind != QueueItemKind.NEW || c.noteBaseType != NoteBaseType.VOCAB) continue
                    // Every NEW kanji item of a kanji the word requires comes before it, with no review in between.
                    for ((y, k) in q.items.withIndex()) {
                        val kc = byId.getValue(k.cardId)
                        if (k.kind != QueueItemKind.NEW || kc.kanjiLiteral !in c.expressionKanji) continue
                        assertTrue(y < x, "seed $seed/$keepGoing: word before its kanji: ${q.shape()}")
                    }
                    val first = q.items.subList(0, x).indexOfLast { it.kind != QueueItemKind.NEW }
                    val unitStart = (first + 1..x).firstOrNull { y -> byId.getValue(q.items[y].cardId).kanjiLiteral in c.expressionKanji }
                    if (unitStart != null) assertTrue(q.items.subList(unitStart, x).none { it.kind == QueueItemKind.REVIEW }, "seed $seed")
                }
                assertEquals(q.items.size, pos.size)
            }
        }
    }

    @Test
    fun rebuildIsTail() {
        // C-28c: random inputs with reviews, units, shared kanji, capped decks, several sources per tier, backfill.
        for (keepGoing in listOf(false, true)) {
            for (seed in 0 until 400) {
                // Keep going: units without backfill here (QueueBackfillShareTest covers backfill with kana words).
                val sim = QueueSim(QueueRandom.input(seed, keepGoing).let { if (keepGoing) it.withoutStudiedWords() else it })
                repeat(3) { sim.materialize(sim.eval().queue) }
                if (QueueRandom.rebuildExcluded(sim.input)) continue
                var q = sim.eval().queue
                var steps = 0
                while (q.items.isNotEmpty() && steps++ < 200) {
                    sim.answer(q.cards().first(), Ans.EASY)
                    val next = sim.eval().queue
                    assertEquals(q.items.drop(1), next.items, "seed $seed keepGoing $keepGoing")
                    q = next
                }
            }
        }
    }

    private fun QueueInput.withoutStudiedWords(): QueueInput =
        copy(candidates = candidates.filter { !(it.noteBaseType == NoteBaseType.VOCAB && it.state == CardState.REVIEW && it.expressionKanji.isNotEmpty()) })

    // ---- C-28c examples ----

    @Test
    fun theWordIsNeverEmittedBeforeAKanjiItRequires() {
        // "Kanji" deck (priority 0) holds 校, the word deck (priority 1) holds 学校; 学 was introduced last today.
        val c = listOf(
            card(51, base = NoteBaseType.KANJI, kanji = "学", state = CardState.LEARNING, due = TOKYO_DAY.endMs + DAY_MS),
            card(52, base = NoteBaseType.KANJI, kanji = "校", members = listOf(member(1, 0))),
            card(101, exprKanji = listOf("学", "校"), members = listOf(member(2, 0))),
        ) + reviews(4)
        val t = listOf(today(51, type = ReviewType.LEARNING, introducedNew = true, at = NOW - 1000))
        val q = build(input(c, decks = listOf(deck(1), deck(2, priority = 1)), limits = lim(newPerDay = 10), today = t))
        val shape = q.shape()
        assertTrue(shape.contains("N52 N101"), shape)
        assertEquals(listOf(52, 101), q.cards(QueueItemKind.NEW))
    }

    @Test
    fun turnOrderExample() {
        // Goal 4; deck D holds a, b; the deckless source holds 学校 and, created after it, 学 and 校.
        val c = listOf(
            card(1, members = listOf(member(1, 0))), card(2, members = listOf(member(1, 1))),
            card(10, created = 1, exprKanji = listOf("学", "校")),
            card(11, base = NoteBaseType.KANJI, kanji = "学", created = 2), card(12, base = NoteBaseType.KANJI, kanji = "校", created = 3),
        )
        val sim = QueueSim(input(c, decks = listOf(deck(1)), limits = lim(newPerDay = 4)))
        assertEquals(listOf(1, 11, 12, 10), sim.build().cards())
        sim.answer(1, Ans.EASY)
        assertEquals(listOf(11, 12, 10), sim.build().cards())
        sim.answer(11, Ans.EASY)
        assertEquals(listOf(12, 10), sim.build().cards())
        sim.answer(12, Ans.EASY)
        sim.answer(10, Ans.EASY)
        sim.nextDay()
        assertEquals(2, sim.build().cards().first(), "the next day: a tie, D first")
    }

    private fun kanaDeckAndHandAddedKanji(): List<kotobaya.contracts.queue.QueueCandidate> =
        (1..30).map { card(it, members = listOf(member(1, it.toLong()))) } +
            (1..50).map { card(100 + it, base = NoteBaseType.KANJI, kanji = "k$it", created = it.toLong()) }

    @Test
    fun kanjiNoWordRequiresAlternateWithTheDeck() {
        val l = QueueLimits(20, 200, 0, 0, true, 0, keepGoingNewBatch = 10)
        val sim = QueueSim(input(kanaDeckAndHandAddedKanji(), decks = listOf(deck(1)), limits = l))
        val r = sim.eval()
        assertEquals(listOf(1, 101, 2, 102, 3, 103, 4, 104, 5, 105), r.queue.cards())
        assertEquals(20, r.state.untilStop)
        // After every answer the stream continues alternating: the deck is never starved.
        val served = mutableListOf<Int>()
        while (sim.eval().queue.items.isNotEmpty()) served += sim.answerHead(Ans.EASY)
        assertEquals((1..10).flatMap { listOf(it, 100 + it) }, served)
    }

    @Test
    fun aRequiredDecklessKanjiIsLeftOutOfTheTurnCount() {
        // Word X (deck position 0) requires k1: its unit is drawn by the deck; k1 does not count for the deckless source.
        val c = kanaDeckAndHandAddedKanji() + card(99, exprKanji = listOf("k1"), members = listOf(member(1, 0)))
        val l = QueueLimits(20, 200, 0, 0, true, 0, keepGoingNewBatch = 100)
        val sim = QueueSim(input(c, decks = listOf(deck(1)), limits = l))
        val first = sim.eval()
        assertEquals(listOf(101, 99, 102, 1, 103, 2), first.queue.cards().take(6))
        assertEquals(10, first.queue.items.count { it.cardId.takeLast(12).toInt() < 100 })
        // The same in today's reviews: answering k1 and X leaves the deckless source at 0 turns, so k2 is next.
        sim.answer(101, Ans.EASY)
        sim.answer(99, Ans.EASY)
        assertEquals(first.queue.cards().drop(2), sim.eval().queue.cards())
    }

    @Test
    fun turnCountsIncludeDeckHomedKanji() {
        // Deck A: 学校 (its kanji 学 is homed in deck K), a2, a3; deck K: 学 and 先 (own turns).
        val c = listOf(
            card(1, exprKanji = listOf("学"), members = listOf(member(1, 0))), card(2, members = listOf(member(1, 1))), card(3, members = listOf(member(1, 2))),
            card(10, base = NoteBaseType.KANJI, kanji = "学", members = listOf(member(2, 0))),
            card(11, base = NoteBaseType.KANJI, kanji = "先", members = listOf(member(2, 1))),
        )
        // After [学, 学校]: t(A) = 1, t(K) = 1 (学 counts for K) → A (source order): a2; then K: 先.
        assertEquals(listOf(10, 1, 2, 11, 3), build(input(c, decks = listOf(deck(1), deck(2)), limits = lim(newPerDay = 10))).cards())
    }

    @Test
    fun ruleAPicksTheOpenUnitsSourceWithTheSmallestCountThenSourceOrder() {
        // 学 (deckless) was just introduced; decks A and B both have a next word requiring it; deck C (lowest
        // count) has a kana word. Rule (a) restricts the choice to A and B.
        val gaku = card(50, base = NoteBaseType.KANJI, kanji = "学", state = CardState.LEARNING, due = TOKYO_DAY.endMs + DAY_MS)
        val c = listOf(
            gaku,
            card(1, exprKanji = listOf("学"), members = listOf(member(1, 0))),
            card(2, exprKanji = listOf("学"), members = listOf(member(2, 0))),
            card(3, members = listOf(member(3, 0))),
        )
        val decks = listOf(deck(1), deck(2), deck(3))
        fun first(aExtra: Int): Int {
            val t = listOf(today(50, type = ReviewType.LEARNING, introducedNew = true, at = NOW - 10)) +
                listOf(1, 2).flatMap { d -> (1..(if (d == 1) aExtra + 1 else 1)).map { today(8000 + 10 * d + it, type = ReviewType.LEARNING, introducedNew = true, decks = listOf(d), at = NOW - 100) } }
            return build(input(c, decks = decks, limits = lim(newPerDay = 10), today = t)).cards().first()
        }
        assertEquals(1, first(0), "tie between A and B: source order")
        assertEquals(2, first(1), "A has more turns: B")
    }

    @Test
    fun ruleAInsideOneBuild() {
        // K (kanji deck) draws 学 on its own turn; W's next word requires it and goes next although t(W) = 3.
        val c = listOf(
            card(10, base = NoteBaseType.KANJI, kanji = "学", members = listOf(member(1, 0))),
            card(1, exprKanji = listOf("学"), members = listOf(member(2, 0))),
            card(2, members = listOf(member(3, 0))),
        )
        val t = (1..3).map { today(8000 + it, type = ReviewType.LEARNING, introducedNew = true, decks = listOf(2), at = NOW - 100 + it) }
        assertEquals(listOf(10, 1, 2), build(input(c, decks = listOf(deck(1), deck(2), deck(3)), limits = lim(newPerDay = 20), today = t)).cards())
    }
}
