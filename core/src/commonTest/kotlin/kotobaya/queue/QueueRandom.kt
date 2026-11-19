package kotobaya.queue

import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.fsrs.ReviewType
import kotobaya.contracts.queue.QueueInput
import kotobaya.contracts.queue.QueueLimits
import kotlin.random.Random

/**
 * CORE-28 / C-28c: random inputs for rule 5 "Rebuild = tail": reviews, deckless words, decks with and
 * without per-deck limits (several sources per tier or one), deck-homed and deckless kanji cards shared
 * between words, studied words (backfill), missing kanji and answers given today. One card per note and
 * one-card kanji parts (exclusion 2); callers mint first (exclusion 1). C-28d: no other input is excluded.
 */
internal object QueueRandom {
    private val lits = listOf("日", "本", "語", "学", "校", "先", "生", "食")
    private val notDue = TOKYO_DAY.endMs + 5 * DAY_MS

    fun input(seed: Int, keepGoing: Boolean): QueueInput {
        val r = Random(seed)
        val decks = (1..r.nextInt(0, 4)).map {
            deck(it, priority = r.nextInt(0, 2), enabled = r.nextInt(6) != 0, newPerDay = if (r.nextInt(3) == 0) r.nextInt(0, 5) else null)
        }
        fun members() = decks.filter { r.nextInt(3) == 0 }.map { member(it.id.takeLast(12).toInt(), r.nextLong(0, 8)) }
        val withNote = lits.filter { r.nextBoolean() }.toSet()
        val words = (1..r.nextInt(0, 14)).map { n ->
            val studied = r.nextInt(3) == 0
            val ks = lits.shuffled(r).take(r.nextInt(0, 4))
            card(n, created = r.nextLong(0, 8), exprKanji = ks, missing = mks(*ks.filter { it !in withNote }.toTypedArray()),
                state = if (studied) CardState.REVIEW else CardState.NEW, due = if (studied) notDue else null, members = members())
        }
        val reviews = (1..r.nextInt(0, 8)).map { n -> card(200 + n, created = 0, state = CardState.REVIEW, due = NOW - r.nextLong(1, 40) * HOUR) }
        val kanjiCards = withNote.mapIndexed { i, l -> card(100 + i, base = NoteBaseType.KANJI, kanji = l, created = r.nextLong(0, 10), members = members()) }
        val t = (1..r.nextInt(0, 3)).map { today(9000 + it, type = ReviewType.LEARNING, introducedNew = true, at = TOKYO_DAY.startMs + it) }
        return input(
            words + reviews + kanjiCards, decks = decks, today = t, decklessPriority = r.nextInt(0, 2),
            limits = QueueLimits(r.nextInt(0, 12), 200, 0, 0, true, 0, keepGoing = keepGoing, keepGoingNewBatch = Int.MAX_VALUE,
                reviewsUnlimited = r.nextBoolean()),
        )
    }
}
