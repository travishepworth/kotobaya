package kotobaya.contracts.queue

import kotobaya.contracts.InstantMs
import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.Uuid
import kotobaya.contracts.WordStatus
import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.fsrs.ReviewType

/**
 * The current study day, half-open `[startMs, endMs)`.
 *
 * Computed by platform code (it needs a time-zone database; `java.time` on JVM/Android, `Intl` on
 * the web) from the settings `scheduler.timezone` (IANA, default `UTC`) and
 * `scheduler.day_rollover_hour` (0–23, default 4): `startMs` = the latest instant `<= now` at which
 * the local wall clock in that zone reads `HH:00:00.000` with `HH` = the rollover hour; `endMs` =
 * the next such instant (normally 24 h later; 23 h or 25 h across a DST change). If the rollover
 * hour does not exist on a day (DST gap), the first instant after the gap is used.
 *
 * Example: zone `Asia/Tokyo`, rollover 4, now = 2024-01-02T01:00+09:00 → start
 * 2024-01-01T04:00+09:00, end 2024-01-02T04:00+09:00.
 */
public data class StudyDay(
    /** First instant of the day. */
    public val startMs: InstantMs,
    /** First instant of the next day (exclusive). `endMs > startMs`. */
    public val endMs: InstantMs,
)

/** Deck setting `add_kanji` (`deck.settings`, SCHEMA comment on `deck.settings`). */
public enum class AddKanjiMode(
    /** Wire value. */
    public val wire: String,
) {
    /** No automatic kanji cards / no ordering constraint. Default when the key is absent. */
    OFF("off"),

    /** New kanji cards for a word's unknown kanji are introduced immediately **before** the word's new cards. */
    BEFORE("before"),

    /** New kanji cards are introduced immediately **after** the word's new cards. */
    ALONGSIDE("alongside"),
}

/** A live deck as the queue sees it. */
public data class QueueDeck(
    /** `deck.id`. */
    public val id: Uuid,
    /** `deck.priority`; new-card sources are drawn in ascending priority tiers (see [QueueBuilder] rule 4). */
    public val priority: Int,
    /** `deck.enabled`; a disabled deck supplies no new cards (its cards are still reviewed). */
    public val enabled: Boolean,
    /** `deck.new_cards_per_day`; null = only the global limit. */
    public val newPerDay: Int?,
    /** `deck.reviews_per_day`; null = only the global limit. */
    public val reviewsPerDay: Int?,
    /** `deck.settings.add_kanji`. */
    public val addKanji: AddKanjiMode,
)

/**
 * A card's membership in a deck, as resolved by the caller: user/media/list decks via `deck_item`
 * (entry items `e:<seq>` match the live vocab note of that entry; `n:<id>` items resolve through
 * `merged_into_id`, SYNC.md 8.5); smart decks via the search evaluator on `deck.query`.
 */
public data class DeckMembership(
    /** The deck. */
    public val deckId: Uuid,
    /** New-card order inside the deck: `deck_item.position`; for a smart deck, the note's `created_at`. */
    public val position: Long,
    /** Tie-break after [position]: `deck_item.item_key`; for a smart deck, the note id. */
    public val itemKey: String,
)

/**
 * One card the queue may show. The caller passes only cards that are **live** (card and note
 * `deleted_at IS NULL`, card not a merge loser); the builder applies every other rule itself.
 */
public data class QueueCandidate(
    /** `card.id`. */
    public val cardId: Uuid,
    /** `card.note_id` (resolved: the survivor if the note was merged). */
    public val noteId: Uuid,
    /** `card.kind`. */
    public val kind: String,
    /** Index of [kind] in the note type's `templates` key order (0-based); orders a note's new cards. */
    public val kindOrder: Int,
    /** The note's base type. */
    public val noteBaseType: NoteBaseType,
    /** `note.created_at`; orders the new cards of the deckless source (cards with no deck membership at all). */
    public val noteCreatedAt: InstantMs,
    /** `card.state` (cache, after any pending replay). */
    public val state: CardState,
    /** `card.due_at` (cache). */
    public val dueAt: InstantMs?,
    /** `card.suspended`. */
    public val suspended: Boolean,
    /** `card.buried_until` (manual bury). */
    public val buriedUntil: InstantMs?,
    /** For a vocab note with an entry: that entry's `word_status.override_status`, else null. */
    public val wordOverride: WordStatus?,
    /** Deck memberships (any order; may be empty). */
    public val memberships: List<DeckMembership>,
    /** For a kanji note: `note.kanji_literal`; else null. */
    public val kanjiLiteral: String?,
    /** For a vocab note: the distinct kanji of its expression in order of first appearance; else empty. */
    public val expressionKanji: List<String>,
)

