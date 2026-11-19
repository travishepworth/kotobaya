package kotobaya.queue

import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.fsrs.ReviewType
import kotobaya.contracts.queue.AddKanjiMode
import kotobaya.contracts.queue.QueueItemKind
import kotobaya.contracts.queue.QueueLimits
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** CORE-28: QueueBuilder rule 4 "Kanji first" (ADR-040, ADR-042, ADR-043), from the contract's examples. */
class QueueKanjiFirstTest {
    private fun lim(newPerDay: Int = 20, batch: Int = 10, bury: Boolean = true): QueueLimits =
        QueueLimits(newPerDay, 200, 0, 0, bury, 0, keepGoingNewBatch = batch)

    private fun kanji(n: Int, literal: String, created: Long = 100, note: Int = n, state: CardState = CardState.NEW) =
        card(n, note = note, base = NoteBaseType.KANJI, kanji = literal, created = created, state = state,
            due = if (state == CardState.NEW) null else TOKYO_DAY.endMs + 5 * DAY_MS)

    private fun word(n: Int, kanji: List<String>, created: Long = n.toLong(), missing: List<String> = emptyList()) =
        card(n, created = created, exprKanji = kanji, missing = mks(*missing.toTypedArray()))

    private val introducedToday = listOf(today(9001, type = ReviewType.LEARNING, introducedNew = true, at = TOKYO_DAY.startMs + 1))

    @Test
    fun withheldUntilMintedThenServedKanjiFirst() {
        // Rule 4 example: たべる, 学校, 先生 deckless; kanji 学 NEW, 先 REVIEW; none for 校, 生.
        val sim = QueueSim(
            input(
                listOf(
                    word(1, emptyList()), word(2, listOf("学", "校"), missing = listOf("校")), word(3, listOf("先", "生"), missing = listOf("生")),
                    kanji(51, "学"), kanji(52, "先", state = CardState.REVIEW),
                ),
                limits = lim(),
            ),
        )
        val q = sim.eval()
        assertEquals(listOf(1), q.queue.cards())
        assertEquals(listOf("校", "生"), q.queue.kanjiToMaterialize)
        assertEquals(6, q.state.untilStop)
        sim.materialize(q.queue)
        val after = sim.eval()
        assertEquals(listOf(1, 51, sim.minted.getValue("校"), 2, sim.minted.getValue("生"), 3), after.queue.cards())
        assertEquals(6, after.state.untilStop)
    }

    @Test
    fun leadAtThirtyNineOfFortyThenTheRestTheNextDay() {
        val today = (1..39).map { today(9000 + it, type = ReviewType.LEARNING, introducedNew = true, at = TOKYO_DAY.startMs + it) }
        val sim = QueueSim(
            input(
                listOf(word(1, listOf("学", "校"), created = 0), card(2, created = 1), kanji(51, "学"), kanji(52, "校")),
                limits = lim(newPerDay = 40), today = today,
            ),
        )
        assertEquals(listOf(51), sim.eval().queue.cards())
        sim.answer(51, Ans.EASY)
        assertEquals(emptyList(), sim.eval().queue.cards())
        sim.nextDay()
        assertEquals(listOf(52, 1, 2), sim.eval().queue.cards())
    }

    @Test
    fun noStarvationOverThreeDays() {
        val sim = QueueSim(
            input(
                listOf(word(1, listOf("日", "本", "語"), created = 0), card(2, created = 1), kanji(51, "日"), kanji(52, "本"), kanji(53, "語")),
                limits = lim(newPerDay = 2),
            ),
        )
        val days = mutableListOf<List<Int>>()
        repeat(3) {
            val q = sim.eval().queue.cards()
            days += q
            q.forEach { sim.answer(it, Ans.EASY) }
            sim.nextDay()
        }
        assertEquals(listOf(listOf(51, 52), listOf(53, 1), listOf(2)), days)
    }

