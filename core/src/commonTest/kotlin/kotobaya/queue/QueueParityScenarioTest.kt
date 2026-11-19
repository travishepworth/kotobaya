package kotobaya.queue

import kotobaya.cards.DefaultKanjiRequirement
import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.cards.KanjiFacts
import kotobaya.contracts.cards.WordForm
import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.fsrs.ReviewType
import kotobaya.contracts.queue.LoopPhase
import kotobaya.contracts.queue.QueueCandidate
import kotobaya.contracts.queue.QueueLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CORE-28: the core half of the C-28 / C-28b parity scenarios (`contracts/api/shell/parity/queue-kanji-*.json`,
 * `queue-until-stop.json`). Core has no parity runner, so each scenario's `expect` on the queue (items in
 * order, `to_materialize`, `loop.phase`, `loop.until_stop`, ready / later / new counts) is replayed here
 * through [DefaultReviewLoop] with the input a loader builds from the scenario's seed. Kanji notes the
 * fetch mints are materialized by [QueueSim] (deckless, created at the fetch), as the server does.
 */
class QueueParityScenarioTest {
    private fun limits(newPerDay: Int = 20, batch: Int = 20): QueueLimits =
        QueueLimits(newPerDay, 200, 0, 0, true, 0, keepGoing = false, keepGoingNewBatch = batch)

    private fun kanji(n: Int, literal: String, created: Long, state: CardState = CardState.NEW, due: Long? = null) =
        card(n, base = NoteBaseType.KANJI, kanji = literal, created = created, state = state, due = due)

    private fun word(n: Int, kanji: List<String>, created: Long, missing: List<String> = emptyList(), state: CardState = CardState.NEW, due: Long? = null, members: List<kotobaya.contracts.queue.DeckMembership> = emptyList()) =
        card(n, created = created, exprKanji = kanji, req = kanji, missing = mks(*missing.toTypedArray()), state = state, due = due, members = members)

    /** Evaluates, materializes what the served build asks for (as the fetch does), evaluates again. */
    private fun fetch(sim: QueueSim): kotobaya.contracts.queue.LoopResult {
        val first = sim.eval()
        if (first.queue.kanjiToMaterialize.isEmpty() && first.queue.toMaterialize.isEmpty()) return first
        sim.materialize(first.queue)
        val after = sim.eval()
        assertEquals(first.state.untilStop, after.state.untilStop, "until_stop is the same before and after materializing")
        return after
    }

    private val past = NOW - 2 * DAY_MS
    private val notDue = TOKYO_DAY.endMs + 5 * DAY_MS

    @Test
    fun queueKanjiFirstDeckless() {
        val sim = QueueSim(
            input(
                listOf(word(10, listOf("学", "校"), created = 1, missing = listOf("学", "校"))),
                decks = listOf(deck(1)),
                limits = limits(newPerDay = 6),
                pending = listOf(
                    entry(1, 1387990, 1024, exprKanji = listOf("先", "生"), req = listOf("先", "生"), missing = mks("先", "生")),
                    entry(1, 1001010, 2048),
                ),
            ),
        )
        val before = sim.eval()
        assertEquals(listOf("先", "生", "学", "校"), before.queue.kanjiToMaterialize)
        assertEquals(listOf(1387990L), before.queue.toMaterialize.map { it.entrySeq })
        val r = fetch(sim)
        val m = sim.minted
        assertEquals(listOf(m.getValue("先"), m.getValue("生"), sim.entries.getValue(1387990), m.getValue("学"), m.getValue("校"), 10), r.queue.cards())
        assertTrue(r.queue.items.all { it.isNew })
        assertEquals(LoopPhase.REVIEWING, r.state.phase)
        assertEquals(6, r.state.untilStop)
        assertEquals(6, r.state.legacy.new)
        assertEquals(0, r.state.newToday)
        assertEquals(6, r.state.newGoal)
        assertTrue(r.state.newAvailable)
        // Step 1: the same fetch mints nothing more.
        val again = sim.eval()
        assertEquals(r, again)
        assertTrue(again.queue.kanjiToMaterialize.isEmpty() && again.queue.toMaterialize.isEmpty())
    }

