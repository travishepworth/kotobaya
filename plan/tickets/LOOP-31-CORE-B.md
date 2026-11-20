# LOOP-31-CORE-B — Core: LoopPresenter + answer projection, exported to JS

- Phase: P10 local-first · Model: opus · Risk: med
- Deps: C-31, LOOP-31-CORE-A
- Branch: t/LOOP-31-CORE-B-loop-presenter
- Owned paths (only edit these): core/src/commonMain/kotlin/kotobaya/queue/DefaultLoopPresenter.kt (new), core/src/jsMain/ (export only), core/src/commonTest/kotlin/kotobaya/queue/
- Module(s) for CI: core

## Read
- Spec: Scheduling
- Contracts: core contracts/queue/ReviewLoop.kt (`LoopPresenter`, `PendingAnswer`, `LoopPresentation`); contracts/core/README.md C-31
- Decisions: ADR-038, 049, 051
- Design: scratchpad `local-first-design.md` (orchestrator: copy to `docs/design/local-first.md` or paste the named section into this ticket).

## Do
Tests first: the presenter worked examples, then extend the fuzz test with I6 (some answers left "pending": the presenter is fed the last evaluated state plus the pending list; assert terminal screen ⇒ no pending ∧ parts zero; `parts.learning + parts.review > 0 ⇒ screen ∈ {CARD, SETTLING}`; `hasLocalCard ⇒ CARD` and the head's part ≥ 1; projected `untilStop` never negative). Then implement `DefaultLoopPresenter` and export it through the JS facade used by `@kotobaya/core` (same style as `previewIntervals`).

## Acceptance (each bullet → at least one named test)
- [ ] Every presenter worked example of C-31 is a named test.
- [ ] Fuzz I6 green on jvm and js.
- [ ] JS export callable from TypeScript with plain objects (a jsTest round-trips one example).
- [ ] `make check MODULE=core` green.

## Out of scope
Web wiring (LOOP-31-WEB).

## Notes from previous attempts
