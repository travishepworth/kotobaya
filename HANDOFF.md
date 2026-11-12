# HANDOFF C-1

STATUS: DONE
BRANCH: t/C-1-contract-align
CHECK: contracts/db/check.sh → green; contracts/sync/check.sh → green (`make check MODULE=contracts` is still a stub)

## Changed (≤ 6 lines)
- Replay order `(reviewed_at, hlc, id)` everywhere: SCHEMA.md 4.2, V1 comments, smoke.sql, scenario text. Added index `review_log_replay_idx` (V1 + Learning.sq).
- `last_review_at` = last rated row; set_due keeps it, reset sets it NULL: SCHEMA 4.2, SYNC 9, V1/.sq comments.
- `core_scheduler_version`: SYNC 9.2/10.3/10.5 + Local.sq. On the server it is an `app.job` row (`kind='core-replay'`, succeeded), written in the replay transaction under lock 7033001.
- SYNC 6.1: a pulled op that fails its schema stops the pull, is never skipped, and shows "update required / invalid op".
- SYNC 5.3/5.4: size = utf8Size(`CanonicalJson`(op tree)), measured before decoding, for every op.
- fsrs_params.published: `fsrs_version` enum ["6"]; exactly 21 weights, each with per-index `prefixItems` bounds from py-fsrs 6.3.2 (read from the installed package). OPS.md note.

## Acceptance → tests
- out-of-range weight → examples/invalid/fsrs-params-published-{weight-out-of-range,wrong-count,zero-decay}.json
- SUMMARY "C-1 changes" sections appended to both SUMMARY.md files (Edit tool, no refusal)

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- The `fsrs_version` enum and the 21-weight length are stricter than before. The ticket asked for this. A future FSRS version must be added additively.
- Server marker via `app.job` is my choice. The row is visible in job listings.
