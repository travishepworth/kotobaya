# HANDOFF T303a (attempt 2)

STATUS: DONE
BRANCH: t/T303a-sync-client-protocol (rebased on main)
CHECK: `make check MODULE=core` → green (jvmTest + jsTest)

## Changed (≤ 6 lines)
- Clock block: the `client.clock_blocked` flag is set when |skew| > 24 h and cleared only when it is < 5 min (`SyncBookkeeping.recordServerSkew`). Used by `OpMinter` and `SyncStatus`.
- `SyncEngine.resetLog` (6.2) also sets `pull_seq = 0` (C-10). It zeroes attempts on `unknown_reference` rows and sets `client.log_reset_epoch`.
- `mintAll(requests, at)`: `mintAt` (Re-mint only), same checks, joins an outer transaction.
- The 413 cap resets after a successful batch. New: `rebuildPending` setter, `PendingOpRow.toOp/toTree`, `OutboxTable.setAttempts`, `Completed.pullAdvanced`.
- *Upgrade required* is stored in `client.upgrade_required`. `SyncStatus` gets `refusedCacheCards`, supplied through `SyncSnapshot.read(tx) { … }`.

## Acceptance → tests
- Clock → `clockBlockLiftsOnly…_25h_3h_4min`, `exactlyFiveMinutes…`
- Livelock → `logResetSetsPullSeqToZero…`; attempt zeroing → `logResetZeroesAttempts…`
- `mintAt` → `mintAtIsForRemint…`; 413 cap → `the413CapIsReset…`
- Upgrade persisted → `upgradeRequiredIsPersisted…`
- `s12_4` checks remint_offset = `remint.offset_ms`. Fuzz checks per-op apply counts and has skewed devices.

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- `resetLog` is here, not in T303b, because it was needed for the livelock fix. T303b calls it, then runs the rebuild and the auto-retry.
- The status shows a stored *upgrade required* only when the last result has no failure of its own. The stored value is cleared when a pull completes, or by a reset.
