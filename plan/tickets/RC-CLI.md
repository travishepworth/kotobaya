# RC-CLI — Review cycle hardening: CLI daemon

- Phase: P10 · Model: sonnet (writer) · Risk: med
- Deps: none
- Branch: t/RC-CLI
- Owned paths: cli/
- Module(s) for CI: cli (run its tests: see Makefile / cli README)

## Read
- docs/DECISIONS.md ADR-033 (no learn-ahead, waiting state) and ADR-031 (keep going)

## Do (audit findings, RC-AUDIT 2026-11-16)
1. [major] daemon/server.py:263-266 — ANY queue-fetch failure (15 s read timeout, 5xx, parse error)
   sets conn="offline"; answer()/undo() then refuse ("offline", review.py:90) and nothing refetches
   for up to 60 s. Live server queue currently takes ~6 s. Fix: distinguish real offline from a
   failed/slow fetch; backoff refetch (e.g. 2s,5s,10s,30s) on failure; never block answers while
   POSTs can succeed (answers are journaled/idempotent).
2. [major] daemon/server.py:208-214, 293-297 — "refresh"/"keep_going" await refetches inside the single
   command worker (keep going: settings + up to 4 queue fetches) → reveal/answer stuck 24–75 s.
   Fix: run refetches as tasks; command worker returns immediately; state shows a loading flag.
3. [minor] session.py:379-386 — check_current swaps the current card ("reviewed elsewhere") whenever it's
   missing from the 20-item snapshot, even if merely pushed past the limit. Only swap when the server
   says it's no longer due/answered (or keep it if it was revealed).
4. [minor] server.py:314-317 — next_due_at in the past + still no items (clock skew) → refetch ~1 s
   loop. Add a floor/backoff.

## Acceptance
- [ ] Test: queue fetch timeout → state not offline-blocking; answer still posts; refetch scheduled.
- [ ] Test: keep_going command doesn't block a concurrent reveal/answer.
- [ ] CLI test suite green.
