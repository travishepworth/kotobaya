# HANDOFF T005 (attempt 4)

STATUS: DONE
BRANCH: t/T005-sync
CHECK: `contracts/sync/check.sh` → green

## Changed (≤ 6 lines)
- §10.4 Re-mint now carries rows created in the window: clock-rejected `*.created` plus every `unknown_reference` op/review/undo that depends on them (one hlc-ordered pass, `refs()` per OPS.md). Same row ids, re-minted first via new `mint_at(t')` (§3.2) at the batch-shifted time, then reviews. Edits of existing rows stay discard-only (reason stated). The `hlc_last`-below-observed text is rewritten.
- §9.1/9.2/OPS: replay order `(reviewed_at, hlc, id)`; a late earlier review triggers a full replay.
- §4/5.3/5.4/6.1: `max_op_bytes` (default 1 MiB), `op_too_large`, 413 halving, a lone oversized op is marked rejected.
- §7.3: notes of a deleted note type stay usable. §14 matches openapi. Card-age server check: known issue.

## Acceptance → tests
- Offline-created notes/cards/edits/reviews survive a 10-day-ahead clock; a merge into B's survivor; convergence → `validate.py ahead_story` (§12.7).

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- `mint_at` deliberately mints below observed HLCs until the rebuild.
- Follow-up T004: `limits.max_op_bytes` in `/meta`, `op_too_large`.
