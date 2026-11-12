package kotobaya.queue

import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.WordStatus
import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.fsrs.ReviewType
import kotobaya.contracts.queue.AddKanjiMode
import kotobaya.contracts.queue.QueueItemKind
import kotobaya.contracts.queue.StudyDay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QueueBuilderTest {
    private val past = NOW - 2 * HOUR

    // ---- Rule 1: eligibility -------------------------------------------------------------------

    @Test
    fun suspendedCardsAreNeverQueued() {
        // ADR-009: imported suspended cards (any state) never show.
        val q = build(
            input(
                listOf(
                    review(1, past).copy(suspended = true),
                    learning(2, past).copy(suspended = true),
                    card(3, suspended = true),
                    review(4, past),
                ),
            ),
        )
        assertEquals(listOf(4), q.cards())
    }

    @Test
    fun manuallyBuriedCardShowsOnlyFromBuriedUntil() {
        val c = listOf(review(1, past, note = 1).copy(buriedUntil = NOW + 1), review(2, past).copy(buriedUntil = NOW))
        assertEquals(listOf(2), build(input(c)).cards())
    }

    @Test
    fun overridesNeverForgetBlacklistedIgnoredExcludeEveryState() {
        val excluded = listOf(WordStatus.NEVER_FORGET, WordStatus.BLACKLISTED, WordStatus.IGNORED)
        for (o in excluded) {
            val c = listOf(review(1, past).copy(wordOverride = o), learning(2, past).copy(wordOverride = o), card(3, override = o))
            assertEquals(emptyList<Int>(), build(input(c)).cards(), "override $o")
        }
    }

    @Test
    fun knownOverrideBlocksIntroductionButNotReviewOrLearning() {
        val k = WordStatus.KNOWN
        val c = listOf(review(1, past).copy(wordOverride = k), learning(2, past).copy(wordOverride = k), card(3, override = k))
        assertEquals("L2 R1", build(input(c)).shape())
    }

    @Test
    fun siblingBuriedByTodaysScheduledOrLearningReviewOfAnotherCard() {
        val c = listOf(review(1, past, note = 9), card(2, note = 9), learning(3, past, note = 9), review(4, past, note = 8))
        val t = listOf(today(5, note = 9, type = ReviewType.LEARNING), today(4, note = 8))
        // card 4 itself was reviewed today: not buried by its own review.
        assertEquals("L3 R4", build(input(c, today = t)).shape())
    }

    @Test
    fun drillCramAndManualReviewsDoNotBurySiblings() {
        val c = listOf(review(1, past, note = 9))
        for (type in listOf(ReviewType.DRILL, ReviewType.CRAM, ReviewType.MANUAL)) {
            assertEquals(listOf(1), build(input(c, today = listOf(today(5, note = 9, type = type)))).cards(), "$type")
        }
    }

    @Test
    fun burySiblingsOffKeepsSiblings() {
        val c = listOf(review(1, past, note = 9), review(2, past + 1, note = 9), card(3, note = 9))
        val t = listOf(today(5, note = 9))
        assertEquals("R1 R2 N3", build(input(c, today = t, limits = limits(burySiblings = false))).shape())
    }

    @Test
    fun oneBuildHoldsAtMostOneNewOrReviewCardPerNote() {
        val c = listOf(
            review(1, past, note = 9),
            review(2, past + 1, note = 9),
            card(3, note = 9),
            card(4, note = 8, kindOrder = 0),
            card(5, note = 8, kindOrder = 1),
            learning(6, past, note = 9),
        )
        assertEquals("L6 R1 N4", build(input(c)).shape())
    }

    @Test
    fun skippedSiblingDoesNotCountAgainstLimits() {
        val c = listOf(review(1, past, note = 9), review(2, past + 1, note = 9), review(3, past + 2))
        assertEquals(listOf(1, 3), build(input(c, limits = limits(reviewsPerDay = 2))).cards())
        val n = listOf(card(1, note = 9, created = 1), card(2, note = 9, created = 1, kindOrder = 1), card(3, created = 2))
        assertEquals(listOf(1, 3), build(input(n, limits = limits(newPerDay = 2))).cards())
    }

    // ---- Rule 2: learning ----------------------------------------------------------------------

    @Test
    fun learningOrderedByDueThenIdWithinLearnAheadAndUnlimited() {
        val c = listOf(
            learning(3, NOW + 20 * 60_000L),
            learning(2, past),
            learning(1, past),
            card(4, state = CardState.RELEARNING, due = NOW),
            learning(5, NOW + 20 * 60_000L + 1),
            learning(6, TOKYO_DAY.endMs - 1),
            learning(7, TOKYO_DAY.endMs),
        )
        val q = build(input(c, limits = limits(reviewsPerDay = 0, newPerDay = 0)))
        assertEquals("L1 L2 L4 L3", q.shape())
        assertEquals(NOW + 20 * 60_000L + 1, q.nextLearningDueAt)
    }

    @Test
    fun nextLearningDueAtNullWhenNothingLaterToday() {
        assertNull(build(input(listOf(learning(1, past), learning(2, TOKYO_DAY.endMs)))).nextLearningDueAt)
    }

    // ---- Rule 3: reviews -----------------------------------------------------------------------

    @Test
    fun reviewsDueBeforeDayEndOrderedByDueThenId() {
        val c = listOf(
            review(3, past),
            review(2, past),
            review(1, NOW + HOUR),
            review(4, TOKYO_DAY.endMs - 1),
            review(5, TOKYO_DAY.endMs),
        )
        assertEquals(listOf(2, 3, 1, 4), build(input(c)).cards())
    }

    @Test
    fun globalReviewLimitCountsTodaysCountedReviews() {
        val c = (1..5).map { review(it, past + it) }
        val t = listOf(
            today(10, countsAsReview = true),
            today(11, countsAsReview = true),
            today(12, type = ReviewType.LEARNING, countsAsReview = false),
        )
        assertEquals(listOf(1), build(input(c, limits = limits(reviewsPerDay = 3), today = t)).cards())
        assertEquals(emptyList<Int>(), build(input(c, limits = limits(reviewsPerDay = 2), today = t)).cards())
    }

    @Test
    fun perDeckReviewLimitUsesHomeDeckByPriorityThenIdEvenIfDisabled() {
        val decks = listOf(deck(1, priority = 1), deck(2, priority = 0, enabled = false, reviewsPerDay = 2), deck(3, priority = 0))
        // Home deck of a card in decks 1,2,3 is deck 2 (priority 0, lower id than 3).
        val m = listOf(member(1, 0), member(3, 0), member(2, 0))
        val c = listOf(review(1, past + 1, m), review(2, past + 2, m), review(3, past + 3, m), review(4, past + 4, listOf(member(3, 0))))
        val t = listOf(today(10, countsAsReview = true, decks = listOf(3, 2)))
        val q = build(input(c, decks = decks, today = t))
        assertEquals(listOf(1, 4), q.cards())
        assertEquals(listOf(deckId(2), deckId(3)), q.items.map { it.deckId })
    }

    // ---- Rule 4: new cards ---------------------------------------------------------------------

    @Test
    fun globalNewLimitCountsTodaysIntroducedCards() {
        val c = (1..5).map { card(it, created = it.toLong()) }
        val t = listOf(today(10, introducedNew = true), today(11, introducedNew = true))
        assertEquals(listOf(1, 2), build(input(c, limits = limits(newPerDay = 4), today = t)).cards())
        assertEquals(emptyList<Int>(), build(input(c, limits = limits(newPerDay = 1), today = t)).cards())
    }

    @Test
    fun sessionCapLimitsNewCards() {
        val c = (1..5).map { card(it, created = it.toLong()) }
        assertEquals(listOf(1, 2, 3), build(input(c, limits = limits(newPerSession = 10, newShown = 7))).cards())
        assertEquals(emptyList<Int>(), build(input(c, limits = limits(newPerSession = 10, newShown = 12))).cards())
    }

    @Test
    fun perDeckNewLimitCountsTodayByHomeNewSource() {
        val decks = listOf(deck(1, newPerDay = 3), deck(2, enabled = false))
        val c = (1..5).map { card(it, members = listOf(member(1, it.toLong()))) }
        // Review 10 had decks 2 (disabled) and 1 -> home new-source deck 1. Review 11 deckless.
        val t = listOf(today(10, introducedNew = true, decks = listOf(2, 1)), today(11, introducedNew = true))
        assertEquals(listOf(1, 2), build(input(c, decks = decks, today = t)).cards())
    }

    @Test
    fun deckOrderIsPositionThenItemKeyThenKindOrderThenId() {
        val decks = listOf(deck(1))
        val c = listOf(
            card(1, note = 1, members = listOf(member(1, 5, "n:b"))),
            card(2, note = 2, members = listOf(member(1, 5, "n:a"))),
            card(3, note = 3, kindOrder = 1, members = listOf(member(1, 1))),
            card(4, note = 3, kindOrder = 0, members = listOf(member(1, 1))),
            card(5, note = 4, members = listOf(member(1, 0))),
        )
        val q = build(input(c, decks = decks, limits = limits(burySiblings = false)))
        assertEquals(listOf(5, 4, 3, 2, 1), q.cards())
    }

    @Test
    fun priorityTiersAscendingAndRoundRobinWithinTierDecklessLast() {
        val decks = listOf(deck(3, priority = 0), deck(1, priority = 0), deck(2, priority = 1))
        val c = listOf(
            card(11, members = listOf(member(1, 1))), card(12, members = listOf(member(1, 2))), card(13, members = listOf(member(1, 3))),
            card(21, members = listOf(member(2, 1))), card(22, members = listOf(member(2, 2))),
            card(31, members = listOf(member(3, 1))),
            card(91, created = 1), card(92, created = 2),
        )
        val q = build(input(c, decks = decks))
        // Tier 0: decks 1, 3 (by id), deckless last; deck 3 exhausts after one round.
        assertEquals(listOf(11, 31, 91, 12, 92, 13, 21, 22), q.cards())
        assertEquals(deckId(1), q.items[0].deckId)
        assertNull(q.items[2].deckId)
    }

    @Test
    fun decklessPriorityMovesMinedCardsToTheirTier() {
        val decks = listOf(deck(1, priority = 0))
        val c = listOf(card(11, members = listOf(member(1, 1))), card(12, members = listOf(member(1, 2))), card(91, created = 1))
        assertEquals(listOf(91, 11, 12), build(input(c, decks = decks, decklessPriority = -1)).cards())
        assertEquals(listOf(11, 12, 91), build(input(c, decks = decks, decklessPriority = 5)).cards())
    }

    @Test
    fun decklessCardsOrderedByCreatedAtThenKindOrderThenId() {
        val c = listOf(
            card(1, note = 1, created = 30),
            card(2, note = 2, created = 10, kindOrder = 1),
            card(3, note = 2, created = 10, kindOrder = 0),
            card(4, note = 3, created = 20),
        )
        assertEquals(listOf(3, 2, 4, 1), build(input(c, limits = limits(burySiblings = false))).cards())
    }

    @Test
    fun cardWhoseDecksAreAllDisabledIsNeverIntroducedButStillReviewed() {
        val decks = listOf(deck(1, enabled = false), deck(2, enabled = false))
        val c = listOf(
            card(1, members = listOf(member(1, 0), member(2, 0))),
            review(2, past, listOf(member(1, 0))),
            card(3, created = 5),
        )
        assertEquals("R2 N3", build(input(c, decks = decks)).shape())
    }

    @Test
    fun newCardIsDrawnOnlyFromItsHomeNewSource() {
        // Card 1 is in disabled deck 1 and enabled decks 3 (prio 1) and 2 (prio 1): home = deck 2.
        val decks = listOf(deck(1, priority = 0, enabled = false), deck(2, priority = 1, newPerDay = 1), deck(3, priority = 1))
        val c = listOf(
            card(1, members = listOf(member(3, 0), member(1, 0), member(2, 9))),
            card(2, members = listOf(member(2, 0))),
            card(3, members = listOf(member(3, 1))),
        )
        val q = build(input(c, decks = decks))
        assertEquals(listOf(2, 3), q.cards())
        assertEquals(listOf(deckId(2), deckId(3)), q.items.map { it.deckId })
    }

    @Test
    fun pendingEntriesInterleaveByPositionAndCountAgainstBudgets() {
        val decks = listOf(deck(1, newPerDay = 3), deck(2, enabled = false))
        val c = listOf(card(1, members = listOf(member(1, 1))), card(2, members = listOf(member(1, 4))))
        val p = listOf(
            entry(1, seq = 100, position = 0),
            entry(1, seq = 101, position = 2, override = WordStatus.KNOWN),
            entry(1, seq = 102, position = 3),
            entry(2, seq = 103, position = 0),
        )
        val q = build(input(c, decks = decks, pending = p))
        assertEquals(listOf(1), q.cards())
        assertEquals(listOf(100L, 102L), q.toMaterialize.map { it.entrySeq })
    }

    @Test
    fun pendingEntriesWithExcludedOverridesAreSkippedAndDuplicatesMaterializeOnce() {
        val decks = listOf(deck(1), deck(2))
        val p = listOf(
            entry(1, 1, 0, WordStatus.NEVER_FORGET),
            entry(1, 2, 1, WordStatus.BLACKLISTED),
            entry(1, 3, 2, WordStatus.IGNORED),
            entry(1, 4, 3, WordStatus.LEARNING),
            entry(2, 4, 0),
            entry(2, 5, 1),
        )
        val q = build(input(emptyList(), decks = decks, pending = p, limits = limits(newPerDay = 10)))
        assertEquals(listOf(4L, 5L), q.toMaterialize.map { it.entrySeq })
    }

    // ---- add_kanji ------------------------------------------------------------------------------

    private val kanjiCards = listOf(
        card(51, note = 51, base = NoteBaseType.KANJI, kanji = "語", created = 100),
        card(52, note = 52, base = NoteBaseType.KANJI, kanji = "日", created = 101),
        card(53, note = 52, base = NoteBaseType.KANJI, kanji = "日", kindOrder = 1, created = 101),
        card(54, note = 54, base = NoteBaseType.KANJI, kanji = "本", created = 102),
    )

    private fun word(n: Int, deck: Int, pos: Long) =
        card(n, members = listOf(member(deck, pos)), exprKanji = listOf("日", "本", "語"))

    @Test
    fun addKanjiBeforeDrawsUnknownKanjiInExpressionOrderBeforeTheWord() {
        val decks = listOf(deck(1, addKanji = AddKanjiMode.BEFORE))
        val q = build(input(listOf(word(1, 1, 0)) + kanjiCards, decks = decks, limits = limits(burySiblings = false)))
        // Expression order 日 本 語; kanji 日 has two cards in kindOrder.
        assertEquals(listOf(52, 53, 54, 51, 1), q.cards())
        // C-8: each kanji card is attributed to its own home new-source (here deckless), V to deck 1.
        assertEquals(List(4) { null } + deckId(1), q.items.map { it.deckId })
    }

    @Test
    fun addKanjiAlongsideDrawsKanjiRightAfterTheWord() {
        val decks = listOf(deck(1, addKanji = AddKanjiMode.ALONGSIDE), deck(2))
        val c = listOf(word(1, 1, 0), card(2, members = listOf(member(1, 1))), card(3, members = listOf(member(2, 0)))) + kanjiCards
        val q = build(input(c, decks = decks))
        // Turn of deck 1 = word 1 + kanji 52 (sibling 53 buried), 54, 51; then deck 2; then deck 1 again.
        assertEquals(listOf(1, 52, 54, 51, 3, 2), q.cards())
    }

    @Test
    fun addKanjiOffDrawsNoKanjiWithTheWord() {
        val decks = listOf(deck(1, addKanji = AddKanjiMode.OFF))
        val q = build(input(listOf(word(1, 1, 0)) + kanjiCards, decks = decks, decklessPriority = 1))
        assertEquals(listOf(1, 51, 52, 54), q.cards())
    }

    @Test
    fun addKanjiSkipsKanjiAlreadyTakenOrWithoutHomeNewSourceOrKnown() {
        val decks = listOf(deck(1, addKanji = AddKanjiMode.BEFORE), deck(9, enabled = false))
        val c = listOf(
            word(1, 1, 0),
            // 日: only in a disabled deck -> never introduced (ADR-006).
            card(52, note = 52, base = NoteBaseType.KANJI, kanji = "日", members = listOf(member(9, 0))),
            // 本: being reviewed already (not NEW).
            card(54, note = 54, base = NoteBaseType.KANJI, kanji = "本", state = CardState.REVIEW, due = NOW + DAY_LATER),
            // 語: known override.
            card(51, note = 51, base = NoteBaseType.KANJI, kanji = "語", override = WordStatus.KNOWN),
        )
        assertEquals(listOf(1), build(input(c, decks = decks)).cards())
    }

    // C-8 rewrite of T205's addKanjiCountsAgainstDrawingSourceAndBudgetAndStopsMidTurn: a group is
    // drawn whole or not at all, and kanji cards are not charged to the drawing deck.
    @Test
    fun addKanjiGroupThatDoesNotFitTheBudgetIsNotDrawnInPart() {
        val decks = listOf(deck(1, addKanji = AddKanjiMode.BEFORE))
        val c = listOf(word(1, 1, 0), card(2, members = listOf(member(1, 1)))) + kanjiCards
        // Group 52 54 51 1 needs 4, budget 2: none of it. Deck 1's turn passes; deckless draws 51; then card 2.
        assertEquals(listOf(51, 2), build(input(c, decks = decks, limits = limits(newPerDay = 2))).cards())
        // Budget exactly 4: the whole group.
        assertEquals(listOf(52, 54, 51, 1), build(input(c, decks = decks, limits = limits(newPerDay = 4))).cards())
    }

    @Test
    fun addKanjiGroupMustFitTheSessionCapToo() {
        for (mode in listOf(AddKanjiMode.BEFORE, AddKanjiMode.ALONGSIDE)) {
            val c = listOf(word(1, 1, 0)) + kanjiCards
            val q = build(input(c, decks = listOf(deck(1, addKanji = mode)), limits = limits(newPerSession = 4, newShown = 1)))
            // Group of 4 > session remaining 3: the deckless source draws 51, 52 (53 buried), 54.
            assertEquals(listOf(51, 52, 54), q.cards(), "$mode")
        }
    }

    @Test
    fun addKanjiCardsAreNotChargedToTheDrawingDeck() {
        val c = listOf(word(1, 1, 0), card(2, members = listOf(member(1, 1)))) + kanjiCards
        // Deck limit 1: V fits, its 3 deckless kanji are not charged to deck 1; card 2 then does not fit.
        val q = build(input(c, decks = listOf(deck(1, addKanji = AddKanjiMode.BEFORE, newPerDay = 1))))
        assertEquals(listOf(52, 54, 51, 1), q.cards())
        assertEquals(listOf(null, null, null, deckId(1)), q.items.map { it.deckId })
        // Deck limit 2: card 2 fits too (the kanji used none of deck 1's limit).
        val q2 = build(input(c, decks = listOf(deck(1, addKanji = AddKanjiMode.BEFORE, newPerDay = 2))))
        assertEquals(listOf(52, 54, 51, 1, 2), q2.cards())
    }

    private val deck2Kanji = listOf(
        card(52, note = 52, base = NoteBaseType.KANJI, kanji = "日", members = listOf(member(2, 0))),
        card(55, note = 55, base = NoteBaseType.KANJI, kanji = "本", members = listOf(member(2, 1))),
        card(56, note = 56, base = NoteBaseType.KANJI, kanji = "学", members = listOf(member(2, 2))),
    )

    @Test
    fun addKanjiCardsAreChargedToTheirOwnHomeDeckAndNeverSkippedForIt() {
        val c = listOf(word(1, 1, 0), card(2, members = listOf(member(1, 1)))) + deck2Kanji
        fun run(deck2Limit: Int) = build(
            input(c, decks = listOf(deck(1, addKanji = AddKanjiMode.BEFORE, newPerDay = 1), deck(2, newPerDay = deck2Limit))),
        )
        // Kanji deck with newPerDay 0 still feeds kanji through add_kanji; its own turns draw nothing.
        val q0 = run(0)
        assertEquals(listOf(52, 55, 1), q0.cards())
        assertEquals(listOf(deckId(2), deckId(2), deckId(1)), q0.items.map { it.deckId })
        // Deck 2's limit 2 is used up by the two add_kanji cards: 56 is not drawn.
        assertEquals(listOf(52, 55, 1), run(2).cards())
        // Limit 3: one left for deck 2's own turn.
        assertEquals(listOf(52, 55, 1, 56), run(3).cards())
    }

    @Test
    fun todaysKanjiCountAgainstTheirHomeDeckNotTheDrawingDeck() {
        val decks = listOf(deck(1, newPerDay = 1), deck(2, newPerDay = 1))
        val c = listOf(card(2, members = listOf(member(1, 0))), deck2Kanji[2])
        // Kanji 52 was introduced today (drawn by deck 1's add_kanji); it counts against deck 2 only.
        val t = listOf(today(52, introducedNew = true, decks = listOf(2)), today(1, introducedNew = true, decks = listOf(1)))
        assertEquals(emptyList<Int>(), build(input(c, decks = decks, today = t)).cards())
        val t2 = listOf(today(52, introducedNew = true, decks = listOf(2)))
        assertEquals(listOf(2), build(input(c, decks = decks, today = t2)).cards())
    }

    @Test
    fun rebuildAfterAnsweringKeepsTheSamePerDeckCount() {
        val decks = listOf(deck(1, addKanji = AddKanjiMode.BEFORE, newPerDay = 2))
        val c = listOf(word(1, 1, 0), card(3, members = listOf(member(1, 1))), card(4, members = listOf(member(1, 2)))) + kanjiCards
        val first = build(input(c, decks = decks))
        assertEquals(listOf(52, 54, 51, 1, 3), first.cards())
        // Everything answered: kanji count against the deckless source, words against deck 1 (2 of 2).
        val t = first.items.map { item ->
            val n = item.cardId.takeLast(12).toInt()
            today(n, introducedNew = true, decks = if (item.deckId == null) emptyList() else listOf(1))
        }
        val left = c.filter { it.cardId !in first.items.map { i -> i.cardId } }
        assertEquals(emptyList<Int>(), build(input(left, decks = decks, today = t)).cards())
    }

    @Test
    fun decklessSourceUsesDecklessAddKanji() {
        val mined = card(1, created = 0, exprKanji = listOf("日", "本", "語"))
        val c = listOf(mined) + kanjiCards
        assertEquals(listOf(52, 54, 51, 1), build(input(c, decklessAddKanji = AddKanjiMode.BEFORE)).cards())
        assertEquals(listOf(1, 52, 54, 51), build(input(c, decklessAddKanji = AddKanjiMode.ALONGSIDE)).cards())
        assertEquals(listOf(1, 51, 52, 54), build(input(c)).cards())
    }

    // ---- Rule 5: interleaving --------------------------------------------------------------------

    @Test
    fun interleaveWorkedExamples() {
        fun shape(r: Int, n: Int): String {
            val c = (1..r).map { review(it, past + it) } + (1..n).map { card(100 + it, created = it.toLong()) }
            return build(input(c, limits = limits(newPerDay = 50))).items.joinToString("") { it.kind.name.take(1) }
        }
        assertEquals("RRNRRN", shape(4, 2)) // k = 3
        assertEquals("RRNRRNR", shape(5, 2)) // k = 3
        assertEquals("NNNR", shape(1, 3)) // k = max(1, 4 / 3) = 1
        assertEquals("RNRN", shape(2, 2)) // k = 2
        assertEquals("RRR", shape(3, 0))
        assertEquals("NN", shape(0, 2))
        assertEquals("RRRRNRRRRN", shape(8, 2)) // k = 5
    }

    @Test
    fun learningComesFirstThenInterleavedPart() {
        val c = listOf(review(1, past), card(2), learning(3, past))
        assertEquals("L3 R1 N2", build(input(c)).shape())
    }

    // ---- Day boundary (StudyDay input, CLAUDE.md rule 9) -------------------------------------------

    @Test
    fun dayBoundaryAroundLocalMidnightUsesStudyDayNotUtcDate() {
        // Tokyo, rollover 0: day 2024-01-02T00:00+09:00 .. 2024-01-03T00:00+09:00 = 2024-01-01T15:00Z .. 2024-01-02T15:00Z.
        val day = StudyDay(1_704_121_200_000L, 1_704_207_600_000L)
        val now = 1_704_121_200_000L + 60_000L // 00:01 local, still 2024-01-01 in UTC
        val c = listOf(
            review(1, 1_704_207_600_000L - 60_000L), // 23:59 local today, 2024-01-02 UTC
            review(2, 1_704_207_600_000L), // 00:00 local tomorrow
        )
        assertEquals(listOf(1), build(input(c, now = now, day = day)).cards())
    }

    @Test
    fun dstFallBackDayIs25HoursLong() {
        // America/New_York, rollover 4: 2024-11-02T04:00-04:00 (08:00Z) .. 2024-11-03T04:00-05:00 (09:00Z).
        val start = 1_730_534_400_000L
        val end = 1_730_624_400_000L
        assertEquals(25 * HOUR, end - start)
        val now = start + 20 * HOUR
        val c = listOf(review(1, start + 24 * HOUR + 30 * 60_000L), review(2, end))
        val q = build(input(c, now = now, day = StudyDay(start, end)))
        assertEquals(listOf(1), q.cards())
        val l = build(input(listOf(learning(3, end - 1)), now = now, day = StudyDay(start, end)))
        assertEquals(end - 1, l.nextLearningDueAt)
    }

    @Test
    fun dstSpringForwardDayIs23HoursLong() {
        // America/New_York, rollover 4: 2024-03-09T04:00-05:00 (09:00Z) .. 2024-03-10T04:00-04:00 (08:00Z).
        val start = 1_709_974_800_000L
        val end = 1_710_057_600_000L
        assertEquals(23 * HOUR, end - start)
        val now = start + 10 * HOUR
        val c = listOf(review(1, end - 1), review(2, start + 24 * HOUR - 1))
        assertEquals(listOf(1), build(input(c, now = now, day = StudyDay(start, end))).cards())
    }

    private companion object {
        const val DAY_LATER: Long = 86_400_000L
    }
}