    @Test
    fun aBuriedKanjiHoldsItsWordAndTheSourceGoesOn() {
        val c = listOf(
            word(1, listOf("学", "校"), created = 0), card(2, created = 1),
            kanji(51, "学").copy(buriedUntil = TOKYO_DAY.endMs), kanji(52, "校"),
        )
        // 学校 is held and its source goes on: たべる, then 校 on its own deckless turn ("Own turn", C-28c example).
        val sim = QueueSim(input(c, limits = lim()))
        assertEquals(listOf(2, 52), sim.build().cards())
        // Tomorrow the bury has ended: 校 unanswered → [学, 校, 学校]; answered today → [学, 学校].
        assertEquals(listOf(51, 52, 1, 2), build(input(c, limits = lim(), now = TOKYO_DAY.endMs + 1000, day = TOKYO_DAY.copy(TOKYO_DAY.endMs, TOKYO_DAY.endMs + DAY_MS))).cards())
        sim.answer(2, Ans.EASY)
        sim.answer(52, Ans.EASY)
        sim.nextDay()
        assertEquals(listOf(51, 1), sim.build().cards())
    }

    @Test
    fun deckLimitExampleAndVariants() {
        // Deck "Core" (newPerDay 1): the word 本, the kanji note 本, うっかり in position order.
        fun core(limit: Int, kanjiDeckless: Boolean = false) = QueueSim(
            input(
                listOf(
                    card(10, exprKanji = listOf("本"), members = listOf(member(1, 0))),
                    kanji(20, "本").copy(memberships = if (kanjiDeckless) emptyList() else listOf(member(1, 1))),
                    card(30, members = listOf(member(1, 2))),
                ),
                decks = listOf(deck(1, newPerDay = limit)), limits = lim(),
            ),
        )
        val one = core(1)
        val r = one.eval()
        assertEquals(listOf(20), r.queue.cards())
        assertEquals(1, r.state.untilStop)
        one.answer(20, Ans.EASY)
        assertEquals(emptyList(), one.eval().queue.cards())
        assertEquals(0, one.eval().state.untilStop)
        one.nextDay()
        assertEquals(listOf(10), one.build().cards())
        one.answer(10, Ans.EASY)
        one.nextDay()
        assertEquals(listOf(30), one.build().cards())
        // Limit 2: whole; after the kanji the word.
        val two = core(2)
        assertEquals(listOf(20, 10), two.build().cards())
        two.answer(20, Ans.EASY)
        assertEquals(listOf(10), two.build().cards())
        // The kanji note deckless, limit 1: deck cost 1, whole.
        val free = core(1, kanjiDeckless = true)
        assertEquals(listOf(20, 10), free.build().cards())
        free.answer(20, Ans.EASY)
        assertEquals(listOf(10), free.build().cards())
    }

    @Test
    fun aLeadEndsAtTheDecksLimit() {
        // C-28d: "Core" (newPerDay 1) holds 学校 then the kanji note 学; 校 is in a disabled deck only (or deckless).
        fun core(kouDeckless: Boolean) = QueueSim(
            input(
                listOf(
                    card(10, exprKanji = listOf("学", "校"), members = listOf(member(1, 0))),
                    kanji(20, "学").copy(memberships = listOf(member(1, 1))),
                    kanji(30, "校").copy(memberships = if (kouDeckless) emptyList() else listOf(member(2, 0))),
                ),
                decks = listOf(deck(1, newPerDay = 1), deck(2, enabled = false)), limits = lim(),
            ),
        )
        val sim = core(kouDeckless = false)
        val r0 = sim.eval()
        assertEquals(listOf(20), r0.queue.cards())
        assertEquals(1, r0.state.untilStop)
        sim.answer(20, Ans.EASY)
        assertEquals(emptyList(), sim.eval().queue.cards())
        assertEquals(0, sim.eval().state.untilStop)
        sim.nextDay()
        assertEquals(listOf(30, 10), sim.build().cards())
        // 校 deckless: the same lead [学]; 校 comes on the deckless source's own turn.
        val free = core(kouDeckless = true)
        assertEquals(listOf(20, 30), free.build().cards())
        free.answer(20, Ans.EASY)
        assertEquals(listOf(30), free.build().cards())
        free.answer(30, Ans.EASY)
        free.nextDay()
        assertEquals(listOf(10), free.build().cards())
    }

    @Test
    fun untilStopNeverDropsByMoreThanOneForOneAnswer() {
        // C-28d: random inputs, normal builds; each answer of the head lowers untilStop by at most 1.
        for (seed in 0 until 300) {
            val sim = QueueSim(QueueRandom.input(seed, keepGoing = false))
            repeat(3) { sim.materialize(sim.eval().queue) }
            var r = sim.eval()
            var steps = 0
            while (r.queue.items.isNotEmpty() && steps++ < 200) {
                sim.answer(r.queue.cards().first(), Ans.EASY)
                val next = sim.eval()
                assertTrue(next.state.untilStop >= r.state.untilStop - 1, "seed $seed: ${r.state.untilStop} -> ${next.state.untilStop}")
                r = next
            }
        }
    }

