# LF-W2 — Web: deeper prefetch, stale-while-revalidate stores, cache events

- Phase: P10 local-first · Model: sonnet · Risk: med
- Deps: LF-W1, LF-A2
- Branch: t/LF-W2-web-swr
- Owned paths (only edit these): web/src/lib/state/ (review.svelte.ts prefetch only; stats, decks, struggle, media, today stores), web/src/lib/events.ts, web/src/lib/api/shell/ (event type only), web/src/routes/+page.svelte, web/src/lib/state/session.svelte.ts (logout only), web/src/routes/+layout.svelte (outbox start only), web/src/lib/review/outboxStore.ts, tests beside them
- Module(s) for CI: web

## Read
- Spec: Clients → Web app
- Contracts: openapi.yaml `getReviewQueue` C1–C8; SHELL.md 2.6 (`cache.updated`, `sync.changed`), K-4, `503 shell_uncached`
- Decisions: ADR-050
- Design: plan/tickets/LF-DESIGN.md (web section)

## Do
1. Review prefetch: `limit` 30, refill when ≤ 10 local cards remain, merge the result (never discard because another fetch applied or a learning card came due: a due card is spliced to the front instead), still excluding cards with stored/pending answers.
2. Stores keep their data while refetching (no skeleton after the first load); refetch on `sync.changed` (debounced 1 s) and on `cache.updated` for their operation.
3. `503 shell_uncached` is a "not downloaded yet" placeholder per widget, not an error toast; it resolves on `cache.updated`.
4. Home: start stats in parallel with the queue (`+page.svelte:37`).
5. (from LF-W1 review) C8 "on page load the outbox is sent first": load + kick the outbox at app start (+layout), not only from ReviewPage.start()/fetchQueue; Home counts must exclude cards with pending answers.
6. (from LF-W1) logout in session.svelte.ts calls clearOutbox().
7. (LF-W1 review minors, optional) outboxStore.ts:184 copy IndexedDB records into memory before falling back; :180 share one reconnect promise so racing calls use one connection.

## Acceptance (each bullet → at least one named test)
- [ ] Test: with every answer response delayed 10 s and 30 cards due, the user can grade 25 cards without a settling state.
- [ ] Test: a refetch does not blank a loaded list; `cache.updated` for another operation does not refetch it.
- [ ] Test: `shell_uncached` renders the placeholder and no toast; the widget fills after the event.
- [ ] Test: after reload on Home, stored answers are sent without opening /review.
- [ ] Test: logout clears the outbox.
- [ ] `make check MODULE=web` green.

## Out of scope
Outbox (LF-W1), loop rendering (LOOP-31-WEB).

## Notes from previous attempts
