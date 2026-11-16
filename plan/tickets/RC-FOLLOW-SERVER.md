# RC-FOLLOW-SERVER — RC-SERVER review minors

- Phase: P10 · Model: sonnet (writer) · Risk: med
- Branch: t/RC-FOLLOW-SERVER · Owned paths: server/ (NOT SettingsApi/ReviewSettings/QueueLoader — REVIEW-UNLIMITED pending merge there) · Module: server

## Do (from plan/FOLLOWUPS.md, RC-SERVER review)
1. StatsModule.kt:69 — statsTx also gates /stats/summary, which home needs fast; give summary its own permit or exempt it, so heavy stats can't delay it.
2. ArrayBindTest.kt:715 guard regex: also catch `ANY (?` (whitespace), `all\(\s*\?`, case-insensitive; make it fail on current CoverageQuery `<> all(?::text[])` then fix those (CoverageQuery.kt:63,83-89,113-115) to `<> all(array(select unnest(?::text[])))`.
3. EntryExamples.kt:184, MediaApiModule.kt:310 — `unnest(?)` add explicit `::uuid[]` / `::bigint[]` casts.
4. ArrayBindTest.kt:758 timing test: drop the fixed 2 s wall-clock assertion (flake risk) or make it compare old vs new plans; the source-scan guard is the real protection.
5. Check the web/stats logs claim "retention/calibration fired up to 4x per page load" against live api logs from after web 0.1.23 (kubectl -n kotobaya logs deploy/kotobaya-api, read-only); report in HANDOFF whether it still happens (web fix would be a separate ticket).

## Acceptance
- [ ] make check MODULE=server green.