    @Test
    fun aCappedDeckWhoseWordHasTwoKanjiHomedInIt() {
        val sim = QueueSim(
            input(
                listOf(
                    card(1, exprKanji = listOf("日", "本"), members = listOf(member(1, 0))),
                    kanji(51, "日").copy(memberships = listOf(member(1, 1))),
                    kanji(52, "本").copy(memberships = listOf(member(1, 2))),
                ),
                decks = listOf(deck(1, newPerDay = 1)), limits = lim(),
            ),
        )
        val days = mutableListOf<List<Int>>()
        repeat(3) {
            val q = sim.build().cards()
            days += q
            q.forEach { sim.answer(it, Ans.EASY) }
            sim.nextDay()
        }
        assertEquals(listOf(listOf(51), listOf(52), listOf(1)), days)
    }

    @Test
    fun theDecksFirstNewCardOfTheDayTakesAMultiCardPart() {
        // burySiblings false, 学 has two NEW kinds homed in the deck (limit 1): taken once as the deck's first cards.
        val c = listOf(
            card(1, exprKanji = listOf("学"), members = listOf(member(1, 0))),
            kanji(51, "学", note = 50).copy(memberships = listOf(member(1, 1))),
            kanji(52, "学", note = 50).copy(memberships = listOf(member(1, 1)), kindOrder = 1),
        )
        assertEquals(listOf(51, 52), build(input(c, decks = listOf(deck(1, newPerDay = 1)), limits = lim(bury = false))).cards())
        // Something from the deck already introduced today: no room, nothing.
        val t = listOf(today(9001, type = ReviewType.LEARNING, introducedNew = true, decks = listOf(1)))
        assertEquals(emptyList(), build(input(c, decks = listOf(deck(1, newPerDay = 2)), limits = lim(bury = false), today = t)).cards())
    }

    @Test
    fun startedAndSuspendedKanjiDoNotBlock() {
        val c = listOf(word(1, listOf("学", "校", "生"), created = 0), kanji(51, "学", state = CardState.REVIEW), kanji(52, "校").copy(suspended = true), kanji(53, "生"))
        assertEquals(listOf(53, 1), build(input(c, limits = lim())).cards())
    }

    @Test
    fun aLeadStopsItsSourceButNotTheOthers() {
        // burySiblings false and a kanji note of two NEW kinds, so a part (学: 2 cards) can fail with room left.
        // Deck 1: 校学 (parts 校 = 1, 学 = 2) then card 2; deck 2: card 3. 1 of 3 done: room 2.
        val decks = listOf(deck(1), deck(2))
        val c = listOf(
            card(1, exprKanji = listOf("校", "学"), members = listOf(member(1, 0))), card(2, members = listOf(member(1, 1))),
            card(3, members = listOf(member(2, 0))),
            kanji(52, "校"), kanji(61, "学", note = 60), kanji(62, "学", note = 60).copy(kindOrder = 1),
        )
        // Deck 1 leads with 校 and stops (card 2 waits behind its word); deck 2 still draws card 3.
        assertEquals(listOf(52, 3), build(input(c, decks = decks, limits = lim(newPerDay = 3, bury = false), today = introducedToday)).cards())
    }

    @Test
    fun aLeadOfAnEntryLeavesTheEntryOutOfToMaterialize() {
        val p = listOf(entry(1, 100, 0, exprKanji = listOf("学", "校"), missing = mks("校")), entry(1, 101, 1))
        val q = build(input(listOf(kanji(51, "学")), decks = listOf(deck(1)), pending = p, limits = lim(newPerDay = 2), today = introducedToday))
        assertEquals(listOf(51), q.cards())
        assertEquals(emptyList(), q.toMaterialize)
        assertEquals(emptyList(), q.kanjiToMaterialize)
    }

