# LF-GOLD — Record shell parity goldens for the local-first scenarios

- Phase: P10 local-first · Model: haiku · Risk: low
- Deps: LF-A3, LF-A6, LF-A8, LF-A8b, LOOP-31-AND (every new scenario has its local handler)
- Branch: t/LF-GOLD
- Owned paths (only edit these): fixtures/shell-parity/golden/
- Module(s) for CI: server, android

## Read
- fixtures/shell-parity/ README; server ParityGoldenTest (how RECORD=1 works); contracts/api/shell/parity/

## Do
Run the server parity recorder (`RECORD=1`, see ParityGoldenTest) to record goldens for every scenario that lacks one: queue-keep-going-target, bulk-cards-all-actions-local, bulk-schedule-cards-local, card-writes-local, confusable-writes-local, grammar-writes-local, note-type-writes-local, preset-writes-local, resolve-speech-local, stats-heatmap-retention-local, stats-summary-local, and any others added in this round. Do not re-record existing goldens unless the scenario changed (list any that did, with a reason).

## Acceptance
- [ ] server ParityGoldenTest green; android LocalParityTest + ParityExpectTest green (or each remaining diff listed with the owning ticket).

## Out of scope
Fixing handler diffs (owning tickets).
