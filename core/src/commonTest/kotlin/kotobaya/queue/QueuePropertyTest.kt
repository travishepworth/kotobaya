package kotobaya.queue

import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.WordStatus
import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.fsrs.ReviewType
import kotobaya.contracts.queue.AddKanjiMode
import kotobaya.contracts.queue.QueueInput
import kotobaya.contracts.queue.QueueItemKind
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
                due = if (state == CardState.NEW) null else NOW + r.nextLong(-30, 30) * HOUR,
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
                newCount <= maxOf(0, minOf(i.limits.newPerDay - introducedToday, i.limits.newPerSession - i.limits.newShownThisSession)),
                "new budget, seed $seed",
            )
            assertTrue(
                q.items.count { it.kind == QueueItemKind.REVIEW } <= maxOf(0, i.limits.reviewsPerDay - i.today.count { it.countsAsReview }),
                "review budget, seed $seed",
            )
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
}
