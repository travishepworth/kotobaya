# HANDOFF AND-ADD2DECK

STATUS: DONE
BRANCH: t/AND-ADD2DECK-ui
CHECK: `make check MODULE=android` → green

## Changed
- decks/DeckMembership.kt: offline put/remove of e:<seq> / n:<id> via OpMinter (remove mints all same-identity keys); lists live user decks by priority, name.
- search/SearchDeckOps.kt + SearchViewModel/SearchFeature: port, state, intents, Koin wiring.
- search/ui/SearchScreen.kt: footer under word/note detail: "Add to my notes" (no note yet; opens deck chooser incl. "No deck"), "Add to deck", decks the item is in.
- ui/components/KbDeckPicker.kt (sheet, check marks, inline new deck); strings_search.xml.

## Acceptance → tests
- e:/n: key → DeckMembershipTest.aNoteWithAnEntryUsesTheEntryKey...
- picker/priority/toggle off → ...pickerListsOnlyLiveUserDecks...
- note + deck in one action → ...addToMyNotesWithADeck...

## New dependencies
- none

## For the reviewer
- Buttons live in the search/ detail wrapper (word/ui screens untouched; notes/NoteCreator untouched, it already takes deckId).
- Item 4 (quick "Add" on search result rows) not done; not cheap enough. Deck detail show/remove items not done (Decks screen read-only).
