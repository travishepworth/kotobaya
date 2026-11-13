# HANDOFF UI-WEB

STATUS: DONE
BRANCH: t/UI-WEB-rebuild (rebased onto origin/main)
CHECK: `make check MODULE=web` green · `make screenshots-web` 172 passed

## Earlier rounds
- Every route rebuilt on lib/ui. Added FEAT-x field regions, `loadFailed`/`retry`/`LoadError`, pre-paint theme and the curated set.

## Round 2
- Review back: a card that overflows goes compact (`cqh` sizing) and scrolls to the answer, with a bottom fade. A card that fits doesn't move.
- Cards `state` column fixed at 8.5rem.
- SegmentedControl stacks as a radio list when labels get cut off (all uses).
- NoteEditor: a region click or `?field=` only highlights. Saving clears overrides equal to the dictionary value.
- Shared `kindLabel` (lib/ui/cardState) covers all 11 built-in kinds.
- DeckItems and UnknownWords: `loadFailed`/`retry`. Fake space token removed.

## Acceptance → tests
- A one-off vitest for the override fix failed before the fix and passes now. Not committed: tests/ isn't in my paths. Suggest adding it.

## New dependencies
- none

## For the reviewer
- Minor 7: nothing is hidden at the end of the scroll (checked in Playwright). Padding would leave a gap under the bar, so I used `scroll-margin-bottom`.
