package kotobaya.queue

import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.Uuid
import kotobaya.contracts.WordStatus
import kotobaya.contracts.cards.CardKinds
import kotobaya.contracts.fsrs.CardSchedule
import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.fsrs.RatedReview
import kotobaya.contracts.fsrs.Rating
import kotobaya.contracts.fsrs.ReviewType
import kotobaya.contracts.fsrs.SchedulerParams
import kotobaya.contracts.queue.LoopPhase
import kotobaya.contracts.queue.LoopPresentation
import kotobaya.contracts.queue.LoopPresenter
import kotobaya.contracts.queue.LoopResult
import kotobaya.contracts.queue.LoopScreen
import kotobaya.contracts.queue.LoopState
import kotobaya.contracts.queue.PendingAnswer
import kotobaya.contracts.queue.PendingEntry
import kotobaya.contracts.queue.QueueCandidate
import kotobaya.contracts.queue.QueueInput
import kotobaya.contracts.queue.QueueItem
import kotobaya.contracts.queue.QueueItemKind
import kotobaya.contracts.queue.QueueLimits
import kotobaya.contracts.queue.ReviewQueue
import kotobaya.contracts.queue.StudyDay
import kotobaya.contracts.queue.TodayReview
import kotobaya.contracts.queue.UntilStopParts
import kotobaya.fsrs.FsrsScheduler
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * C-31 (ADR-051): invariants I1 … I5, I7, I8 and I9 (ADR-054) of the `ReviewLoop` KDoc after every step of a
 * seeded random session ("Fuzz test shape"), and I6: the presenter fed what a client holds
 * (LOOP-31-CORE-B).
 *
 * A run: a random [QueueInput] (cards in all four states with dues before/after `now` and around
 * `day.endMs`; suspended, buried, retired and overridden cards; siblings; kanji notes, words that
 * require them, kanji to mint and pending entries; per-deck limits; limits incl. 0 and unlimited;
 * both toggles; keep going with a target null / ahead / reached / exceeded), then at most
 * [MAX_STEPS] steps, each one of: answer the served head through the real [FsrsScheduler] (after
 * materializing, as callers do), advance the clock by seconds, jump to the next learning due,
 * roll the study day over, press Keep going, end the visit, a pulled change (a review came due,
 * another device introduced a card, a card suspended), and for I6 "hold the answer back": the
 * client answers the next card it holds and keeps the answer as a [PendingAnswer] instead of
 * applying a new state (the truth, `input`, does change: the answer is in the outbox), or applies
 * a state that reflects only the oldest pending answers (a fetch sent before the rest were
 * acknowledged). Every other step first and last applies a fresh state (pending cleared). `kotlin.random.Random(seed)` is a common-code
 * XorWow, so a seed gives the same run on jvm and js. On failure the seed and the step prefix up to
 * the first violation are printed.
 */
class ReviewLoopInvariantFuzzTest {
    private val lits = listOf("日", "本", "語", "学", "校")
    private val params = SchedulerParams(
        weights = FsrsScheduler.defaultWeights,
        desiredRetention = 0.9,
        maximumIntervalDays = 36500,
        learningStepsSeconds = listOf(60, 600),
        relearningStepsSeconds = listOf(600),
        fuzz = false,
        easyDays = List(7) { 1.0 },
        weightsVersion = 0,
    )
    private val excludedOverrides = setOf(WordStatus.NEVER_FORGET, WordStatus.BLACKLISTED, WordStatus.IGNORED)

    private class Violation(val invariant: String, message: String) : Exception(message)

