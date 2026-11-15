# HANDOFF WEB-DECK-INSPECT

STATUS: DONE
BRANCH: t/WEB-DECK-INSPECT-ui
CHECK: `make check MODULE=web` → green

## Changed (≤ 6 lines)
- routes/decks/[id]/DeckItems.svelte: filter chips (All/New/Learning/Known/Ignored), sort, per-row status badge + action menu, checkboxes, select-all, bulk bar with progress.
- lib/state/decks.svelte.ts: DeckItemsState gets filter/sort (resets cursor, stale-request guard), act/bulk/mine, rows update or leave the filter in place.
- lib/state/deckInspect.ts (new): chip→status lists, bulk runner. lib/components/WordStatusActions.svelte (new). Strings in lib/decks/messages.ts.

## Acceptance → tests
- Chips map to status lists; filter switch resets cursor → deckInspect.test.ts (mapping); `setView` nulls cursor and refetches.
- Bulk calls setWordStatus once per entry, reports failures → deckInspect.test.ts applyBulk.
- make check web green.

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- No chip counts: getDeckCoverage is token-based, not per-item status counts, so omitted (ticket allows).
- Did not refactor reader TokenSheet/UnknownWords onto the new component (reader.svelte.ts/media.svelte.ts are outside owned paths); "Mine" looks up the Vocab note type like the dictionary page does.
- Partial bulk failures keep the failed rows selected and toast the count.
