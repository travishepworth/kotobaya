# LOOP-31-CORE-A — Core loop: invariant fuzz test first, keep-going target, keepGoingAvailable (ADR-051)

- Phase: P10 local-first · Model: opus · Risk: high
- Deps: C-31
- Branch: t/LOOP-31-CORE-A-loop-fuzz-target
- Owned paths (only edit these): core/src/commonMain/kotlin/kotobaya/queue/DefaultReviewLoop.kt, DefaultQueueBuilder*.kt, core/src/commonTest/kotlin/kotobaya/queue/
- Module(s) for CI: core

## Read
- Spec: Scheduling
- Contracts: core contracts/queue/ReviewLoop.kt, QueueBuilder.kt (as amended by C-31); contracts/core/README.md C-31 section
- Decisions: ADR-037, 038, 039, 049, 051
- Design: scratchpad `local-first-design.md` (orchestrator: copy to `docs/design/local-first.md` or paste the named section into this ticket).

## Do
Tests first, from the contract's worked examples.
1. `ReviewLoopInvariantFuzzTest` (commonTest, jvm + js): seeded generator of `QueueInput` (cards in every state, due before/after now, buried/suspended/retired, pending entries with kanji units, limits incl. 0 and unlimited, both toggles, target null / reached / exceeded); a model that applies an answer to the served head with the real `FsrsScheduler`, advances the clock (also across a learning due and the day boundary) and re-evaluates. ≥ 2 000 runs × ≤ 60 steps on jvm (200 on js). Assert I1–I5 and I7 after every step; print the seed and the minimal failing prefix.
2. Run it against the current implementation and record which invariants fail (HANDOFF.md) before changing code.
3. Implement `keepGoingNewTarget` (K draws ≤ `target − newToday`; phase rule for "target reached") and the new `keepGoingAvailable` / `EMPTY` definitions. Old inputs (target null) keep their worked-example numbers (A1, A3, B3, B4, U1, W1, W2).

## Acceptance (each bullet → at least one named test)
- [ ] `ReviewLoopInvariantFuzzTest` exists, is deterministic per seed and green on jvm and js.
- [ ] Worked examples of C-31 (target) pass as named tests; all existing `ReviewLoopTest` / `ReviewLoopWaitTest` cases still pass unchanged except where C-31 says the value changes.
- [ ] A test proves `EMPTY ⇒ K∞ empty` and `GOAL_REACHED/DONE_FOR_TODAY ⇒ keepGoingAvailable`.
- [ ] `make check MODULE=core` green.

## Out of scope
Presenter (LOOP-31-CORE-B), API, clients, Android loader.

## Notes from previous attempts
