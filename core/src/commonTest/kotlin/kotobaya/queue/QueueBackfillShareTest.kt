package kotobaya.queue

import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.fsrs.ReviewType
import kotobaya.contracts.queue.QueueCandidate
import kotobaya.contracts.queue.QueueDeck
import kotobaya.contracts.queue.QueueInput
import kotobaya.contracts.queue.QueueItemKind
import kotobaya.contracts.queue.QueueLimits
import kotobaya.contracts.queue.TodayReview
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-044 (C-28b): QueueBuilder rule 4 "Half share", from the contract's examples. Base data: 日本語 (card
 * 10) in REVIEW since an earlier day; kanji 日 (40), 本 (50), 語 (60) NEW and deckless; deck 1 holds the
 * new words うっかり (20), Ｔシャツ (30) and, where used, あれ (35).
 */
class QueueBackfillShareTest {
    private val notDue = TOKYO_DAY.endMs + 5 * DAY_MS

    private fun lim(newPerDay: Int, batch: Int = 10, keepGoing: Boolean = false, bury: Boolean = true): QueueLimits =
        QueueLimits(newPerDay, 200, 0, 0, bury, 0, keepGoing = keepGoing, keepGoingNewBatch = batch)

    private fun kanji(n: Int, literal: String, created: Long, note: Int = n) =
        card(n, note = note, base = NoteBaseType.KANJI, kanji = literal, created = created)

    private val nihongo = card(10, created = 1, exprKanji = listOf("日", "本", "語"), state = CardState.REVIEW, due = notDue)
    private val kanji = listOf(kanji(40, "日", 5), kanji(50, "本", 6), kanji(60, "語", 7))
    private val words = listOf(card(20, created = 2, members = listOf(member(1, 1))), card(30, created = 3, members = listOf(member(1, 2))))
    private val are = card(35, created = 4, members = listOf(member(1, 3)))

    private fun base(
        newPerDay: Int,
        cards: List<QueueCandidate> = listOf(nihongo) + kanji + words,
        decks: List<QueueDeck> = listOf(deck(1)),
        today: List<TodayReview> = emptyList(),
        decklessPriority: Int = 0,
        keepGoing: Boolean = false,
        batch: Int = 10,
    ): QueueInput = input(cards, decks = decks, limits = lim(newPerDay, batch, keepGoing), today = today, decklessPriority = decklessPriority)

    private fun detailed(i: QueueInput) = DefaultQueueBuilder.buildDetailed(i)

    @Test
    fun halfShareExample() {
        val sim = QueueSim(base(4))
        val r0 = sim.eval()
        assertEquals(listOf(40, 50, 20, 30), r0.queue.cards())
        assertEquals(4, r0.state.untilStop)
        sim.answer(40, Ans.EASY)
        sim.answer(50, Ans.EASY)
        assertEquals(2, detailed(sim.input).backfillToday)
        assertEquals(listOf(20, 30), sim.eval().queue.cards())
        sim.answer(20, Ans.EASY)
        sim.answer(30, Ans.EASY)
        assertEquals(emptyList(), sim.eval().queue.cards())
        sim.nextDay()
        assertEquals(listOf(60), sim.eval().queue.cards())
    }

    @Test
    fun withoutWordsTheRemainderStepTakesTheRest() {
        assertEquals(listOf(40, 50, 60), build(base(4, cards = listOf(nihongo) + kanji)).cards())
    }

    @Test
    fun withAGoalOfFiveTheThirdKanjiComesLast() {
        assertEquals(listOf(40, 50, 20, 30, 60), build(base(5)).cards())
    }

    @Test
    fun aDecklessBackfillKanjiHasNoTurnOfItsOwn() {
        // Same tier as the deck, and in a tier of its own ahead of it: the same N.
        val expected = listOf(40, 50, 20, 30)
        assertEquals(expected, build(base(4)).cards())
        assertEquals(expected, build(base(4, decklessPriority = -1)).cards())
    }