    @Test
    fun queueKanjiDisabledHomeDeck() {
        val sim = QueueSim(
            input(
                listOf(
                    word(10, listOf("学", "校"), created = 1),
                    word(40, listOf("先", "生"), created = 2),
                    kanji(20, "学", created = 3).copy(memberships = listOf(member(2, 1))),
                    kanji(30, "校", created = 3).copy(suspended = true),
                    kanji(50, "先", created = 3, state = CardState.REVIEW, due = notDue),
                    kanji(60, "生", created = 4),
                ),
                decks = listOf(deck(2, enabled = false)),
                limits = limits(),
            ),
        )
        val r = fetch(sim)
        assertEquals(listOf(20, 10, 60, 40), r.queue.cards())
        assertEquals(4, r.state.untilStop)
        sim.answer(20, Ans.GOOD)
        val s2 = sim.eval()
        assertEquals(listOf(10, 60, 40), s2.queue.cards())
        assertEquals(0, s2.state.readyLearning + s2.state.readyReviews)
        assertEquals(1, s2.state.laterToday)
        assertEquals(4, s2.state.untilStop)
        assertEquals(1, s2.state.newToday)
    }

    @Test
    fun queueKanjiGroupNotSplit() {
        val sim = QueueSim(
            input(
                listOf(
                    card(10, state = CardState.REVIEW, due = past - 100 * DAY_MS),
                    card(20, state = CardState.REVIEW, due = past - 90 * DAY_MS),
                    card(30, state = CardState.REVIEW, due = past - 80 * DAY_MS),
                    card(40, state = CardState.REVIEW, due = past - 70 * DAY_MS),
                    word(50, listOf("学", "校"), created = 1),
                    word(60, emptyList(), created = 2),
                    kanji(70, "学", created = 3),
                    kanji(80, "校", created = 4),
                ),
                limits = limits(newPerDay = 4),
            ),
        )
        val r = fetch(sim)
        assertEquals(listOf(10, 70, 80, 50, 20, 30, 60, 40), r.queue.cards())
        assertEquals(4, r.state.readyReviews)
        assertEquals(8, r.state.untilStop)
        sim.answer(10, Ans.GOOD)
        sim.answer(70, Ans.EASY)
        val s3 = sim.eval()
        assertEquals(listOf(80, 50, 20, 30, 60, 40), s3.queue.cards())
        assertEquals(3, s3.state.readyReviews)
        assertEquals(6, s3.state.untilStop)
        assertEquals(1, s3.state.newToday)
        sim.answer(80, Ans.EASY)
        val s5 = sim.eval()
        assertEquals(listOf(50, 20, 30, 60, 40), s5.queue.cards())
        assertEquals(5, s5.state.untilStop)
        assertEquals(2, s5.state.newToday)
    }

    @Test
    fun queueKanjiBackfill() {
        val sim = QueueSim(
            input(
                listOf(
                    word(10, listOf("先", "生"), created = 1, state = CardState.REVIEW, due = notDue),
                    word(50, listOf("学", "校"), created = 2, missing = listOf("学", "校"), state = CardState.REVIEW, due = notDue),
                    word(40, emptyList(), created = 3),
                    kanji(20, "先", created = 4),
                    kanji(30, "生", created = 5),
                ),
                limits = limits(),
            ),
        )
        val before = sim.eval()
        assertEquals(listOf(20, 30, 40), before.queue.cards())
        assertEquals(listOf("学", "校"), before.queue.kanjiToMaterialize)
        val r = fetch(sim)
        assertEquals(listOf(20, 30, sim.minted.getValue("学"), sim.minted.getValue("校"), 40), r.queue.cards())
        assertTrue(r.queue.items.all { it.isNew })
        assertEquals(5, r.state.untilStop)
        sim.answer(20, Ans.GOOD)
        val s2 = sim.eval()
        assertEquals(listOf(30, sim.minted.getValue("学"), sim.minted.getValue("校"), 40), s2.queue.cards())
        assertEquals(1, s2.state.laterToday)
        assertEquals(5, s2.state.untilStop)
    }

