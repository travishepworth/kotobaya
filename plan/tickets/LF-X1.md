# LF-X1 — Acceptance: black-hole network run of the shell API

- Phase: P10 local-first · Model: sonnet · Risk: med
- Deps: LF-A1, LF-A2, LF-A3, LF-A4, LF-A5, LF-A6, LF-A8, LF-A8b, LOOP-31-AND
- Branch: t/LF-X1-blackhole
- Owned paths (only edit these): acceptance/
- Module(s) for CI: android (acceptance target)

## Read
- Spec: Sync and offline (offline guarantees)
- Contracts: SYNC.md 11, 12, 13; SHELL.md 8, L-9
- Decisions: ADR-050, 051
- Design: scratchpad `local-first-design.md` (orchestrator: copy to `docs/design/local-first.md` or paste the named section into this ticket).

## Do
Extend the offline/sync simulation: a transport that (a) never answers, (b) answers after 8 s, (c) drops every second request. Script a day: open Home, review 60 cards incl. new cards from deck entries with Keep going, search, open dictionary entries, edit a note, bulk-tag — against the shell `LocalApi` with a virtual clock. Then heal the network and sync two devices + server.

## Acceptance (each bullet → at least one named test)
- [ ] No scripted UI-path request takes longer than its local execution (virtual clock: zero network wait) in modes a–c.
- [ ] After healing: convergence (SYNC.md 11) across both devices and the server; every answer present exactly once; materialized duplicates merged to one live note per entry.
- [ ] The loop invariants I1–I4 hold on every queue response of the run (reuses the core checker).
- [ ] `make check MODULE=android` green (acceptance target).

## Out of scope
Real-device measurement (HUMAN_CHECKS, orchestrator).

## Notes from previous attempts

## Orchestrator notes
- Remove every entry from UiPathNeverTouchesNetworkTest.STILL_FORWARDS whose route has landed (stats x3 from LF-A3, etc.); the strict pass must leave it empty or justified.
- Drop the 9 card/preset/note-type ops (LF-A8) from STILL_FORWARDS too.
