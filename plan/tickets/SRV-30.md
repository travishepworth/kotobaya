# SRV-30 — Server: loop.keep_going_available + until_stop_parts; re-record goldens (ADR-049)

- Phase: P10 · Model: sonnet (writer) · Risk: med · Deps: C-30, CORE-30, audio chain merged to main
- Branch: t/SRV-30 · Owned: server/**, fixtures/shell-parity/golden/**
- CI: server

## Read
- docs/DECISIONS.md ADR-033, ADR-037, ADR-038, ADR-039, ADR-049; plan/handoffs/C-30.md
- contracts/api/SUMMARY.md "C-30 changes"; contracts/core/README.md "ADR-049"; core/src/commonMain/kotlin/kotobaya/contracts/queue/ReviewLoop.kt; docs/REVIEW-LOOP.md §14 (C3, C4, C6); parity scenario queue-loop-wait-before-stop

## Do
Map LoopState.keepGoingAvailable / untilStopParts to the API ReviewLoop; re-record parity goldens (queue-loop-wait-before-stop new, queue-until-stop step 8, 19 /loop expectations).

## Acceptance
- [ ] make check MODULE=server green; parity incl. queue-loop-wait-before-stop.
