# HANDOFF LOOP-31-WEB

STATUS: DONE
BRANCH: t/LOOP-31-WEB
CHECK: `make check MODULE=web` → green (321 tests), after rebase on LF-int.

## Changed
- `web/src/lib/core/index.ts`: `presentLoop` binding. `web/src/lib/state/review.svelte.ts`: header parts, bar total and the screen come from the presenter (pending = outbox log since the applied fetch began, plus unacked answers). `review/model.ts`: `pending` phase removed; settling uses backoff, and every 5 s with `to_materialize` (C5).
- `+page.svelte`: "Saving…" status line instead of a button on the held card; quiet "Check again" on settling. `LoopEnd`: Keep going only iff `keepGoingAvailable`.
- `keepGoingDay.ts`: in-memory `newTarget`, cleared with the flag. Every visit fetch sends `keepGoingNewTarget`. Home sends none; Home's press sets T. `LoopHero` uses `presentedParts`.

## Acceptance → tests
- All four bullets → `web/tests/review/loopPresenter.svelte.test.ts`. `sessionEnd` tests were updated to settling semantics.

## Attempt 2
- Rebased. Pending table now applies the stale response via reload and asserts settling (plus stop phases with parts). Polled settling fetch is one request per 5 s (C5). Saving text only while the outbox is non-empty; Home Keep going skips the target on legacy; log trims only acked entries below every page's applied seq; presenter throw with a non-empty outbox falls back to settling; `presentedParts` passes dayEndsAt 0.

## New dependencies
- none

## For the reviewer
- A `loop` fixture whose stop phase still has parts left is not guarded; the presenter trusts the server there.
- Wire items have no `kind`, so it is derived from `isNew` / `card.state` in `itemKind`.
- Finding 5 (contract follow-up, orchestrator files it): no code change.
- No target is sent on legacy (no `loop`) servers, since they would 422 the unknown parameter.
