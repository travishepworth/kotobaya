# HANDOFF LOOP-C

STATUS: DONE
BRANCH: t/LOOP-C-review-loop-contract
CHECK: `make check MODULE=contracts` → green; core `compileKotlinJvm`/`compileKotlinJs` → green

## Changed (≤ 6 lines)
- core contract: new `contracts/queue/ReviewLoop.kt`; `QueueBuilder.kt` KDoc rules 4-6 (no signature change).
- openapi: `ReviewLoop`, `ReviewQueue.loop`, phases + C1-C5, legacy fields deprecated; clients regenerated.
- Shell: `LOCAL_API_VERSION` 2, display prefs ops, capability `display`, 4 new parity scenarios, `queue-session-cap-more-available` deleted.
- SCHEMA.md 6, core README (C-26, C-27), three SUMMARY.md, SPEC note, ADR-037 line.
- User amendment: rule 5 is a proportional mix (both modes); keep going stays learning, reviews, new. `docs/REVIEW-LOOP.md` edited on coordinator instruction (outside ticket paths).

## Acceptance → tests
- contracts green, tags enforced → `parity_mandatory_covered`, `selftest_parity_missing_loop_tag`, `selftest_parity_loop_phase_unpinned`
- core compiles → `check_kotlin.py`, gradle compile
- `ReviewLoop` documented, example valid → redocly lint, `shell_schema_docs`
- scenarios pin `loop` → the four `queue-loop-*.json`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Mix formula is mine: new next iff `introducedToday * (reviewsToday + |R|) < reviewsToday * newPerDay`. Lopsided history gives a run of new cards.
- My decisions: `evaluate` forces learn-ahead 0; only the served build's `toMaterialize` is materialized; `getStatsSummary` batch 20.
- Parity `expect` values are hand-derived, not run; goldens are stale until LOOP-SRV.
