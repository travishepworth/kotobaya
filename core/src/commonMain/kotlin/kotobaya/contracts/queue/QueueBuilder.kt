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

/**
 * Deck setting `add_kanji` (`deck.settings`, SCHEMA comment on `deck.settings`).
 *
 * **Deprecated (ADR-040): no longer read by [QueueBuilder].** Kanji cards come before their first
 * word for every drawing source (rule 4 "Kanji first"), whatever a deck stores here, and kanji
 * notes are minted for every drawn word (contracts/core README "C-27"). The enum stays so stored
 * values remain readable and existing callers compile; `off` and `alongside` no longer opt out.
 */
public enum class AddKanjiMode(
    /** Wire value. */
    public val wire: String,
) {
    /** Stored value `off`. Before ADR-040: no automatic kanji cards, no ordering constraint. Now without effect. */
    OFF("off"),

    /** Stored value `before`. What every source now does: new kanji cards immediately before the word's new card. */
    BEFORE("before"),

    /** Stored value `alongside`. Before ADR-040: kanji cards after the word's. Now without effect (kanji come first). */
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
    /** `deck.reviews_per_day`; null = only the global limit. Ignored when `QueueLimits.reviewsUnlimited` (ADR-034). */
    public val reviewsPerDay: Int?,
    /**
     * **Deprecated (ADR-040): ignored by the builder.** Was the deck's effective `add_kanji`
     * ordering. Kept so existing callers compile; any value gives the same build. New loaders pass
     * [AddKanjiMode.OFF] and do not resolve `scheduler.kanji_before_vocab`.
     */
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
    /**
     * ADR-042 (additive): for a vocab note, the kanji this word **requires**:
     * `KanjiRequirement.evaluate(...).learnableKanji` for the note's displayed form (a sub-list of
     * [expressionKanji], same order; empty for a word that requires none; ADR-045, ADR-046: a
     * word-level tagged word requires its jōyō kanji, an untagged one its learnable kanji, a kana
     * word has no kanji). Only these kanji join the word's group, block it, are minted for it and are
     * backfilled because of it (rule 4 "Kanji first"). `null` = the loader does not know (a loader
     * older than ADR-042): the builder then uses [expressionKanji], which is the behaviour with
     * `cards.kanji_exemptions` off. Loaders implementing ADR-042 always pass a list, for every card
     * of the note, in every state. Ignored for non-vocab notes. Example (setting on): 昨日 → `["昨",
     * "日"]`; 寿司 → `["寿", "司"]`; 鱈 → `[]`; 鱈子 → `["子"]`.
     */
    public val requiredKanji: List<String>? = null,
    /**
     * ADR-040 (additive): for a vocab note, the required kanji that have **no kanji note yet** and
     * that materialization would create, in required order (contracts/core README "C-27", step
     * A): a literal of the required kanji such that no live kanji note has that `kanji_literal`,
     * no user-deleted kanji note has it (`deleted_at` set and `merged_into_id` null: a kanji note
     * the user deleted is never re-created) and minting would give it at least one card. The same
     * value on every card of the note, in every state: the builder reads it on the NEW vocab cards
     * it draws and, for backfill (ADR-043), on words already introduced.
     * Empty = nothing to mint. Example: 学校 with a live note for 学 only → `[MissingKanji("校", 1)]`.
     */
    public val missingKanji: List<MissingKanji> = emptyList(),
    /**
     * ADR-040 (additive): **companion** of a scoped build. A loader that restricts candidates to a
     * scope (the API's `deck_id`) MUST also pass every live card of every live kanji note whose
     * `kanji_literal` is a required kanji of a passed vocab candidate or pending entry and that is
     * not itself in scope, with `companion = true` (real memberships, real state). Otherwise the
     * words of that deck would be served without their kanji. A companion: its NEW cards are drawn
     * only through a group or backfill (rule 4), never on a turn of their own; its
     * LEARNING/RELEARNING cards are served and counted like any other (rule 2: a kanji introduced
     * in this session comes back for its steps); its REVIEW cards are never taken or counted
     * (rule 3: out of scope). An unscoped loader passes false for every card. Default false.
     */
    public val companion: Boolean = false,
)

/**
 * ADR-040: one kanji that materializing a word would create a kanji note for
 * ([QueueCandidate.missingKanji], [PendingEntry.missingKanji]).
 *
 * Example: `MissingKanji(literal = "校", newCards = 1)`.
 */
public data class MissingKanji(
    /** The kanji, one code point (`CardGenerator.kanjiOf` element). */
    public val literal: String,
    /**
     * How many NEW cards of the minted note join the word's group: the number of card kinds step A
     * of the materialization creates for it (`kindsForNewNote(kanji type, null)` minus kinds whose
     * front would render empty), counting at most 1 when `scheduler.bury_siblings` (rule 1: one
     * NEW card per note in a build). `>= 1` (a literal that would get no card is not listed);
     * values `< 1` count as 1. With the built-in kanji type: 1.
     */
    public val newCards: Int,
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
    /**
     * ADR-037 (additive): `review_log.reviewed_at`. Used only by the rule 5 mix, to count the
     * reviews answered since the latest new card was introduced today. Loaders MUST pass it; the
     * default `0` exists only so that existing callers compile (with all values equal the mix
     * still is deterministic: every review counts until a card is introduced today, none after).
     * Example: `1795168802000`.
     */
    public val reviewedAt: InstantMs = 0,
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
    /**
     * C-27 (additive): the distinct kanji of the entry's headword in order of first appearance
     * (`CardGenerator.kanjiOf`), i.e. the `QueueCandidate.expressionKanji` its vocab card will have
     * once materialized. Empty = none or unknown (then no existing kanji card joins its group).
     * Since ADR-042 the group uses [requiredKanji] when that is non-null.
     */
    public val expressionKanji: List<String> = emptyList(),
    /**
     * **Deprecated (ADR-040): ignored by the builder**, replaced by [missingKanji] (which names
     * the kanji, so two words that share a missing kanji count it once). Was the number of NEW
     * kanji cards materializing this entry creates. Kept so existing callers compile; any value
     * gives the same build. New loaders pass 0.
     */
    public val newKanjiCards: Int = 0,
    /** ADR-042 (additive): as [QueueCandidate.requiredKanji], for the entry's headword. `null` = use [expressionKanji]. */
    public val requiredKanji: List<String>? = null,
    /**
     * ADR-040 (additive): as [QueueCandidate.missingKanji], for the entry's headword: the required
     * kanji without a kanji note that materializing this entry mints first. Loaders always pass
     * it, whatever the deck's `add_kanji` says.
     */
    public val missingKanji: List<MissingKanji> = emptyList(),
)

