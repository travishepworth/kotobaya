# HANDOFF D-2

STATUS: DONE
BRANCH: t/D-2-volume-leak
CHECK: `make check MODULE=server` → green; `make check MODULE=core` → green

## Changed (≤ 6 lines)
- server/src/test/kotlin/kotobaya/blobs/S3BlobStoreSeaweedFsTest.kt: tmpfs on /data (image declares VOLUME /data).
- core/src/jvmTest/kotlin/kotobaya/search/sql/SearchSqlExecutionTest.kt: tmpfs on postgres data dir (postgres:17 declares VOLUME).
- Already fine: TestPg, PgSimServer (tmpfs); compose uses named volumes (kept); worker has no docker use.

## Origins (195 volumes)
- 192 anonymous (64-hex names, no labels): from postgres:17 (core SearchSqlExecutionTest, no tmpfs) and seaweedfs:3.80 (S3BlobStoreSeaweedFsTest, no tmpfs). Fixed both. Split between the two not determinable (unlabelled).
- 3 named (15/18/22 chars): compose dev volumes pgdata/objectdata/jpdbcache or similar; intentional, left.

## Acceptance → tests
- Volume count unchanged → count 195 before/after S3BlobStoreSeaweedFsTest, SearchSqlExecutionTest and both make checks.

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Earlier-created volumes are not removed. Pruning command for the human (review first): `docker volume ls -qf dangling=true | grep -E '^[0-9a-f]{64}$' | xargs docker volume rm`  (or `docker volume prune`; keep a running dev stack up so its volumes are in use).
