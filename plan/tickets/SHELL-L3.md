# SHELL-L3 — Local answerReview and undoReview

- Phase: SHELL · Model: fable · Risk: high
- Deps: SHELL-L2
- Branch: t/SHELL-L3-local-answer
- Owned paths (only edit these): android/src/main/kotlin/kotobaya/android/shell/api/review/Answer*.kt, android/src/main/kotlin/kotobaya/android/shell/api/review/Routes.kt, android/src/test/kotlin/kotobaya/android/shell/api/review/Answer*
- Module(s) for CI: android
- Gradle-heavy: yes · First phone test: yes

## Read
- Spec: Android app; Web app; Sync and offline
- Design: docs/ANDROID-SHELL.md (ADR-036) — sections 5, 6
- Contracts: openapi.yaml (answerReview, undoReview, ReviewResult), contracts/sync/SYNC.md (3.4 guards G1/G2, 7, 10.4, 10.5 and its worked examples), contracts/sync/OPS.md (review.logged, review.undone)
- Decisions: ADR-030, 033, 036

## Do
Write the property/fuzz tests first, from the SYNC.md worked examples. Read `review/ReviewSession.kt` as the reference for the mint
requests and timing; do not edit it (the legacy UI keeps using it until Z1).
- `answerReview`: `id` required; existing `id` → stored review + current card, 200, nothing minted (also when it belongs to another card);
  `button`↔`rating` map; `mode` schedule/drill/cram → `review_type`; `reviewed_at` default device now with in_future / before_floor / before_card bounds;
  `duration_ms` clamp 600000; suspended + schedule → 409 card_not_reviewable; deleted → 410; merge loser answered on the survivor.
  One `review.logged` through `LocalWrites`; card state recomputed by the existing replay path, never by handler arithmetic.
- `undoReview`: `review_id` required; unknown → 404; already undone → 200, nothing minted; else one `review.undone`.
- After commit: reschedule the due alarm / widget refresh exactly as `ReviewSession` does today (`DueTrigger`).

## Acceptance (each bullet → at least one named test)
- [ ] Parity scenarios green: answer retry same id, suspended 409, deleted 410, reviewed_at bounds, button mismatch, undo twice, unknown review_id
- [ ] `AnswerIdempotencyTest`: N retries of one answer → one op, one review_log row, card state equals a single answer
- [ ] `AnswerOutOfOrderTest`: local answers interleaved with pulled reviews of the same card with earlier and later HLCs → card state equals a full replay in timestamp order (SYNC.md worked examples)
- [ ] `AnswerConvergenceFuzz`: random answer/undo/pull sequences on two stores converge to identical card and review tables (reuses the core fuzz harness; seed printed on failure)
- [ ] `ClockSkewTest`: device clock 10 min ahead and 2 days behind → the documented 422s, nothing minted
- [ ] Minted ops validate against op.schema.json

## Out of scope
Suspend/bury/flag (L4). Any change to ReviewSession.

## Notes from previous attempts
