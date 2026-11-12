# HANDOFF T005 (attempt 3)

STATUS: DONE
BRANCH: t/T005-sync
CHECK: `contracts/sync/check.sh` → green (`make check MODULE=contracts` is still a stub)

## Changed (≤ 6 lines)
- Re-mint (SYNC §10.4): clock-rejected reviews are discarded and re-logged under new ids with one batch shift `delta = min(max(offset, d_lo), d_hi)`, then a per-review clamp to `[min(card.created_at, now), now]`. Spacing is kept. Default action; edits discard-only.
- Device guard G1/G2 (§3.4): no minting when `pt < FLOOR` or `pt < wall(hlc_last) − 24 h`.
- Card-age server check dropped (§3.4 justifies it; SUMMARY lists it).
- `clock_ahead` HLC slack ε = 60 s. §6.2: auto-retry `unknown_reference` after a log reset; a sentence on the clock being 24 h ahead after a restore.
- New `sync_state.remint_offset`; optional scenario `remint` property. Batch $defs untouched.

## Acceptance → tests
- A week of 700 reviews is re-minted, the replicas converge and replay uses the re-minted times → `validate.py remint_story`.
- Re-mint formula → scenario `clock-behind-remint` and the `remint_times` unit checks.

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Re-minted reviews sort after reviews other devices logged meanwhile; re-mint triggers a rebuild.
- Follow-up for `contracts/db`: the purge wording, plus five `sync_state` keys.
