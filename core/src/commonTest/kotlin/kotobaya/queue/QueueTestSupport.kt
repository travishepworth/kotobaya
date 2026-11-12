package kotobaya.queue

import kotobaya.contracts.InstantMs
import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.Uuid
import kotobaya.contracts.WordStatus
import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.fsrs.ReviewType
import kotobaya.contracts.queue.AddKanjiMode
import kotobaya.contracts.queue.DeckMembership
import kotobaya.contracts.queue.PendingEntry
import kotobaya.contracts.queue.QueueCandidate
import kotobaya.contracts.queue.QueueDeck
import kotobaya.contracts.queue.QueueInput
import kotobaya.contracts.queue.QueueItemKind
import kotobaya.contracts.queue.QueueLimits
import kotobaya.contracts.queue.ReviewQueue
import kotobaya.contracts.queue.StudyDay
import kotobaya.contracts.queue.TodayReview

internal const val HOUR: Long = 3_600_000L

/** 2024-01-02T01:00+09:00 (= 2024-01-01T16:00Z), the StudyDay KDoc example. */
internal const val NOW: InstantMs = 1_704_124_800_000L

/** Asia/Tokyo, rollover 4: 2024-01-01T04:00+09:00 .. 2024-01-02T04:00+09:00. */
internal val TOKYO_DAY: StudyDay = StudyDay(1_704_049_200_000L, 1_704_135_600_000L)

internal fun deckId(n: Int): Uuid = "0193a1b2-00de-7000-8000-" + n.toString().padStart(12, '0')

internal fun cardId(n: Int): Uuid = "0193a1b2-00ca-7000-8000-" + n.toString().padStart(12, '0')

internal fun noteId(n: Int): Uuid = "0193a1b2-00be-7000-8000-" + n.toString().padStart(12, '0')

internal fun deck(
    n: Int,
    priority: Int = 0,
    enabled: Boolean = true,
    newPerDay: Int? = null,
    reviewsPerDay: Int? = null,
    addKanji: AddKanjiMode = AddKanjiMode.OFF,
): QueueDeck = QueueDeck(deckId(n), priority, enabled, newPerDay, reviewsPerDay, addKanji)

internal fun member(deck: Int, position: Long, itemKey: String = "n:$position"): DeckMembership =
    DeckMembership(deckId(deck), position, itemKey)

internal fun card(
    n: Int,
    note: Int = n,
    kindOrder: Int = 0,
    base: NoteBaseType = NoteBaseType.VOCAB,
    created: InstantMs = 0L,
    state: CardState = CardState.NEW,
    due: InstantMs? = null,
    suspended: Boolean = false,
    buriedUntil: InstantMs? = null,
    override: WordStatus? = null,
    members: List<DeckMembership> = emptyList(),
    kanji: String? = null,
    exprKanji: List<String> = emptyList(),
): QueueCandidate = QueueCandidate(
    cardId = cardId(n),
    noteId = noteId(note),
    kind = "k$kindOrder",
    kindOrder = kindOrder,
    noteBaseType = base,
    noteCreatedAt = created,
    state = state,
    dueAt = due,
    suspended = suspended,
    buriedUntil = buriedUntil,
    wordOverride = override,
    memberships = members,
    kanjiLiteral = kanji,
    expressionKanji = exprKanji,
)

internal fun review(n: Int, due: InstantMs, members: List<DeckMembership> = emptyList(), note: Int = n): QueueCandidate =
    card(n, note = note, state = CardState.REVIEW, due = due, members = members)

internal fun learning(n: Int, due: InstantMs, note: Int = n): QueueCandidate =
    card(n, note = note, state = CardState.LEARNING, due = due)

internal fun today(
    card: Int,
    note: Int = card,
    type: ReviewType = ReviewType.SCHEDULED,
    introducedNew: Boolean = false,
    countsAsReview: Boolean = false,
    decks: List<Int> = emptyList(),
): TodayReview = TodayReview(cardId(card), noteId(note), type, introducedNew, countsAsReview, decks.map { deckId(it) })

internal fun entry(deck: Int, seq: Long, position: Long, override: WordStatus? = null): PendingEntry =
    PendingEntry(deckId(deck), seq, position, "e:$seq", override)

internal fun limits(
    newPerDay: Int = 20,
    reviewsPerDay: Int = 200,
    newPerSession: Int = 100,
    newShown: Int = 0,
    burySiblings: Boolean = true,
    learnAheadMs: Long = 1_200_000L,
): QueueLimits = QueueLimits(newPerDay, reviewsPerDay, newPerSession, newShown, burySiblings, learnAheadMs)

internal fun input(
    candidates: List<QueueCandidate>,
    decks: List<QueueDeck> = emptyList(),
    limits: QueueLimits = limits(),
    today: List<TodayReview> = emptyList(),
    pending: List<PendingEntry> = emptyList(),
    now: InstantMs = NOW,
    day: StudyDay = TOKYO_DAY,
    decklessPriority: Int = 0,
    decklessAddKanji: AddKanjiMode = AddKanjiMode.OFF,
): QueueInput = QueueInput(now, day, limits, decks, candidates, today, pending, decklessPriority, decklessAddKanji)

internal fun build(input: QueueInput): ReviewQueue = DefaultQueueBuilder.build(input)

/** Card numbers of the queue, in order (all kinds). */
internal fun ReviewQueue.cards(): List<Int> = items.map { it.cardId.takeLast(12).toInt() }

/** Card numbers of one kind, in order. */
internal fun ReviewQueue.cards(kind: QueueItemKind): List<Int> =
    items.filter { it.kind == kind }.map { it.cardId.takeLast(12).toInt() }

/** Compact rendering: `L1 R2 N3`. */
internal fun ReviewQueue.shape(): String = items.joinToString(" ") {
    it.kind.name.first() + it.cardId.takeLast(12).toInt().toString()
}
