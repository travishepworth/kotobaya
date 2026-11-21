# HANDOFF C-32

STATUS: DONE
BRANCH: t/C-32-media-deck-delete (from origin/t/LF-int)
CHECK: `make check MODULE=contracts` → green. `MODULE=server` → red only on ParityGoldenTest (13 failures, all "no golden" or golden-count checks: the known LF-GOLD drift, grown since the ticket was written).

## Changed
- `contracts/sync/OPS.md` `deck.deleted`: `invalid_target` only when the media deck's media is **live** at validation time. New C-32 bullet covers same batch, separate pushes, another replica deleting first, out-of-order (rejected; the server's cascade converges; Retry or Discard both fix it) and replay. `media.deleted` has a back-reference.
- `server/.../sync/OpRules.kt`: `invalidTarget` now checks `mediaOfDeckTombstoned`.
- `contracts/sync/SYNC.md` 5.3 table (authorized by the orchestrator): `invalid_target` row now reads "client `deck.deleted` on a media deck whose media is live".

## Acceptance → tests
- same batch / separate pushes → `MediaDeckDeleteTest.mediaDeletedThenDeckDeletedInOneBatchAreBothAccepted` (+ idempotent re-push), `...InSeparatePushesAreBothAccepted`, `deckDeletedIsAcceptedAfterAnotherReplicaDeletedTheMedia`
- live media still rejected → `deckDeletedForAMediaDeckWithLiveMediaIsStillRejectedInvalidTarget`
- out-of-order → `deckDeletedBeforeItsMediaDeletedIsRejectedThenConvergesAndRetrySucceeds`

## New dependencies
none

## For the reviewer
- A missing media row counts as live, so the op is rejected.
