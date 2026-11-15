# WEB-PRIO — Web decks list and labels invert deck priority

- Phase: P9 hotfix · Model: haiku · Risk: low
- Deps: none
- Branch: t/WEB-PRIO-order
- Owned paths (only edit these): web/src/lib/state/decks.svelte.ts, web/src/lib/decks/messages.ts, web/src/routes/decks/, web/src/routes/settings/ (mined priority label/hint only), web tests
- Module(s) for CI: web

## Bug
Queue rule (contracts/db/SCHEMA.md 6.2, openapi listDecks "Ordered by (priority, id) ascending - the order new cards are drawn in"): LOWER priority number is drawn FIRST. The web shows the opposite:
- web/src/lib/state/decks.svelte.ts:39 sorts `b.priority - a.priority` (descending) → 100 shown at top.
- web/src/lib/decks/messages.ts:29 'decks.priorityLabel': 'Priority (higher is introduced first)', wrong.
- web/src/routes/decks/messages.ts:23 'dk.priorityHint': 'Higher priority decks introduce their new cards first.', wrong.

## Do
1. Sort ascending by (priority, then id/name) so the list reads in draw order (first drawn at top).
2. Fix the label/hint: "Priority (lower numbers are introduced first)" / "Decks with a lower number introduce their new cards first; equal numbers take turns."
3. Settings "Priority of mined (deckless) new cards": add the same hint (lower = earlier; equal to a deck = takes turns; default 0).
4. Small unit test on the sort comparator.

## Acceptance
- [ ] Test: decks [-10, 0, 5, 100] sort in that order.
- [ ] make check MODULE=web green.