/** One review answered earlier in the current study day (not undone). */
public data class TodayReview(
    /** `review_log.card_id`, resolved to the survivor if merged. */
    public val cardId: Uuid,
    /** The card's note (resolved). */
    public val noteId: Uuid,
    /** `review_type`. */
    public val reviewType: ReviewType,
    /** True iff the card was NEW before this review (it introduced the card): counts against the new limits. */
    public val introducedNew: Boolean,
    /** True iff the card was in REVIEW state before this review and the type is scheduled: counts against the review limits. */
    public val countsAsReview: Boolean,
    /** The card's deck memberships (deck ids) now, for per-deck attribution. */
    public val deckIds: List<Uuid>,
)

/** A deck entry `e:<seq>` that has no live note yet; drawing it means creating its note and cards first. */
public data class PendingEntry(
    /** The deck. */
    public val deckId: Uuid,
    /** `deck_item.entry_seq`. */
    public val entrySeq: Long,
    /** `deck_item.position`. */
    public val position: Long,
    /** `deck_item.item_key`. */
    public val itemKey: String,
    /** `word_status.override_status` of the entry, or null. */
    public val wordOverride: WordStatus?,
)

/** Daily and session limits (settings `scheduler.*`, SCHEMA.md 6). */
public data class QueueLimits(
    /** `scheduler.new_per_day` (default 20). */
    public val newPerDay: Int,
    /** `scheduler.reviews_per_day` (default 200). */
    public val reviewsPerDay: Int,
    /** `scheduler.new_per_session` (default 10). */
    public val newPerSession: Int,
    /** New cards already introduced in the current session (the UI's counter; 0 at session start). */
    public val newShownThisSession: Int,
    /** `scheduler.bury_siblings` (default true). */
    public val burySiblings: Boolean,
    /** Learning cards due within this many ms are shown now (default 1 200 000 = 20 min). */
    public val learnAheadMs: Long,
)

/** Everything [QueueBuilder.build] needs. */
public data class QueueInput(
    /** Current instant. */
    public val now: InstantMs,
    /** The study day containing [now]. */
    public val day: StudyDay,
    /** Limits. */
    public val limits: QueueLimits,
    /** All live decks. */
    public val decks: List<QueueDeck>,
    /** Candidate cards. */
    public val candidates: List<QueueCandidate>,
    /** Today's non-undone reviews (`reviewed_at` in `[day.startMs, now]`), any order. */
    public val today: List<TodayReview>,
    /** Entries of enabled decks without a live note. */
    public val pendingEntries: List<PendingEntry>,
    /**
     * Priority of the **deckless source** (ADR-006): setting `scheduler.deckless_new_priority`
     * (int, default 0 = the same tier as ordinary decks of priority 0, interleaved with them).
     */
    public val decklessPriority: Int = 0,
)

/** Why an item is in the queue. */
public enum class QueueItemKind {
    /** LEARNING or RELEARNING card due now (within learn-ahead). */
    LEARNING,

    /** REVIEW card due today. */
    REVIEW,

    /** NEW card being introduced. */
    NEW,
}

/** One card to show. */
public data class QueueItem(
    /** `card.id`. */
    public val cardId: Uuid,
    /** Kind of item; the answer is logged with `review_type` `scheduled` for REVIEW and `learning` otherwise. */
    public val kind: QueueItemKind,
    /** Deck the item is attributed to for limits (null = none). */
    public val deckId: Uuid?,
)

/** The result of [QueueBuilder.build]. */
public data class ReviewQueue(
    /** Cards to show, in order. A card appears at most once. */
    public val items: List<QueueItem>,
    /** Entries the client should create notes and default cards for (origin `deck`), then rebuild. Bounded by the new limits. */
    public val toMaterialize: List<PendingEntry>,
    /** Earliest `dueAt` of a learning card due later today but outside learn-ahead; null if none. */
    public val nextLearningDueAt: InstantMs?,
)

