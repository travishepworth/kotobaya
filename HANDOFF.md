# HANDOFF C-31

STATUS: DONE
BRANCH: t/C-31-local-first
CHECK: `make check MODULE=contracts` → green; `make check MODULE=core` → green

## Changed (≤ 6 lines)
- `contracts/api/shell/`: class `cached` (K-1…K-4, `503 shell_uncached`, `cache.updated`), L-9, L-7/P-2/P-3 rewritten, `first_sync`, `LOCAL_API_VERSION` 3, 6 parity scenarios, `check_shell.py`.
- `openapi.yaml`: `keep_going_new_target`, rules C2/C5 amended, C7, C8.
- `core/.../contracts/queue/`: `QueueLimits.keepGoingNewTarget`, I1…I8, W3/W4/X1–X3, new `LoopPresenter.kt`.
- `SYNC.md` 6.1/8.1 notes; `response_cache` DDL + SCHEMA.md; SUMMARY/README sections; clients regenerated.

## Acceptance → tests
- Checks green, counts match → `ops_map_complete`, `ops_map_counts`, `selftest_ops_counts`
- Scopes → `ops_map_scopes`, `selftest_ops_cached_scope`, `selftest_ops_local_scope`, `selftest_ops_cached_not_get`
- Cross-check → `shell_md_cross_check` (SYNC.md 14 needs no change)
- Edge cases → SHELL.md 10.15–10.20; ReviewLoop X2, X3
- Worked examples → `parity_mandatory_covered`; CORE-31 test list in `contracts/core/README.md`
- SUMMARY → `contracts/api/SUMMARY.md`, `contracts/core/SUMMARY.md` (C-31)

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Outside owned paths: one allow-list line in `contracts/db/check.sh` (`response_cache`); the DDL fails the check without it.
- `LoopPresenter.present` has a fifth parameter `dayEndsAt`; I7 is not asserted for target-less keep going; a due review waits behind an open kanji unit.
- New parity goldens are unrecorded (server `RECORD=1`); expectations are hand-evaluated.
