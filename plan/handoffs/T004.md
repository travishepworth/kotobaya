# HANDOFF T004 (attempt 3)

STATUS: DONE
BRANCH: t/T004-openapi
CHECK: `contracts/api/check.sh` → green against the real `../sync/batch.schema.json`, sync examples and SYNC.md §14

## Changed (≤ 6 lines)
- Size: `/meta` publishes `limits.max_op_bytes` (1 MiB, ≤ `max_push_bytes − 4096`); oversized op → per-op `op_too_large` (+ example); 413 = whole batch only, client halves down to one op.
- `answerReview`: 422 for `reviewed_at` > now+5 min, < 2020-01-01, or < `min(card.created_at, now) − 24 h` (Re-mint's bound; push-side known issue unchanged).
- Idempotent creates: id check + emit in one tx under advisory lock 7033001.
- `pairDevice`: optional `hlc_last`; below the device's max logged HLC → 422 `hlc_regressed`, code not consumed.
- `origin` (not `override_origin`) for op payloads; review history ordered `(reviewed_at, hlc, id)` desc; deleted note types' notes stay usable; pull pages bounded by `max_push_bytes`; rejected example re-synced from T005.

## Acceptance → tests
- check_contract.py rule 7 (new): every SYNC §14 endpoint exists and emits its table's op or is a job.

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- SYNC §14 omits `importJpdbHistory` under `note`/`card` and `importMedia` under `deck`; API table is a superset.
- Re-minted ops (§10.4) need no API change.
