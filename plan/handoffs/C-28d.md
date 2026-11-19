# HANDOFF C-28d

STATUS: DONE
BRANCH: t/C-28d-contracts
CHECK: `make check MODULE=contracts` → green, no drift; core compiles (jvm, js)

## Resolutions (apply in CORE-28b)
1. `pick`: S "claims" L = S can take a turn and its next item is a word requiring L or a NEW kanji card of L. (a) first claimer of `lastLiteral` in source order (no `t`); (b) smallest `t`; (c) while an earlier source claims a kanji part of the candidate's word unit, the earliest such source takes the turn.
2. Keep going: the share step draws no part (test before every part) while a source of any tier claims `lastLiteral`.
3. Lead: stop after the part that leaves the drawing deck's remaining at 0, also before cost-0 parts.
4. Tests: delete `rebuildExcluded` and `newWordKanji = false`; the rule (a) test expects source order.

## Changed
- `QueueBuilder.kt` rules 4-6, "Rebuild = tail" (exclusion 3 removed)
- Parity `queue-kanji-shared-kanji`, `-keep-going-open-unit`, `-lead-deck-limit`; SHELL.md 8.4, `check_shell.py`
- README tests, SUMMARY, REVIEW-LOOP §13

## Acceptance → tests
- hand-evaluated expects → the three parity scenarios

## New dependencies
- none

## For the reviewer
- Prototyped in `DefaultQueueBuilder` (not committed): 40000 seeds x 3 modes, no exclusion, 0 failures; seeds 506, 1471 pass; only the old rule (a) test breaks.
- Item 3: the lead ends instead of a capped deck finishing it; the kanji comes with the word.
