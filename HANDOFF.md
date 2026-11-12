# HANDOFF C-1 (attempt 2)

STATUS: DONE
BRANCH: t/C-1-contract-align
CHECK: contracts/db/check.sh → green; contracts/sync/check.sh → green

## Changed (≤ 6 lines)
- Fixes 1–3: I dropped the `app.job` marker and reverted the job.kind comment. Added the server-only table `app.server_state(key, value, updated_at)` to V1, SCHEMA 4.4/5.2 and the check.sh server-only list. SYNC 9.2/10.3/10.5: on startup the server takes lock 7033001, re-reads `core_scheduler_version`, replays if needed and upserts it in the same transaction. It applies no op until that commits.
- Fix 4: SYNC 15 now says a released op_version's schema is never tightened. The fsrs_params.published tightening is noted as a one-time exception because nothing is deployed yet.
- Fix 5: OPS.md and the schema description say finite schedules need the bounds "together with the core's clamps (T006)".
- Fix 6: SYNC 9 says a rated review after `set_due` with `last_review_at` NULL is a first review.
- Updated the "C-1 changes" sections in both SUMMARY files.

## Acceptance → tests
- out-of-range weight → examples/invalid/fsrs-params-published-{weight-out-of-range,wrong-count,zero-decay}.json
- server_state parity → db/check.sh (server-only allow-list)

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- `app.server_state` is a new table, which is additive. No existing key/value table fit: `settings` is synced, and `job` gets cleaned up.
