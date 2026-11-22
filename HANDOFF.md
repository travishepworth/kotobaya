# HANDOFF C-34

STATUS: DONE
BRANCH: t/C-34-until-stop-ready-now
CHECK: `make check MODULE=contracts` → green

## Changed (≤ 6 lines)
- openapi.yaml: `until_stop` = learning + review + new; C3 shows three chips, `later` only in the hint; Home's big number is `until_stop`, not `ready.total`; `until_stop>=1` iff reviewing (materialization included); examples fixed (23→9, 255→229, 66→40, 65→60, 19→16)
- ReviewLoop.kt / LoopPresenter.kt KDoc: I9, P-I5, I3, projection table, worked examples; "steady total" now says "usually" and lists the I7 exceptions
- parity: 20 `until_stop` values in 7 scenarios plus narratives (attempt 2: fixed two narratives that still read as sums)
- check_shell.py `parity_until_stop`: sum rule, 0 when waiting or terminal, ≥1 when reviewing, per-part pointers; 4 self-tests
- SHELL.md, both SUMMARY.md files, core README: ADR-038/049 marked amended; C-34 sections; the README "already due" edge case is scoped to the loop; clients regenerated

## Acceptance → tests
- make check green → `make check MODULE=contracts`
- no text sums `later` → `parity_until_stop` + grep

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- I worked out `later` by hand for 5 kanji pointer expectations that pin no parts.
- Presenter equality now needs every returning pending answer to have `dueAt > now`.
