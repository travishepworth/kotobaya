# HANDOFF LOOP-31-WEB

STATUS: DONE
BRANCH: t/LOOP-31-WEB
CHECK: `make check MODULE=web` → red only on 3 shell tests (tests/shell/access, shellState: ops.json/route gating). They fail on the base too and no file I touched is involved (diff vs base on shell/api paths is empty). All 315 others pass, including the new ones.

## Changed
- `web/src/lib/core/index.ts`: `presentLoop` binding. `web/src/lib/state/review.svelte.ts`: header parts, bar total and the screen come from the presenter (pending = outbox log since the applied fetch began, plus unacked answers). `review/model.ts`: `pending` phase removed; settling uses backoff, and every 5 s with `to_materialize` (C5).
- `+page.svelte`: "Saving…" status line instead of a button on the held card; quiet "Check again" on settling. `LoopEnd`: Keep going only iff `keepGoingAvailable`.
- `keepGoingDay.ts`: in-memory `newTarget`, cleared with the flag. Every visit fetch sends `keepGoingNewTarget`. Home sends none; Home's press sets T. `LoopHero` uses `presentedParts`.

## Acceptance → tests
- All four bullets → `web/tests/review/loopPresenter.svelte.test.ts`. `sessionEnd` tests were updated to settling semantics.

## New dependencies
- none

## For the reviewer
- A `loop` fixture whose stop phase still has parts left is not guarded; the presenter trusts the server there.
- Wire items have no `kind`, so it is derived from `isNew` / `card.state` in `itemKind`.
- No target is sent on legacy (no `loop`) servers, since they would 422 the unknown parameter.
