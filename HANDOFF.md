# HANDOFF SHELL-P1

STATUS: DONE
BRANCH: t/SHELL-P1-parity-server
CHECK: `make check MODULE=server` → green

## Changed (≤ 6 lines)
- `server/src/test/kotlin/kotobaya/parity/`: `ParityServerHarness` (fresh DB + unmodified dict dump, two device tokens, seed/apply via `POST /sync/push` as seed device, steps over HTTP as `device_id`, one injected clock), `Normalizer` (SHELL.md 8.3), `ParityScenario`, tests.
- `fixtures/shell-parity/golden/*.json` (17, recorded with `RECORD=1`) + README.

## Acceptance → tests
- Every scenario has a golden, server reproduces it → `ParityGoldenTest.everyScenarioHasAGoldenAndTheServerReproducesIt` (one dynamic test per file), `everyGoldenBelongsToAScenario`
- Rejected seed op fails loudly; executed count = file count → `aRejectedSeedOpFailsTheScenarioLoudly`, dynamic test "executed scenarios = scenario files"
- Normalisation rules → `NormalizerTest` (5 tests)

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- No `expect`/invariant disagreements: every step's `expect` and every invariant held against the real server.
- No main code changed. `composeApp` has no clock parameter, so the harness builds the handler set itself (auth, dict, sync, notes, decks, settings, review, cards, stats; no jobs, blobs, or parser) with `SyncWiring.config().copy(clock=…)`.
- `expect` is checked on the normalised body. Ignored subtrees still advance the id/hlc counters, because rule 6 runs after rule 5.
