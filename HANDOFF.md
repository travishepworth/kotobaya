# HANDOFF C-34

STATUS: DONE
BRANCH: t/C-34-until-stop-ready-now
CHECK: `make check MODULE=contracts` → green

## Changed (≤ 6 lines)
- openapi.yaml: `until_stop` = learning + review + new; C3 has three header chips, `later` only in the hint; invariant `until_stop>=1` iff reviewing; examples fixed (23→9, 255→229, 66→40, 65→60, 19→16)
- ReviewLoop.kt / LoopPresenter.kt KDoc: new I9 and P-I5, I3, projection table, A1/U1/W1–W3/X1 and P1–P10 numbers
- parity: 20 `until_stop` values in 7 scenarios recomputed, plus their narratives and one title
- check_shell.py: new `parity_until_stop` check (sum rule, 0 when waiting or terminal) with 2 self-tests
- SHELL.md, api and core SUMMARY.md, core README: ADR-038/049 marked amended; new C-34 sections; clients regenerated

## Acceptance → tests
- make check green → `make check MODULE=contracts`
- no text sums `later` → `parity_until_stop` + grep

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- 5 kanji pointer expectations had no parts; I worked out `later` for each by hand (Good answers go to learning, Easy answers graduate).
- I also edited parity narrative text and the queue-until-stop title (not only values), so no text still sums `later`.
- Presenter equality now needs every returning pending answer to have `dueAt > now`.
