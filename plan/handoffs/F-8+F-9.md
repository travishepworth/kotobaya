# HANDOFF F-8 + F-9

STATUS: DONE
BRANCH: t/F-8+F-9-build-hygiene
CHECK: `make check MODULE=worker` green (342 passed); `make check MODULE=server` green

## Changed
- server/build.gradle.kts: copy `V*__*.sql` (glob) instead of V1 only.
- server/.../MigrationsShippedTest.kt (new): every contracts/db/V*__*.sql is on classpath db/migration.
- server TestPg.kt: Postgres container gets tmpfs on /var/lib/postgresql/data (no anonymous volume). Ryuk was not disabled anywhere.
- worker/tests/dict/conftest.py: root cause = raw `docker run` + `docker rm -f` (no `-v`, not Testcontainers). Added `--tmpfs` and `rm -f -v`.

## Acceptance → tests
- migrations on classpath → MigrationsShippedTest
- Volumes: before 10; after 16 (+6, all with creation times 12:11-12:12 JST while other worktrees' postgres containers w/o tmpfs were running; mine use tmpfs, a probe confirmed 0 mounts). Count was falling (15) once others finished. Not caused by my runs; nothing pruned.

## New dependencies
none

## For the reviewer
- NOT done: acceptance/offline/.../PgSimServer.kt (Testcontainers Postgres) needs the same `.withTmpFs(mapOf("/var/lib/postgresql/data" to "rw"))`; it is outside my owned paths (ticket lists only acceptance/build.gradle.kts and resources). One-line follow-up.