/** Daily limits and per-build flags (settings `scheduler.*`, SCHEMA.md 6). */
public data class QueueLimits(
    /** `scheduler.new_per_day` (default 20). */
    public val newPerDay: Int,
    /** `scheduler.reviews_per_day` (default 200). Ignored when [reviewsUnlimited] (ADR-034). */
    public val reviewsPerDay: Int,
    /**
     * **Deprecated (ADR-037): ignored by the builder.** Was `scheduler.new_per_session` (default
     * 10), the per-session new cap. Kept so existing callers compile; any value gives the same
     * build. New callers pass 0. Not annotated `@Deprecated` so that no caller gets a warning.
     */
    public val newPerSession: Int,
    /**
     * **Deprecated (ADR-037): ignored by the builder.** Was the UI's count of new cards shown in
     * the current session. Kept so existing callers compile; any value gives the same build. New
     * callers pass 0 and keep no such counter.
     */
    public val newShownThisSession: Int,
    /** `scheduler.bury_siblings` (default true). */
    public val burySiblings: Boolean,
    /**
     * Learning cards due within this many ms may be shown early, after everything else (rules 2, 5). Kotobaya's
     * server and devices pass 0 (ADR-033): a learning card is never served before its step has passed; the UI waits
     * for [ReviewQueue.nextLearningDueAt] instead.
     */
    public val learnAheadMs: Long,
    /**
     * C-26 (additive, ADR-031) **Keep going**: a stateless per-build override, never stored (not a
     * setting, not an op). When true the builder lifts every daily cap ([reviewsPerDay],
     * [newPerDay], `QueueDeck.reviewsPerDay`, `QueueDeck.newPerDay`), bounds new cards per build
     * by [keepGoingNewBatch] alone and puts reviews before new cards; every other rule is unchanged
     * ([QueueBuilder] rule 6). Default false = the normal build. Scope (ADR-039): one review
     * visit of one client; never persisted (rule 6 "Scope").
     */
    public val keepGoing: Boolean = false,
    /**
     * The **batch**: how many new cards one build draws at most, in **both** modes (ADR-037; before
     * it, only when [keepGoing]). Values `< 1` count as 1. Normal build: new groups are drawn while
     * fewer than the batch are drawn and the group fits the day's remaining goal (rule 4). Keep
     * going: the batch is the whole budget (rule 6). A kanji group may overshoot it. It bounds
     * `items` and `toMaterialize` of one build only: callers rebuild after every answer, so the
     * stream continues until the goal (normal) or the pool (keep going) is exhausted. The server
     * passes the request's `limit` (`getStatsSummary`: 20, the default `limit`); a device leaves the
     * default, which keeps note creation for pending entries to a handful per build.
     * Example: goal 40, 12 introduced, batch 10 → a normal build holds 10 NEW, not 28.
     */
    public val keepGoingNewBatch: Int = DEFAULT_KEEP_GOING_NEW_BATCH,
    /**
     * ADR-034 (additive): setting `scheduler.reviews_unlimited` (bool, default false; SCHEMA.md
     * 6.2). When true, rule 3 ignores the global [reviewsPerDay] cap and every
     * `QueueDeck.reviewsPerDay` cap: every eligible REVIEW card with `dueAt < day.endMs` is taken
     * (still at most one NEW/REVIEW item per note if [burySiblings]; never a card due at or after
     * `day.endMs`). Nothing else changes: new-card limits (rule 4), learning and learn-ahead
     * (rule 2), the proportional mix of rule 5 (its `rRem` is then every ready review) and
     * [ReviewQueue.moreAvailable] are as for false, and
     * [keepGoing] behaves as before (it already lifts the review caps). Unlike [keepGoing] this is
     * a persisted, synced setting and applies to every build, including the Today counts, so all
     * clients show the same counts. Default false = the behaviour before ADR-034, bit for bit.
     * The platform loader reads it with the bool test of SCHEMA.md 6.1 (anything but JSON
     * `true` → false).
     */
    public val reviewsUnlimited: Boolean = false,
) {
    /** Constants. */
    public companion object {
        /** Default [keepGoingNewBatch]: 10 (what a device uses for every build). */
        public const val DEFAULT_KEEP_GOING_NEW_BATCH: Int = 10
    }
}

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
    /**
     * Candidate cards. The loader MUST NOT pass a card whose `card.last_review_id` is
     * `Reserved.REPLAY_REFUSED_ID` (refused cache, SYNC.md 9.4): it has no trustworthy schedule and
     * would be offered as new every day. (C-8)
     */
    public val candidates: List<QueueCandidate>,
    /** Today's non-undone reviews (`reviewed_at` in `[day.startMs, now]`), any order. */
    public val today: List<TodayReview>,
    /** Entries of enabled decks without a live note. */
    public val pendingEntries: List<PendingEntry>,
    /**
     * Priority of the **deckless source** (ADR-006): setting `scheduler.mined_priority`
     * (int4, default 0 = the same tier as ordinary decks of priority 0, interleaved with them;
     * lower is drawn earlier). SCHEMA.md 6.2. The platform loader applies the int test of SCHEMA.md
     * 6.1 (`1.0` → 1; a fraction, out of range or non-number → 0).
     */
    public val decklessPriority: Int = 0,
    /**
     * **Deprecated (ADR-040): ignored by the builder.** Was the `add_kanji` ordering of the
     * deckless source, from `scheduler.kanji_before_vocab`. Kept so existing callers compile; any
     * value gives the same build (the deckless source puts kanji first like every source).
     */
    public val decklessAddKanji: AddKanjiMode = AddKanjiMode.OFF,
    /**
     * ADR-035 (additive): setting `cards.reading_enabled` (bool, default **false**; SCHEMA.md 6.4),
     * read by the platform loader with `kotobaya.contracts.cards.CardKinds.readingEnabled` (the 6.1
     * bool test: only JSON `true` is true). While false, every candidate for which
     * `CardKinds.isRetired(noteBaseType, kind, false)` holds (vocab `reading` cards) is **retired**
     * and rule 0 removes it. Loaders keep passing retired cards (they are live); the builder alone
     * applies the rule, so server and devices agree. True = the behaviour before ADR-035, bit for bit.
     */
    public val readingEnabled: Boolean = kotobaya.contracts.cards.CardKinds.READING_ENABLED_DEFAULT,
)

/** Why an item is in the queue. */
public enum class QueueItemKind {
    /** LEARNING or RELEARNING card due now, or within learn-ahead (rule 2; learn-ahead ones are placed last, rule 5). */
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
) {
    /**
     * C-26 (additive): true iff the card is in state NEW at build time, i.e. it is shown for the
     * first time (never reviewed, or made new again by a `manual` row with `manual_action =
     * 'reset'`) and answering it counts as introducing a card ([TodayReview.introducedNew]).
     * Exactly `kind == NEW`. The UI shows a small i18n "New" badge for it. Same meaning as the
     * API's `QueueItem.is_new`. Not a constructor property, so `equals`/`copy` are unchanged.
     */
    public val isNew: Boolean get() = kind == QueueItemKind.NEW
}

/** The result of [QueueBuilder.build]. */
public data class ReviewQueue(
    /** Cards to show, in order. A card appears at most once. */
    public val items: List<QueueItem>,
    /**
     * Entries the client should create notes and cards for (origin `deck`; contracts/core README
     * "C-27"), then rebuild. Bounded by the new limits, each counted as its whole kanji group.
     */
    public val toMaterialize: List<PendingEntry>,
    /** Earliest `dueAt` of a learning card due later today but outside learn-ahead; null if none. */
    public val nextLearningDueAt: InstantMs?,
    /**
     * C-26 (additive): "done under the limits, but keep going has more". True iff **all** of: the
     * input's `limits.keepGoing` is false; the queue is empty ([items], [toMaterialize] and
     * [kanjiToMaterialize] all empty); and building
     * the same input with `keepGoing = true` (same [QueueLimits.keepGoingNewBatch]) returns a
     * non-empty queue. False in every other case: always for a keep-going
     * build, and whenever the normal queue is non-empty (a learn-ahead item counts). Independent of
     * [nextLearningDueAt]; both may be set.
     *
     * **Deprecated as a UI signal (ADR-037)**: still computed with exactly this meaning (it feeds
     * [LegacyCounts.moreAvailable] and the API's deprecated `more_available`), but callers decide
     * end states from [LoopState.phase] ([ReviewLoop]), never from this. There is no session cap any
     * more, so it no longer means "done for now".
     */
    public val moreAvailable: Boolean = false,
    /**
     * ADR-040 (additive): kanji the client should create kanji notes for (origin `auto_kanji`;
     * contracts/core README "C-27", step A) **before** it materializes [toMaterialize], then
     * rebuild: the literals of the `missingKanji` of every group this build drew (existing vocab
     * cards and pending entries alike), distinct, in draw order and inside a group in required
     * order. A vocab card with a missing kanji is **withheld**: it and the existing kanji cards
     * of its group are taken and counted but are not in [items] until the kanji exist, so no
     * client ever shows a word before its kanji. Empty when nothing is missing.
     *
     * **Pending materialization** = `toMaterialize.size + kanjiToMaterialize.size` (the API's
     * `to_materialize`). Wherever this contract says a queue is **non-empty** it means: [items],
     * [toMaterialize] or `kanjiToMaterialize` is non-empty.
     * Example: deckless NEW card 学校, no kanji notes → `items = []`, `kanjiToMaterialize = ["学",
     * "校"]`; after minting, the rebuild gives `items = [学, 校, 学校]`.
     */
    public val kanjiToMaterialize: List<String> = emptyList(),
)

