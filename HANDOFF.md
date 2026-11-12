# HANDOFF T004 (attempt 2)

STATUS: DONE
BRANCH: t/T004-openapi
CHECK: `contracts/api/check.sh` → green against the real `../sync/batch.schema.json` (stand-in removed; missing file now fails)

## Changed (≤ 6 lines)
- Sync: bodies are exactly `batch.schema.json#/$defs/PushRequest|PushResponse|PullResponse`; 400 only on `PushRequestLenient`; all else per op (adds `insufficient_scope`); no `exclude_own`, limit ≤ 1000, past-head `since` → empty 200; `high_water_seq`/`log_epoch` used everywhere.
- Review: `answerReview.id` and `undoReview.review_id` are required; reschedule/reset moved to `POST /cards/bulk/schedule` (scope `review`).
- Auth: `GET/PATCH /devices/self`; ankiConnect `security: [{}, …]` + `x-auth: body-key`.
- Minors fixed: device-id reuse (SYNC §3.3), keyset cursor skip/repeat semantics, `importMedia.import_id`.
- check_contract.py: sync bodies must be the `$defs`; sync examples must equal `contracts/sync/examples` files.

## Acceptance → tests
- Spec endpoints, scopes, tables → `check_contract.py`; validity and examples → Redocly lint.

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Per-op `insufficient_scope` is permanent; the alternative (whole-request 403) blocks the outbox.
- `import_id` idempotency relies on job/media rows surviving; job retention is unspecified.
- T005's §10.4 re-mint change is device-side; no API impact.
