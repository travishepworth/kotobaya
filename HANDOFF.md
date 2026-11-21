# HANDOFF LF-GOLD

STATUS: DONE
BRANCH: t/LF-GOLD
CHECK: `make check MODULE=server` → green; `make check MODULE=android` → green

## Changed (≤ 6 lines)
- server/src/test/kotlin/kotobaya/parity/ParityServerHarness.kt: added GrammarModule, ConfusableModule, TtsModule handlers (same as Main.kt; no handler changes)
- fixtures/shell-parity/golden/: 11 new goldens recorded (RECORD=1): the 8 earlier plus confusable-writes-local, grammar-writes-local, resolve-speech-local
- Existing goldens unchanged (byte-identical)

## Acceptance → tests
- 11 missing goldens recorded → ParityGoldenTest (55/55 green)
- Android LocalParityTest (3/3) and ParityExpectTest (1/1) green; the 2 earlier LocalParityTest failures were the same unrecorded scenarios, not handler diffs

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Remaining diff, not fixed: GrammarRoutes.kt sorts titles with Keyset.TEXT_ASC (code point), server uses Postgres collation. Golden has one grammar item, so no order difference is visible. Owner: LF-A8b follow-up.