/**
 * Builds the review queue (SPEC "Queue building"). Pure and deterministic; the UI calls it again
 * after every answer (it is cheap to rebuild and that keeps sibling burying and limits exact).
 *
 * Rules, in order:
 * 1. **Eligible**: not `suspended`; `buriedUntil == null || buriedUntil <= now`; `wordOverride`
 *    not NEVER_FORGET, BLACKLISTED or IGNORED; and, if `burySiblings`, not **sibling-buried**: a
 *    card in state NEW or REVIEW is buried when [QueueInput.today] has a `scheduled` or `learning`
 *    review of another card of the same note. LEARNING/RELEARNING cards are never sibling-buried.
 *    **Within one build** (if `burySiblings`): at most one NEW or REVIEW item per note. Rule 3 runs
 *    before rule 4; once a NEW or REVIEW card of note N is taken, every later NEW/REVIEW candidate of
 *    N is skipped (not taken, not counted against any limit). LEARNING items do not bury and are
 *    not buried.
 * 2. **Learning**: eligible LEARNING/RELEARNING cards with `dueAt <= now + learnAheadMs`, ordered
 *    by `(dueAt, cardId)`. Not limited. Those due later but before `day.endMs` set `nextLearningDueAt`.
 * 3. **Reviews**: eligible REVIEW cards with `dueAt < day.endMs`, ordered by `(dueAt, cardId)`.
 *    The **home deck** of a card is the first of its membership decks in `(priority, id)` order
 *    (enabled or not); null without memberships. A card is taken iff the global remaining
 *    (`reviewsPerDay` − today's `countsAsReview` reviews) and its home deck's remaining
 *    (`reviewsPerDay` − today's reviews attributed to that home deck, if the deck has a limit) are
 *    both `> 0`; taking it decrements both.
 * 4. **New**: budget = `min(newPerDay − today's introducedNew, newPerSession − newShownThisSession)`
 *    (`>= 0`). Sources are the **enabled** decks plus one virtual **deckless source** (ADR-006)
 *    holding the NEW cards with **no deck membership at all** (`memberships` empty), ordered by
 *    `(noteCreatedAt, kindOrder, cardId)`, with priority [QueueInput.decklessPriority] and no deck
 *    limit. A NEW card's **home new-source** is the first **enabled** deck of its memberships in
 *    `(priority, id)` order; with no memberships, the deckless source; if it has memberships but all
 *    of their decks are disabled, it has none and **is not introduced** ("a disabled deck supplies no
 *    new cards"). A NEW card is drawn only from its home new-source.
 *    Sources are grouped into **tiers** by priority, ascending; a tier is exhausted (every source
 *    empty or at its limit) before the next starts. Inside a tier, sources take turns round-robin —
 *    decks in ascending `id`, the deckless source last in each round — one **turn** being one card
 *    (together with its add_kanji cards, below) or one pending entry; an exhausted source is skipped.
 *    Inside a deck: `(position, itemKey, kindOrder, cardId)`; pending entries of that
 *    deck are interleaved by `(position, itemKey)` and, when reached, go to `toMaterialize`
 *    (counting 1 against the budgets) unless their `wordOverride` is KNOWN, NEVER_FORGET,
 *    BLACKLISTED or IGNORED. Cards whose `wordOverride` is KNOWN are not introduced.
 *    Per-deck limit: `deck.newPerDay` − today's `introducedNew` reviews whose card's home
 *    new-source is that deck. **add_kanji** of the drawing deck: BEFORE — before a vocab card,
 *    draw the eligible NEW cards of kanji notes whose `kanjiLiteral` is in its `expressionKanji`
 *    (expression order, then `kindOrder`) that are not yet taken; ALONGSIDE — the same cards right
 *    after the word's card. They count against the drawing deck and the budget; if the budget ends
 *    in the middle, the cards already drawn stay.
 * 5. **Order**: learning items first; then reviews `R` and new `N` interleaved: if either is empty
 *    the other follows alone; else `k = max(1, (|R| + |N|) / |N|)` (integer division) and position
 *    `i` (0-based within the interleaved part) holds a new card iff `(i + 1) % k == 0` and new
 *    cards remain, or reviews are exhausted.
 */
public interface QueueBuilder {
    /** Builds the queue for [input]. */
    public fun build(input: QueueInput): ReviewQueue
}

/** One live card of a vocab note, for [WordStatusRules.autoStatus]. */
public data class WordCardState(
    /** `card.state`. */
    public val state: CardState,
    /** `card.interval_days`. */
    public val intervalDays: Int?,
)

/**
 * `word_status.auto_status`, derived on every replica (SCHEMA comment on `auto_status`: "rule owned
 * by the core"), re-derived whenever a card of a vocab note with that entry is created, deleted,
 * merged or replayed.
 */
public interface WordStatusRules {
    /**
     * Auto status from the live, non-loser cards of the **live vocab note** of the entry:
     * - no live vocab note → null;
     * - [cards] empty, or every card NEW → [WordStatus.NEW];
     * - every non-NEW card in REVIEW with `intervalDays >= KNOWN_INTERVAL_DAYS` → [WordStatus.KNOWN];
     * - otherwise → [WordStatus.LEARNING].
     *
     * [noteLive] false means there is no live vocab note for the entry.
     */
    public fun autoStatus(noteLive: Boolean, cards: List<WordCardState>): WordStatus?

    /** `status = coalesce(override, auto, NEW)` (the `word_status` CHECK). */
    public fun effective(override: WordStatus?, auto: WordStatus?): WordStatus

    /** Constants. */
    public companion object {
        /** Interval in days from which a reviewed card counts as known (jpdb-style "mature"). */
        public const val KNOWN_INTERVAL_DAYS: Int = 21
    }
}
