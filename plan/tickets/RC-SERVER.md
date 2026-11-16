# RC-SERVER — Review cycle hardening: queue + stats latency, queue edge cases

- Phase: P10 (review-cycle hardening) · Model: opus (architect role) · Risk: high
- Deps: ADR-033 (merged)
- Branch: t/RC-SERVER
- Owned paths: server/, core/ (queue builder only if the cost lives there), contracts/db/ (ADDITIVE migrations only, e.g. indexes)
- Module(s) for CI: server (+ core, contracts if touched)

## Read
- Spec: review queue / stats sections of docs/SPEC.md
- Contracts: contracts/api (review/queue, stats/*), contracts/db
- Decisions: ADR-033 (no learn-ahead, next_learning_due_at, counts), ADR-028 superseded

## Live evidence (prod, api 0.1.20, 2026-11-16)
- GET /api/v1/review/queue takes 5.9–6.5 s on EVERY call, all day, for cli and web alike
  (answer/undo take 10–50 ms). Suspiciously constant ~6.0 s — look for a timeout, a sleep,
  a retry, an external call (objectstore/voicevox/dict), a full-table scan or per-card N+1.
- GET /api/v1/stats/summary ~6.2 s (same constant — likely shares the cost with the queue).
- stats/known-words, heatmap, calibration, retention: 14–76 s when the web dashboard fires them
  together → gateway 504 (~60 s). Probably connection-pool starvation / serialized heavy queries.
- Live queue response right now: items=0, counts={new:0,learning:3,review:0},
  more_available=true, to_materialize=0, next_learning_due_at=+2min. Check more_available=true
  with 0 items and nothing to materialize is correct; if not, fix it.

## Do
1. Find the root cause of the constant ~6 s (measure; add timing logs if needed). Fix it.
   Target: queue and summary < 300 ms on the user's data size (one user, ~tens of thousands of
   notes/cards, large dictionary tables).
2. Make stats endpoints fast enough not to 504 (indexes, aggregate in SQL, cache per study day,
   whatever fits), and ensure stats can never starve the pool used by review/queue + answer.
3. Audit the server review cycle end-to-end for robustness: queue build, answer (idempotent,
   out-of-order, stale card), undo, day rollover, keep going, waiting (items=0 + counts>0 must
   always carry next_learning_due_at). Fix real bugs; list the rest in HANDOFF.
4. Don't change API shapes. If a contract change is unavoidable, return CONTRACT_CHANGE_REQUEST.

## Acceptance
- [ ] Root cause named in HANDOFF with before/after timings (measured locally on a realistic dataset).
- [ ] Test: queue with only not-yet-due learning cards → items=0, counts.learning>0, next_learning_due_at set.
- [ ] Any query rewrite that could change results has a test proving identical output.
- [ ] make check MODULE=server green (and core/contracts if touched).

## Out of scope
Web, Android, CLI code (separate tickets).
