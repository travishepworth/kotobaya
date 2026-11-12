# HANDOFF T108b (attempt 2)

STATUS: DONE
BRANCH: t/T108b-search-sql (rebased on main)
CHECK: `make check MODULE=core` → green (jvm + js); `make check MODULE=web` → green

## Changed
- Execution test `core/src/jvmTest/.../search/sql/SearchSqlExecutionTest.kt`: seeded fixture (19 notes, 26 cards: tags, fields incl. escaped quote, kanji, vocab-expression kanji, 5 decks incl. smart/deleted/merge-loser item, word statuses, reviews incl. undone/manual/merged-card, leech, hot note, merged loser note+cards, deleted note/card, 2 presets). Loaded into SQLite from `contracts/db/sqldelight/app` and Postgres 17 (C.UTF-8) from `V1__init.sql`; 80 queries (every term type, Not/Or/And over residual terms) each asserted against hand-written card sets in both dialects (SQLite after `accepts`), hence equal to each other. Postgres test prints SKIPPED and returns if Docker is unavailable (it ran here).
- `SearchContext.dayStart: (offset) -> InstantMs` (caller computes tz-aware study-day starts; default = 24 h days). `due`/`rated`/`added` use it. Test `dayBoundariesComeFromTheCallerAcrossDst` (fake zone, 23 h and 25 h days).
- Postgres uuid params now `CAST(? AS uuid)` (found by thinking about JDBC text binding); snapshots updated.

## Acceptance → tests
- examples snapshot → `everyPlusExampleCompilesInBothDialectsAndMatchesSnapshot`; execution → `sqliteSelectsTheExpectedCards`, `postgresSelectsTheExpectedCards`; injection → `injectionShapedInputNeverReachesSqlText`; DST → above.

## New dependencies (jvmTest only, `core/build.gradle.kts`)
- org.xerial:sqlite-jdbc:3.41.2.2 (real SQLite for device SQL), org.postgresql:postgresql:42.7.4 (JDBC driver), org.testcontainers:postgresql:1.21.4 (Postgres 17 container). All already in the Gradle cache.

## For the reviewer / contract note
- Contract addition to decide on (package `kotobaya.search.sql`): `SearchSqlCompiler.compile(node, Dialect, SearchContext): CompiledSearch`; `SearchContext(studyDay, nowMs, leechThreshold, hotNoteIds, smartDecks, dayStart)`; `CompiledSearch.select(): SqlFragment(sql, params)` (`?` placeholders; params are String/Long/Double only), `.residual`, `.accepts(ResidualRow)`. Statement: `FROM card c JOIN note n` (Postgres `app.` prefix). On SQLite with `residual`, the caller must select via `select()` and keep only rows where `accepts` is true; `LIMIT` is only valid when `!residual`. Bulk actions must apply `accepts` before acting.
- SQLite prefilter assumes canonical JSON in `fields`/`tags` (raw UTF-8); chunks with `"`, `\`, control chars are not prefiltered.