    @Test
    fun queueKanjiPartialUnit() {
        val sim = QueueSim(
            input(
                listOf(
                    word(10, listOf("日", "本", "語"), created = 1),
                    word(20, emptyList(), created = 2),
                    kanji(30, "日", created = 3),
                    kanji(40, "本", created = 4),
                    kanji(50, "語", created = 5),
                ),
                limits = limits(newPerDay = 2),
            ),
        )
        val r = fetch(sim)
        assertEquals(listOf(30, 40), r.queue.cards())
        assertEquals(LoopPhase.REVIEWING, r.state.phase)
        assertEquals(2, r.state.untilStop)
        sim.answer(30, Ans.EASY)
        sim.answer(40, Ans.EASY)
        val s3 = sim.eval()
        assertEquals(emptyList(), s3.queue.items)
        assertEquals(LoopPhase.DONE_FOR_TODAY, s3.state.phase)
        assertEquals(0, s3.state.untilStop)
        assertEquals(2, s3.state.newToday)
        assertTrue(s3.state.newAvailable)
        sim.nextDay()
        val s4 = sim.eval()
        assertEquals(listOf(50, 10), s4.queue.cards())
        assertEquals(LoopPhase.REVIEWING, s4.state.phase)
        assertEquals(2, s4.state.untilStop)
        assertEquals(0, s4.state.newToday)
    }

    // Stand-ins of the scenario: 寿司 (ateji), 昨日 (no tag), 鱈 (no facts), 鱈子 without an entry.
    private val facts = listOf(
        KanjiFacts("寿", 8, 1500), KanjiFacts("司", 4, 1000), KanjiFacts("昨", 4, 900),
        KanjiFacts("日", 1, 1), KanjiFacts("子", 1, 70), KanjiFacts("鱈", null, null),
    )
    private val forms = listOf(
        10 to WordForm("寿司", setOf("ateji"), false),
        20 to WordForm("昨日", emptySet(), false),
        30 to WordForm("鱈", emptySet(), false),
        40 to WordForm("鱈子", emptySet(), false),
    )

    /** The loader's per-note values: `requiredKanji`, `missingKanji` (no kanji note in [existing]) and the front flag. */
    private fun exemptionWords(enabled: Boolean, existing: Set<String>): Pair<List<QueueCandidate>, Map<Int, Boolean>> {
        val furigana = HashMap<Int, Boolean>()
        val cards = forms.mapIndexed { i, (n, form) ->
            val e = DefaultKanjiRequirement.evaluate(form, facts, enabled)
            furigana[n] = e.frontFurigana
            card(
                n, created = i + 1L, exprKanji = kotobaya.cards.DefaultCardGenerator.kanjiOf(form.expression),
                req = e.learnableKanji, missing = mks(*e.learnableKanji.filter { it !in existing }.toTypedArray()),
            )
        }
        return cards to furigana
    }

    @Test
    fun queueKanjiExemptions() {
        val (on, furiganaOn) = exemptionWords(enabled = true, existing = emptySet())
        val sim = QueueSim(input(on, limits = limits()))
        val r0 = fetch(sim)
        val m = sim.minted
        assertEquals(listOf("昨", "日", "子"), m.keys.toList())
        assertEquals(listOf(10, m.getValue("昨"), m.getValue("日"), 20, 30, m.getValue("子"), 40), r0.queue.cards())
        assertEquals(7, r0.state.untilStop)
        assertEquals(mapOf(10 to true, 20 to false, 30 to true, 40 to false), furiganaOn)
        // Step 1-2: the setting off. The loader re-evaluates every word; 昨, 日, 子 are notes now.
        val (off, furiganaOff) = exemptionWords(enabled = false, existing = setOf("昨", "日", "子"))
        val words = off.associateBy { it.cardId }
        sim.input = sim.input.copy(candidates = sim.input.candidates.map { words[it.cardId] ?: it })
        val r2 = fetch(sim)
        assertEquals(
            listOf(m.getValue("寿"), m.getValue("司"), 10, m.getValue("昨"), m.getValue("日"), 20, m.getValue("鱈"), 30, m.getValue("子"), 40),
            r2.queue.cards(),
        )
        assertEquals(10, r2.state.untilStop)
        assertTrue(furiganaOff.values.none { it })
    }