    /** One session: the input, the scheduler's view of every card and the step log. */
    private inner class World(val seed: Int, val presenter: LoopPresenter = DefaultLoopPresenter) {
        val r = Random(seed)
        lateinit var input: QueueInput

        /** Plain inputs (no kanji, no per-deck limits, no pending entries): the presenter's exact consistency check. */
        var plain = false

        // The client (I6): the state it applied, where that came from, the cards it holds and its pending answers.
        lateinit var shown: LoopState
        lateinit var shownInput: QueueInput
        val held = mutableListOf<QueueItem>()
        val pending = mutableListOf<PendingAnswer>()
        val pendingCards = mutableListOf<Uuid>()

        /** `input` after each pending answer (the state a fetch sent then would have returned). */
        val pendingSnaps = mutableListOf<QueueInput>()

        /**
         * True iff every pending answer's outcome is known to the client (the consistency bound applies).
         * Only held-back answers ever happen between [shownInput] and `input`: every other step applies a fresh state.
         */
        var allKnown = true
        val schedules = HashMap<Uuid, CardSchedule>()
        val log = mutableListOf<String>()
        val trace = mutableListOf<String>()
        var nextCard = 3000
        var nextEntry = 100L
        var nextOther = 9000
        var answers = 0

        fun generate() {
            val day = TOKYO_DAY
            plain = r.nextInt(4) == 0
            val now = if (r.nextInt(4) == 0) day.endMs - r.nextLong(1, 20) * 60_000L else day.startMs + r.nextLong(60, 1400) * 60_000L
            val decks = (1..r.nextInt(0, 4)).map {
                deck(
                    it,
                    priority = r.nextInt(0, 2),
                    enabled = r.nextInt(5) != 0,
                    newPerDay = if (!plain && r.nextInt(3) == 0) r.nextInt(0, 5) else null,
                    reviewsPerDay = if (!plain && r.nextInt(3) == 0) r.nextInt(0, 5) else null,
                )
            }
            fun members() = decks.filter { r.nextInt(3) == 0 }.map { member(it.id.takeLast(12).toInt(), r.nextLong(0, 10)) }
            val withNote = if (plain) emptySet() else lits.filter { r.nextBoolean() }.toSet()
            val cands = mutableListOf<QueueCandidate>()
            // Kanji notes of the built-in type: one card each (I8's documented overshoot needs several).
            for ((i, l) in withNote.withIndex()) {
                val st = if (r.nextInt(4) == 0) randomState(exceptNew = true) else CardState.NEW
                cands += card(100 + i, base = NoteBaseType.KANJI, kanji = l, created = r.nextLong(0, 10), state = st,
                    due = dueFor(st, now, day), members = members())
            }
            var prev: QueueCandidate? = null
            for (n in 1..r.nextInt(0, 18)) {
                val st = randomState(exceptNew = false)
                val p = prev
                val c = if (p != null && p.noteBaseType == NoteBaseType.VOCAB && r.nextInt(6) == 0) {
                    // A sibling: same note, next kind.
                    p.copy(cardId = cardId(n), kind = "k${p.kindOrder + 1}", kindOrder = p.kindOrder + 1, state = st, dueAt = dueFor(st, now, day))
                } else {
                    val ks = if (plain || r.nextBoolean()) emptyList() else lits.shuffled(r).take(r.nextInt(1, 3))
                    card(n, created = r.nextLong(0, 10), state = st, due = dueFor(st, now, day), members = members(),
                        exprKanji = ks, missing = ks.filter { it !in withNote }.map { mk(it) })
                }
                val decorated = when (r.nextInt(14)) {
                    0 -> c.copy(suspended = true)
                    1 -> c.copy(buriedUntil = now + r.nextLong(-2, 3) * HOUR)
                    2 -> c.copy(kind = CardKinds.READING) // retired (rule 0)
                    3 -> c.copy(wordOverride = WordStatus.entries[r.nextInt(WordStatus.entries.size)])
                    else -> c
                }
                cands += decorated
                prev = decorated
            }
            val pending = if (plain || decks.isEmpty()) emptyList() else (1..r.nextInt(0, 4)).map {
                val d = decks[r.nextInt(decks.size)].id.takeLast(12).toInt()
                val ks = lits.shuffled(r).take(r.nextInt(0, 3))
                entry(d, nextEntry++, r.nextLong(0, 10), exprKanji = ks, missing = ks.filter { it !in withNote }.map { mk(it) })
            }
            // Earlier today: reviews and introductions of cards that are not candidates (other notes).
            val rows = mutableListOf<TodayReview>()
            repeat(r.nextInt(0, 6)) { rows += otherRow(introduced = r.nextBoolean(), at = day.startMs + rows.size + 1) }
            val newToday = rows.count { it.introducedNew }
            val goal = listOf(0, 1, 2, 3, 5, 8, 20)[r.nextInt(7)]
            val keepGoing = r.nextInt(3) == 0
            val target = if (!keepGoing) null else randomTarget(newToday, goal)
            val limits = QueueLimits(
                goal, listOf(0, 1, 3, 10, 200)[r.nextInt(5)], 0, 0, r.nextInt(5) != 0, 0,
                keepGoing = keepGoing, keepGoingNewBatch = r.nextInt(1, 11), reviewsUnlimited = r.nextBoolean(),
                keepGoingNewTarget = target,
            )
            input = QueueInput(now, day, limits, decks, cands, rows, pending, decklessPriority = r.nextInt(0, 2))
            for (c in cands) schedules[c.cardId] = scheduleOf(c, now)
            log += "gen: now=${now - day.startMs}ms into day, goal=$goal reviews=${limits.reviewsPerDay} unlimited=${limits.reviewsUnlimited} " +
                "bury=${limits.burySiblings} batch=${limits.keepGoingNewBatch} keepGoing=$keepGoing target=$target newToday=$newToday " +
                "cards=${cands.size} pending=${pending.size} decks=${decks.size} plain=$plain"
            applyFresh()
        }

        private fun randomTarget(newToday: Int, goal: Int): Int? = when (r.nextInt(4)) {
            0 -> null
            1 -> newToday // reached
            2 -> newToday - r.nextInt(1, 3) // exceeded
            else -> newToday + maxOf(1, goal)
        }

        private fun randomState(exceptNew: Boolean): CardState {
            val x = r.nextInt(if (exceptNew) 6 else 10)
            return when {
                x < 3 -> CardState.REVIEW
                x < 5 -> CardState.LEARNING
                x < 6 -> CardState.RELEARNING
                else -> CardState.NEW
            }
        }

        private fun dueFor(st: CardState, now: Long, day: StudyDay): Long? = when (st) {
            CardState.NEW -> null
            CardState.REVIEW -> when (r.nextInt(4)) {
                0 -> now - r.nextLong(1, 72) * HOUR
                1 -> now + r.nextLong(1, 600) * 1000L // later today, still due by day granularity if before the end
                2 -> day.endMs + r.nextLong(0, 3) * HOUR // tomorrow
                else -> day.endMs - 1
            }
            else -> when (r.nextInt(6)) {
                0 -> now - r.nextLong(0, 600) * 1000L
                1 -> now
                2 -> now + r.nextLong(1, 900) * 1000L
                3 -> day.endMs - r.nextLong(1, 120) * 1000L
                4 -> day.endMs + r.nextLong(0, 600) * 1000L
                else -> if (r.nextInt(4) == 0) null else now + r.nextLong(1, 60) * 1000L
            }
        }

        private fun scheduleOf(c: QueueCandidate, now: Long): CardSchedule = when (c.state) {
            CardState.NEW -> CardSchedule.NEW
            CardState.REVIEW -> CardSchedule(CardState.REVIEW, null, 3.0 + r.nextInt(30), 5.0, c.dueAt, 3, (c.dueAt ?: now) - 3 * DAY_MS, 3, 0)
            CardState.LEARNING -> CardSchedule(CardState.LEARNING, r.nextInt(0, 2), 1.0, 5.0, c.dueAt, 0, now - 10 * 60_000L, 1, 0)
            CardState.RELEARNING -> CardSchedule(CardState.RELEARNING, 0, 2.0, 6.0, c.dueAt, 0, now - 10 * 60_000L, 5, 1)
        }

        private fun otherRow(introduced: Boolean, at: Long): TodayReview {
            val n = nextOther++
            return TodayReview(cardId(n), noteId(n), if (introduced) ReviewType.LEARNING else ReviewType.SCHEDULED, introduced, !introduced, emptyList(), at)
        }

        fun eval(i: QueueInput = input): LoopResult = DefaultReviewLoop.evaluate(i)

        /** The client applies a state evaluated from the current truth: nothing is pending any more. */
        fun applyFresh() {
            val res = eval()
            shown = res.state
            shownInput = input
            held.clear()
            held += res.queue.items
            pending.clear()
            pendingCards.clear()
            pendingSnaps.clear()
            allKnown = true
        }

        /** What [presenter] makes of the client's view. */
        fun present(): LoopPresentation =
            presenter.present(shown, pending.toList(), held.isNotEmpty(), held.firstOrNull()?.kind, shownInput.day.endMs)

        /** One random step [k] (I7 is checked inside an answer, at the same `now`). */
        fun step(k: Int) {
            val x = r.nextInt(100)
            if (x < 20) {
                holdBack(k)
                return
            }
            if (pending.isNotEmpty()) applyFresh()
            val before = eval()
            when (x) {
                in 20 until 50 -> answerOrMaterialize(k, before)
                in 50 until 60 -> tick(r.nextLong(1, 400) * 1000L, "tick")
                in 60 until 72 -> {
                    val next = before.state.nextDueAt
                    if (next != null) {
                        setNow(next, "to next due")
                    } else {
                        // The next learning due of any day (may roll the day over).
                        val anyNext = input.candidates.filter { it.state == CardState.LEARNING || it.state == CardState.RELEARNING }
                            .mapNotNull { it.dueAt }.filter { it > input.now }.minOrNull()
                        if (anyNext != null) setNow(anyNext, "to next learning due") else tick(60_000L, "tick (no learning due)")
                    }
                }
                in 72 until 76 -> setNow(input.day.endMs + r.nextLong(0, 3) * HOUR + 1000L, "past day end")
                in 76 until 86 -> {
                    val s = before.state
                    val t = if (r.nextInt(6) == 0) null else s.newToday + maxOf(1, s.newGoal)
                    input = input.copy(limits = input.limits.copy(keepGoing = true, keepGoingNewTarget = t))
                    log += "#$k press keep going target=$t (newToday=${s.newToday}, keepGoingAvailable=${s.keepGoingAvailable}, phase=${s.phase})"
                }
                in 86 until 91 -> {
                    // Sometimes a sloppy client keeps the stale target: a normal build must ignore it (I8).
                    val stale = if (r.nextInt(3) == 0) input.limits.keepGoingNewTarget else null
                    input = input.copy(limits = input.limits.copy(keepGoing = false, keepGoingNewTarget = stale))
                    log += "#$k end visit (stale target $stale)"
                }
                else -> pull(k, x)
            }
            applyFresh()
        }

        /** I6 steps: answer the next held card and keep the answer pending, or apply a stale state. */
        private fun holdBack(k: Int) {
            if (pending.isNotEmpty() && r.nextInt(4) == 0) {
                // A fetch sent after the first j answers were acknowledged, before the rest.
                val j = r.nextInt(1, pending.size + 1)
                val snap = pendingSnaps[j - 1]
                val res = eval(snap)
                val rest = pendingCards.drop(j)
                shown = res.state
                shownInput = snap
                held.clear()
                held += res.queue.items.filter { it.cardId !in rest }
                repeat(j) {
                    pending.removeAt(0)
                    pendingCards.removeAt(0)
                    pendingSnaps.removeAt(0)
                }
                allKnown = pending.all { it.resultingState != null }
                log += "#$k apply the state after $j of ${j + rest.size} pending answers (${res.state.phase}, ${held.size} held)"
                return
            }
            val head = held.firstOrNull()
            if (head == null) {
                // Nothing held: the client fetches (and, with nothing pending, answers from the fresh queue).
                if (pending.isNotEmpty()) {
                    applyFresh()
                    log += "#$k nothing held: fetch"
                } else {
                    answerOrMaterialize(k, eval())
                    applyFresh()
                }
                return
            }
            held.removeAt(0)
            val c = input.candidates.first { it.cardId == head.cardId }
            val rating = Rating.entries[r.nextInt(4)]
            val now = input.now
            val after = applyAnswer(c, rating)
            input = input.copy(now = now + 1000L)
            // Sometimes the browser is offline and has no preview: the outcome is unknown to the client.
            val unknown = r.nextInt(5) == 0
            pending += PendingAnswer(head.kind, rating.value, if (unknown) null else after.state, if (unknown) null else after.dueAt)
            pendingCards += c.cardId
            pendingSnaps += input
            if (unknown) allKnown = false
            log += "#$k hold back ${head.kind} ${c.cardId.takeLast(4)} $rating -> ${after.state} due ${after.dueAt?.let { it - now }}ms" +
                "${if (unknown) " (unknown to the client)" else ""}, ${pending.size} pending, ${held.size} held"
        }

        private fun pull(k: Int, x: Int) {
            when (x % 3) {
                0 -> {
                    // A review answered elsewhere is now due (pulled).
                    val c = input.candidates.filter { it.state == CardState.REVIEW && (it.dueAt ?: 0) >= input.day.endMs }.randomOrNull(r)
                    if (c == null) {
                        log += "#$k pull: nothing"
                        return
                    }
                    replace(c.copy(dueAt = input.now - 1000L))
                    schedules[c.cardId] = schedules.getValue(c.cardId).copy(dueAt = input.now - 1000L)
                    log += "#$k pull: review ${c.cardId.takeLast(4)} came due"
                }
                1 -> {
                    input = input.copy(today = input.today + otherRow(introduced = true, at = input.now))
                    input = input.copy(now = input.now + 1000L)
                    log += "#$k pull: another device introduced a card"
                }
                else -> {
                    val c = input.candidates.randomOrNull(r) ?: return
                    replace(c.copy(suspended = !c.suspended))
                    log += "#$k pull: ${c.cardId.takeLast(4)} suspended=${!c.suspended}"
                }
            }
        }

        private fun replace(c: QueueCandidate) {
            input = input.copy(candidates = input.candidates.map { if (it.cardId == c.cardId) c else it })
        }

        private fun tick(ms: Long, what: String) = setNow(input.now + ms, what)

        private fun setNow(t: Long, what: String) {
            var day = input.day
            var rolled = false
            while (t >= day.endMs) {
                day = StudyDay(day.endMs, day.endMs + DAY_MS)
                rolled = true
            }
            input = if (rolled) {
                // A new study day: nothing carries over, the visit (flag and target) ends.
                input.copy(now = t, day = day, today = emptyList(), limits = input.limits.copy(keepGoing = false, keepGoingNewTarget = null))
            } else {
                input.copy(now = t)
            }
            log += "${what} -> now=${t - day.startMs}ms into day${if (rolled) " (rolled over)" else ""}"
        }

        private fun answerOrMaterialize(k: Int, before: LoopResult) {
            val q = before.queue
            if (q.toMaterialize.isNotEmpty() || q.kanjiToMaterialize.isNotEmpty()) {
                materialize(q)
                log += "#$k materialize kanji=${q.kanjiToMaterialize} entries=${q.toMaterialize.map { it.entrySeq }}"
                return
            }
            val head = q.items.firstOrNull()
            if (head == null) {
                log += "#$k answer: nothing served (${before.state.phase})"
                return
            }
            val rating = Rating.entries[r.nextInt(4)]
            val c = input.candidates.first { it.cardId == head.cardId }
            val now = input.now
            val after = applyAnswer(c, rating)
            log += "#$k answer ${head.kind} ${c.cardId.takeLast(4)} $rating -> ${after.state} due ${after.dueAt?.let { it - now }}ms"
            // I7: same `now`, same limits; not for keep going without a target.
            val lim = input.limits
            if (!lim.keepGoing || lim.keepGoingNewTarget != null) {
                val res = eval()
                trace += summary(res)
                checkAll(input, res)
                if (res.state.untilStop > before.state.untilStop + 1) {
                    throw Violation("I7", "untilStop ${before.state.untilStop} -> ${res.state.untilStop} after answering the head")
                }
            }
            input = input.copy(now = now + 1000L)
        }

        /** Answers [c] with [rating] at `now` through the real scheduler and records it in the truth. */
        private fun applyAnswer(c: QueueCandidate, rating: Rating): CardSchedule {
            val sched = schedules.getValue(c.cardId)
            val now = input.now
            val after = FsrsScheduler.review(sched, RatedReview("fuzz-$seed-${answers++}", now, rating, 0), params)
            schedules[c.cardId] = after
            val row = TodayReview(
                c.cardId, c.noteId, if (sched.state == CardState.REVIEW) ReviewType.SCHEDULED else ReviewType.LEARNING,
                introducedNew = sched.state == CardState.NEW, countsAsReview = sched.state == CardState.REVIEW,
                deckIds = c.memberships.map { it.deckId }, reviewedAt = now,
            )
            input = input.copy(
                candidates = input.candidates.map { if (it.cardId == c.cardId) it.copy(state = after.state, dueAt = after.dueAt) else it },
                today = input.today + row,
            )
            return after
        }

        /** C-27 materialization as QueueSim does: deckless kanji notes, then entry notes in their deck. */
        private fun materialize(q: ReviewQueue) {
            val now = input.now
            val newCards = mutableListOf<QueueCandidate>()
            val minted = q.kanjiToMaterialize.toSet()
            for (l in q.kanjiToMaterialize) {
                val n = nextCard++
                newCards += card(n, base = NoteBaseType.KANJI, kanji = l, created = now)
            }
            for (e in q.toMaterialize) {
                val n = nextCard++
                newCards += card(
                    n, created = now, members = listOf(DeckMemberships.of(e.deckId, e.position, e.itemKey)),
                    exprKanji = e.expressionKanji, req = e.requiredKanji, missing = e.missingKanji.filter { it.literal !in minted },
                )
            }
            val done = q.toMaterialize.map { it.entrySeq }.toSet()
            input = input.copy(
                candidates = input.candidates.map { c -> c.copy(missingKanji = c.missingKanji.filter { it.literal !in minted }) } + newCards,
                pendingEntries = input.pendingEntries.filter { it.entrySeq !in done }
                    .map { e: PendingEntry -> e.copy(missingKanji = e.missingKanji.filter { it.literal !in minted }) },
            )
            for (c in newCards) schedules[c.cardId] = CardSchedule.NEW
        }
    }

