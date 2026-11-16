package kotobaya.queue

import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.WordStatus
import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.fsrs.ReviewType
import kotobaya.contracts.queue.AddKanjiMode
import kotobaya.contracts.queue.QueueInput
import kotobaya.contracts.queue.LoopPhase
import kotobaya.contracts.queue.QueueItemKind
import kotobaya.contracts.queue.ReviewQueue
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Determinism and invariants over random inputs. `kotlin.random.Random(seed)` is a common-code
 * XorWow, so the generated inputs are identical on jvm and js.
 */
class QueuePropertyTest {
    private val kanji = listOf("日", "本", "語", "学", "生")

    private fun randomInput(seed: Int): QueueInput {
        val r = Random(seed)
        val decks = (1..r.nextInt(0, 5)).map {
            deck(
                it,
                priority = r.nextInt(0, 3),
                enabled = r.nextInt(4) != 0,
                newPerDay = if (r.nextBoolean()) r.nextInt(0, 6) else null,
                reviewsPerDay = if (r.nextBoolean()) r.nextInt(0, 6) else null,
                addKanji = AddKanjiMode.entries[r.nextInt(3)],
            )
        }
        val states = CardState.entries
        val candidates = (1..r.nextInt(0, 40)).map { n ->
            val isKanji = r.nextInt(4) == 0
            val note = r.nextInt(1, 25)
            val state = states[r.nextInt(states.size)]
            card(
                n,
                note = note,
                kindOrder = r.nextInt(0, 3),
                base = if (isKanji) NoteBaseType.KANJI else NoteBaseType.VOCAB,
                created = r.nextLong(0, 10),
                state = state,
                due = when {
                    state == CardState.NEW -> null
                    // Minute-scale dues around now exercise the learn-ahead split (C-22).
                    r.nextInt(3) == 0 -> NOW + r.nextLong(-30, 30) * 60_000L
                    else -> NOW + r.nextLong(-30, 30) * HOUR
                },
                suspended = r.nextInt(10) == 0,
                buriedUntil = if (r.nextInt(10) == 0) NOW + r.nextLong(-2, 2) else null,
                override = if (r.nextInt(6) == 0) WordStatus.entries[r.nextInt(6)] else null,
                members = decks.filter { r.nextInt(3) == 0 }.map { member(it.id.takeLast(12).toInt(), r.nextLong(0, 5)) },
                kanji = if (isKanji) kanji[note % kanji.size] else null,
                exprKanji = if (isKanji) emptyList() else kanji.shuffled(r).take(r.nextInt(0, 3)),
            )
        }
        val today = (1..r.nextInt(0, 6)).map {
            today(
                100 + it,
                note = r.nextInt(1, 25),
                type = ReviewType.entries[r.nextInt(ReviewType.entries.size)],
                introducedNew = r.nextBoolean(),
                countsAsReview = r.nextBoolean(),
                decks = decks.filter { r.nextInt(3) == 0 }.map { d -> d.id.takeLast(12).toInt() },
            )
        }
        val pending = decks.flatMap { d ->
            (0 until r.nextInt(0, 3)).map { entry(d.id.takeLast(12).toInt(), r.nextLong(1, 8), r.nextLong(0, 5)) }
        }
        return input(
            candidates,
            decks = decks,
            limits = limits(
                newPerDay = r.nextInt(0, 12),
                reviewsPerDay = r.nextInt(0, 12),
                newPerSession = r.nextInt(0, 12),
                newShown = r.nextInt(0, 4),
                burySiblings = r.nextInt(4) != 0,
            ),
            today = today,
            pending = pending,
            decklessPriority = r.nextInt(-1, 3),
            decklessAddKanji = AddKanjiMode.entries[r.nextInt(3)],
        )
    }

    private fun shuffled(i: QueueInput, seed: Int): QueueInput {
        val r = Random(seed)
        return i.copy(
            decks = i.decks.shuffled(r),
            candidates = i.candidates.map { it.copy(memberships = it.memberships.shuffled(r)) }.shuffled(r),
            today = i.today.map { it.copy(deckIds = it.deckIds.shuffled(r)) }.shuffled(r),
            pendingEntries = i.pendingEntries.shuffled(r),
        )
    }

