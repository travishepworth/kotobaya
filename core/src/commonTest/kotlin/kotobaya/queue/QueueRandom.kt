package kotobaya.queue

import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.fsrs.ReviewType
import kotobaya.contracts.queue.QueueCandidate
import kotobaya.contracts.queue.QueueDeck
import kotobaya.contracts.queue.QueueInput
import kotobaya.contracts.queue.QueueLimits
import kotlin.random.Random

/**
 * CORE-28 / C-28c: random inputs for rule 5 "Rebuild = tail": reviews, deckless words, decks with and
 * without per-deck limits (several sources per tier or one), deck-homed and deckless kanji cards shared
 * between words, studied words (backfill), missing kanji and answers given today. One card per note and
 * one-card kanji parts (exclusion 2); callers mint first (exclusion 1) and skip [rebuildExcluded].
 */
internal object QueueRandom {
    private val lits = listOf("日", "本", "語", "学", "校", "先", "生", "食")
    private val notDue = TOKYO_DAY.endMs + 5 * DAY_MS

    /** [newWordKanji] false: NEW words are kana-only (studied words keep their kanji, so backfill stays). */
    fun input(seed: Int, keepGoing: Boolean, newWordKanji: Boolean = true): QueueInput {
        val r = Random(seed)
        val decks = (1..r.nextInt(0, 4)).map {
            deck(it, priority = r.nextInt(0, 2), enabled = r.nextInt(6) != 0, newPerDay = if (r.nextInt(3) == 0) r.nextInt(0, 5) else null)
        }
        fun members() = decks.filter { r.nextInt(3) == 0 }.map { member(it.id.takeLast(12).toInt(), r.nextLong(0, 8)) }
        val withNote = lits.filter { r.nextBoolean() }.toSet()
        val words = (1..r.nextInt(0, 14)).map { n ->
            val studied = r.nextInt(3) == 0
            val ks = lits.shuffled(r).take(r.nextInt(0, 4)).takeIf { studied || newWordKanji }.orEmpty()
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

    private fun home(c: QueueCandidate, decks: Map<String, QueueDeck>): String? {
        if (c.memberships.isEmpty()) return "deckless"
        return c.memberships.mapNotNull { decks[it.deckId] }.filter { it.enabled }
            .minWithOrNull(compareBy({ it.priority }, { it.id }))?.id
    }

    private fun req(c: QueueCandidate) = c.requiredKanji ?: c.expressionKanji

    /**
     * Inputs outside the guarantee (call after minting):
     * - rule 5 exclusion 3, widened to any home: a not yet started kanji required by NEW words of two sources;
     * - a NEW word of a capped deck D requiring a kanji homed in D and another one homed elsewhere: its
     *   lead takes both, and once the D-homed one is answered D (remaining 0) takes no turn, so the
     *   other part waits for its own turn (HANDOFF, follow-up).
     */
    fun rebuildExcluded(i: QueueInput): Boolean {
        val decks = i.decks.associateBy { it.id }
        val newKanji = i.candidates.filter { it.noteBaseType == NoteBaseType.KANJI && it.state == CardState.NEW }
        val newWords = i.candidates.filter { it.noteBaseType == NoteBaseType.VOCAB && it.state == CardState.NEW }
        val shared = newKanji.mapNotNull { it.kanjiLiteral }.toSet().any { l ->
            newWords.filter { l in req(it) }.mapNotNull { home(it, decks) }.toSet().size > 1
        }
        val capped = newWords.any { w ->
            val d = home(w, decks)?.let { decks[it] } ?: return@any false
            if (d.newPerDay == null) return@any false
            val ks = newKanji.filter { it.kanjiLiteral in req(w) }
            ks.any { home(it, decks) == d.id } && ks.any { home(it, decks) != d.id }
        }
        return shared || capped
    }
}
