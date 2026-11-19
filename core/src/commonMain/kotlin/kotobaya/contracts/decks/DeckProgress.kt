package kotobaya.contracts.decks

import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.WordStatus
import kotobaya.contracts.queue.WordCardState

/**
 * One **deck word** for [DeckProgress] (ADR-041): one thing the deck asks the user to learn.
 *
 * The host builds the list for a deck from its live items (`deck_item.deleted_at IS NULL`) and
 * de-duplicates by [key]:
 * - an item `e:<seq>` → key `e:<seq>`;
 * - an item `n:<id>` → resolve the note through `merged_into_id` to its survivor (SYNC.md 8.5); a
 *   tombstoned survivor (`deleted_at` set) is skipped (no word); a live note with an `entry_seq`
 *   → key `e:<seq>` (so the same word reached by an `e:` and an `n:` item counts once); a live
 *   note without one → key `n:<survivor id>`.
 *
 * For a key `e:<seq>`: [status] = the effective `word_status.status` of the entry (no row → null);
 * [noteBaseType] / [cards] = the base type and the live cards (`deleted_at` null, not a merge
 * loser) of the live note holding that `entry_seq`, or null / empty when there is none. For a key
 * `n:<id>`: [status] = null; [noteBaseType] / [cards] of that note.
 *
 * Example: `DeckWord("e:1358280", WordStatus.LEARNING, NoteBaseType.VOCAB, [WordCardState(REVIEW, 12, "meaning")])`.
 */
public data class DeckWord(
    /** `e:<seq>` or `n:<uuid>`; unique in the list (duplicates: the first counts). */
    public val key: String,
    /** Effective word status of the entry, or null (no `word_status` row, or a note without an entry). */
    public val status: WordStatus?,
    /** Base type of the word's live note; null when the entry has no live note. */
    public val noteBaseType: NoteBaseType?,
    /** The live cards of that note with `state`, `intervalDays` and `kind` set; empty without a note. Suspended cards are included. */
    public val cards: List<WordCardState>,
)

/**
 * "N of M words learned (P%)" for one deck (API `Deck.progress`).
 *
 * Invariants: `0 <= learned <= total`; [percent] = `floor(100 * learned / total)` in integer
 * arithmetic, 0 when `total == 0`; so it is 100 only when every word is learned and never rounds
 * up. Example: `DeckProgressCount(learned = 412, total = 2143)` → `percent = 19`.
 */
public data class DeckProgressCount(
    /** Deck words that are learned ([DeckProgress.isLearned]). */
    public val learned: Int,
    /** Deck words that count ([DeckProgress.counts]). */
    public val total: Int,
) {
    /** Whole percent, rounded down: `if (total == 0) 0 else (100L * learned / total).toInt()`. */
    public val percent: Int get() = if (total <= 0) 0 else (100L * learned / total).toInt()
}

/**
 * ADR-041: deck progress, decided in one place so the server (`listDecks`, `getDeck`) and the
 * phone's local API show the same numbers offline. Pure; implementation
 * `kotobaya.decks.DefaultDeckProgress` (ticket CORE-28). Hosts load the rows ([DeckWord]) and
 * call [count]; they never restate the rule in SQL or TypeScript.
 *
 * **counts(word)**: false iff `word.status` is [WordStatus.BLACKLISTED] or [WordStatus.IGNORED]
 * (the user will never learn it: it is in neither number). True otherwise, including words
 * without a note and words whose dictionary entry is missing.
 *
 * **isLearned(word, readingEnabled)**: `counts(word)` **and** (`word.status` is
 * [WordStatus.KNOWN] or [WordStatus.NEVER_FORGET], **or** at least one of `word.cards`, after
 * dropping retired cards (`noteBaseType != null && kind != null &&
 * CardKinds.isRetired(noteBaseType, kind, readingEnabled)`), is in state REVIEW). A card in
 * NEW, LEARNING or RELEARNING does not count: a lapsed word (relearning) is not learned until it
 * graduates again. Suspension, due date and interval are irrelevant.
 *
 * **count** = `(learned = words with isLearned, total = words with counts)` over the list
 * de-duplicated by key.
 *
 * Scope: user, list and media decks. Smart decks have no items: the API sends `progress: null`
 * for them, and for a tombstoned deck. Nothing here is learning data: no op, nothing stored.
 *
 * Edge cases: empty deck → `(0, 0)`, percent 0; a word marked known with no note → learned; a
 * word with only a retired reading card in REVIEW (reading cards off) → not learned unless its
 * status says known; a note with a REVIEW and a NEW card → learned; an item whose note was deleted
 * → not in the list; an offline-created item counts at once; statuses derived on the replica may
 * lag the server while ops are pending (the local replica wins).
 *
 * Example: words A (status LEARNING, meaning card REVIEW), B (no note, status null), C (status
 * KNOWN, no note), D (BLACKLISTED), E (meaning card LEARNING) → `learned = 2` (A, C), `total = 4`,
 * `percent = 50`.
 */
public interface DeckProgress {
    /** True iff [word] is part of the total. */
    public fun counts(word: DeckWord): Boolean

    /** True iff [word] is learned; [readingEnabled] is the typed setting `cards.reading_enabled`. */
    public fun isLearned(word: DeckWord, readingEnabled: Boolean = kotobaya.contracts.cards.CardKinds.READING_ENABLED_DEFAULT): Boolean

    /** The deck's numbers over [words] (de-duplicated by key, first wins). */
    public fun count(words: List<DeckWord>, readingEnabled: Boolean = kotobaya.contracts.cards.CardKinds.READING_ENABLED_DEFAULT): DeckProgressCount
}
