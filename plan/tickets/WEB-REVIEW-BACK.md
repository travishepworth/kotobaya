# WEB-REVIEW-BACK — Back = previous card flipped; dictionary round-trip restores exact review state (ADR-048)

- Phase: P10 · Model: sonnet (writer) · Risk: med · Deps: —
- Branch: t/WEB-REVIEW-BACK · Owned: web/src/** (review route, review state store, navigation/history glue, dictionary links from review), web/playwright/**, android/** shell back wiring only if the WebView back path needs it
- CI: web (+ android if touched)

## Read
- docs/DECISIONS.md ADR-030, ADR-048; plan/tickets/AND-BACK.md, SHELL-W3 (SPA back stack with ADR-030) for the existing back design
- web/src/routes/review/+page.svelte, the review state store, the undo action path (must emit the same review.undone op as the Undo button — rule 8, no new mutation path)

## Do
User (2026-11-19): "Back button on the mouse needs to go back to the previous card while reviewing (already flipped). Same behaviour on android. If you follow a link to a dictionary, back button (android too) takes you back to the exact review state you were at (i.e. card already flipped)."
1. During review, browser back (mouse back button = history back; Android system back in the shell WebView): undo the last answer via the existing undo path and show that card **revealed**; repeated back keeps undoing this session's answers; nothing to undo → leave review (normal back). Use history entries (e.g. pushState per answered card) or a popstate guard — pick the robust one; forward must not double-grade.
2. Links out of review (dictionary entry, kanji detail, Open in dictionary, any card link): coming back restores the same card, same revealed state, same session queue and position, scrollTop 0 — no new card fetched in place of it. Keep review session state in a module-level store (survives route change) rather than re-initialising on mount.
3. Android: the review screen is the SPA in the shell WebView; make sure system back routes into this behaviour (check the shell's back handling; only touch android if needed).
4. Keep keyboard shortcuts and the Undo button working the same.

## Acceptance
- [ ] Playwright (mock API): answer 2 cards, history back → card 2 shown revealed and exactly one undo request; back again → card 1 revealed; back again with nothing to undo → leaves /review.
- [ ] Playwright: on revealed card, open dictionary link, go back → same card, revealed; unrevealed case too.
- [ ] make check web (and android if touched) green.

## Out of scope
Audio (C-29); dictionary layout.
