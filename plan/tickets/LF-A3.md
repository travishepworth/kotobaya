# LF-A3 — Shell: local stats for Home (summary, heatmap, retention)

- Phase: P10 local-first · Model: opus · Risk: med
- Deps: C-31, LF-A2
- Branch: t/LF-A3-local-stats
- Owned paths (only edit these): android/src/main/kotlin/kotobaya/android/shell/api/stats/ (new group + Routes.kt registration line in LocalApi.kt `LocalRoutes`), android/src/main/sqldelight queries for stats, tests under android/src/test/kotlin/kotobaya/android/shell/api/stats/ and parity inputs
- Module(s) for CI: android

## Read
- Spec: Scheduling; Clients
- Contracts: openapi.yaml `getStatsSummary`, `getStatsHeatmap`, `getStatsRetention`; SHELL.md 8 (parity), L-3, L-9; server `kotobaya/stats/` is the oracle (read-only)
- Decisions: ADR-041, 049, 050
- Design: scratchpad `local-first-design.md` (orchestrator: copy to `docs/design/local-first.md` or paste the named section into this ticket).

## Do
Three read handlers over the replica (`review_log`, `card`, `note`, `word_status`, `settings`), same validation and bodies as the server (rule L-3): study-day bucketing by the user's time zone and rollover hour, undone reviews excluded, drill/cram handling as the server; `StatsSummary.due` and `more_available` come from the same `ReviewLoop.evaluate` the queue route uses (never counted separately). Each handler reads with `store.read` (no write lock).

## Acceptance (each bullet → at least one named test)
- [ ] Parity scenarios of C-31 for the three operations pass (shell vs server on the same seeded data), including a day-boundary case and an undone review.
- [ ] Test: after a local answer (not yet pushed) the summary's reviewed-today and the heatmap's today cell include it.
- [ ] Test: `StatsSummary.due` equals `getReviewQueue.loop` for the same instant.
- [ ] `make check MODULE=android` green.

## Out of scope
Forecast, known words, calibration (cached, LF-A2).

## Notes from previous attempts