    private fun ReviewQueue.nonEmpty(): Boolean = items.isNotEmpty() || toMaterialize.isNotEmpty() || kanjiToMaterialize.isNotEmpty()

    private fun LoopState.counts() =
        copy(phase = LoopPhase.EMPTY, keepGoing = false, untilStop = 0, keepGoingAvailable = false, untilStopParts = UntilStopParts())

    private fun summary(r: LoopResult): String = with(r.state) {
        "$phase kg=$keepGoing kga=$keepGoingAvailable until=$untilStop parts=$untilStopParts head=${r.queue.items.firstOrNull()?.cardId?.takeLast(4)}"
    }

    private fun eligibleLearning(i: QueueInput, c: QueueCandidate): Boolean {
        if (c.state != CardState.LEARNING && c.state != CardState.RELEARNING) return false
        if (CardKinds.isRetired(c.noteBaseType, c.kind, i.readingEnabled)) return false
        if (c.suspended) return false
        val bu = c.buriedUntil
        if (bu != null && bu > i.now) return false
        return c.wordOverride !in excludedOverrides
    }

    private fun must(invariant: String, ok: Boolean, msg: () -> String) {
        if (!ok) throw Violation(invariant, msg())
    }

    /** I1 … I5 and I8 for one evaluation [res] of [i]. */
    private fun checkAll(i: QueueInput, res: LoopResult) {
        val s = res.state
        val q = res.queue
        val p = s.untilStopParts
        val terminal = s.phase == LoopPhase.GOAL_REACHED || s.phase == LoopPhase.DONE_FOR_TODAY || s.phase == LoopPhase.EMPTY
        val kInf = DefaultQueueBuilder.build(i.copy(limits = i.limits.copy(learnAheadMs = 0, keepGoing = true, keepGoingNewTarget = null))).nonEmpty()

        // I1
        must("I1", s.readyLearning + s.readyReviews == 0 || (s.phase == LoopPhase.REVIEWING && q.items.isNotEmpty())) {
            "ready ${s.readyLearning}+${s.readyReviews} but phase ${s.phase}, ${q.items.size} items"
        }
        must("I1", (s.phase == LoopPhase.REVIEWING) == q.nonEmpty()) { "phase ${s.phase} with served non-empty=${q.nonEmpty()}" }
        // I2
        if (terminal) {
            must("I2", s.readyLearning == 0 && s.readyReviews == 0 && s.laterToday == 0 && s.untilStop == 0 &&
                p.learning == 0 && p.later == 0 && p.review == 0 && p.new == 0) { "terminal ${s.phase} with $s" }
        }
        // I3
        must("I3", p.learning + p.review + p.new == s.untilStop) { "parts $p: learning + review + new != untilStop ${s.untilStop}" }
        must("I3", p.learning >= 0 && p.later >= 0 && p.review >= 0 && p.new >= 0) { "negative part $p" }
        must("I3", p.learning == s.readyLearning && p.later == s.laterToday) { "parts $p vs ready ${s.readyLearning} later ${s.laterToday}" }
        // I9 (ADR-054): the total counts only what is doable now.
        must("I9", (s.untilStop > 0) == (s.phase == LoopPhase.REVIEWING)) { "untilStop ${s.untilStop} on phase ${s.phase} ($p)" }
        must("I9", (s.untilStop > 0) == q.nonEmpty()) { "untilStop ${s.untilStop} but served non-empty=${q.nonEmpty()} ($p)" }
        if (s.untilStop == 0) {
            must("I9", s.phase == LoopPhase.WAITING || terminal) { "untilStop 0 on phase ${s.phase}" }
        }
        // I4
        when (s.phase) {
            LoopPhase.EMPTY -> must("I4", !kInf && !s.keepGoingAvailable) { "EMPTY with K∞ non-empty=$kInf, keepGoingAvailable=${s.keepGoingAvailable}" }
            LoopPhase.REVIEWING -> must("I4", !s.keepGoingAvailable) { "REVIEWING with keepGoingAvailable" }
            else -> must("I4", s.keepGoingAvailable == kInf) { "${s.phase}: keepGoingAvailable=${s.keepGoingAvailable} but K∞ non-empty=$kInf" }
        }
        if (s.phase == LoopPhase.GOAL_REACHED || s.phase == LoopPhase.DONE_FOR_TODAY) {
            must("I4", s.keepGoingAvailable) { "${s.phase} without keepGoingAvailable" }
        }
        if (s.keepGoingAvailable) {
            val press = DefaultReviewLoop.evaluate(
                i.copy(limits = i.limits.copy(keepGoing = true, keepGoingNewTarget = s.newToday + maxOf(1, s.newGoal))),
            )
            must("I4", press.state.phase == LoopPhase.REVIEWING) { "dead end: pressing Keep going gives ${press.state.phase}" }
        }
        // I5
        val byId = i.candidates.associateBy { it.cardId }
        val learningPrefix = q.items.takeWhile { it.kind == QueueItemKind.LEARNING }.size
        must("I5", q.items.drop(learningPrefix).none { it.kind == QueueItemKind.LEARNING }) { "a LEARNING item after a non-learning item: ${q.shape()}" }
        for (it in q.items.filter { it.kind == QueueItemKind.LEARNING }) {
            val due = byId[it.cardId]?.dueAt
            must("I5", due == null || due <= i.now) { "LEARNING ${it.cardId.takeLast(4)} served ${due!! - i.now}ms early" }
        }
        if (s.readyLearning > 0) {
            val expected = i.candidates.filter { eligibleLearning(i, it) && (it.dueAt ?: Long.MIN_VALUE) <= i.now }
                .minWithOrNull(compareBy({ it.dueAt ?: Long.MIN_VALUE }, { it.cardId }))
            must("I5", q.items.firstOrNull()?.cardId == expected?.cardId) {
                "head ${q.items.firstOrNull()?.cardId?.takeLast(4)} is not the earliest due learning card ${expected?.cardId?.takeLast(4)}"
            }
        }
        if (i.limits.keepGoing) {
            val lastReview = q.items.indexOfLast { it.kind == QueueItemKind.REVIEW }
            val newBefore = (learningPrefix until maxOf(learningPrefix, lastReview)).filter { q.items[it].kind == QueueItemKind.NEW }
            if (newBefore.isNotEmpty()) {
                // Only the open group (rule 5 "Open group first"): right after the learning items, right
                // after a kanji was introduced (s == 0), at most one word's unit.
                val intros = i.today.filter { it.introducedNew }
                val last = intros.maxWithOrNull(compareBy({ it.reviewedAt }, { it.cardId }))
                val sinceLast = last != null && i.today.any { it.countsAsReview && it.reviewedAt > last.reviewedAt }
                val lastIsKanji = last != null && byId[last.cardId]?.noteBaseType == NoteBaseType.KANJI
                val contiguous = newBefore == (learningPrefix until learningPrefix + newBefore.size).toList()
                must("I5", lastIsKanji && !sinceLast && contiguous && newBefore.size <= 1 + lits.size) {
                    "keep going: NEW before a REVIEW outside an open group: ${q.shape()}"
                }
            }
        }
        // `LoopState`: every count but the mode's own fields is identical with and without keep going.
        if (i.limits.keepGoing || i.limits.keepGoingNewTarget != null) {
            val normal = DefaultReviewLoop.evaluate(i.copy(limits = i.limits.copy(keepGoing = false, keepGoingNewTarget = null))).state
            must("Counts", s.counts() == normal.counts()) { "counts differ from the normal evaluation: ${s.counts()} vs ${normal.counts()}" }
        }
        // I8
        if (!i.limits.keepGoing) {
            val bound = maxOf(0, s.newGoal - s.newToday)
            val news = q.items.count { it.kind == QueueItemKind.NEW } + q.toMaterialize.size + q.kanjiToMaterialize.size
            must("I8", news <= bound) { "normal build serves $news new (${q.shape()}, mint ${q.kanjiToMaterialize}) > max(0, goal - newToday) = $bound" }
            must("I8", p.new <= bound) { "parts.new ${p.new} > $bound" }
        }
        // Rule 6 "Target": keep going with a target draws and counts at most `max(0, T − newToday)` new
        // cards (one-card kanji parts, so the first-draw exception never overshoots).
        val target = i.limits.keepGoingNewTarget
        if (i.limits.keepGoing && target != null) {
            val bound = maxOf(0, target - s.newToday)
            val news = q.items.count { it.kind == QueueItemKind.NEW } + q.toMaterialize.size + q.kanjiToMaterialize.size
            must("Target", news <= bound) { "keep going with target $target serves $news new (${q.shape()}) > targetRemaining $bound" }
            must("Target", p.new <= bound) { "keep going with target $target: parts.new ${p.new} > targetRemaining $bound" }
        }
    }