    @Test
    fun sameInputGivesIdenticalQueue() {
        for (seed in 0 until 300) {
            val i = randomInput(seed)
            assertEquals(build(i), build(randomInput(seed)), "seed $seed")
        }
    }

    @Test
    fun inputOrderNeverChangesTheQueue() {
        // The contract fixes every order by keys, so no permutation of the inputs may reorder the queue.
        for (seed in 0 until 300) {
            val i = randomInput(seed)
            val expected = build(i)
            for (s in 1..3) assertEquals(expected, build(shuffled(i, seed * 7 + s)), "seed $seed/$s")
        }
    }

    @Test
    fun invariantsHoldOnRandomInputs() {
        for (seed in 0 until 500) {
            val i = randomInput(seed)
            val q = build(i)
            val byId = i.candidates.associateBy { it.cardId }
            val decksById = i.decks.associateBy { it.id }
            assertEquals(q.items.size, q.items.map { it.cardId }.toSet().size, "duplicate card, seed $seed")
            val introducedToday = i.today.count { it.introducedNew }
            val newCount = q.items.count { it.kind == QueueItemKind.NEW } + q.toMaterialize.size
            assertTrue(
                // ADR-037: the day's remainder bounds the normal build; the session cap is gone.
                newCount <= maxOf(0, i.limits.newPerDay - introducedToday),
                "new budget, seed $seed",
            )
            assertTrue(
                q.items.count { it.kind == QueueItemKind.REVIEW } <= maxOf(0, i.limits.reviewsPerDay - i.today.count { it.countsAsReview }),
                "review budget, seed $seed",
            )
            // C-22: due learning first, then reviews/new, then learn-ahead learning.
            val phases = q.items.map { item ->
                val due = byId.getValue(item.cardId).dueAt
                when {
                    item.kind != QueueItemKind.LEARNING -> 1
                    due == null || due <= i.now -> 0
                    else -> {
                        assertTrue(due <= i.now + i.limits.learnAheadMs, "learning beyond learn-ahead, seed $seed")
                        2
                    }
                }
            }
            assertEquals(phases.sorted(), phases, "learning phase order, seed $seed")
            val notes = mutableSetOf<String>()
            for (item in q.items) {
                val c = byId.getValue(item.cardId)
                assertTrue(!c.suspended, "suspended, seed $seed")
                if (item.kind == QueueItemKind.NEW) {
                    assertEquals(CardState.NEW, c.state)
                    assertTrue(c.wordOverride != WordStatus.KNOWN, "known introduced, seed $seed")
                    val ms = c.memberships
                    // ADR-006: a card whose decks are all disabled is never introduced.
                    assertTrue(ms.isEmpty() || ms.any { decksById[it.deckId]?.enabled == true }, "disabled-only, seed $seed")
                }
                if (i.limits.burySiblings && item.kind != QueueItemKind.LEARNING) {
                    assertTrue(notes.add(c.noteId), "two NEW/REVIEW cards of one note, seed $seed: " + q.items.map { x -> x.kind.name + " " + byId.getValue(x.cardId).let { y -> y.cardId.takeLast(3) + "/" + y.noteId.takeLast(3) + "/" + y.state + "/" + y.noteBaseType + "/" + y.kanjiLiteral + "/" + y.expressionKanji } })
                }
            }
        }
    }

    // ---- ADR-037: ReviewLoop properties (P1) and the rule 5 mix (M4) ----

    /** [randomInput] plus the loop's knobs: both modes, both settings, a random batch and learn-ahead, answer times. */
    private fun randomLoopInput(seed: Int): QueueInput {
        val i = randomInput(seed)
        val r = Random(seed * 31 + 7)
        return i.copy(
            limits = i.limits.copy(
                keepGoing = r.nextBoolean(),
                reviewsUnlimited = r.nextBoolean(),
                keepGoingNewBatch = r.nextInt(-1, 6),
                learnAheadMs = if (r.nextBoolean()) 0L else r.nextLong(0, 40) * 60_000L,
            ),
            today = i.today.map { it.copy(reviewedAt = TOKYO_DAY.startMs + r.nextLong(0, 5)) },
        )
    }