    @Test
    fun queueKanjiBackfillShare() {
        val sim = QueueSim(
            input(
                listOf(
                    word(10, listOf("日", "本", "語"), created = 1, state = CardState.REVIEW, due = notDue),
                    card(20, created = 2, members = listOf(member(1, 1))),
                    card(30, created = 3, members = listOf(member(1, 2))),
                    kanji(40, "日", created = 5),
                    kanji(50, "本", created = 6),
                    kanji(60, "語", created = 7),
                ),
                decks = listOf(deck(1)),
                limits = limits(newPerDay = 4),
            ),
        )
        val r0 = fetch(sim)
        assertEquals(listOf(40, 50, 20, 30), r0.queue.cards())
        assertEquals(4, r0.state.untilStop)
        sim.answer(40, Ans.EASY)
        sim.answer(50, Ans.EASY)
        val r3 = sim.eval()
        assertEquals(listOf(20, 30), r3.queue.cards())
        assertEquals(2, r3.state.untilStop)
        assertEquals(2, r3.state.newToday)
        sim.answer(20, Ans.EASY)
        sim.answer(30, Ans.EASY)
        val r6 = sim.eval()
        assertEquals(emptyList(), r6.queue.items)
        assertEquals(LoopPhase.DONE_FOR_TODAY, r6.state.phase)
        assertEquals(0, r6.state.untilStop)
        assertEquals(4, r6.state.newToday)
        assertTrue(r6.state.newAvailable)
        sim.nextDay()
        val r7 = sim.eval()
        assertEquals(listOf(60), r7.queue.cards())
        assertEquals(1, r7.state.untilStop)
        assertEquals(0, r7.state.newToday)
    }

    @Test
    fun queueKanjiOrderStable() {
        val sim = QueueSim(
            input(
                listOf(
                    word(10, listOf("日"), created = 1, state = CardState.REVIEW, due = notDue),
                    word(20, listOf("家"), created = 2, state = CardState.REVIEW, due = notDue),
                    word(30, listOf("本"), created = 3, state = CardState.REVIEW, due = notDue),
                    word(40, listOf("日", "本", "語"), created = 4),
                    kanji(50, "日", 5), kanji(60, "家", 6), kanji(70, "本", 7), kanji(80, "語", 8),
                ),
                limits = limits(newPerDay = 5),
            ),
        )
        val r0 = fetch(sim)
        assertEquals(listOf(50, 60, 70, 80, 40), r0.queue.cards())
        assertEquals(5, r0.state.untilStop)
        sim.answer(50, Ans.EASY)
        sim.answer(60, Ans.EASY)
        val r3 = sim.eval()
        assertEquals(listOf(70, 80, 40), r3.queue.cards())
        assertEquals(3, r3.state.untilStop)
        assertEquals(2, r3.state.newToday)
        sim.answer(70, Ans.EASY)
        val r5 = sim.eval()
        assertEquals(listOf(80, 40), r5.queue.cards())
        assertEquals(2, r5.state.untilStop)
        assertEquals(3, r5.state.newToday)
    }

    @Test
    fun queueKanjiDeckLimit() {
        val sim = QueueSim(
            input(
                listOf(
                    word(10, listOf("本"), created = 1, members = listOf(member(1, 1024))),
                    kanji(20, "本", 2).copy(memberships = listOf(member(1, 2048))),
                    card(30, created = 3, members = listOf(member(1, 3072))),
                ),
                decks = listOf(deck(1, newPerDay = 1)),
                limits = limits(),
            ),
        )
        val r0 = fetch(sim)
        assertEquals(listOf(20), r0.queue.cards())
        assertEquals(1, r0.state.untilStop)
        sim.answer(20, Ans.EASY)
        val r2 = sim.eval()
        assertEquals(emptyList(), r2.queue.cards())
        assertEquals(0, r2.state.untilStop)
        assertEquals(1, r2.state.newToday)
        sim.nextDay()
        val r3 = sim.eval()
        assertEquals(listOf(10), r3.queue.cards())
        assertEquals(LoopPhase.REVIEWING, r3.state.phase)
        assertEquals(1, r3.state.untilStop)
    }

