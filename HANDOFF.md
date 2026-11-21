# HANDOFF LF-GOLD

STATUS: BLOCKED
BRANCH: t/LF-GOLD
CHECK: `make check MODULE=server` → red (3 scenarios unrecorded; 5 test failures); `make check MODULE=android` → red (LocalParityTest 2 failures; ParityExpectTest green)

## Changed (≤ 6 lines)
- fixtures/shell-parity/golden/: recorded 8 new goldens (RECORD=1): bulk-cards-all-actions-local, bulk-schedule-cards-local, card-writes-local, note-type-writes-local, preset-writes-local, queue-keep-going-target, stats-heatmap-retention-local, stats-summary-local
- Existing goldens unchanged (byte-identical)

## Acceptance → tests
- 8 missing goldens recorded → ParityGoldenTest (RECORD=1)
- Not recorded: confusable-writes-local, grammar-writes-local, resolve-speech-local → server answers 501 not_implemented; the recorder refuses to write them

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Harness `server/src/test/kotlin/kotobaya/parity/ParityServerHarness.kt` wires no ConfusableModule, grammar module or resolveSpeech handler (owners: SHELL-P1 for harness; LF-A8 / LF-A6 for handlers, unverified).
- Grammar title-sort check (LF-A8b): not evaluable; grammar-writes-local has no golden.

## If BLOCKED
QUESTION: Should the parity harness wire ConfusableModule, the grammar module and resolveSpeech so the 3 scenarios can be recorded (out of LF-GOLD's owned paths), or should those 3 goldens wait while LF-GOLD closes with 8 recorded?