    private fun ReviewQueue.nonEmpty(): Boolean = items.isNotEmpty() || toMaterialize.isNotEmpty()

    @Test
    fun loopCountsAreInvariantUnderKeepGoing() {
        for (seed in 0 until 400) {
            val i = randomLoopInput(seed)
            val off = DefaultReviewLoop.evaluate(i.copy(limits = i.limits.copy(keepGoing = false))).state
            val on = DefaultReviewLoop.evaluate(i.copy(limits = i.limits.copy(keepGoing = true))).state
            assertEquals(off.copy(phase = on.phase, keepGoing = true), on, "seed $seed")
        }
    }

    @Test
    fun loopInvariantsHoldOnRandomInputs() {
        for (seed in 0 until 500) {
            val i = randomLoopInput(seed)
            val r = DefaultReviewLoop.evaluate(i)
            val st = r.state
            val k = DefaultQueueBuilder.build(i.copy(limits = i.limits.copy(keepGoing = true, learnAheadMs = 0)))
            val byId = i.candidates.associateBy { it.cardId }
            assertEquals(i.limits.keepGoing, st.keepGoing, "seed $seed")
            for (n in listOf(st.readyLearning, st.readyReviews, st.laterToday, st.backlog, st.backlogBeyondLimit, st.reviewsToday, st.newToday, st.newGoal)) {
                assertTrue(n >= 0, "negative count, seed $seed")
            }
            assertTrue(st.backlog >= st.readyReviews, "backlog < ready, seed $seed")
            assertEquals(st.backlog - st.readyReviews, st.backlogBeyondLimit, "seed $seed")
            if (i.limits.reviewsUnlimited) {
                assertEquals(0, st.backlogBeyondLimit, "seed $seed")
                assertEquals(null, st.reviewsLimit, "seed $seed")
            }
            assertEquals(st.laterToday == 0, st.nextDueAt == null, "seed $seed")
            st.nextDueAt?.let { assertTrue(it > i.now && it < i.day.endMs, "nextDueAt, seed $seed") }
            assertEquals(r.queue.nonEmpty(), st.phase == LoopPhase.REVIEWING, "phase vs served queue, seed $seed")
            if (st.phase == LoopPhase.GOAL_REACHED || st.phase == LoopPhase.DONE_FOR_TODAY) {
                assertTrue(!st.keepGoing && k.nonEmpty(), "dead-end phase, seed $seed")
                assertEquals(i.limits.reviewsUnlimited, st.phase == LoopPhase.GOAL_REACHED, "seed $seed")
            }
            if (st.phase == LoopPhase.WAITING) assertTrue(!k.nonEmpty() && st.laterToday > 0, "seed $seed")
            for (item in r.queue.items) {
                if (item.kind != QueueItemKind.LEARNING) continue
                val due = byId.getValue(item.cardId).dueAt
                assertTrue(due == null || due <= i.now, "learning served early, seed $seed")
            }
            assertEquals(st.readyLearning, r.queue.items.count { it.kind == QueueItemKind.LEARNING }, "seed $seed")
            assertEquals(st.readyLearning + st.laterToday, st.legacy.learning, "seed $seed")
            assertEquals(st.readyReviews, st.legacy.review, "seed $seed")
        }
    }

    @Test
    fun loopResultIsIndependentOfInputOrder() {
        for (seed in 0 until 300) {
            val i = randomLoopInput(seed)
            val expected = DefaultReviewLoop.evaluate(i)
            for (s in 1..3) assertEquals(expected, DefaultReviewLoop.evaluate(shuffled(i, seed * 11 + s)), "seed $seed/$s")
        }
    }

