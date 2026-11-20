# LOOP-31-WEB — Web review: one presenter, Keep going on every non-final screen, no pending screen

- Phase: P10 local-first · Model: opus · Risk: high
- Deps: LOOP-31-CORE-B, LOOP-31-API
- Branch: t/LOOP-31-WEB-loop-web
- Owned paths (only edit these): web/src/routes/review/, web/src/lib/state/review.svelte.ts, web/src/lib/state/today.svelte.ts, web/src/lib/review/, web/src/lib/core/ (binding for the presenter only), web tests beside them
- Module(s) for CI: web

## Read
- Spec: Clients → Web app
- Contracts: contracts/api/openapi.yaml `getReviewQueue` rules C1–C8
- Decisions: ADR-038, 039, 048, 049, 051
- Design: scratchpad `local-first-design.md` (orchestrator: copy to `docs/design/local-first.md` or paste the named section into this ticket).

## Do
1. Header, progress bar and screen come from `LoopPresenter.present` (via `web/src/lib/core/`), fed with the last applied `loop`, the outbox entries answered since that fetch began (kind + the core preview's resulting state/due) and whether a local card is shown. Delete the direct reads of `view.loop` for parts/total in `+page.svelte` and the `reviewPhase` switch on `loopPhase`; no counting in TypeScript.
2. Remove the `pending` / `pendingStopped` screen: `REVIEWING` with nothing served is `SETTLING` (auto-refetch with the existing backoff, a quiet "Check again" after the last try).
3. The held card is visibly inert: grade buttons replaced by a "Saving…" status (i18n), never tappable-looking.
4. Keep going: on press store `newTarget = loop.new.today + max(1, loop.new.goal)` in the visit state (cleared like ADR-039), send it with every queue fetch of the visit (Home's `today.svelte.ts` never sends it). `LoopEnd`: the button shows iff `keep_going_available` in `waiting`, `goalReached`, `doneForToday`; `done` (empty) never.
5. Home hero uses the same presenter output (no pending answers).

## Acceptance (each bullet → at least one named test)
- [ ] Test: answering 5 cards with all answer requests held open moves the header parts after each tap (no frozen counts) and the bar total does not grow on Good.
- [ ] Test: a terminal screen is never rendered while the outbox is non-empty or while presenter parts are non-zero (table-driven over all phases).
- [ ] Test: `reviewing` + no items + `to_materialize > 0` shows settling, then refetches; no screen without a way forward.
- [ ] Test: Keep going sends `keep_going_new_target`; at the target the stop screen returns with Keep going; `empty` has none.
- [ ] `make check MODULE=web` green.

## Out of scope
Persisted outbox and retry policy (LF-W1), prefetch depth (LF-W2), Android.

## Notes from previous attempts
