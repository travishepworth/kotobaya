# HANDOFF T108b

STATUS: DONE
BRANCH: t/T108b-search-sql
CHECK: `make check MODULE=core` → green (jvm + js); `make check MODULE=web` → green (unaffected)

## Changed
- `core/.../search/sql/SearchSqlCompiler.kt`: public `Dialect`, `SearchContext` (StudyDay, now, leech threshold, hot ids, smart decks), `SearchSqlCompiler.compile` -> `CompiledSearch` (`where`, `select()`, `probes`, `residual`, `accepts(ResidualRow)`). Bind params only; `?` placeholders; ints bound as Long.
- `SqlText.kt`, `MiniJson.kt`: ASCII-only folding, LIKE escaping, residual pattern matcher, tolerant JSON reader.
- Postgres decides every term (`search_text` ILIKE + ASCII-fold LIKE, `jsonb ->>`, `unnest(tags)`). SQLite: prefilter + residual (tag, field, text, kanji, is:hot); Or/Not over residual use "probe" columns (`select()` adds `p0..`), `not` over residual => TRUE prefilter.
- Tests in `commonTest/.../search/sql/`: snapshots of all `+` examples x 2 dialects (`SqlSnapshots.kt`, generated), injection, residual, merge/live filters, day math.

## Acceptance → tests
- `+` examples compile, snapshot → `everyPlusExampleCompilesInBothDialectsAndMatchesSnapshot`
- Execution test → NOT done: no JVM SQLite driver in the build (no new dependency added); snapshot and in-memory residual tests only.
- Injection → `injectionShapedInputNeverReachesSqlText`
- core check green.

## New dependencies
- none

## For the reviewer
- Contract addition for the architect: `CompiledSearch`/`SearchSqlCompiler` API above (no SQL interface was frozen). SQLite statement uses aliases `card c`, `note n`; Postgres uses `app.` prefix.
- `due<op>n` is rewritten to ranges on `due_at` with days = 24 h (StudyDay has no zone; DST days off by an hour). `rated:N`/`added:N` likewise. Smart-deck membership needs `SearchContext.smartDecks` (caller supplies live smart decks). The SQLite text/tag/field prefilter assumes canonical JSON (raw UTF-8, only `"`/`\`/controls escaped); chunks containing those are not prefiltered.