    @Test
    fun keepGoingExampleAndItsAlternatingVariant() {
        val introduced = { t: Long -> listOf(40, 50).mapIndexed { i, n -> today(n, type = ReviewType.LEARNING, introducedNew = true, at = t + i) } }
        val answered = (listOf(nihongo) + kanji + words + are).map {
            if (it.cardId == cardId(40) || it.cardId == cardId(50)) it.copy(state = CardState.REVIEW, dueAt = notDue) else it
        }
        val kg = base(2, cards = answered, today = introduced(TOKYO_DAY.startMs + 1), keepGoing = true)
        assertEquals(2, detailed(kg).backfillToday)
        assertEquals(listOf(20, 30, 35, 60), build(kg).cards())
        // iToday 4, bToday 2: a word and a kanji in turn.
        val more = introduced(TOKYO_DAY.startMs + 1) + listOf(9001, 9002).map { today(it, type = ReviewType.LEARNING, introducedNew = true, at = TOKYO_DAY.startMs) }
        assertEquals(listOf(20, 60, 30, 35), build(base(4, cards = answered, today = more, keepGoing = true)).cards())
    }

    @Test
    fun goalsOfOneAndThreeGiveAShareOfOne() {
        assertEquals(listOf(40), build(base(1)).cards())
        assertEquals(listOf(40, 20, 30), build(base(3)).cards())
    }

    @Test
    fun bTodayCountsAKanjiOfAWordIntroducedEarlierToday() {
        val t = TOKYO_DAY.startMs
        val w = nihongo.copy(state = CardState.LEARNING, dueAt = notDue)
        val k = kanji.map { if (it.cardId == cardId(40)) it.copy(state = CardState.LEARNING, dueAt = notDue) else it }
        val today = listOf(today(10, type = ReviewType.LEARNING, introducedNew = true, at = t + 1), today(40, type = ReviewType.LEARNING, introducedNew = true, at = t + 2))
        val i = base(4, cards = listOf(w) + k + words, today = today)
        assertEquals(1, detailed(i).backfillToday)
        assertEquals(listOf(50, 20), build(i).cards())
        // Ties: the kanji answered in the same millisecond as its word is not a backfill introduction.
        val tie = i.copy(today = today.map { it.copy(reviewedAt = t + 1) })
        assertEquals(0, detailed(tie).backfillToday)
        assertEquals(listOf(50, 60), build(tie).cards())
    }

    @Test
    fun bTodayIgnoresAKanjiAnsweredAheadOfItsOwnWord() {
        val t = TOKYO_DAY.startMs
        // 学校 (11) has been studied for days; its kanji 学 (70) and 校 (80) are backfill kanji.
        val gakkou = card(11, created = 0, exprKanji = listOf("学", "校"), state = CardState.REVIEW, due = notDue)
        val gk = listOf(kanji(70, "学", 8), kanji(80, "校", 9))
        // Unit: 日 本 語 and then the word 日本語 itself, all answered today.
        val unitDone = listOf(10, 40, 50, 60).map { n ->
            (listOf(nihongo) + kanji).first { it.cardId == cardId(n) }.copy(state = CardState.LEARNING, dueAt = notDue)
        }
        val unitRows = listOf(40, 50, 60, 10).mapIndexed { i, n -> today(n, type = ReviewType.LEARNING, introducedNew = true, at = t + 1 + i) }
        val unit = base(6, cards = unitDone + listOf(gakkou) + gk + words.take(1), today = unitRows)
        assertEquals(0, detailed(unit).backfillToday)
        assertEquals(listOf(70, 80), build(unit).cards())
        // Lead: 日 answered ahead of 日本語, which is still new.
        val leadCards = listOf(nihongo.copy(state = CardState.NEW, dueAt = null)) +
            kanji.map { if (it.cardId == cardId(40)) it.copy(state = CardState.LEARNING, dueAt = notDue) else it } + listOf(gakkou) + gk + words.take(1)
        val lead = base(4, cards = leadCards, today = listOf(today(40, type = ReviewType.LEARNING, introducedNew = true, at = t + 1)))
        assertEquals(0, detailed(lead).backfillToday)
        assertEquals(listOf(70, 80, 20), build(lead).cards())
    }

