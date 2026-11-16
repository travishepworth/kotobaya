# LOOP-SRV — Server: `loop` on getReviewQueue, legacy fields from core, parity goldens

- Phase: P10 · Model: sonnet · Risk: med
- Deps: LOOP-CORE
- Branch: t/LOOP-SRV-review-loop
- Owned paths (only edit these): server/src/main/kotlin/kotobaya/review/{ReviewApi.kt,QueueLoader.kt,ReviewSettings.kt}, server/src/main/kotlin/kotobaya/stats/StatsModule.kt (the `due` / `more_available` part only), server/src/test/kotlin/kotobaya/review/**, server/src/test/kotlin/kotobaya/parity/**, fixtures/shell-parity/golden/**
- Module(s) for CI: server
- Gradle-heavy: yes (Testcontainers)

## Read
- Design: docs/REVIEW-LOOP.md §5, §8, §9, §10
- Contracts: openapi `getReviewQueue`, `ReviewQueue`, `ReviewLoop`, `StatsSummary`; SHELL.md §8; contracts/core README C-27
- Decisions: ADR-037, ADR-032

## Do
1. `ReviewApi.render`: call `DefaultReviewLoop.evaluate(loaded.input)` once; `items` from `result.queue`; emit `loop` from `result.state`; emit `counts`, `more_available`, `next_learning_due_at` from `state.legacy` / `state.nextDueAt`. Delete the local `laterLearning` and the "normal build for counts" code. `kinds`: filters `items` and `to_materialize` only; with a `kinds` filter `more_available` keeps its old per-kind probe.
2. `QueueLoader`: stop reading `new_per_session` into the budget (pass the contract defaults); pending-entry window `B = max(1, batch)`; keep `keepGoingNewBatch = limit`. Materialization rounds (C-27) run on the served build exactly as now.
3. `StatsModule`: `due` and `more_available` through `ReviewLoop` legacy counts (same numbers as the queue).
4. Parity: `RECORD=1` re-record the goldens listed in design §9, add the four new ones, delete the retired one. Recording must fail on any `expect` mismatch (existing behaviour).

## Acceptance (each bullet → at least one named test)
- [ ] `ReviewApiTest.loopUnlimitedLaterLearningServesNew` (A1 end to end: 26 learning later, phase reviewing, items new, `counts.learning` 26 legacy)
- [ ] `ReviewApiTest.loopDoneForTodayThenKeepGoingBacklogFirst` (B3 → `keep_going=true` B4; `loop` equal except phase/keep_going)
- [ ] `ReviewApiTest.loopGoalReachedThenKeepGoing` (A3)
- [ ] `ReviewApiTest.loopIndependentOfKindsAndLimit`; `ReviewApiTest.legacyFieldsFrozen` (snapshot of an old-client response shape)
- [ ] `DeckMaterializeTest` still green: `limit=1` normal fetch materializes at most one entry group; a second fetch creates nothing
- [ ] `ParityGoldenTest` reproduces every golden without `RECORD`
- [ ] `make check MODULE=server` green

## Out of scope
Android local API (LOOP-AND); web/CLI rendering; contract edits.

## Notes from previous attempts
