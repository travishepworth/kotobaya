# RC-WEB — Review cycle hardening: web review flow + dashboard errors

- Phase: P10 · Model: opus (writer-senior) · Risk: high (user has hit review-flow bugs repeatedly)
- Deps: ADR-033 (merged)
- Branch: t/RC-WEB
- Owned paths: web/
- Module(s) for CI: web

## Read
- Contracts: contracts/api (review/queue, review/answer, review/undo, stats/*)
- Decisions: ADR-033 (no learn-ahead; waiting screen with countdown; counts identical across clients)

## User report (live web 0.1.22, 2026-11-16)
"Shows 3 cards, but clicking gets stuck loading and doesn't start any reviews, but takes you to
the review over screen. Also an error just showing 'something went wrong' on the web app."
Live queue response at that moment: items=[], counts={new:0,learning:3,review:0},
more_available=true, to_materialize=0, next_learning_due_at≈+2 min.
Server latency today: /review/queue ~6 s per call (being fixed in RC-SERVER); stats endpoints
14–76 s and some 504 (gateway timeout) — the dashboard turned that into a global error.

## Do
1. Review page: items=0 with counts>0 or next_learning_due_at set must show the ADR-033 waiting
   state ("next card in m:ss") and auto-resume at the due instant — NEVER the session-over
   screen. Session over only when counts are all 0 and no next_learning_due_at (and keep going
   offers nothing). Handle more_available=true with 0 items sensibly (refetch, not done).
2. Loading: a slow queue fetch (6 s+) shows a loading state, never a dead spinner; on failure or
   timeout show a retry, not "session over". Prefetch/refetch must not race the current card
   (keep the stale-fetch answer filtering from ADR033-WEB).
3. Answer/undo failures: surface + retry; never silently drop an answer or advance past it.
4. Dashboard/home: each stats widget fails independently (inline "couldn't load" + retry);
   no page-level "something went wrong" for a failed/504 stats call. Don't fire stats
   requests that block the review button or the counts.
5. Counts on the home/review button must come from the queue's counts (same as other clients).

## Acceptance
- [ ] vitest: queue {items:[], counts.learning:3, next_learning_due_at: future} → waiting state, not done.
- [ ] vitest: queue fetch rejects/504 → error+retry state, not done.
- [ ] vitest: counts all 0, no next_learning_due_at → done.
- [ ] make check MODULE=web green.

## Out of scope
Server performance (RC-SERVER), Android, CLI.
