# HANDOFF T005

STATUS: DONE
BRANCH: t/T005-sync
CHECK: `contracts/sync/check.sh` → green (not yet wired into `make check`)

## Changed (≤ 6 lines)
- `contracts/sync/SYNC.md`: protocol, HLC, apply/merge/replay rules, rejected ops, convergence, diagrams.
- `contracts/sync/OPS.md` + `ops/*.schema.json` + `ops/index.json`: 45 ops (SCHEMA.md names), each with schema, conflict rule, example.
- `batch.schema.json` (T004 entry point: `#/$defs/PushRequest|PushResponse|PullResponse|PushRequestLenient`), `op.schema.json`, `common.schema.json`.
- `examples/`: 53 op examples, 6 batches, 18 must-fail instances, 14 scenarios (`scenario.schema.json`).
- `tools/model.py`, `tools/validate.py`, `check.sh`; `SUMMARY.md`.

## Acceptance → tests
- Schemas validate all examples → `check.sh`.
- Every spec conflict case has a worked example → SYNC.md §12.4 table ↔ `examples/scenarios/`.
- Every §4.1 op has schema, rule, example → `validate.py` registry check.
- "Must also resolve" 1–8 → SYNC.md §11, §8.2, §8.4, §8.4, §8.5, §7.9, §10, §8.4.

## New dependencies
- none (`check.sh` needs python `jsonschema>=4.18`, else uses `uv`).

## For the reviewer (≤ 3 lines)
- Riskiest: no clock-skew rejection (§3.4); discard of a rejected op = full rebuild from seq 0 (§10.3); `fsrs_params.updated` forces replay (§9.2, T006 must confirm).
- Decisions beyond the ticket: REST-originated ops use the server device id; instants are epoch ms; new `sync_state` key `rebuild_pending`.
- T004: validate push bodies with `PushRequestLenient`, per-op errors go in `PushResult`. Snapshot blob formats undefined.