/**
 * Builds the review queue (SPEC "Queue building"). Pure and deterministic; the UI calls it again
 * after every answer (it is cheap to rebuild and that keeps sibling burying and limits exact).
 * Since ADR-037 callers do not call [build] directly for anything they show: they call
 * [ReviewLoop.evaluate], which runs the normal and the keep-going build and decides the phase.
 *
 * Rules, in order:
 * 0. **Retired cards** (ADR-035): the set `X` = ids of candidates with
 *    `CardKinds.isRetired(noteBaseType, kind, input.readingEnabled)`. Every candidate in `X` is
 *    dropped, and every [QueueInput.today] review whose `cardId` is in `X` is dropped, before any
 *    other rule runs. So a retired card is never served, counted, drawn (new or add_kanji), never
 *    sibling-buries another card (not even through a review answered today, e.g. an offline
 *    review synced later) and does not use up any daily limit; [ReviewQueue.nextLearningDueAt]
 *    and [ReviewQueue.moreAvailable] ignore it; it is never a word that makes a kanji "introduced"
 *    or "started" (rule 4). A today review of a card that is not among the
 *    candidates is kept as before (it cannot be identified as retired; loaders never omit a live
 *    retired card). Pending entries are unaffected: the kinds their materialization creates come
 *    from `CardGenerator.kindsForNewNote`, which never returns a retired kind, and an entry counts
 *    1 for its vocab card(s) as before.
 *    Example (burySiblings, readingEnabled false): note N with meaning M (REVIEW, due today) and
 *    reading R (REVIEW, due today, answered `scheduled` today) → `[M]` (R neither shown nor burying
 *    M); with readingEnabled true → `[]` (R's review buries M; R is not due again).
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
 *    Companions ([QueueCandidate.companion]) are included like any other card.
 *    They split into **due learning** (`dueAt <= now`) and **learn-ahead** (`now < dueAt <= now +
 *    learnAheadMs`); rule 5 places the two parts differently (C-22, ADR-023). A null `dueAt` (should
 *    not occur) counts as due learning, sorted first.
 * 3. **Reviews**: eligible REVIEW cards with `dueAt < day.endMs` that are not companions
 *    ([QueueCandidate.companion]: out of scope, never taken or counted), ordered by `(dueAt, cardId)`.
 *    The **home deck** of a card is the first of its membership decks in `(priority, id)` order
 *    (enabled or not); null without memberships. A card is taken iff the global remaining
 *    (`reviewsPerDay` − today's `countsAsReview` reviews) and its home deck's remaining
 *    (`reviewsPerDay` − today's reviews attributed to that home deck, if the deck has a limit) are
 *    both `> 0`; taking it decrements both. **Unlimited reviews** (ADR-034): if
 *    `limits.reviewsUnlimited`, both remainders are unlimited (as under rule 6), so every eligible
 *    REVIEW card with `dueAt < day.endMs` is taken; rules 1, 2, 4 and 5 are unchanged.
 *    Example: 300 eligible reviews due today, `reviewsPerDay` 200, nothing reviewed today →
 *    200 REVIEW items when false, 300 when true.
 * 4. **New**: **budget** (ADR-037; normal build): `dayRemaining = max(0, newPerDay − today's
 *    introducedNew)` and `batch = max(1, keepGoingNewBatch)`. `drawn` counts what this build has
 *    drawn against the budget (each card 1, each pending entry its whole unit, below). A turn's
 *    unit of size `g` is drawn whole iff `drawn < batch` **and** `drawn + g <= dayRemaining` (it
 *    fits the **day**; the batch may be overshot by `g − 1`, as in rule 6) and the per-deck test
 *    below holds. A unit that does not fit the day **leads** with as many of its kanji as fit and
 *    its source stops there for this build (ADR-043, "Fits or leads" below): the goal is not
 *    overshot (one exception, next sentence) and no source skips ahead of a word. Once
 *    `drawn >= batch` rule 4 stops.
 *    **A kanji part fits the day** (ADR-044; used by "Lead" and "Backfill" below, never for a
 *    whole unit or a single card) iff `drawn + size <= dayRemaining`, **or** it is the **first
 *    new card of the day**: `newPerDay >= 1`, [QueueInput.today] (after rule 0) has no
 *    `introducedNew` review and `drawn == 0`. So a part larger than the whole goal (possible only
 *    with a kanji note type of several new kinds; the built-in type has 1) is drawn once, as the
 *    day's first new cards, and overshoots the goal by `size − newPerDay`; without this it would
 *    never be drawn and its word never follow. After its first card is answered `today` has an
 *    `introducedNew` review, so the exception cannot repeat that day. `newPerDay <= 0` draws nothing.
 *    `newPerSession` and `newShownThisSession` are ignored (no session cap, no "done for now").
 *    Wherever the text below says "the budget" it means this test. Example: goal 40, 12 introduced,
 *    batch 10, 100 single cards → 10 NEW; 39 introduced and the next unit is `[学, 校, 学校]` → 学 alone.
 *    **`availableToday`** = what `drawn` would reach if the batch were unbounded (same input, same
 *    turn order, only the `drawn < batch` test removed): the new cards that can still be introduced
 *    today under the day goal and the per-deck caps, `<= dayRemaining` (except through the
 *    first-of-the-day part above). The batch-bounded draw is a
 *    prefix of that unbounded draw. Rule 5 uses it; [ReviewLoop] derives the legacy new count from
 *    the same unbounded draw. Example: 12 of 40 introduced, 100 new cards → 28; only 3 new cards
 *    exist → 3; a deck capped at 5 with 2 introduced from it and no other source → 3.
 *    Sources are the **enabled** decks plus one virtual **deckless source** (ADR-006)
 *    holding the NEW cards with **no deck membership at all** (`memberships` empty) that are not
 *    companions, ordered by `(noteCreatedAt, kanjiLiteral or "", kindOrder, cardId)` (the literal,
 *    compared by code point, only breaks ties between kanji notes minted in the same millisecond,
 *    so server and device order them alike), with priority [QueueInput.decklessPriority] and no deck
 *    limit. A NEW card's **home new-source** is the first **enabled** deck of its memberships in
 *    `(priority, id)` order; with no memberships, the deckless source; if it has memberships but all
 *    of their decks are disabled, it has none and **is not introduced** ("a disabled deck supplies no
 *    new cards"). A NEW card is drawn only from its home new-source.
 *    Sources are grouped into **tiers** by priority, ascending; a tier is exhausted (every source
 *    empty or at its limit) before the next starts. Inside a tier, sources take turns — one
 *    **turn** being one **unit**: one card together with its kanji group (below), or one pending
 *    entry with its group. A companion NEW card has no turn of its own.
 *    **Turn order (C-28c; replaces "round-robin, restarted at every build"; C-28d: rule (a)
 *    by source order, rule (c)).** The **source
 *    order** of a tier is: decks in ascending `id`, the deckless source last. A source can take
 *    a turn while it is not empty, not at its per-deck limit and not stopped by a lead. Which
 *    of them takes the next turn is decided from data only, so that a rebuild continues where
 *    the previous build was instead of starting again at the first deck:
 *    - **Turn count** `t(S)` of a source S = the number of today's `introducedNew` reviews
 *      (after rule 0) whose card's home new-source is S (from [TodayReview.deckIds] and the
 *      decks now, exactly as for the per-deck limit; no deck ids = the deckless source; decks
 *      all disabled = no source) **plus** the cards taken so far in this build whose home
 *      new-source is S (a pending entry counts 1 for its deck when drawn), **not counting a
 *      required deckless kanji card**: a card of a kanji note whose home is the deckless source
 *      and whose `kanjiLiteral` is in `req(X)` of some vocab candidate (after rule 0, in any
 *      state, eligible or not) or pending entry X of the input. Such a card is drawn for a
 *      word or by backfill, not as the deckless source's own choice (auto-minted kanji). A
 *      deckless kanji card that **no** word requires (kanji notes the user added by hand, or
 *      minted ones whose word was deleted) is an ordinary card of the deckless source and
 *      **counts**, so the deckless source cannot take every turn with them. The test is the
 *      same for today's reviews and for this build's draws (a today review is such a card iff
 *      its `cardId` is a candidate of a kanji note, its `deckIds` give the deckless source and
 *      the literal is required as above; a review whose card is not among the candidates
 *      counts), and it does not depend on how the card was drawn. A kanji
 *      card homed in a deck counts for that deck whichever unit or step took it. A lead's word
 *      is not taken and does not count.
 *    - **Last new card** `lastNew`: the last thing rule 4 has drawn so far in this build (a
 *      card, or the last literal claimed for minting); when nothing is drawn yet, the card of
 *      today's `introducedNew` review with the greatest `(reviewedAt, cardId)` (rule 5's
 *      `last`); else none. `lastLiteral` = its kanji literal when it is a card of a kanji note
 *      or a claimed literal, else none. A drawn pending entry (`e:<seq>`, put in
 *      `toMaterialize`) is the last thing its unit draws, after the unit's kanji cards and
 *      claimed literals, exactly like the vocab card of a whole unit (withheld or not):
 *      `lastNew` is then the entry, which has no literal, so `lastLiteral` is none after
 *      `[先, 生, 先生(entry)]`.
 *    - **Claims (C-28d).** A source S **claims** a literal L iff S can take a turn and the
 *      item its next turn would draw is the unit of a word W (card or entry, not held) with
 *      `L in req(W)`, or a NEW kanji card with `kanjiLiteral == L` (drawn alone, "Own turn"
 *      below). A source that is empty, at its per-deck limit or stopped by a lead claims
 *      nothing.
 *    - **Next turn** in the current tier, three steps in this order (C-28d: (a) no longer
 *      compares `t(S)`; (c) is new):
 *      (a) **open unit**: if `lastLiteral` is L and some source of the tier claims L, the
 *      **first of them in source order** is the candidate.
 *      (b) Otherwise the candidate is the source that can take a turn with the smallest
 *      `t(S)`, ties in source order.
 *      (c) **earliest claimer**: while the candidate's next turn would draw the unit of a
 *      word and some source of the tier **earlier in source order** than the candidate
 *      claims the literal of one of that unit's kanji parts ("Kanji parts" below, as the
 *      unit would be built at this moment: a literal of `req` whose `kc` is not empty or
 *      that the unit would mint), the earliest such source becomes the candidate; repeat
 *      (the candidate moves strictly earlier, so this ends). A candidate whose next turn
 *      draws a single card is final.
 *      The candidate takes the turn.
 *    With nothing introduced today and one-card units this is the old round-robin (decks by
 *    id, the deckless source last in each round). After an answer the counts are what they
 *    were in the previous build at the same point, so the rebuild picks the same source next:
 *    a whole unit adds to `t` at once what its cards add one by one when answered. While a
 *    unit is half answered (its kanji 学 answered, `[校, 学校]` left) the counts differ from
 *    the build's, and rules (a) and (c) are what gives the turn back to the unit's own
 *    source: by (c) a unit is only ever drawn by a source that no earlier source disputes
 *    any kanji part with, so for every kanji of the unit its source is the first claimer in
 *    source order, and that is what (a) picks once that kanji is the last new card. Both
 *    read the data only (which source drew a kanji is stored nowhere, and need not be).
 *    Rule (a) applies inside a build as well (after a kanji drawn by the share step, on a
 *    deck's own turn or as a lead, the first source that claims it goes next), which is
 *    what makes build and rebuild agree; no rule ever moves a unit that is already drawn.
 *    (a) deliberately does not look at `t`: a count changes when a kanji homed in a deck is
 *    answered, source order does not. (c) costs no fairness: the earlier source's count
 *    rises with the unit it draws, so (b) serves the others next.
 *    Example (goal 4; deck D holds the kana words a, b; the deckless source holds 学校 and,
 *    created after it, the NEW kanji notes 学, 校; one tier; nothing introduced today):
 *    `t(D) = t(deckless) = 0` → D: a (`t(D) = 1`); deckless: `[学, 校, 学校]` (`drawn = 4`,
 *    `t(deckless) = 1`: only the word counts) → N = `a 学 校 学校`. After a is answered:
 *    `t(D) = 1 > t(deckless) = 0` → the deckless unit first, it fits (3 of 3) → `学 校 学校`
 *    (a restart at D would draw b, and the unit would no longer fit and lead: the word
 *    withdrawn after its kanji). After 学: `lastLiteral = 学` and the deckless source's next
 *    word requires it → rule (a) → `校 学校`. The next day: a tie → D: b.
 *    Example (kanji no word requires; goal 20, batch 10; deck D holds 30 new kana words w1,
 *    w2, …; the deckless source holds 50 NEW kanji notes k1, k2, … the user added by hand, no
 *    vocab candidate or entry requires any of them; one tier; nothing introduced today): each
 *    kanji card counts for the deckless source → `w1 k1 w2 k2 w3 k3 w4 k4 w5 k5`,
 *    `availableToday = 20` (10 words, 10 kanji). After w1 and k1 are answered (`t = 1, 1`) →
 *    `w2 k2 …`. Were they left out of `t`, the deckless source would stay at 0, win every
 *    turn and D would introduce nothing until all 50 are done.
 *    Example (C-28d, a kanji that two sources need; goal 5; deck D holds the kana word a,
 *    then 日本語; the deckless source holds the word 本 and, created after it, the NEW kanji
 *    notes 本, 日, 語; one tier; nothing introduced today): tie → D: a (`t(D) = 1`). (b) names
 *    the deckless source (`0 < 1`), whose turn would draw the unit `[本, 本(word)]`; D is
 *    earlier in source order and claims 本 (its next word 日本語 requires it) → (c): D takes
 *    the turn, `[日, 本, 語, 日本語]` (`drawn = 5`, `t(D) = 2`). The deckless word no longer
 *    fits and leads with nothing (本 is taken) → N = `a 日 本 語 日本語`, `availableToday = 5`.
 *    After a: the same two steps → `日 本 語 日本語`. After 日: only D claims 日 → `本 語 日本語`.
 *    After 本: `lastLiteral = 本`, D and the deckless source both claim it, D is first in
 *    source order → `語 日本語`, the tail. (The smallest `t` would name the deckless source
 *    here, `0 < 1`, although 本 was drawn in D's unit: the word 本 would be served, and
 *    `[語, 日本語]` would no longer fit, lead with 語 and lose 日本語 until the next day.) The
 *    next day, nothing else answered: `語 日本語 本(word)`.
 *    Inside a deck: `(position, itemKey, kindOrder, cardId)`; pending entries of that
 *    deck are interleaved by `(position, itemKey)` and, when reached, go to `toMaterialize`
 *    unless their `wordOverride` is KNOWN, NEVER_FORGET, BLACKLISTED or IGNORED. Cards whose
 *    `wordOverride` is KNOWN are not introduced.
 *    Per-deck limit (remaining, clamped to `>= 0`): `deck.newPerDay` − today's `introducedNew`
 *    reviews whose card's home new-source is that deck (from [TodayReview.deckIds] and the decks
 *    now) − the cards taken in this build whose home new-source is that deck, **including kanji
 *    cards whose home new-source is that deck, whichever unit took them; excluding kanji cards
 *    this deck's units took whose home is elsewhere** (C-8 attempt 3: the same count before and
 *    after a rebuild).
 *    **Kanji first (ADR-040, ADR-042; replaces the add_kanji modes).** A new vocab card is never
 *    served before the new kanji cards of the kanji it requires. This holds for **every** drawing
 *    source: decks whatever their `add_kanji` says, and the deckless source. [QueueDeck.addKanji],
 *    [QueueInput.decklessAddKanji] and `scheduler.kanji_before_vocab` are not read.
 *    - **Required kanji** `req(X)` of a vocab card or pending entry X: `X.requiredKanji`, or
 *      `X.expressionKanji` when that is null (ADR-042, ADR-045, ADR-046: the loader applies
 *      `KanjiRequirement`; a word may require none). Cards of other
 *      base types require none.
 *    - **Started.** A literal L is **started** iff some candidate (after rule 0; eligible or not,
 *      suspended or not, companion or not) of a kanji note with `kanjiLiteral == L` is in a state
 *      other than NEW. A started kanji never blocks a word, joins no group and is not backfilled
 *      ("kanji that already have a learning or review card do not block").
 *    - **Kanji cards of L**, `kc(L)`: empty if L is started; else the eligible (rule 1) NEW
 *      candidates of kanji notes with `kanjiLiteral == L` that are not yet taken in this build,
 *      in `(noteCreatedAt, noteId, kindOrder, cardId)` order, at most one per note if
 *      `burySiblings`. **Whatever their home new-source**: a kanji card whose decks are all
 *      disabled, a deckless one and a companion all join (this amends ADR-006 for kanji cards
 *      pulled by a word; such a card still has no turn of its own). A suspended kanji card is
 *      not eligible: it neither joins nor blocks. A manually buried one is not eligible either,
 *      but it **holds** the word ("Buried kanji" below).
 *    - **Unit of a vocab card V** (NEW, eligible, reached on a turn of its home new-source):
 *      `K` = `kc(L)` for each L of `req(V)` in order; `M` = the elements of `V.missingKanji` whose
 *      literal is in `req(V)`, has no candidate kanji note in the input (a loader inconsistency
 *      is ignored) and was not **claimed** by an earlier unit of this build. Size `g = 1 + |K| +
 *      Σ newCards(M)`. Queue order of the unit: `K` (required order, then `kc` order), then V.
 *      Kanji always come first; there is no "alongside".
 *    - **Unit of a pending entry E** (C-27): the same with E in place of V (`req(E)`,
 *      `E.missingKanji`); the 1 stands for the vocab card its materialization creates. E goes to
 *      `toMaterialize`, never to `items`.
 *    - **Kanji parts.** The unit's kanji come in parts, one per required literal that
 *      contributes, in required order: the `kc(L)` cards of L, or the `newCards` cards to mint
 *      for an element of `M`. A part is never divided.
 *    - **Buried kanji (ADR-043).** If for some L of `req(V)` that is not started a NEW candidate
 *      of a kanji note of L is not suspended and is manually buried (`buriedUntil > now`), the
 *      unit is **held**: nothing of it is drawn in this build and the turn passes to the source's
 *      next card. The word comes back when the bury ends (normally the next study day), still
 *      behind its kanji. Suspended kanji cards and retired cards never hold a word.
 *    - **Fits or leads (ADR-043; replaces "atomic or nothing").** A unit that is not held is
 *      considered iff its vocab card or entry fits the drawing source's per-deck remaining
 *      (`>= 1`; the deckless source has no limit) and `drawn < batch`.
 *      **Deck cost (C-28c).** When the drawing source is a deck D with a limit (normal build,
 *      `D.newPerDay != null`), the **deck cost** of a kanji part is the number of its cards
 *      whose home new-source is D itself (0 for cards homed elsewhere, deckless or without a
 *      home, and for cards to mint: minted notes are deckless), and the deck cost of the unit is
 *      `dc = 1 + Σ deck cost(parts)`: the word plus the kanji that D itself supplies. A part
 *      **fits the deck** iff its deck cost is 0, or `<=` D's per-deck remaining at that moment,
 *      or it is **the deck's first new card of the day** (`D.newPerDay >= 1`, no
 *      `introducedNew` review of today is attributed to D and nothing was taken from D in this
 *      build; the per-deck mirror of ADR-044's first part of the day). The deckless source, a
 *      deck without a limit and every source of a keep-going build have no deck test: `dc`
 *      always fits. Then:
 *      - **Whole**: if `drawn + g <= dayRemaining` (keep going: always) **and** `dc <=` D's
 *        per-deck remaining, the unit is drawn:
 *        V and `K` are taken, the literals of `M` are claimed (appended to
 *        [ReviewQueue.kanjiToMaterialize]), `drawn += g`, D's remaining `-= dc`.
 *      - **Lead**: otherwise its kanji parts are taken in order while the part **fits the day**
 *        (budget paragraph above: `drawn + size(part) <= dayRemaining`, or the first new card of
 *        the day) **and fits the deck** (above; each taken part lowers D's remaining by its
 *        deck cost), stopping at the first part that fails either test (none only when
 *        something was already introduced today or drawn in this build, or the deck has no
 *        room for its own first part). The taken
 *        `kc` cards are items of this build, the taken missing literals are claimed; V is **not**
 *        taken (an entry is not put in `toMaterialize`) and does not count against the per-deck
 *        limit. The **source then stops** for this build: it takes no further turn, so nothing
 *        behind the word in that source is introduced before it. Other sources go on.
 *        **The lead ends at the deck's limit (C-28d).** When D has a limit and, after a part
 *        is taken, D's per-deck remaining (clamped) is 0, the lead ends there: no further
 *        part is taken, **not even one of deck cost 0** (a kanji homed elsewhere). Once the
 *        part that used up the limit is answered, D takes no turn any more that day, so the
 *        rebuild could not draw a later part again: it would be shown and then withdrawn
 *        (`untilStop` dropping by 2 for one answer). The part left out comes with the word
 *        on a later day (its deck cost is 0, so it never keeps the word from fitting D), or
 *        before that on a turn of its own home source or by backfill. A lead whose parts all
 *        have deck cost 0 is not affected (D's remaining does not change).
 *      So today's goal is filled exactly, not overshot (but for a first part larger than the
 *      goal), and a word whose unit is larger than
 *      what is left (or than `newPerDay` itself) is not starved: its kanji are introduced over
 *      as many days as needed and the word follows, with its last kanji, on the first day the
 *      rest fits. Kanji introduced on an earlier day are started and no longer part of the unit.
 *      The same holds for a deck's own limit: **a word is drawn only when the deck's limit has
 *      room for it and for every kanji card of its unit that is homed in that deck**, so a
 *      build never shows `[学, word]` and then, 学 answered and the deck's limit used up by it,
 *      withdraws the word (before C-28c the unit was drawn whenever the deck had room for the
 *      word alone, and the rebuild after the kanji's answer dropped the word).
 *    - **Withheld.** If V has any element of `missingKanji` in `req(V)` without a candidate
 *      kanji note (claimed by this unit or by an earlier one), a whole unit is drawn and counted
 *      but **none of its cards is an item** of this build (as for an entry): the word must not be
 *      shown before its kanji exist. A lead is withheld the same way iff one of its taken parts
 *      is a claimed missing literal. After materialization the rebuild shows the unit, with the
 *      same counts (the minted cards are then in `K`).
 *    - **Attribution.** V (or E) counts 1 against the drawing source's per-deck limit. Each card
 *      of `K` and each card to be minted counts against the budget only and, when the kanji card
 *      has a home new-source that is a deck, against **that** deck's daily count, never the
 *      drawing source's (when that deck **is** the drawing deck the two coincide: this is the
 *      "deck cost" above, tested before the draw); a kanji card homed in **another** deck is
 *      never skipped because its home deck's limit is used up
 *      (that deck's remaining is clamped to 0 for its own later turns). [QueueInput.today] counts
 *      an answered kanji card the same way, so a rebuild neither frees the drawing deck's limit
 *      nor double-counts.
 *    - **Backfill (ADR-040, ADR-043, ADR-044).** In both builds. A literal
 *      L is a **backfill kanji** iff it is not started and is a required kanji of an
 *      **introduced** word: a vocab note W with `L in req(W)`, at least one candidate of W (after
 *      rule 0) in a state other than NEW, and `wordOverride` neither BLACKLISTED nor IGNORED.
 *      Its part is `kc(L)` when a candidate kanji note of L exists, **whatever the cards' home
 *      new-source** (deckless, a deck, or none because its decks are all disabled), so a kanji
 *      that sits in a low-priority or capped deck is not starved. The part is possibly empty
 *      (suspended or buried cards are simply not drawn; cards already taken in this build are
 *      not in `kc`): an empty part is passed over. Else the `newCards`
 *      cards to mint for the element of
 *      `W.missingKanji` with that literal (ADR-043: backfill **also mints** the missing required
 *      kanji of words already studied; a literal in no `missingKanji`, e.g. one whose note the
 *      user deleted, has no part). Order of the parts: by `(W.noteCreatedAt, W.noteId, index of
 *      L in req(W), L)` of the first introduced word W in `(noteCreatedAt, noteId)` order that
 *      requires L, so the kanji of the oldest words come first and the order is the same before
 *      and after minting. Each part is a unit of its own (size = its number of cards), outside
 *      tiers, turns and per-deck tests, whatever the card's home new-source; attribution as for a
 *      `K` card. A part to mint claims its literal ([ReviewQueue.kanjiToMaterialize]) and has no
 *      item until the rebuild.
 *      **Half share (ADR-044; replaces "all backfill before every source").** Backfill may use at
 *      most half of the new cards of a study day; the rest belongs to the sources. Terms:
 *      - `iToday` = the number of `introducedNew` reviews in [QueueInput.today] (after rule 0).
 *      - `bToday` = how many of those are **backfill introductions**: a review `r` whose card is
 *        a candidate of a kanji note with literal L (whatever its home new-source and whichever
 *        way it was drawn: the build cannot tell and does not need to) such that some vocab note W has `L in req(W)`,
 *        `wordOverride` neither BLACKLISTED nor IGNORED, and a candidate `c` of W (after rule 0)
 *        in a state other than NEW that has **no** `introducedNew` review in `today` with
 *        `reviewedAt >= r.reviewedAt` (W was already introduced when the kanji was; a word of
 *        an earlier day always qualifies). A kanji answered ahead of its own word in a unit or a
 *        lead is therefore not counted (its word is introduced later or not yet); a backfill
 *        kanji that a new word's unit happened to take is counted. Stateless: derived from the
 *        candidates and `today` only, so it is the same on every client and after every rebuild.
 *      - `b` = the NEW cards of backfill kanji drawn so far in this build, **by whatever path**:
 *        the share step, a deck's own turn ("Own turn" below) or `K` of a new word's unit, plus
 *        the cards to mint of claimed backfill parts. (The same cards are in `bToday` once
 *        answered, so `bToday + b` does not change when the head is answered.) Only the share
 *        step is ever refused by the share test; the other paths count but are not limited by it.
 *      - **Share test** for the next part, of `size` cards. Normal build:
 *        `share = max(1, newPerDay / 2)` (integer division; `newPerDay` is
 *        `limits.newPerDay`, the whole goal, not `dayRemaining`), and the test is
 *        `bToday + b + size <= share` **or** `bToday + b == 0` (a part larger than the share is
 *        still the day's first backfill, so at least one part per day). Keep going (rule 6; no
 *        day goal exists): `2 * (bToday + b + size) <= iToday + drawn + size` (64-bit), i.e.
 *        with the part, backfill is still at most half of everything introduced today plus
 *        everything this build has drawn.
 *      - **Share step**: before **every** source turn (so also before the first): the not yet
 *        drawn parts are drawn in order while `drawn < batch`, the part fits the day (budget
 *        paragraph; keep going: always) and the share test holds. The first part that fails one
 *        of the three ends the step (no skipping ahead to a smaller part); the next step starts
 *        again at that same part. In a normal build the share test does not depend on `drawn`,
 *        so everything the share allows is drawn before the first turn and the later steps draw
 *        nothing; in a keep-going build the steps interleave backfill with the sources (about
 *        one kanji per source card), which is what makes one build equal to rebuilding after
 *        every answer.
 *        **Not while a unit is open (C-28d; keep-going build only).** In a keep-going build
 *        the share step draws no part while `lastLiteral` is L and some source, **of
 *        whichever tier**, claims L ("Turn order" above). The test is made before every
 *        part: a step is skipped whole when the last new card opened a unit, and a step
 *        ends right after a part whose kanji a source claims. It is a third way for a step
 *        to end, with the same effect as the others: the next step starts again at the same
 *        part. Reason: the keep-going share test grows with `iToday + drawn`, so right after
 *        the first kanji of a unit is answered it would admit a backfill part that the
 *        build before had placed behind the whole unit. The normal build has no such rule
 *        and needs none (its share test reads neither `drawn` nor `iToday`); the remainder
 *        step is not affected, so backfill is only ever delayed by the open unit, not lost.
 *      - **Remainder step** ("with nothing else to draw, backfill may use the remainder"): after
 *        the source turns have ended — every source is empty, at its per-deck limit or stopped by
 *        a lead (also when there is no source at all) — the parts still not drawn are drawn in
 *        order while `drawn < batch` and the part fits the day (keep going: `drawn < batch`),
 *        **without** the share test; the first part that does not fit ends rule 4. These cards
 *        count in `bToday` once answered, like any backfill introduction.
 *      So on a normal day the kanji of words the user already studies come ahead of every other
 *      new card, but only up to half the goal; the sources get the rest, and whatever they
 *      cannot use goes back to backfill. A unit that does not fit after the share step leads as
 *      usual. The share is per study day, not per build: answering a backfill kanji moves it from
 *      `b` to `bToday`, so a rebuild draws the same cards (`drawn + iToday`, `availableToday +
 *      iToday` and the queue order are the same before and after an answer).
 *    - **Own turn.** A NEW kanji card that is not taken by a unit or by backfill is an ordinary
 *      card of its home new-source and is drawn alone on that source's turn (a kanji deck
 *      studied for itself). That keeps it before any word that requires it. One exception:
 *      **No own turn for deckless backfill kanji (ADR-044).** A NEW card of a kanji note whose
 *      literal is a backfill kanji (definition above; evaluated once per build, before the first
 *      draw) and whose home new-source is the **deckless source** has no turn of its own, in
 *      both builds: on the deckless source's turn it is passed over as if it were not there (not
 *      taken, not counted; a deckless source holding only such cards is empty). It is drawn only
 *      by the share step, by the remainder step, or as part of `K` of a new word's unit. Without
 *      this the half share would be void: auto-minted kanji notes are deckless, so the deckless
 *      source would draw them on its own turns, next to the share (same tier) or ahead of every
 *      deck (lower `decklessPriority`).
 *      **A deck-homed backfill kanji card has both ways**: it is in its literal's backfill part
 *      (share and remainder steps, never skipped for its deck's priority, position or per-deck
 *      limit; it counts against its home deck's daily count by "Attribution") **and** it keeps
 *      its deck's own turn (the user chose to study that deck; subject to that deck's tier,
 *      position and per-deck limit as any card). Whichever reaches it first takes it; a taken
 *      card is in neither afterwards. Either way it counts in `b` / `bToday`; an own-turn draw
 *      is not refused by the share test, so a kanji deck may carry backfill kanji past the share.
 *    Edge cases: two words sharing a new kanji → the first drawn takes it, the second unit is the
 *    word alone (or with its other kanji); the same kanji twice in a word counts once; a word with
 *    one started, one new and one missing kanji → `[new kanji, word]` withheld until the missing
 *    one is minted, then `[new, minted, word]` in required order; `burySiblings` false and a kanji
 *    note with two NEW kinds → both in `K`; a kanji note the user deleted → not in `missingKanji`,
 *    the word is drawn without it; a word whose own decks are all disabled is not introduced (as
 *    before), so it pulls nothing; KNOWN-override words are not introduced and pull nothing; a
 *    lead of zero parts (no room is left for its first part: something was already introduced
 *    today or drawn in this build) still stops its source; a first part larger than `newPerDay`
 *    is drawn as the day's first new cards (ADR-044); `newPerDay` 1 → share 1: backfill takes
 *    the whole goal until it is done (ADR-044 "at least 1"); `newPerDay` 3 → share 1;
 *    **the deck's first new card of the day** (C-28c, "Deck cost"): a kanji part whose deck
 *    cost is larger than the deck's whole `newPerDay` (several NEW kinds, `burySiblings`
 *    false) is taken when nothing homed in that deck was introduced today or taken in this
 *    build, and overshoots the deck's limit by `deck cost − newPerDay` that once; without it
 *    the part and its word would never be drawn from that deck; with one-card parts (the
 *    built-in kanji type) the exception never changes a result;
 *    a held word does not stop its source, and a NEW kanji of a held word that is not itself
 *    buried keeps its own turn; a deck with remaining 1 whose next word has a kanji card homed
 *    in the same deck leads with that kanji (C-28c "Deck cost"); a deck with remaining 0 draws
 *    nothing, not even a lead; a capped deck's lead stops after the part that uses up its
 *    limit, whatever follows (C-28d); a kanji card buried by the user whose word is
 *    then deleted is an ordinary card again when the bury ends.
 *    Example (goal 20, deckless NEW cards in creation order: たべる (no kanji), 学校, 先生; kanji
 *    notes: 学 NEW, 先 REVIEW; none for 校, 生): units `[たべる]`, `[学, (校), 学校]` withheld (g = 3,
 *    claims 校), `[(生), 先生]` withheld (g = 2, claims 生; 先 is started) → `items = [たべる]`,
 *    `kanjiToMaterialize = [校, 生]`, `drawn = 6`; after minting: `items = [たべる, 学, 校, 学校, 生,
 *    先生]`, `drawn = 6`.
 *    Example (lead; 39 of 40 introduced, the next unit of the deck is `[学, 校, 学校]`): it does
 *    not fit → 学 is drawn (40 of 40), the deck stops; the next day `[校, 学校]` is a unit of 2.
 *    Example (no starvation; `newPerDay` 2, deckless 日本語 then たべる, kanji notes 日, 本, 語 NEW):
 *    day 1 → `[日, 本]` (たべる is not drawn: the source stays at 日本語); day 2, 日 and 本 started →
 *    `[語, 日本語]`; day 3 → `[たべる]`.
 *    Example (buried; deckless 学校 then たべる, then the kanji notes 学 and 校 (created after the
 *    words); 学 NEW and buried until tomorrow, 校 NEW) → N = `[たべる, 校]`: 学校 is held, so 校 is
 *    not drawn through it (not as a part of its unit, not ahead of たべる); the source goes on to
 *    たべる and then reaches 校, which is an ordinary deckless card with a turn of its own ("Own
 *    turn": the hold reserves nothing). Tomorrow: `[学, 学校]` if 校 was answered today (it is
 *    started), else `[学, 校, 学校]`. Either way the word comes after both kanji (C-28c: the
 *    example said `[たべる]`, which contradicted "Own turn"; the rule stands).
 *    Example (deck limit, C-28c; deck "Core" with `newPerDay` 1 holds, in position order, the
 *    word 本, the kanji note 本 (NEW) and うっかり; goal 20, nothing introduced today): the unit
 *    `[本(kanji), 本(word)]` has deck cost 2 > 1 → it leads with the kanji (deck cost 1, fits)
 *    and the deck stops → N = `[本(kanji)]`, `availableToday = 1`. After it is answered the
 *    deck's remaining is 0 → N = `[]`: nothing was promised and withdrawn. The next day the
 *    kanji is started → `[本(word)]`; うっかり the day after. With `newPerDay` 2: `[本(kanji),
 *    本(word)]` whole (deck cost 2), and after the kanji is answered `[本(word)]`. With the kanji
 *    note deckless instead (limit 1): deck cost 1 → `[本(kanji), 本(word)]` whole, and after the
 *    kanji is answered (no review is attributed to "Core") `[本(word)]`.
 *    Example (C-28d, a lead ends at the deck's limit; deck "Core" with `newPerDay` 1 holds
 *    学校 and then the kanji note 学 (NEW); the kanji note 校 (NEW) is an item of a disabled
 *    deck only; goal 20, nothing introduced today): deck cost 2 > 1 → lead: 学 (deck cost 1,
 *    fits) is taken, "Core" has 0 left and the lead ends; 校 (deck cost 0) is **not** taken
 *    → N = `[学]`, `availableToday = 1`. After 学: N = `[]`. The next day 学 is started:
 *    `[校, 学校]` whole (deck cost 1). Before C-28d the lead was `[学, 校]` and 校 disappeared
 *    with the answer of 学 (`untilStop` 2, then 0). With 校 deckless instead: the same lead
 *    `[学]`, and 校 is an ordinary card of the deckless source, drawn on its own turn → N =
 *    `[学, 校]`, after 学 `[校]`; the next day, both answered, `[学校]`.
 *    Example (backfill; goal 20, so share 10): 先生 and 学校 are in REVIEW (先生 the older note), kanji notes 先 and 生 are
 *    NEW, 学 and 校 have no note, a new word たべる waits → parts 先, 生, (学), (校): `items = [先, 生,
 *    たべる]`, `kanjiToMaterialize = [学, 校]`, `drawn = 5`; after minting N = `[先, 生, 学, 校, たべる]`.
 *    Example (half share, ADR-044; `newPerDay` 4, nothing introduced today): 日本語 is in REVIEW,
 *    kanji notes 日, 本, 語 are NEW, a deck holds the new words うっかり then Ｔシャツ → share 2: the
 *    share step draws 日, 本 and stops at 語 (`0 + 2 + 1 > 2`); the deck draws うっかり, Ｔシャツ
 *    (`drawn = 4`); the remainder step has no room → N = `[日, 本, うっかり, Ｔシャツ]`. The deckless
 *    source, which holds the cards of 日, 本 and 語 (all backfill kanji), has no turn to take:
 *    語 is not drawn on it, whatever `decklessPriority` is. After 日 and 本
 *    are answered: `bToday = 2`, the share step draws nothing, N = `[うっかり, Ｔシャツ]`. The next
 *    day: `[語]`. Without the two words: day 1 → `[日, 本, 語]` (語 by the remainder step).
 *    With `newPerDay` 5: share 2, N = `[日, 本, うっかり, Ｔシャツ, 語]` (語 by the remainder step,
 *    after the sources).
 *    Example (deck-homed backfill kanji; the same data, `newPerDay` 4, but the note of 日 is an
 *    item of a second enabled deck "Kanji", same priority, greater id than the word deck): the
 *    share step draws 日, 本 as before (日 counts against the daily count of "Kanji"); turns:
 *    うっかり (word deck), nothing ("Kanji": 日 is taken), Ｔシャツ → N = `[日, 本, うっかり, Ｔシャツ]`, the
 *    same as with 日 deckless. The same if "Kanji" has priority 10, `newPerDay` 0 or is
 *    disabled: backfill does not depend on the deck. With 語 in "Kanji" instead of 日: the share
 *    step draws 日, 本 and stops at 語; turns: うっかり, then 語 on the own turn of "Kanji"
 *    (`drawn = 4`) → N = `[日, 本, うっかり, 語]` (`b = 3`: the kanji deck carried one backfill kanji
 *    past the share); the next day `[Ｔシャツ]`. With "Kanji" at priority 10 in that variant: its
 *    tier is not reached, N = `[日, 本, うっかり, Ｔシャツ]`, 語 the next day by the share step.
 *    Example (keep going; the same data plus a third new word あれ after Ｔシャツ; goal 2, met with 日
 *    and 本 introduced today: `iToday = 2`, `bToday = 2`; batch 10): before the first turn 語 fails
 *    (`2 * 3 > 2 + 0 + 1`); うっかり (`drawn = 1`); 語 fails (`6 > 4`); Ｔシャツ (`drawn = 2`); 語
 *    fails (`6 > 5`); あれ (`drawn = 3`); no turn is left, the remainder step draws 語 → N =
 *    `[うっかり, Ｔシャツ, あれ, 語]`. With `iToday = 4`, `bToday = 2` instead (goal 4, two words also
 *    done) and words left: 語 fails (`6 > 5`), one word (`drawn = 1`), then 語 passes (`6 <= 6`):
 *    a word and a kanji in turn.
 *    Example (C-28d, keep going with an open unit; the word 日 is in REVIEW; the deckless
 *    NEW word 学校 was created before the kanji notes 学, 校, 日, all NEW and deckless; nothing
 *    introduced today; batch 10): before the first turn 日 fails (`2 * 1 > 0 + 0 + 1`); the
 *    deckless turn draws `[学, 校, 学校]` (`drawn = 3`); 日 passes (`2 <= 0 + 3 + 1`) → N =
 *    `学 校 学校 日`. After 学 (`iToday = 1`, `bToday = 0`): `lastLiteral = 学` and the deckless
 *    source claims it → no share step; `[校, 学校]` (`drawn = 2`); the last card drawn is a
 *    word, so the step runs: 日 passes (`2 <= 1 + 2 + 1`) → `校 学校 日`, the tail. After 校:
 *    `学校 日`. Without the rule 日 would pass at once after 学 (`2 <= 1 + 0 + 1`) and the
 *    rebuild would be `日 校 学校`.
 *    **Breaking** against the C-8 / C-27 text: the modes are gone (every source groups, kanji
 *    first); kanji cards without a home new-source join groups; `PendingEntry.newKanjiCards` is
 *    replaced by `missingKanji`; existing vocab cards can be withheld; a unit that does not fit
 *    leads with its kanji and stops its source instead of being skipped (ADR-043). ADR-044
 *    (against the ADR-043 text): backfill no longer takes the whole goal ahead of the sources
 *    (half share, remainder step), and a part may overshoot as the day's first new card.
 *    C-28c (against the C-28b text): a unit's kanji cards homed in the drawing deck are tested
 *    against that deck's limit before the draw ("Deck cost": whole or lead); rule 5 "Open group
 *    first" only promotes the **first** unit left in `N` and never reorders `N`; the buried
 *    example is `[たべる, 校]`; the sources of a tier no longer restart their round-robin at
 *    every build ("Turn order": turn counts from today's reviews, open unit first).
 *    C-28d (against the C-28c text): "Turn order" rule (a) takes the first claimer in source
 *    order instead of the smallest `t`, and rule (c) "earliest claimer" is new; a lead ends
 *    when the drawing deck's limit is used up, even before a part of deck cost 0; a
 *    keep-going share step draws nothing while a unit is open.
 * 5. **Order** (ADR-037, normal build): due learning items first (rule 2 order); then the reviews
 *    `R` (rule 3 order, most overdue first) and the new **units** `N` (rule 4 draw order; a unit
 *    is a kanji group or a single card; withheld units and entries have no items and are left
 *    out) in the **proportional mix**; then the learn-ahead items last (only when `learnAheadMs > 0`, which
 *    Kotobaya never passes; [ReviewLoop] forces 0).
 *    **Proportional mix** (a stride on what **remains**; both modes, `reviewsUnlimited` or not):
 *    new cards are spread evenly over the ready reviews, so both run out at about the same time,
 *    never two new cards in a row while a review is left. Integers only, so every platform gives
 *    the same order:
 *    - `rRem = |R|` (ready reviews left: already bounded by the review caps, i.e. `min(due,
 *      remaining cap)` in capped mode and every due review in unlimited mode);
 *      `gRem = availableToday` (rule 4: `min(remaining day goal, new cards available under the
 *      deck caps)`; not the batch-bounded number drawn);
 *      `s` = the number of today's `countsAsReview` reviews with `reviewedAt` **greater than** `T`,
 *      `T` = the largest `reviewedAt` among today's `introducedNew` reviews (all of today's
 *      `countsAsReview` reviews when nothing was introduced today). All from [QueueInput.today]
 *      after rule 0. `s` = "reviews answered since the last new card".
 *      `last` = the card of today's `introducedNew` review with the greatest `(reviewedAt,
 *      cardId)`, or none (ADR-040).
 *    - **Open group first (ADR-040; narrowed by C-28c).** Whenever `s == 0` and `last` is a
 *      card of a kanji note with literal L and the **first unit left in `N`** (the next one in
 *      draw order, nothing else) was built for a word W (the vocab card or entry
 *      of the unit, whether the unit is whole or a lead) with `L in req(W)`: that unit
 *      is emitted **now**, all its cards in a row, before any
 *      review; then `gRem -= its size` and `last` = its last card (`s` stays 0). This is what
 *      keeps a group together across rebuilds: after the first kanji of `[学, 校, 学校]` is
 *      answered, the rebuild sees `last = 学` and emits `[校, 学校]` next instead of a review. It
 *      applies inside one build too (a kanji card emitted alone, by backfill or on its own turn,
 *      is followed without a review in between by the next unit if that unit's word requires
 *      it), so one build and a rebuild after every answer agree.
 *      **`N` is never reordered (C-28c).** Rule 5 emits the units of `N` in rule 4 draw order,
 *      always; the open group only decides that the next unit comes before the next review. A
 *      unit further down `N` whose word requires L is **not** pulled forward over other units
 *      (before C-28c "the first such unit in draw order" was). Two reasons, both data-order
 *      bugs: (1) the pulled word could pass a kanji it requires that sits earlier in `N` as a
 *      unit of its own (drawn by backfill or on a deck's own turn), i.e. be served before its
 *      kanji; in draw order that cannot happen, because a unit takes every not yet taken
 *      eligible kanji card of its word, so each required kanji is either inside the unit or in
 *      an earlier one. (2) Serving in another order than the draw changes which backfill kanji
 *      are answered first, so `bToday` after an answer no longer equals `b` at the same point
 *      of the draw and the rebuild's share step refuses a part the previous build had served
 *      next (example below).
 *    - Otherwise, while both `R` and `N` have something left: the next item is the next **unit**
 *      of `N`, all its cards in a row, iff `s * gRem >= rRem` (64-bit), then `s = 0`,
 *      `gRem -= its size`, `last` = its last card; else the next **review**, then
 *      `s += 1`, `rRem -= 1`.
 *    - When one of the two runs out, the rest of the other follows. (`N` may run out before
 *      `gRem` reaches 0: the batch; the rebuild after the next answer continues the same stride.)
 *    A unit is **never split by the mix**: no review falls between a kanji card and its word.
 *    Only a learning card that falls due can come between them (due learning is always first);
 *    the open-group rule then continues the group right after it.
 *    Properties: a fresh day starts with a review (`s = 0`, `rRem > 0`); right after a new card
 *    the next card is a review if one is left; between two new cards there are at most
 *    `ceil(rRem / gRem)` reviews. The counters advance exactly as the input changes when the
 *    items are answered in order (a review answer adds a later `countsAsReview` row and removes a
 *    ready review; a new answer adds a later `introducedNew` row and uses one of
 *    `availableToday`; a learning answer changes none of the three), so the order of one build
 *    equals the order produced by rebuilding after every answer; there is no session state.
 *    **Rebuild = tail (normative, C-28c).** With nothing but answers changing the data (no
 *    learning step falling due, no sync, no day change), answering the first item of a build
 *    and rebuilding gives exactly the remaining items of that build, in the same order, plus
 *    whatever the batch had cut off: no item moves and none disappears, in the normal and in
 *    the keep-going build, with units, leads, backfill, per-deck limits and any number of
 *    sources in a tier (rule 4 "Turn order"). Outside this guarantee, each for a stated
 *    reason: (1) a build with pending materialization: its withheld units have no items until
 *    the client has minted, so the item list grows by design. (2) A kanji part of several
 *    cards (a kanji note type with several NEW kinds and `burySiblings` false; never the
 *    built-in type): its first answer makes the literal started, which by ADR-040 dissolves
 *    the part ("kanji that already have a learning card do not block"). **Nothing else
 *    (C-28d).** The three cases that C-28c and CORE-28 still left out are inside the
 *    guarantee now: two sources of one tier whose next words require the same new kanji,
 *    wherever that kanji is homed and however its unit's source was picked (rule 4 "Turn
 *    order", (a) by source order and (c): the kanji's unit belongs to the first claimer in
 *    source order, before and after the answer); a keep-going build with backfill and a
 *    half-answered unit (rule 4 "Share step", "Not while a unit is open"); a capped deck's
 *    lead with a kanji homed elsewhere (rule 4 "Lead", "The lead ends at the deck's limit").
 *    Tests of the property use one-card parts, mint first, and exclude **no** input. The
 *    mix does not depend on the batch, on input order or on the caller. Between two new units there
 *    are at most `ceil(rRem / gRem)` reviews and never two units in a row while a review is left,
 *    except through the open-group rule. `toMaterialize` entries and withheld units are not items
 *    and take no slot (they are part of `availableToday`); they join `N` after materialization.
 *    Example (nothing done today, R1 R2 R3 R4 ready, `availableToday` 2, new N1 N2):
 *    `R1 R2 N1 R3 N2 R4`. Example (200 ready, 40 available, nothing done): five reviews, a new
 *    card, repeated (`RRRRRN…`); the last four gaps are four reviews and four reviews follow the
 *    40th new card. Example (ADR-040; nothing done, R1..R4 ready, `availableToday` 4, units
 *    `[学, 校, 学校]`, `[たべる]`): `R1 学 校 学校 R2 R3 たべる R4` (`0*4 >= 4` no → R1; `1*4 >= 3` → the
 *    unit, `s = 0`, `gRem = 1`; `0*1 >= 3` no → R2; `1*1 >= 2` no → R3; `2*1 >= 1` → たべる; R4).
 *    After R1 and 学 are answered the rebuild gives `校 学校 R2 R3 たべる R4` (open group first:
 *    `last = 学`, `s = 0`; `availableToday` 3 → `gRem = 1` after the group).
 *    Example (C-28c, `N` is not reordered; goal 5, so share 2; no review due; the words 日, 家
 *    and 本 are in REVIEW, created in that order; kanji notes 日, 家, 本, 語 are NEW and deckless,
 *    created after the deckless NEW word 日本語): backfill parts 日, 家, 本; the share step draws 日,
 *    家 and stops at 本; the deckless turn draws the unit `[本, 語, 日本語]` (`drawn = 5`, `b = 3`)
 *    → N = `[日], [家], [本, 語, 日本語]` and the queue is `日 家 本 語 日本語`: after 日, `last = 日`
 *    and 日本語 requires 日, but its unit is not the first one left, so 家 comes next (before
 *    C-28c: `日 本 語 日本語 家`; once 日 and 本 were answered, `bToday` was 2 and the rebuild's share
 *    step refused 家, which the first build had admitted at `b = 1`: 家 fell to the remainder
 *    step, behind every source card). After 日 and 家 are answered
 *    (`bToday = 2`) the rebuild gives `本 語 日本語`; after 本 (`bToday = 3`) `語 日本語`: always the
 *    tail. Example (C-28c, kanji first; "Kanji" deck at priority 0 holding 校, word deck at
 *    priority 1 holding 学校, 学 was the last card introduced today, `s = 0`): N = `[校], [学校]`
 *    → `校 学校` (after 校, `last = 校` and the first unit left is 学校's: no review between).
 *    Example (9 ready, 28 available): `R N R N … R N` nine times, then the
 *    remaining 19 new cards. Example (cap 2, goal 1, 5 due, 3 new): `R1 N1 R2`.
 *    Loader note: with pending entries, `availableToday` is computed over the entries the loader
 *    passed (the C-27 window), so it can be a lower bound when a deck has more entries than the
 *    window; server and device pass the same window for the same `limit`.
 *    Example with learning (`now` = T, learn-ahead 20 min): L1 due T+15 min, L2 due T−1 min, R3 due
 *    today, new N4 → `L2 R3 N4 L1`; with learn-ahead 0 (always, in Kotobaya) → `L2 R3 N4`.
 *    Before ADR-037 this was an interleave with a stride from the drawn items only
 *    (`k = max(1, (|R| + |N|) / |N|)`).
 * 6. **Keep going** (C-26, ADR-031), only when `limits.keepGoing` is true; otherwise rules 1–5
 *    apply exactly as written. These changes and nothing else:
 *    - Rule 3: the global and per-deck review remaining are unlimited, so every eligible REVIEW
 *      card with `dueAt < day.endMs` is taken (still one NEW/REVIEW item per note if burying).
 *      Cards with `dueAt >= day.endMs` are **never** taken: no study-ahead.
 *    - Rule 4: budget = `max(1, keepGoingNewBatch)` alone; `newPerDay` and today's
 *      `introducedNew` count are ignored (no day test), and no source has a per-deck limit
 *      (`QueueDeck.newPerDay` ignored). Sources, home new-source (a disabled deck still supplies
 *      nothing on its own turns), tiers, turn order, in-deck order, pending entries, KNOWN
 *      handling, backfill parts and their order, kanji units, withholding and unit order are
 *      unchanged. Backfill keeps its half share (ADR-044) in the keep-going form of the share
 *      test (rule 4 "Half share": at most half of today's introductions plus this build's
 *      draw; `iToday` is read for this test only), checked before every source turn but
 *      not while a unit is open (rule 4 "Share step", C-28d), and its
 *      remainder step; every part "fits the day".
 *      **Atomic** becomes: a unit is drawn iff `drawn < batch`; it may overshoot the batch
 *      (batch 1 and a word with two new kanji gives 3 cards), so a large group never starves.
 *    - Rule 5: **no mix**. The order is due learning, then the open group if there is one (rule
 *      5 "Open group first", with the same `s == 0` test: only right after a new card; it is the first unit of `N`
 *      or nothing, C-28c), then all of `R` (rule 3 order,
 *      most overdue first), then the rest of `N` (rule 4 order, never reordered), then learn-ahead. Reviews left over past the caps
 *      are backlog and are cleared before more new cards; with `reviewsUnlimited` (no backlog) a
 *      review that becomes due is simply next, then the new cards continue. The proportional mix
 *      belongs to the normal build only (user decision 2026-11-17).
 *    - Rules 1 and 2: unchanged (eligibility, sibling burying, learning, learn-ahead).
 *    - [ReviewQueue.moreAvailable] is false.
 *    Accounting does not change: cards studied through keep going are answered with ordinary
 *    reviews, so they appear in [QueueInput.today] as `introducedNew`/`countsAsReview` and use up
 *    today's normal limits; they never raise tomorrow's allowance (limits are per study day).
 *    **moreAvailable** (`keepGoing` false): after rules 1–5, only when the queue is empty
 *    (`items`, `toMaterialize`, `kanjiToMaterialize`), the builder runs the same input under rule 6 and sets it to
 *    whether that result is non-empty. Deprecated as a UI signal: end states come from
 *    [ReviewLoop] (ADR-037), which always runs both builds.
 *    **Scope (normative, ADR-039; replaces ADR-037's "persisted for the study day").** Keep
 *    going lasts **one review visit** of one client. A visit starts when the user enters the
 *    review screen (or starts the CLI's review session) and ends when they leave it, when the
 *    app or daemon restarts, when the study day changes, or after 10 minutes without an answer.
 *    The client holds the flag in memory only: it passes `keepGoing = true` on the builds of that
 *    visit after the user pressed Keep going, and `false` everywhere else — every new visit, the
 *    home screen, widgets, notifications and status lines always build with `false`. So a user
 *    who met the goal, kept going, left and comes back to newly due reviews gets those reviews
 *    and then the `goal_reached` / `done_for_today` screen again before any card over the quota
 *    (ADR-049: after a `waiting` phase while today's learning cards are still on their timers).
 *    It is never stored, never an op and never a synced setting; counts do not depend on it
 *    ([LoopState], except `untilStop`, `untilStopParts` and `keepGoingAvailable`).
 *    Example (`newPerDay` 2 with two `introducedNew` today; `reviewsPerDay` 1 with one
 *    `countsAsReview` today; batch 10): new N1, N2, N3 in deck order, R4 due today, R5 due
 *    tomorrow → normal build: empty, `moreAvailable = true`; keep-going build: `R4 N1 N2 N3`
 *    (never R5). With batch 2: `R4 N1 N2`.
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
    /**
     * ADR-035 (additive): `card.kind`, or null when the caller does not know it (then the card is
     * never treated as retired). Callers MUST pass it for the retirement rule to apply.
     */
    public val kind: String? = null,
)