    @Test
    fun theGoalIsNeverOvershotWhenEveryPartHasOneCard() {
        // Property: built-in kanji type (one card per note), random words, decks, goals.
        val lits = listOf("日", "本", "語", "学", "校", "先", "生")
        for (seed in 0 until 300) {
            val r = Random(seed)
            val decks = (1..r.nextInt(0, 3)).map { deck(it, priority = r.nextInt(0, 2), newPerDay = if (r.nextBoolean()) r.nextInt(0, 5) else null) }
            val words = (1..r.nextInt(0, 12)).map { n ->
                val ks = lits.shuffled(r).take(r.nextInt(0, 4))
                card(n, created = r.nextLong(0, 5), exprKanji = ks, missing = mks(*ks.filter { r.nextInt(3) == 0 }.toTypedArray()),
                    state = if (r.nextInt(4) == 0) CardState.REVIEW else CardState.NEW, due = TOKYO_DAY.endMs + DAY_MS,
                    members = decks.filter { r.nextBoolean() }.map { member(it.id.takeLast(12).toInt(), r.nextLong(0, 5)) })
            }
            val kanjiCards = lits.filter { r.nextBoolean() }.mapIndexed { i, l -> kanji(100 + i, l, created = r.nextLong(0, 5)) }
            val done = r.nextInt(0, 3)
            val t = (1..done).map { today(9000 + it, type = ReviewType.LEARNING, introducedNew = true, at = TOKYO_DAY.startMs + it) }
            val goal = r.nextInt(0, 8)
            val q = DefaultReviewLoop.evaluate(input(words + kanjiCards, decks = decks, limits = lim(newPerDay = goal, batch = 100), today = t))
            val news = q.queue.items.count { it.kind == QueueItemKind.NEW } + q.queue.toMaterialize.size + q.queue.kanjiToMaterialize.size
            assertTrue(news <= maxOf(0, goal - done), "seed $seed: $news > ${goal - done}")
            assertTrue(q.state.untilStop - q.state.laterToday - q.state.readyReviews - q.state.readyLearning <= maxOf(0, goal - done), "seed $seed")
        }
    }

    @Test
    fun aDecklessWordGetsItsKanjiFirst() {
        val c = listOf(word(1, listOf("学", "校"), created = 0), kanji(51, "学", created = 5), kanji(52, "校", created = 6))
        assertEquals(listOf(51, 52, 1), build(input(c, limits = lim())).cards())
    }

    @Test
    fun aDeckWithAddKanjiOffBehavesTheSame() {
        val c = listOf(card(1, exprKanji = listOf("学", "校"), members = listOf(member(1, 0))), kanji(51, "学"), kanji(52, "校"))
        val results = AddKanjiMode.entries.map { build(input(c, decks = listOf(deck(1, addKanji = it)), limits = lim(), decklessAddKanji = it)) }
        assertEquals(listOf(51, 52, 1), results.first().cards())
        assertTrue(results.all { it == results.first() })
    }

    @Test
    fun theFortyCardBudgetIncludesKanji() {
        // 20 words with two kanji each = 60 cards; a goal of 40 gives 13 whole units and a lead of 1.
        val c = (1..20).flatMap { n ->
            listOf(word(n, listOf("k${n}a", "k${n}b"), created = n.toLong()), kanji(100 + 2 * n, "k${n}a"), kanji(101 + 2 * n, "k${n}b"))
        }
        val r = DefaultReviewLoop.evaluate(input(c, limits = lim(newPerDay = 40, batch = 100)))
        assertEquals(40, r.queue.items.size)
        assertEquals(40, r.state.untilStop)
        assertEquals(40, r.state.legacy.new)
        // The batch of 10 cuts whole units: 4 units (12 cards), the fourth started at 9.
        assertEquals(12, DefaultReviewLoop.evaluate(input(c, limits = lim(newPerDay = 40, batch = 10))).queue.items.size)
    }

    @Test
    fun aKanjiCardWhoseDecksAreAllDisabledJoinsItsWordsUnit() {
        val c = listOf(word(1, listOf("学"), created = 0), kanji(51, "学").copy(memberships = listOf(member(2, 0))))
        val q = build(input(c, decks = listOf(deck(2, enabled = false)), limits = lim()))
        assertEquals(listOf(51, 1), q.cards())
        assertEquals(listOf(null, null), q.items.map { it.deckId })
    }

    @Test
    fun twoWordsSharingAKanji() {
        val c = listOf(word(1, listOf("学", "校"), created = 0), word(2, listOf("学", "生"), created = 1), kanji(51, "学"), kanji(52, "校"), kanji(53, "生"))
        assertEquals(listOf(51, 52, 1, 53, 2), build(input(c, limits = lim())).cards())
    }

    @Test
    fun theSameKanjiTwiceInAWordCountsOnce() {
        val c = listOf(card(1, exprKanji = listOf("日", "曜", "日")), kanji(51, "日"), kanji(52, "曜"))
        assertEquals(listOf(51, 52, 1), build(input(c, limits = lim(newPerDay = 3))).cards())
    }

