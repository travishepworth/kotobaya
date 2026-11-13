# HANDOFF F-10

STATUS: DONE
BRANCH: t/F-10-acceptance-tmpfs
CHECK: `make check MODULE=acceptance` → green

## Changed (≤ 6 lines)
- `acceptance/offline/src/test/kotlin/kotobaya/acceptance/offline/PgSimServer.kt`: `Pg.container` adds `.withTmpFs(mapOf("/var/lib/postgresql/data" to "rw"))`, same as F-9 `TestPg`. No other changes.

## Acceptance → tests
- Module compiles and Postgres-backed tests pass → NamedChaosTest (7), OfflineWeekChaosTest (1), SmokeTest (1); all 17 acceptance tests green, 0 failures.
- No new anonymous volume after run → `docker volume ls -q | wc -l`: before 47, after 47; `comm` diff shows no added or removed volumes.

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Verified volume leakage by diffing volume names before and after, not only the count, since other runs may be active on the host. Nothing was pruned.
- `make check` runs Gradle with `-q`, so per-test output is suppressed. Pass/fail counts come from `acceptance/build/test-results/test/*.xml`.