/**
 * `word_status.auto_status`, derived on every replica (SCHEMA comment on `auto_status`: "rule owned
 * by the core"), re-derived whenever a card of a vocab note with that entry is created, deleted,
 * merged or replayed.
 */
public interface WordStatusRules {
    /**
     * Auto status from the live, non-loser cards of the **live vocab note** of the entry, after
     * removing every card that is **retired** (ADR-035: `kind != null &&
     * CardKinds.isRetired(VOCAB, kind, readingEnabled)`, i.e. `reading` cards while
     * `cards.reading_enabled` is not true):
     * - no live vocab note → null;
     * - [cards] empty, or every card NEW → [WordStatus.NEW];
     * - every non-NEW card in REVIEW with `intervalDays >= KNOWN_INTERVAL_DAYS` → [WordStatus.KNOWN];
     * - otherwise → [WordStatus.LEARNING].
     *
     * [noteLive] false means there is no live vocab note for the entry. [readingEnabled] is the
     * typed setting `cards.reading_enabled` (default false).
     *
     * Example (readingEnabled false): meaning NEW + reading LEARNING → NEW; meaning REVIEW 30 d +
     * reading LEARNING → KNOWN; only a reading card in REVIEW 40 d → NEW. With true: LEARNING,
     * LEARNING, KNOWN.
     *
     * When to re-derive: whenever a card of the entry's vocab note is created, deleted, merged or
     * replayed (above), **and for every vocab entry** when a replica applies a `settings.set` of
     * `cards.reading_enabled` (whichever value wins; re-deriving is idempotent).
     */
    public fun autoStatus(
        noteLive: Boolean,
        cards: List<WordCardState>,
        readingEnabled: Boolean = kotobaya.contracts.cards.CardKinds.READING_ENABLED_DEFAULT,
    ): WordStatus?

    /** `status = coalesce(override, auto, NEW)` (the `word_status` CHECK). */
    public fun effective(override: WordStatus?, auto: WordStatus?): WordStatus

    /** Constants. */
    public companion object {
        /** Interval in days from which a reviewed card counts as known (jpdb-style "mature"). */
        public const val KNOWN_INTERVAL_DAYS: Int = 21
    }
}
