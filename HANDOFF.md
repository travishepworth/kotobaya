# HANDOFF T005 (attempt 2)

STATUS: DONE
BRANCH: t/T005-sync
CHECK: `contracts/sync/check.sh` → green (`make check MODULE=contracts` is still a stub)

## Changed (≤ 6 lines)
- Restore: `log_epoch`, `durable_seq` in Push/PullResponse; log reset; acked rows kept until backed up; server HLC raised on restore (SYNC §6.2, §12.5).
- Clocks: `clock_ahead` (>24 h), `clock_behind` (<2020, review >24 h before card); discard-only, HLC repair (§3.4, §10.4).
- Fold only if `weights_version` matches (§9.2); duplicate check under lock (§5.2); rebuild finishes at upgrade stop (§10.3).
- `batch.schema.json`: lenient-validation rules, new `PullResponseLenient`; existing $defs names unchanged.
- §8.5 deck-item removal covers all equivalent keys; §10.2 rejected parent documented.
- model.py: Server (derived verdicts, validation subset, restore) and Device sim; new scenario `merge-two-level-cards`.

## Acceptance → tests
- Merge verdicts derived → `validate.py check_server`.
- Restore loses nothing (legacy loses) → `restore_story`.
- Clock codes → `rejected-ops` scenario.

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Reviews made with a clock >24 h off are lost (Discard only).
- Follow-up for `contracts/db`: SCHEMA.md 5.3 purge wording and four new `sync_state` keys.
- After a restore, REST and job ops since the last backup are lost.