    @Test
    fun mixNeverServesTwoNewCardsInARowWhileAReviewIsLeft() {
        for (seed in 0 until 500) {
            val i = randomLoopInput(seed)
            val q = DefaultReviewLoop.evaluate(i.copy(limits = i.limits.copy(keepGoing = false))).queue
            val kinds = q.items.map { it.kind }
            for (x in 0 until kinds.size - 1) {
                if (kinds[x] == QueueItemKind.NEW && kinds[x + 1] == QueueItemKind.NEW) {
                    assertTrue(QueueItemKind.REVIEW !in kinds.drop(x + 1), "N N before a review, seed $seed: $kinds")
                }
            }
        }
    }

    @Test
    fun mixDoesNotDependOnTheBatch() {
        // A smaller batch draws a prefix of the new cards; the order is the unbounded one without the others.
        for (seed in 0 until 300) {
            val i = randomLoopInput(seed)
            val l = i.limits.copy(keepGoing = false)
            val all = DefaultReviewLoop.evaluate(i.copy(limits = l.copy(keepGoingNewBatch = Int.MAX_VALUE))).queue
            for (b in 1..4) {
                val q = DefaultReviewLoop.evaluate(i.copy(limits = l.copy(keepGoingNewBatch = b))).queue
                val news = q.items.filter { it.kind == QueueItemKind.NEW }
                val allNews = all.items.filter { it.kind == QueueItemKind.NEW }
                assertEquals(allNews.take(news.size), news, "new prefix, seed $seed/$b")
                val extra = allNews.drop(news.size).toSet()
                assertEquals(all.items.filter { it !in extra }, q.items, "order, seed $seed/$b")
                assertEquals(all.toMaterialize.take(q.toMaterialize.size), q.toMaterialize, "entries, seed $seed/$b")
            }
        }
    }

    @Test
    fun answeringTheHeadAndRebuildingYieldsTheTail() {
        // M4: no learning, one card per note, so answering changes only what rule 5 counts.
        for (seed in 0 until 200) {
            val r = Random(seed)
            val reviews = (1..r.nextInt(0, 30)).map { review(it, NOW - r.nextLong(1, 50) * HOUR) }
            val news = (1001 until 1001 + r.nextInt(0, 30)).map { card(it, created = r.nextLong(0, 5)) }
            var t = TOKYO_DAY.startMs
            val today = (1..r.nextInt(0, 12)).map {
                val intro = r.nextBoolean()
                today(5000 + it, type = if (intro) ReviewType.LEARNING else ReviewType.SCHEDULED, introducedNew = intro, countsAsReview = !intro)
                    .copy(reviewedAt = ++t)
            }
            var cur = input(
                reviews + news,
                limits = limits(newPerDay = r.nextInt(0, 25), reviewsPerDay = r.nextInt(0, 40), learnAheadMs = 0)
                    .copy(reviewsUnlimited = r.nextBoolean(), keepGoingNewBatch = Int.MAX_VALUE),
                today = today,
            )
            var q = DefaultReviewLoop.evaluate(cur).queue
            while (q.items.isNotEmpty()) {
                val head = q.items.first()
                val c = cur.candidates.first { it.cardId == head.cardId }
                val intro = head.kind == QueueItemKind.NEW
                val row = today(0, type = if (intro) ReviewType.LEARNING else ReviewType.SCHEDULED, introducedNew = intro, countsAsReview = !intro)
                    .copy(cardId = c.cardId, noteId = c.noteId, reviewedAt = cur.now)
                cur = cur.copy(
                    now = cur.now + 1000,
                    // Answered: due after today, so neither an introduced card's step nor the review comes back in this run.
                    candidates = cur.candidates.map {
                        if (it.cardId != c.cardId) it else it.copy(state = if (intro) CardState.LEARNING else CardState.REVIEW, dueAt = TOKYO_DAY.endMs + HOUR)
                    },
                    today = cur.today + row,
                )
                val next = DefaultReviewLoop.evaluate(cur).queue
                assertEquals(q.items.drop(1), next.items, "seed $seed")
                q = next
            }
        }
    }
}
