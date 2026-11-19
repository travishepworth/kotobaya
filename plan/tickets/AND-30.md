# AND-30 — Android: local loop fields + parity goldens (ADR-049)

- Phase: P10 · Model: sonnet (writer) · Risk: med · Deps: C-30, CORE-30, SRV-30
- Branch: t/AND-30 · Owned: android/**
- CI: android

## Read
- docs/DECISIONS.md ADR-033, ADR-037, ADR-038, ADR-039, ADR-049; plan/handoffs/C-30.md
- contracts/api/SUMMARY.md "C-30 changes"; contracts/core/README.md "ADR-049"; core/src/commonMain/kotlin/kotobaya/contracts/queue/ReviewLoop.kt; docs/REVIEW-LOOP.md §14 (C3, C4, C6); parity scenario queue-loop-wait-before-stop

## Do
Local API maps keepGoingAvailable / untilStopParts like the server; LocalParityTest goldens from SRV-30; any native count surface (widget/tile/notification) uses the same numbers and refreshes at next_due_at.

## Acceptance
- [ ] make check MODULE=android green; local parity incl. queue-loop-wait-before-stop.
