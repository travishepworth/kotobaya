# WEB-ADD2DECK — "Add to deck" from note view and dictionary; "Add to my notes" with optional deck

- Phase: P9 · Model: sonnet · Risk: med
- Deps: none
- Branch: t/WEB-ADD2DECK-ui
- Owned paths (only edit these): web/src/routes/dictionary, web/src/routes/notes, web/src/routes/cards, web/src/routes/search, web/src/lib/state/(dictionary|notes|cards|mergedSearch|preview)*, web/src/lib/dictionary, web/src/lib/cards, web/src/lib/ui (a shared DeckPicker component), web tests. NOT web/src/routes/review or lib/state/review* (WEB-REVIEW-FAST owns them)
- Module(s) for CI: web

## User request (2026-11-15)
"There isn't a good way to add a card to a deck. I'd expect the note viewer to have an Add to deck button, and the dictionary to have Add to notes and Add to deck buttons."

## Model (contracts/db/SCHEMA.md "Deck items"; contracts/sync/OPS.md deck_item)
Deck membership is per WORD or NOTE, not per card: deck_item key is e:<entry_seq> (a dictionary word; used whenever the note has an entry_seq) or n:<note_id> (a custom note without an entry). Cards come from notes. Only USER decks accept manual items (media/list decks are snapshots: deck.item_put to them is rejected invalid_target). Adding the same thing twice converges to one row.
API/ops: data via the generated client only: notesApi.createNote (has deck_id, emits note.created+card.created+deck.item_put), decksApi.addDeckItems (entry_seq or note_id; note with entry_seq stored as e:<seq>), decksApi deck items removal if a remove action is added.

## Do
1. Shared "Add to deck" picker: lists live USER decks (in draw order: priority ascending, then name) with a check mark on decks the word/note is already in; toggle adds (deck.item_put) / removes (deck.item_removed). Option to create a new user deck inline (name only) if cheap.
2. Note view (note detail / preview panel): "Add to deck" button → picker (uses the note's entry_seq when it has one, else note id).
3. Dictionary entry view: if the user has no note for the entry: "Add to my notes" (creates the note + default cards) with an optional deck chooser (default: none → mined/deckless). Always: "Add to deck" (adds e:<seq>; works even with no note: the word is then a deck word to learn). If a note exists, show "In my notes" + its decks.
4. Search results (merged search) rows: a quick "Add" action (add to notes; long-press/menu for deck) if cheap; else note it.
5. All strings via i18n. Show which decks the word/note is in on these views.

## Acceptance
- [ ] Test: adding a note that has entry_seq to a deck uses e:<seq>; a custom note uses n:<id>.
- [ ] Test: picker lists only live user decks, priority ascending; toggling off removes the item.
- [ ] Test: "Add to my notes" with a deck creates the note and the deck item (one action).
- [ ] make check MODULE=web green.

## Out of scope
Server changes (endpoints/ops exist), media/list decks (read-only), review screen.
