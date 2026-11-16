# LOOP-C2 — Fix parity scenario queue-loop-cap-keep-going-backlog expectations

- Model: opus (architect) · Risk: low · Deps: LOOP-SRV, LOOP-AND · Gradle: yes (server + android parity runs)
- Owned: contracts/api/shell/parity/queue-loop-cap-keep-going-backlog.json, fixtures/shell-parity/golden/queue-loop-cap-keep-going-backlog.json, server parity test allowlist (EXPECT_DISAGREES), android parity equivalents
## Why
LOOP-SRV found the hand-derived `expect` orders review cards by answer order, but Easy-graduated seed cards get fuzzed intervals (6 vs 7 days) so dueAt order differs (server is correct: most overdue first by dueAt). Fix the scenario (seed explicit due dates or expect by dueAt), re-record, remove the EXPECT_DISAGREES allowlist entry on both server and android.
## Acceptance
- [ ] contracts, server, android checks green with no allowlist entry.