    private val terminalScreens = setOf(LoopScreen.GOAL_REACHED, LoopScreen.DONE_FOR_TODAY, LoopScreen.EMPTY)

    private fun screenOf(phase: LoopPhase): LoopScreen = when (phase) {
        LoopPhase.REVIEWING -> LoopScreen.SETTLING
        LoopPhase.WAITING -> LoopScreen.WAITING
        LoopPhase.GOAL_REACHED -> LoopScreen.GOAL_REACHED
        LoopPhase.DONE_FOR_TODAY -> LoopScreen.DONE_FOR_TODAY
        LoopPhase.EMPTY -> LoopScreen.EMPTY
    }

    /** I6: the presenter's P-I1 … P-I4 and its consistency with the loop, for the client's view in [w]. */
    private fun checkPresenter(w: World) {
        val pres = w.present()
        val p = pres.parts
        val s = w.shown
        val hasLocal = w.held.isNotEmpty()
        val head = w.held.firstOrNull()?.kind
        val ctx = {
            "screen ${pres.screen} parts $p untilStop ${pres.untilStop}; shown ${s.phase} ${s.untilStopParts}; " +
                "${w.pending.size} pending ${w.pending.map { "${it.kind}/${it.rating}/${it.resultingState}" }}; ${w.held.size} held (head $head)"
        }
        // P-I1
        if (pres.screen in terminalScreens) {
            must("I6", w.pending.isEmpty() && !hasLocal && p == UntilStopParts()) { "P-I1 terminal screen: ${ctx()}" }
        }
        // P-I2
        if (p.learning + p.review + p.new > 0) {
            must("I6", pres.screen == LoopScreen.CARD || pres.screen == LoopScreen.SETTLING) { "P-I2 cards left on a ${pres.screen} screen: ${ctx()}" }
        }
        // P-I3
        must("I6", p.learning >= 0 && p.later >= 0 && p.review >= 0 && p.new >= 0) { "P-I3 negative part: ${ctx()}" }
        must("I6", p.learning + p.review + p.new == pres.untilStop) { "P-I3 untilStop is not learning + review + new: ${ctx()}" }
        if (pres.screen == LoopScreen.CARD) must("I6", pres.untilStop >= 1) { "P-I3 CARD with untilStop 0: ${ctx()}" }
        if (hasLocal) {
            val part = when (head) {
                QueueItemKind.NEW -> p.new
                QueueItemKind.LEARNING -> p.learning
                else -> p.review
            }
            must("I6", pres.screen == LoopScreen.CARD && part >= 1) { "held card not shown or not counted: ${ctx()}" }
        }
        // P-I4
        if (w.pending.isEmpty() && !hasLocal) {
            must("I6", p == s.untilStopParts && pres.screen == screenOf(s.phase)) { "P-I4 presenter added something: ${ctx()}" }
            // P-I5 with nothing pending and nothing held: a total iff the loop serves a card.
            must("I6", (pres.untilStop > 0) == (s.phase == LoopPhase.REVIEWING)) { "P-I5 untilStop vs phase ${s.phase}: ${ctx()}" }
        }
        // P-I5 (ADR-054): a total only on a card or settling screen; waiting and end screens show 0.
        if (pres.untilStop > 0) {
            must("I6", pres.screen == LoopScreen.CARD || pres.screen == LoopScreen.SETTLING) { "P-I5 untilStop > 0 on ${pres.screen}: ${ctx()}" }
        }
        if (pres.screen == LoopScreen.WAITING || pres.screen in terminalScreens) {
            must("I6", pres.untilStop == 0) { "P-I5 ${pres.screen} with untilStop ${pres.untilStop}: ${ctx()}" }
        }
        // Consistency with the loop: state' = the truth (every pending answer applied, same limits).
        val lim = w.input.limits
        if (w.pending.isNotEmpty() && w.allKnown && (!lim.keepGoing || lim.keepGoingNewTarget != null)) {
            val truth = w.eval().state
            val tp = truth.untilStopParts
            must("I6", kotlin.math.abs(pres.untilStop - truth.untilStop) <= w.pending.size) {
                "consistency: untilStop ${pres.untilStop} vs re-evaluated ${truth.untilStop} ($tp) with ${w.pending.size} pending: ${ctx()}"
            }
            // Exact only without sibling burying: the first answer today on a LEARNING card buries its note's
            // REVIEW/NEW sibling that the state counted, which no PendingAnswer can tell (CONTRACT_CHANGE_REQUEST,
            // LOOP-31-CORE-B HANDOFF; seed 171 of the first run). The bound above covers it.
            if (w.plain && !w.input.limits.burySiblings) {
                must("I6", p.review == tp.review && p.new == tp.new && p.learning + p.later == tp.learning + tp.later) {
                    "plain consistency: $p vs re-evaluated $tp: ${ctx()}"
                }
                // ADR-054: equal totals when no learning card came due since the shown state (a returning
                // answer due again already, or a `later` card whose timer ran out while answers were held back,
                // is `learning` in the truth but `later` in the projection; the presenter has no clock).
                val now = w.input.now
                val noneDueYet = w.pending.all { a ->
                    val st = a.resultingState
                    val returning = (st == CardState.LEARNING || st == CardState.RELEARNING) && (a.dueAt ?: Long.MIN_VALUE) < w.shownInput.day.endMs
                    !returning || (a.dueAt ?: Long.MIN_VALUE) > now
                } && w.input.candidates.none { c ->
                    (c.state == CardState.LEARNING || c.state == CardState.RELEARNING) &&
                        (c.dueAt ?: Long.MIN_VALUE) > w.shownInput.now && (c.dueAt ?: Long.MIN_VALUE) <= now
                }
                if (noneDueYet) {
                    must("I6", pres.untilStop == truth.untilStop) { "plain consistency: untilStop ${pres.untilStop} vs re-evaluated ${truth.untilStop}: ${ctx()}" }
                }
                // ADR-054: the header total never stays above 0 when the next applied state waits or ends.
                if (truth.phase != LoopPhase.REVIEWING) {
                    must("I6", pres.untilStop == 0) {
                        "header total ${pres.untilStop} while the applied state is ${truth.phase} ($tp): ${ctx()}"
                    }
                }
            }
        }
    }

