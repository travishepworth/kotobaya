# BUG-14a — Web review reuses answerId when the same card returns → grades silently deduplicated

- Phase: P10 (0.1.10, CRITICAL) · Model: sonnet · Risk: med (reviewed)
- Deps: none
- Branch: t/BUG-14a-review-answer-id
- Owned paths (only edit these): web/src/lib/state/review*.ts, web/src/lib/state/review*.test.ts, web/src/routes/review/** (only if needed)
- Module(s) for CI: web

## Diagnosis (live 0.1.9, verified)
`web/src/lib/state/review.svelte.ts:117-124` mints a new `answerId` only when `current.card.id` changes. When the queue serves the same card again (relearning card due soon — see C-22), the next grade reuses the previous id; server `ReviewApi.kt:126` treats it as an idempotent retry → 200, nothing recorded. Live: last 3 h 118 answer POSTs vs 100 review_log rows; since 00:22 31 POSTs → 5 reviews (26 were 1–9 ms dedups). No outbox, no data stuck client-side.

## Do
- After every successful answer (and after undo), mint a fresh answerId and reset flipped/shownAt, regardless of whether the next card id is the same. Keep the SAME id for a genuine network retry of the same press (idempotency must still protect double-submits / retries).
- Audit other idempotency keys in web/src/lib/state (undo, suspend/bury, etc.) for the same "mint on card change" pattern; fix within owned paths, list others in HANDOFF.
- Check Android briefly (read only): does its review path mint a fresh op/answer id per grade? Report in HANDOFF; don't edit android.

## Acceptance (named tests)
- [ ] Vitest ReviewPage with mocked reviewApi: queue returns the same card twice; grade twice → two different ids sent; both grades recorded.
- [ ] Vitest: a retried POST for the same press reuses the id.
- [ ] make check MODULE=web green.

## Out of scope
Queue ordering (C-22). Server.
