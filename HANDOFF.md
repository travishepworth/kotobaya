# HANDOFF WEB-ADD2DECK

STATUS: DONE
BRANCH: t/WEB-ADD2DECK-ui
CHECK: `make check MODULE=web` → green

## Changed (≤ 6 lines)
- web/src/lib/ui/AddToDeck.svelte + deckPicker.ts + state/notesDeckPicker.svelte.ts: shared picker (live user decks, draw order, check marks, toggle add/remove, inline create deck), chips of holding decks.
- Note editor: "Add to deck" (e:<seq> if the note has an entry, else n:<id>).
- Dictionary entry: "Add to my notes" with optional deck chooser (one createNote with deckId); always "Add to deck" (e:<seq>) + chips.
- Strings in lib/ui/messages.ts (ui.deck.*).

## Acceptance → tests
- e:<seq> vs n:<id> → tests/ui/deckPicker.test.ts "deck item keys"
- live user decks, priority asc; toggle off removes → "picker" tests
- Add to notes with deck, one action → "add to my notes with a deck"

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- API gap: no "decks holding word X" read, so membership scans listDeckItems (limit 500, max 20 pages) of every user deck; costly for huge decks. Suggest an additive `deck_ids` on DictEntry/Note.
- Search-row quick Add skipped (ListRow is a link, and per-row membership scans would be N x decks requests); would be cheap once the gap above is filled.
