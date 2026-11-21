# HANDOFF LF-W2

STATUS: DONE
BRANCH: t/LF-W2-web-swr
CHECK: `make check MODULE=web` → green

## Changed
- review.svelte.ts: limit 30, refill at ≤10, a due card no longer discards a prefetch (merged); exports `startOutbox`, `outboxSettled`.
- New state/swr.ts (shared event subscription, debounced `sync.changed`, per-operation `cache.updated`, `isUncached`); stats/decks/struggle/today keep data while refetching, expose `uncached`, `watch()`.
- Home: stats in parallel with the queue, "not downloaded yet" placeholders (`home.notDownloaded` in routes/messages.ts); counts wait up to 3 s for the outbox.
- +layout starts the outbox; logout calls `clearOutbox()`; `cache.updated` added to events.ts; outboxStore shares one reconnect promise.

## Acceptance → tests
- 25 grades, answers delayed 10 s → tests/review/prefetchOutbox.svelte.test.ts
- no blanking / other-operation event ignored; uncached placeholder, no toast, fills on event → tests/state/swrStores.test.ts
- reload sends stored answers without /review; logout clears outbox → prefetchOutbox.svelte.test.ts

## New dependencies
none

## For the reviewer
- A prefetch made stale by a newer full fetch is still dropped (sessionEnd test guards buried siblings) but now retried at once (`refill`), so the refill is not lost.
- Skipped optional outboxStore :184 (copying IDB records to memory: no transaction can start there, so nothing is readable).
- client.ts (not owned) still reports `shell_uncached` 503s via `reportApiFailure`; stores avoid toasts by using quiet clients. Decks and struggle lists now use quiet clients (inline error, no toast). Stats screen route has no placeholder markup (skeleton), only Home does.
