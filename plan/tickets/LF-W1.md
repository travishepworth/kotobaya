# LF-W1 — Web: persisted answer outbox, background retry, grading never blocks

- Phase: P10 local-first · Model: opus · Risk: high
- Deps: LOOP-31-WEB
- Branch: t/LF-W1-web-outbox
- Owned paths (only edit these): web/src/lib/state/review.svelte.ts (Outbox, send/restore paths), new web/src/lib/review/outboxStore.ts, tests beside them
- Module(s) for CI: web

## Read
- Spec: Clients → Web app
- Contracts: openapi.yaml `answerReview` (idempotency by `id`), `undoReview`, client rule C8
- Decisions: ADR-048, 050
- Design: scratchpad `local-first-design.md` (orchestrator: copy to `docs/design/local-first.md` or paste the named section into this ticket).

## Do
1. `outboxStore`: IndexedDB database `kotobaya`, store `outbox`, key = answer `id`, value = `{req, cardId, kind, addedAt, attempts}`; in-memory fallback when IndexedDB is unavailable. Write on tap before the send; delete on `2xx` or permanent refusal. Scoped per principal id (cleared on logout).
2. On load: replay stored answers in `addedAt` order before the first queue fetch is applied (cards with stored answers are filtered from fetches, as pending ones are today).
3. Retryable failures (timeout, network, 401, 408, 429, 5xx): the entry stays, retried with backoff 1 s → 60 s and on `online` / visibility; later taps keep flowing behind it in order; the card is not put back and `gradeLocked` is not set. Clock `422` on `/reviewed_at`: resend once without `reviewed_at`. Permanent refusals: drop + toast (unchanged).
4. Undo of a not-yet-sent answer removes it from the store; undo of a sent one is itself queued the same way.
5. In shell mode the same code runs (the bridge answers in ms); nothing shell-specific.

## Acceptance (each bullet → at least one named test)
- [ ] Test: tap, reload before the response → the answer is sent exactly once after reload (same `id`), and the card is not served again.
- [ ] Test: 10 answers with the network down → all 10 cards advance with no restore; when the network returns they are sent in tap order.
- [ ] Test: duplicate delivery (response lost, retry) leaves one review (server mock dedups by `id`); out-of-order completion cannot reorder sends.
- [ ] Test: undo before send removes the stored entry; logout clears the store.
- [ ] `make check MODULE=web` green.

## Out of scope
Prefetch depth and other stores (LF-W2); a service worker / offline browser.

## Notes from previous attempts
