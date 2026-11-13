# HANDOFF C-16

STATUS: DONE
BRANCH: t/C-16-fsrs-params-publish
CHECK: `make check MODULE=contracts` → green

## Changed (≤ 6 lines)
- New `contracts/db/FSRS_PUBLISH.md`: worker `fsrs-optimize` protocol, internal kind `fsrs-params-publish`, runner steps, error codes, idempotency.
- New `contracts/db/optimizer/`: payload schema, 2 worked examples with expected op/result, `validate.py` (reference `plan()`, run by `check.sh`).
- `SCHEMA.md` 4.4 registers the kind; `OPS.md` `fsrs_params.published` names its minter.
- `openapi.yaml`: `optimizePreset` "Execution"; `JobResultFsrsOptimize` gains optional `log_loss_before`, `holdout_review_count`, `reason`. Clients regenerated.
- SUMMARY notes in db, api, sync.

## Acceptance → tests
- Payload, publish-only-if-better, bounds, stale base, re-run → `contracts/db/optimizer/validate.py` self-test (52 checks)

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Additive only: no DDL, no op schema change, no new `JobKind`. Exactly-once rests on child `succeeded` committing with the op, plus `base_weights_version` (`stale_base`) and a same-parent no-op.
- Left to T606: review selection, split, minimum count. Gate is strict `<` with no margin.
- T606 server code must emit the two new `optimizer_stats` keys and `reason`; T605 may show them.
