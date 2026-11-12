# HANDOFF T003

STATUS: DONE
BRANCH: t/T003-db-schema
CHECK: `make check MODULE=contracts` → green (still the stub). Real check: `contracts/db/check.sh` → green.

## Changed (≤ 6 lines)
- `contracts/db/V1__init.sql`: Postgres 17 DDL, `ref.*` + `app.*`, comments, pg_trgm indexes, seeds.
- `contracts/db/sqldelight/{app,ref}/`: device schema (`AppDatabase`, `RefDatabase`).
- `contracts/db/SCHEMA.md`, `SUMMARY.md`: rules, table ↔ op ↔ API cross-check, decisions.
- `contracts/db/check.sh`, `test/smoke.sql`, `verify/`: verification.

## Acceptance → tests
- DDL applies to Postgres 17 → `check.sh` step 1 (`docker run postgres:17`, psql `ON_ERROR_STOP`, `test/smoke.sql`).
- SQLDelight compiles → `check.sh` step 3 (`./gradlew -p contracts/db/verify generateSqlDelightInterface`); step 2 loads into sqlite3 and diffs columns/seeds against Postgres.
- Every learning table ↔ op → SCHEMA.md §4.1 and §7.

## New dependencies
- `app.cash.sqldelight` Gradle plugin 2.0.2 — only in the standalone `contracts/db/verify` build, to compile-check `.sq` files.

## For the reviewer (≤ 3 lines)
- Makefile needs wiring: `check-contracts` should run `contracts/db/check.sh` (not my path).
- Migration aborts on `LC_CTYPE=C` databases (trigrams ignore Japanese); T009's CloudNativePG defaults to `C` and must set `C.UTF-8`.
- Deviations from spec, in SUMMARY.md: `grammar_point` in `app.*`; `review_type = manual`; `card.preset_id`. T004 must add endpoints listed in SCHEMA.md §7.
