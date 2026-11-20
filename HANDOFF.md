# HANDOFF LF-A4

STATUS: DONE
BRANCH: t/LF-A4 (from origin/t/LF-int)
CHECK: `make check MODULE=android` → red only on the pre-existing/expected failures below (no new ones)

## Changed
- `BulkRoute.kt`: every bulkCards action local (set_preset, add/remove_tags, move, delete added; Fallthrough on action type removed; query Forward fallthrough stays for LF-A5). New `bulkScheduleCards` handler (manual review.logged).
- `CardMints.kt` (new): reusable mint builders for card ops, tags, manual review, UUIDv7 ids (for LF-A8). Deck puts reuse `DeckMints`.
- `Routes.kt`: bulkScheduleCards route registered (ApiOperations has it LOCAL after the rebase onto LF-A2).

## Acceptance → tests
- Per action/selector/dry_run/empty/dup/merge loser/tombstoned/unknown → `BulkAllActionsTest`; parity `bulk-cards-all-actions-local` passes ParityExpectTest.
- Idempotency (tags, delete, move) and op schema validity (`isValidOp`) → `BulkAllActionsTest`. `BulkAtomicTest` forwarded-test replaced.
- Server push acceptance: only via the parity scenario; no separate push test.

## New dependencies
none

## For the reviewer
- Rebased on origin/t/LF-int; `scheduleRescheduleAndResetMintManualReviewRows` now runs and passes; RouteTableTest.operationClassesEqualOpsJson passes.
- Remaining failures (none new): RouteTableTest.everyLocalOperationIsRegisteredOrPending (other tickets' routes), ParityExpectTest (queue-keep-going-target), LocalParityTest x2 (missing server-generated goldens: bulk-cards-all-actions-local, bulk-schedule-cards-local, queue-keep-going-target).