    @Test
    fun aSourceStoppedByALeadOrItsLimitHandsTheRemainderToBackfill() {
        // Per-deck limit 1: 日 本 (share), うっかり, then the deck is at its limit: 語 by the remainder step.
        assertEquals(listOf(40, 50, 20, 60), build(base(4, decks = listOf(deck(1, newPerDay = 1)))).cards())
        // Lead: the deck's word 校学 has parts 校 (1) and 学 (2 kinds, burySiblings false); after 日 本 only 校 fits.
        val x = card(21, created = 2, exprKanji = listOf("校", "学"), members = listOf(member(1, 0)))
        // (学's cards sit in a disabled deck: no turn of their own, so only the lead could take them.)
        val ks = listOf(kanji(80, "校", 9), kanji(71, "学", 8, note = 70), kanji(72, "学", 8, note = 70).copy(kindOrder = 1))
            .map { if (it.kanjiLiteral == "学") it.copy(memberships = listOf(member(9, 0))) else it }
        val i = input(listOf(nihongo) + kanji + x + ks + words, decks = listOf(deck(1), deck(9, enabled = false)), limits = lim(4, bury = false))
        assertEquals(listOf(40, 50, 80, 60), build(i).cards())
    }

    @Test
    fun deckHomedBackfillKanji() {
        val kanjiDeck = { priority: Int, newPerDay: Int?, enabled: Boolean -> deck(2, priority = priority, newPerDay = newPerDay, enabled = enabled) }
        val inKanjiDeck = kanji.map { if (it.cardId == cardId(40)) it.copy(memberships = listOf(member(2, 0))) else it }
        for ((p, n, e) in listOf(Triple(0, null, true), Triple(10, null, true), Triple(0, 0, true), Triple(0, null, false))) {
            val q = build(base(4, cards = listOf(nihongo) + inKanjiDeck + words, decks = listOf(deck(1), kanjiDeck(p, n, e))))
            assertEquals(listOf(40, 50, 20, 30), q.cards(), "priority $p newPerDay $n enabled $e")
            assertEquals(if (e) deckId(2) else null, q.items.first().deckId, "日 counts against its home deck")
        }
        // Counted against the Kanji deck's daily count: with a limit of 1 its own kanji 学 has no room.
        val gaku = kanji(70, "学", 8).copy(memberships = listOf(member(2, 1)))
        val big = { limit: Int -> build(base(6, cards = listOf(nihongo) + inKanjiDeck + words + gaku, decks = listOf(deck(1), deck(2, newPerDay = limit)))).cards() }
        assertEquals(listOf(40, 50, 60, 20, 30), big(1))
        // Limit 2: 学 has its turn; "Turn order" (C-28c): 日 already counts for Kanji (t = 1), so the word deck
        // (t = 1 after うっかり) wins the tie in source order before 学.
        assertEquals(listOf(40, 50, 60, 20, 30, 70), big(2))
        // 語 in the Kanji deck: drawn on the deck's own turn after the share stopped at it.
        val goInDeck = kanji.map { if (it.cardId == cardId(60)) it.copy(memberships = listOf(member(2, 0))) else it }
        val sim = QueueSim(base(4, cards = listOf(nihongo) + goInDeck + words, decks = listOf(deck(1), deck(2))))
        assertEquals(listOf(40, 50, 20, 60), sim.eval().queue.cards())
        listOf(40, 50, 20, 60).forEach { sim.answer(it, Ans.EASY) }
        assertEquals(3, detailed(sim.input).backfillToday)
        sim.nextDay()
        assertEquals(listOf(30), sim.eval().queue.cards())
        // The Kanji deck at priority 10: its tier is not reached; 語 the next day by the share step.
        assertEquals(listOf(40, 50, 20, 30), build(base(4, cards = listOf(nihongo) + goInDeck + words, decks = listOf(deck(1), deck(2, priority = 10)))).cards())
    }

