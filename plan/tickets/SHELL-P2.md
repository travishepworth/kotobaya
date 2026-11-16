# SHELL-P2 — Android parity harness against the goldens

- Phase: SHELL · Model: opus · Risk: med
- Deps: SHELL-A2, SHELL-P1
- Branch: t/SHELL-P2-parity-android
- Owned paths (only edit these): android/src/test/kotlin/kotobaya/android/shell/parity/**
- Module(s) for CI: android
- Gradle-heavy: yes · First phone test: yes

## Read
- Spec: Android app; Web app; Sync and offline
- Design: docs/ANDROID-SHELL.md (ADR-036) — sections 6
- Contracts: contracts/api/shell/SHELL.md (parity), parity/*.json; contracts/sync/SYNC.md 10
- Decisions: ADR-036

## Do
Plain JVM test harness (JDBC SQLite driver, no Robolectric): build a `SqlDelightDeviceStore`, apply `seed.log` through the core pull-apply
path (as if pulled), fix the clock per step, run steps on `LocalApi.handle`, normalise, compare with `fixtures/shell-parity/golden/<id>.json`.
A scenario runs only when all its operationIds are registered; the rest are reported as `pending` and the test prints the list
(so L tickets turn scenarios on by registering routes). A dictionary fixture from `fixtures/dict` is installed for scenarios that need entries.

## Acceptance (each bullet → at least one named test)
- [ ] `LocalParityTest` runs with zero registered routes (all pending) and fails if a registered operation's scenario differs from its golden
- [ ] Harness self-test: a deliberately wrong fake handler is detected as a mismatch with a readable JSON-pointer diff
- [ ] Seed apply uses the production apply path (asserted by op count in `pending`/`applied` tables)

## Out of scope
Writing handlers.

## Notes from previous attempts
