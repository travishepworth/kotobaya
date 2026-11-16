# HANDOFF SHELL-L3

STATUS: DONE
BRANCH: t/SHELL-L3-local-answer (from t/SHELL-L2-local-queue)
CHECK: `make check MODULE=android` → green (542 tests)

## Changed (≤ 6 lines)
- `shell/api/review/AnswerRoute.kt`: `answerReview`, `undoReview` (validation, idempotency, bounds, one mint via `LocalWrites`, `DueTrigger` after commit)
- `AnswerViews.kt`: `ReviewResult` view; `Routes.kt`: two routes
- `RouteTableTest` PENDING, `ParityDevice` (`DueTrigger`) updated
- **Outside owned paths, own commit 7c24c536:** one line in `QueueParityTest` (its "still pending" list named `empty-replica`, `queue-day-rollover-timezone`). Revert it if unwanted; check is then red.

## Acceptance → tests
- Parity scenarios → `AnswerParityTest`
- `AnswerIdempotencyTest` (retries, concurrent, undo, re-delivery)
- `AnswerOutOfOrderTest` (SYNC.md 12.1 in four arrival orders, HLC vs answer-time, undo)
- `AnswerConvergenceFuzz` (120 seeds, seed in failure message)
- ClockSkew → `AnswerClockSkewTest`
- Op schema → `AnswerOpsTest`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Fuzz is an Android-level harness (two phones + observer on real stores); core's harness is in core's test sources, not reachable.
- "Clock 10 min ahead" is only a 422 for an explicit `reviewed_at`; a device clock ahead <24 h is accepted (SHELL.md 10.4).
- `utc_offset_min` absent → null, as the server; a process-wide lock wraps both writes; `AnswerViews.card` duplicates `QueueViews`' private card view.
