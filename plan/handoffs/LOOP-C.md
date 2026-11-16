# HANDOFF LOOP-C

STATUS: DONE
BRANCH: t/LOOP-C-review-loop-contract
CHECK: `make check MODULE=contracts` → green; core jvm/js main + jvm test compile → green

## Changed (≤ 6 lines)
- Attempt 2, C3: headline is always `loop.ready.total`; backlog only in its line (`backlog.reviews`), both modes (openapi, REVIEW-LOOP §4, SHELL 4.3, core README).
- Rule 5 mix is now a stride on remaining quantities: new iff `s * gRem >= rRem`; additive `TodayReview.reviewedAt` (default 0) gives `s`.
- Rule 4 defines `availableToday` (unbounded-batch draw) = `gRem`.
- Legacy `counts.new` / `StatsSummary.due.new` frozen: day remainder under deck caps, not the batch (`LegacyCounts.new` via N∞).
- Display prefs ops work unpaired, `503` during rebuild (shell.yaml, SHELL.md 3.1, 9, 10.1, 12).
- Parity: A1 `counts.new` 12; clients regenerated; SUMMARYs updated.

## Acceptance → tests
- contracts green, tags enforced → `parity_mandatory_covered`, `selftest_parity_missing_loop_tag`, `selftest_parity_loop_phase_unpinned`
- core compiles → `check_kotlin.py`, gradle compile
- `ReviewLoop` documented, example valid → redocly lint, `shell_schema_docs`
- scenarios pin `loop` → the four `queue-loop-*.json`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- LOOP-CORE: loaders must pass `reviewedAt`; `s` ignores ties with the latest introduction.
- With pending entries beyond the C-27 window, `gRem` and legacy `counts.new` are lower bounds.
- Parity `expect` values are hand-derived, not run; goldens stale until LOOP-SRV.
