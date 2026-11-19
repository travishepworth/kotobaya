# CORE-30 — Core: DefaultReviewLoop waits before stop; keepGoingAvailable; untilStopParts (ADR-049)

- Phase: P10 · Model: opus (writer-senior) · Risk: high · Deps: C-30
- Branch: t/CORE-30 · Owned: core/** except core/src/commonMain/kotlin/kotobaya/contracts/**
- CI: core

## Read
- docs/DECISIONS.md ADR-033, ADR-037, ADR-038, ADR-039, ADR-049; plan/handoffs/C-30.md
- contracts/api/SUMMARY.md "C-30 changes"; contracts/core/README.md "ADR-049"; core/src/commonMain/kotlin/kotobaya/contracts/queue/ReviewLoop.kt; docs/REVIEW-LOOP.md §14 (C3, C4, C6); parity scenario queue-loop-wait-before-stop

## Do
Implement C-30 in DefaultReviewLoop: WAITING before GOAL_REACHED/DONE_FOR_TODAY whenever served queue empty and laterToday > 0 (both modes); set LoopState.keepGoingAvailable and untilStopParts (sum = untilStop). Tests from contracts/core/README ADR-049 + W1/W2 + the reported case (goal met, 9 ready learning, 14 later, 0 reviews, 707 new). Never learn-ahead.

## Acceptance
- [ ] ReviewLoop tests incl. W1/W2 and reported case; make check MODULE=core green.