    /** Runs [seed]; returns null when green, else the violated invariant and a report. */
    private fun runSeed(seed: Int, steps: Int = MAX_STEPS, presenter: LoopPresenter = DefaultLoopPresenter): Pair<String, String>? {
        val w = World(seed, presenter)
        w.generate()
        try {
            checkAll(w.input, w.eval().also { w.trace += summary(it) })
            checkPresenter(w)
            for (k in 0 until steps) {
                w.step(k)
                val res = w.eval()
                w.trace += summary(res)
                checkAll(w.input, res)
                checkPresenter(w)
            }
        } catch (v: Violation) {
            val report = "seed=$seed ${v.invariant}: ${v.message}\n  minimal failing prefix (${w.log.size} steps):\n    " +
                w.log.joinToString("\n    ") + "\n  last state: ${w.trace.lastOrNull()}"
            return v.invariant to report
        }
        return null
    }

    @Test
    fun invariantsHoldAfterEveryStep() {
        val failures = mutableListOf<Pair<String, String>>()
        for (seed in 1..RUNS) runSeed(seed)?.let { failures += it }
        if (failures.isNotEmpty()) {
            val byInvariant = failures.groupBy({ it.first }, { it.second })
            val counts = byInvariant.entries.sortedBy { it.key }.joinToString { "${it.key}: ${it.value.size} seeds" }
            val firsts = byInvariant.entries.sortedBy { it.key }.joinToString("\n") { it.value.first() }
            println("ReviewLoopInvariantFuzzTest: $counts\n$firsts")
            fail("${failures.size}/$RUNS runs violate an invariant ($counts)\n$firsts")
        }
    }