    @Test
    fun theBatchCutsAPrefixOfTheUnboundedDraw() {
        for (b in 1..4) {
            val r = DefaultReviewLoop.evaluate(base(4, batch = b))
            assertEquals(listOf(40, 50, 20, 30).take(b), r.queue.cards(), "batch $b")
            assertEquals(4, r.state.untilStop, "availableToday is unchanged, batch $b")
        }
    }

    @Test
    fun aTwoCardPartWithAGoalOfOneIsDrawnOnceAsTheDaysFirstNewCards() {
        val two = listOf(kanji(41, "日", 5, note = 40), kanji(42, "日", 5, note = 40).copy(kindOrder = 1))
        // Backfill part (日本語 introduced earlier).
        val w = card(10, created = 1, exprKanji = listOf("日"), state = CardState.REVIEW, due = notDue)
        val bf = QueueSim(input(listOf(w) + two, limits = lim(1, bury = false)))
        assertEquals(listOf(41, 42), bf.eval().queue.cards())
        bf.answer(41, Ans.GOOD)
        assertEquals(emptyList(), bf.eval().queue.cards())
        // Lead part (the word is new).
        val lead = QueueSim(input(listOf(w.copy(state = CardState.NEW, dueAt = null)) + two, limits = lim(1, bury = false)))
        assertEquals(listOf(41, 42), lead.eval().queue.cards())
        lead.answer(41, Ans.GOOD)
        assertEquals(emptyList(), lead.eval().queue.cards())
    }

    @Test
    fun aGoalOfZeroDrawsNothing() {
        assertEquals(emptyList(), build(base(0)).cards())
        assertEquals(0, DefaultReviewLoop.evaluate(base(0)).state.untilStop)
    }

    // ---- Properties ----

    /** Plays [i] to the end, asserting rule 5 "Rebuild = tail" after every answer (minting first, exclusion 1). */
    private fun assertRebuildIsTail(i: QueueInput, what: String) {
        val sim = QueueSim(i)
        repeat(3) { sim.materialize(sim.eval().queue) }
        if (QueueRandom.rebuildExcluded(sim.input)) return
        var q = sim.eval().queue
        var steps = 0
        while (q.items.isNotEmpty() && steps++ < 200) {
            sim.answer(q.cards().first(), Ans.EASY)
            val next = sim.eval().queue
            assertEquals(q.items.drop(1), next.items, what)
            q = next
        }
    }

    @Test
    fun answeringTheHeadAndRebuildingYieldsTheTail() {
        // Normal build: every random input (backfill, units, shared kanji, capped decks, several sources per tier).
        for (seed in 0 until 400) assertRebuildIsTail(QueueRandom.input(seed, keepGoing = false), "normal seed $seed")
        // Keep going with backfill: new words without kanji (see QueueRandom: a unit answered half-way lets
        // the keep-going share step admit a backfill part before the rest of the unit; HANDOFF).
        for (seed in 0 until 400) {
            assertRebuildIsTail(QueueRandom.input(seed, keepGoing = true, newWordKanji = false), "keep going seed $seed")
        }
    }

    @Test
    fun aNormalBuildKeepsBackfillWithinTheShare() {
        for (seed in 0 until 400) {
            val i = QueueRandom.input(seed, keepGoing = false)
            val d = detailed(i)
            val share = maxOf(1, i.limits.newPerDay / 2)
            val within = d.backfillToday + d.backfillAfterFirstShare <= share
            assertTrue(within || (d.backfillToday == 0 && d.partsInFirstShare == 1), "seed $seed")
        }
    }
}
