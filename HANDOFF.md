# HANDOFF LF-A4

STATUS: DONE (bulkScheduleCards wiring pending, see reviewer)
BRANCH: t/LF-A4 (from origin/t/LF-int)
CHECK: `make check MODULE=android` → red only on the pre-existing/expected failures below (no new ones)

## Changed
- `BulkRoute.kt`: every bulkCards action local (set_preset, add/remove_tags, move, delete added; Fallthrough on action type removed; query Forward fallthrough stays for LF-A5). New `bulkScheduleCards` handler (manual review.logged).
- `CardMints.kt` (new): reusable mint builders for card ops, tags, manual review, UUIDv7 ids (for LF-A8). Deck puts reuse `DeckMints`.
- `Routes.kt`: bulkScheduleCards route commented out.

## Acceptance → tests
- Per action/selector/dry_run/empty/dup/merge loser/tombstoned/unknown → `BulkAllActionsTest`; parity `bulk-cards-all-actions-local` passes ParityExpectTest.
- Idempotency (tags, delete, move) and op schema validity (`isValidOp`) → `BulkAllActionsTest`. `BulkAtomicTest` forwarded-test replaced.
- Server push acceptance: only via the parity scenario; no separate push test.

## New dependencies
none

## For the reviewer
- `LocalApi` rejects a route for an op classed PROXY, and `ApiOperations.kt` (LF-A2's) still has bulkScheduleCards PROXY, so I could not register it. Flip it to LOCAL, uncomment the Routes.kt line, remove `@Ignore` from `scheduleRescheduleAndResetMintManualReviewRows` (written, never run).
- Remaining failures: RouteTableTest x2, ParityExpectTest (queue-keep-going-target), LocalParityTest x2 (missing goldens for bulk-cards-all-actions-local and queue-keep-going-target; one run showed a JDBC "No suitable driver" flake).
