# HANDOFF AND-DECK-INSPECT

STATUS: DONE
BRANCH: t/AND-DECK-INSPECT-ui
CHECK: `make check MODULE=android` → red only on BackNavigationTest.searchBackClosesTheDetail (Robolectric "Compose not idle", search screen, unrelated to decks; passed on 1 of 3 runs, so flaky). All decks tests green.

## Changed
- decks/DeckRepository.kt: DeckWord has status/hasNote/reading; words() takes filter+sort (one LEFT JOIN on word_status PK); statusCounts() one grouped query; entrySeqs() for select-all.
- decks/DeckWordOps.kt (new): SqlDeckWordActions mints word.status_set/cleared via OpMinter.mintAll in one tx (skips no-ops), remove via DeckMembership, add-to-notes via NoteCreator. Wired in DecksFeature.
- DecksViewModel + ui/DecksScreen.kt + ui/DeckWordSheet.kt: filter chips with counts, sort, status badge, action sheet, long-press multi-select with bulk Known/Ignore/Reset; reload after each write; strings in strings_decks.xml.
- Bug fix (coordinator): e:<seq> rows with no resolvable label now get label "" (UI shows "Word not on this device yet") instead of the raw key; dictionary labels were already batched via the labeler.
- Touched outside owned paths: demo/DemoPorts.kt (signature only).

## Acceptance → tests
- effective status + filters → DeckRepositoryTest.wordsCarryEffectiveStatusAndFiltersGroupIt
- bulk mint/idempotent/reset → DeckWordOpsTest
- e: item without note shows headword → DeckRepositoryTest.anEntryItemWithoutANoteShowsTheDictionaryHeadwordNeverTheRawKey (DeckLabelsTest updated to expect "" not key)

## New dependencies
none

## For the reviewer
- Root cause of raw keys unverified on device: if the headword still misses, the installed dictionary lacks the entry or isn't available; no lazy API lookup added.
- "Frequency" sort = deck_item.occurrence_count desc. n: items count as New and get no actions. List refresh is a VM reload after each action, not a SQL Flow.