    /**
     * The pre-C-31 client: screen from `state.phase` (a held card wins), header = `state.untilStopParts`
     * frozen until the next state. I6 must catch it (the reviewer's "header shows reviews left, then
     * the done screen appears").
     */
    private object NaivePresenter : LoopPresenter {
        override fun present(
            state: LoopState,
            pending: List<PendingAnswer>,
            hasLocalCard: Boolean,
            localHeadKind: QueueItemKind?,
            dayEndsAt: Long,
        ): LoopPresentation {
            val screen = if (hasLocalCard) {
                LoopScreen.CARD
            } else {
                when (state.phase) {
                    LoopPhase.REVIEWING -> LoopScreen.SETTLING
                    LoopPhase.WAITING -> LoopScreen.WAITING
                    LoopPhase.GOAL_REACHED -> LoopScreen.GOAL_REACHED
                    LoopPhase.DONE_FOR_TODAY -> LoopScreen.DONE_FOR_TODAY
                    LoopPhase.EMPTY -> LoopScreen.EMPTY
                }
            }
            val p = state.untilStopParts
            return LoopPresentation(screen, p, p.learning + p.review + p.new)
        }
    }

    @Test
    fun i6CatchesANaivePresenter() {
        val failures = (1..NAIVE_RUNS).mapNotNull { runSeed(it, presenter = NaivePresenter) }
        val i6 = failures.filter { it.first == "I6" }
        val byCheck = i6.groupingBy { it.second.substringAfter("I6: ").substringBefore(":") }.eachCount()
        println("ReviewLoopInvariantFuzzTest: the naive presenter violates I6 in ${i6.size}/$NAIVE_RUNS runs $byCheck" +
            (i6.firstOrNull()?.let { "; first:\n${it.second}" } ?: ""))
        assertTrue(i6.isNotEmpty(), "I6 does not catch a presenter that ignores pending answers")
        assertEquals(failures.size, i6.size, "only I6 may fail: ${failures.filter { it.first != "I6" }.map { it.second }}")
    }

    @Test
    fun aSeedAlwaysPlaysTheSameSession() {
        for (seed in listOf(1, 7, 42, 1234)) {
            val a = World(seed).apply { generate(); repeat(MAX_STEPS) { k -> runCatching { step(k) }; trace += summary(eval()) } }
            val b = World(seed).apply { generate(); repeat(MAX_STEPS) { k -> runCatching { step(k) }; trace += summary(eval()) } }
            assertEquals(a.log, b.log, "seed $seed")
            assertEquals(a.trace, b.trace, "seed $seed")
        }
    }

    private companion object {
        const val MAX_STEPS: Int = 60

        /** `Double.toString` drops ".0" only on js: 2 000 runs on jvm, 200 on js (slow Longs). */
        val RUNS: Int = if (1.0.toString() == "1") 200 else 2000

        /** Runs of [i6CatchesANaivePresenter]. */
        const val NAIVE_RUNS: Int = 200
    }
}
