# AND-DECK-INSPECT — Android deck inspector: status filters + per-word and bulk actions (offline)

- Phase: P9 · Model: sonnet · Risk: med
- Deps: none
- Branch: t/AND-DECK-INSPECT-ui
- Owned paths (only edit these): android/src/**/decks/, android/src/**/ui/components (shared status-action sheet), android SQLDelight .sq query files ONLY to add read queries (e.g. a deck-words-with-status query in LocalQueue.sq — no schema/table changes), the android op-minting helper ONLY if it lacks word.status_set / word.status_cleared minting (say so in HANDOFF), android i18n strings, android/src/test/
- Module(s) for CI: android

## User request (2026-11-15)
"The deck inspector (Decks tab -> the list) should give filtering options (known, unknown, new, etc.) and actions on the words, similar to viewing the deck from Reading (mark as known, etc.)."

## Today
decks/ui/DecksScreen.kt + DecksViewModel.kt:119 + DeckRepository.kt:104: local SQL over deck_item + note; rows have a dictionary-open button. DeckRepository.words() does not join word_status, so DeckWord has no status. Local SQLite has word_status (LocalQueue.sq:13,29,76).

## Model (contracts/sync/OPS.md word_status, deck_item)
word.status_set (entity_id = entry_seq, payload status + origin; see contracts/sync/ops/word.status_set.schema.json) and word.status_cleared (reset to auto). Effective status = coalesce(override, auto, 'new'): apply locally through the existing op-apply path so the UI updates offline, then sync pushes. Values: new, learning, known, never_forget, blacklisted, ignored. deck_item keys: e:<entry_seq> (has status) or n:<note_id> (custom note: no word status).

## Do
1. DeckWord carries effective status (join word_status on entry_seq; missing row = new). Keep queries indexed/point-lookup-friendly; no per-row queries (see AND-REVIEW-INFO perf lesson).
2. Filter chips on deck detail: All · New · Learning · Known (known+never_forget) · Ignored (blacklisted+ignored), with counts from one grouped query. Sort: deck order · frequency.
3. Row status badge; tap/long-press -> action sheet: Mark known, Never forget, Ignore, Blacklist, Reset, Add to my notes (when no note; reuse NoteCreator), Open in dictionary, Remove from deck (user decks only; existing deck.item_removed path).
4. Multi-select mode (long-press to start, select all visible): bulk Mark known / Ignore / Reset, all ops minted in one transaction.
5. List and counts update reactively after an action (Flow), no manual refresh.
6. Hide status actions for n:<note_id> rows. All strings via i18n. Works offline.

## Acceptance
- [ ] Test: deck words query returns effective status (override beats auto; missing row = new) and filter groupings are right.
- [ ] Test: bulk mark-known mints one word.status_set per selected entry_seq in one tx (idempotent on repeat); Reset mints word.status_cleared.
- [ ] make check MODULE=android green.

## Out of scope
Server/contract changes, schema changes, web (WEB-DECK-INSPECT), an Android reader.
