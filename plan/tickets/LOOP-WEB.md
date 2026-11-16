# LOOP-WEB — Web: Home + review screens on `loop`

- Phase: P10 · Model: opus · Risk: med (UI over a new state machine; visual verification required)
- Deps: LOOP-C (generated client); LOOP-SRV for the live check
- Branch: t/LOOP-WEB-review-loop
- Owned paths (only edit these): web/src/lib/state/{review.svelte.ts,today.svelte.ts}, web/src/lib/review/**, web/src/routes/+page.svelte, web/src/routes/messages.ts, web/src/routes/review/**, web/tests/review/**, web/tests/home/**, web/playwright/** (review/home specs), the web bundle manifest `min_local_api`
- Module(s) for CI: web
- Gradle-heavy: no

## Read
- Design: docs/REVIEW-LOOP.md §4 (client rules C1-C5), §5, §10
- Contracts: openapi `getReviewQueue`, `ReviewLoop`
- Decisions: ADR-037; memory rule "verify UI visually" (layout test + screenshot)

## Do
State (no counting or phase logic in TypeScript: read `loop` only):
1. `today.svelte.ts`: keep `getReviewQueue({limit: 1, keepGoing: <persisted>})`; expose `loop`. Remove `due = new + learning + review`.
2. Keep-going store (C2): `localStorage["kotobaya.keepGoingDay"]` = study day; one helper used by Home and review; cleared on `study_day` mismatch (then refetch with `false`).
3. `review.svelte.ts`: end states come from `loop.phase`; delete `sessionCapReached`, the `moreAvailable` / `nextLearningDueAt` branching and the visit-scoped keep going; implement C1 (refetch before the next card when `now >= T`), C4 (one timer at `next_due_at`), C5.
Screens (all strings via i18n):
4. Home: ready-now number (C3), Start button; new bar `today/goal`; backlog line "Backlog: 335 reviews due · 200/day limit" when `beyond_limit > 0`; hint "8 learning later today · next in 6 min"; phase `done_for_today` / `goal_reached` → "Done for today · Keep going" / "New-card goal reached · Keep going"; `waiting` → countdown; `empty` → all done.
5. Review: `goal_reached` screen (bar 40/40, Keep going), `done_for_today` screen (reviews 200/200, new 40/40, "135 more reviews in backlog", Keep going), `waiting` (next in m:ss), `empty`. Keep going sets the store and refetches.
6. Settings: remove the "new per session" row if one exists.
7. Bundle manifest `min_local_api: 2`.

## Acceptance (each bullet → at least one named test)
- [ ] `loopPhases.svelte.test.ts`: one case per phase renders the right screen from a mocked `loop`; no code path reads `counts`
- [ ] `keepGoingDay.svelte.test.ts`: persists across a remount; dropped when `study_day` changes; Home and review send the same flag
- [ ] `dueFirst.svelte.test.ts` (C1): page holds 3 NEW, a learning step falls due → refetch happens before the next card
- [ ] `homeLoop.test.ts`: 0 ready + 26 later + new available → Start enabled, hint shown, number 0 (not 26/28)
- [ ] Layout test + screenshots (desktop and shell-mobile viewport) of Home with backlog line and hint, goal_reached, done_for_today, waiting; no overlap with the answer bar
- [ ] `make check MODULE=web` green

## Out of scope
Device settings row for portrait (PORTRAIT-WEB); any re-implementation of counts in TS (return CONTRACT_CHANGE_REQUEST if `loop` lacks something).

## Notes from previous attempts
