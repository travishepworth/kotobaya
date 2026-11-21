# HANDOFF C-32

STATUS: DONE
BRANCH: t/C-32-media-deck-delete (from origin/t/LF-int)
CHECK: `make check MODULE=contracts` → green. `MODULE=server` → red only on ParityGoldenTest (13 failures, all "no golden" or golden-count checks: the known LF-GOLD drift, grown since the ticket was written).

## Changed
- `contracts/sync/OPS.md` `deck.deleted`: `invalid_target` only when the media deck's media is **live** at validation time. New C-32 bullet covers same batch, separate pushes, another replica deleting first, out-of-order (rejected; the server's cascade converges; Retry or Discard both fix it) and replay. `media.deleted` has a back-reference.
- `server/.../sync/OpRules.kt`: `invalidTarget` now checks `mediaOfDeckTombstoned`.
- `contracts/sync/SYNC.md` (authorized): 5.3 `invalid_target` row adds "whose media is live"; 10.2 Retry row lists that case as retryable.
- Review tweaks (doc only): OPS.md says the media row always exists and the check only goes from reject to accept; `invalid_target` is not auto-retried after a log reset.

## Acceptance → tests
- same batch / separate pushes → `MediaDeckDeleteTest.mediaDeletedThenDeckDeletedInOneBatchAreBothAccepted` (+ idempotent re-push), `...InSeparatePushesAreBothAccepted`, `deckDeletedIsAcceptedAfterAnotherReplicaDeletedTheMedia`
- live media still rejected → `deckDeletedForAMediaDeckWithLiveMediaIsStillRejectedInvalidTarget`
- out-of-order → `deckDeletedBeforeItsMediaDeletedIsRejectedThenConvergesAndRetrySucceeds`

## New dependencies
none

## For the reviewer
- Approved (reviewer + architect 2nd pass); doc tweaks applied. Server still treats a missing media row as live (unreachable).
