# PERF-SNAP2 — Keep the Home snapshot current in the background

- Phase: P11 perf · Model: opus · Risk: med
- Deps: PERF-SNAP (merged)
- Branch: t/PERF-SNAP2-snapshot-refresh
- Owned paths (only edit these): web/src/lib/state/snapshot.svelte.ts, web/src/lib/state/today.svelte.ts, web/src/lib/state/stats.svelte.ts, web/src/lib/state/review.svelte.ts (snapshot calls only), tests under web/tests/
- Module(s) for CI: web

## Why
The instant Home from PERF-SNAP "helps a ton". But it shows the numbers from the last visit to Home, then waits for the local handlers. The proposal to fill Home's counts from a server endpoint was rejected (plan/handoffs/PERF-REVIEW-2026-11-21.md). It breaks the rule that every client shows the same numbers (REVIEW-LOOP.md:14, ADR-051(b)): unpushed ops are missing and unpulled ops are present. And "server minus pending answers" can't be computed exactly. The correct version keeps the snapshot itself up to date, from local data.

## Do
1. While the app is open, rewrite the snapshot's Home sections in the background after:
   - each grading (from the review session's own state: the loop counts that come back with the answer and the next queue);
   - each `sync.changed` / `cache.updated` that affects Home;
   - a day rollover.
   Debounce to at most one write every 2 s, and always flush the last change on `visibilitychange` hidden and pagehide.
2. When the user returns to Home, the snapshot rendered first matches what the review header last showed. Keep the existing rules: don't save during Keep going, ignore a snapshot past dayEndsAt, and grading stays gated by the confirming fetch.
3. Never block anything on these writes. If IndexedDB fails, nothing changes.

## Acceptance (each bullet → at least one named test)
- [ ] Test: grade 3 cards, then go to Home. The snapshot hero equals the review header's last `ready.total`, before any fetch resolves.
- [ ] Test: a `sync.changed` that changes the counts rewrites the snapshot within the debounce window. 10 rapid events write at most 2 times.
- [ ] Test: hidden or pagehide flushes a pending write.
- [ ] Test: nothing is saved during Keep going, and a snapshot past dayEndsAt is still ignored. These are regression tests from PERF-SNAP.
- [ ] `make check MODULE=web` green.

## Out of scope
Server endpoints. Android handler changes.