    @Test
    fun queueKanjiTurnOrder() {
        val sim = QueueSim(
            input(
                listOf(
                    card(10, created = 1, members = listOf(member(1, 1))),
                    card(20, created = 2, members = listOf(member(1, 2))),
                    word(30, listOf("学", "校"), created = 3),
                    kanji(40, "学", 4), kanji(50, "校", 5),
                ),
                decks = listOf(deck(1)),
                limits = limits(newPerDay = 4),
            ),
        )
        val r0 = fetch(sim)
        assertEquals(listOf(10, 40, 50, 30), r0.queue.cards())
        assertEquals(4, r0.state.untilStop)
        sim.answer(10, Ans.EASY)
        val r2 = sim.eval()
        assertEquals(listOf(40, 50, 30), r2.queue.cards())
        assertEquals(3, r2.state.untilStop)
        sim.answer(40, Ans.EASY)
        val r4 = sim.eval()
        assertEquals(listOf(50, 30), r4.queue.cards())
        assertEquals(2, r4.state.untilStop)
        assertEquals(2, r4.state.newToday)
    }

    @Test
    fun queueUntilStop() {
        val sim = QueueSim(
            input(
                listOf(
                    card(10, state = CardState.REVIEW, due = past),
                    card(20, state = CardState.REVIEW, due = past + 1),
                    card(30, state = CardState.LEARNING, due = NOW + 55_000L),
                    card(40, created = 1),
                    card(50, created = 2),
                    card(60, created = 3),
                ),
                limits = limits(newPerDay = 2),
                today = listOf(today(30, type = ReviewType.LEARNING, at = NOW - 5_000L)),
            ),
        )
        val s0 = sim.eval()
        assertEquals(LoopPhase.REVIEWING, s0.state.phase)
        assertEquals(4, s0.queue.items.size)
        assertEquals(2, s0.state.readyReviews)
        assertEquals(1, s0.state.laterToday)
        assertEquals(NOW + 55_000L, s0.state.nextDueAt)
        assertEquals(2, s0.state.backlog)
        assertEquals(5, s0.state.untilStop)
        sim.answer(10, Ans.AGAIN)
        val s2 = sim.eval()
        assertEquals(1, s2.state.readyReviews)
        assertEquals(2, s2.state.laterToday)
        assertEquals(NOW + 55_000L, s2.state.nextDueAt)
        assertEquals(5, s2.state.untilStop)
        sim.answer(20, Ans.GOOD)
        val s4 = sim.eval()
        assertEquals(0, s4.state.readyReviews)
        assertEquals(2, s4.state.laterToday)
        assertEquals(2, s4.state.reviewsToday)
        assertEquals(4, s4.state.untilStop)
        assertEquals(2, s4.queue.items.size)
        sim.answer(40, Ans.EASY)
        val s6 = sim.eval()
        assertEquals(3, s6.state.untilStop)
        assertEquals(listOf(50), s6.queue.cards())
        sim.answer(50, Ans.EASY)
        val s8 = sim.eval()
        assertEquals(LoopPhase.DONE_FOR_TODAY, s8.state.phase)
        assertEquals(2, s8.state.untilStop)
        assertEquals(2, s8.state.laterToday)
        assertEquals(emptyList(), s8.queue.items)
        // Step 9: keep going, limit 5: one new card drawn.
        val s9 = DefaultReviewLoop.evaluate(sim.input.copy(limits = sim.input.limits.copy(keepGoing = true, keepGoingNewBatch = 5)))
        assertEquals(LoopPhase.REVIEWING, s9.state.phase)
        assertTrue(s9.state.keepGoing)
        assertEquals(3, s9.state.untilStop)
        assertEquals(listOf(60), s9.queue.cards())
        assertEquals(s8.state.copy(phase = s9.state.phase, keepGoing = true, untilStop = 3), s9.state)
    }
}