    @Test
    fun startedNewAndMissingKanjiInOneWord() {
        // 先 started, 学 new, 生 missing: [学, word] withheld until 生 is minted, then [学, 生, word].
        val sim = QueueSim(input(listOf(word(1, listOf("先", "学", "生"), created = 0, missing = listOf("生")), kanji(51, "先", state = CardState.REVIEW), kanji(52, "学")), limits = lim()))
        val q = sim.eval().queue
        assertEquals(emptyList(), q.cards())
        assertEquals(listOf("生"), q.kanjiToMaterialize)
        sim.materialize(q)
        assertEquals(listOf(52, sim.minted.getValue("生"), 1), sim.eval().queue.cards())
    }

    @Test
    fun burySiblingsFalseTakesEveryNewKindOfAKanjiNote() {
        val c = listOf(word(1, listOf("学"), created = 0), kanji(51, "学", note = 50), kanji(52, "学", note = 50).copy(kindOrder = 1))
        assertEquals(listOf(51, 52, 1), build(input(c, limits = lim(bury = false))).cards())
        assertEquals(listOf(51, 1), build(input(c, limits = lim(bury = true))).cards())
    }

    @Test
    fun companionsAreNewOnlyThroughAUnitLearningServedReviewNever() {
        val c = listOf(
            card(1, exprKanji = listOf("学"), members = listOf(member(1, 0))),
            kanji(51, "学", created = 0).copy(companion = true, memberships = listOf(member(2, 0))),
            kanji(52, "校", created = 0).copy(companion = true),
            kanji(53, "先").copy(companion = true, state = CardState.LEARNING, dueAt = NOW - 1000),
            kanji(54, "生").copy(companion = true, state = CardState.REVIEW, dueAt = NOW - 1000),
        )
        val decks = listOf(deck(1), deck(2))
        assertEquals("L53 N51 N1", build(input(c, decks = decks, limits = lim())).shape())
    }

    @Test
    fun requiredKanjiEmptyMeansNoUnitAndNullMeansTheExpression() {
        val ks = listOf(kanji(51, "寿", created = 5), kanji(52, "司", created = 6))
        val exempt = card(1, created = 0, exprKanji = listOf("寿", "司"), req = emptyList())
        // No unit: the word alone; the kanji are ordinary deckless cards on their own turns.
        assertEquals(listOf(1, 51, 52), build(input(listOf(exempt) + ks, limits = lim())).cards())
        val legacy = card(1, created = 0, exprKanji = listOf("寿", "司"), req = null)
        assertEquals(listOf(51, 52, 1), build(input(listOf(legacy) + ks, limits = lim())).cards())
        val partial = card(1, created = 0, exprKanji = listOf("寿", "司"), req = listOf("司"))
        assertEquals(listOf(52, 1, 51), build(input(listOf(partial) + ks, limits = lim())).cards())
    }

    @Test
    fun decklessTieBreakByLiteral() {
        // Two kanji notes minted in the same millisecond: by literal (code point), whatever the card ids.
        val c = listOf(kanji(2, "本", created = 7), kanji(1, "日", created = 7), kanji(3, "𠀋", created = 7), kanji(4, "Ａ", created = 7))
        // 日 U+65E5 < 本 U+672C < Ａ U+FF21 < 𠀋 U+2000B (UTF-16 order would put 𠀋 before Ａ).
        assertEquals(listOf(1, 2, 4, 3), build(input(c, limits = lim())).cards())
    }

    @Test
    fun countsAreEqualBeforeAndAfterMaterialization() {
        val p = listOf(entry(1, 100, 0, exprKanji = listOf("学", "校"), req = listOf("学", "校"), missing = mks("学", "校")), entry(1, 101, 1))
        val sim = QueueSim(input(listOf(word(5, listOf("先"), missing = listOf("先"))), decks = listOf(deck(1)), pending = p, limits = lim(newPerDay = 5)))
        val before = sim.eval()
        sim.materialize(before.queue)
        val after = sim.eval()
        // Every count but the frozen legacy one (it counts a withheld unit only once materialized).
        assertEquals(before.state.copy(legacy = after.state.legacy), after.state)
        assertEquals(1, before.state.legacy.new)
        assertEquals(5, after.state.untilStop)
        assertEquals(5, after.queue.items.size)
    }
}
