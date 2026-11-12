# HANDOFF T304a

STATUS: DONE
BRANCH: t/T304a-queue-loader
CHECK: `make check MODULE=android` → green

## Changed (≤ 6 lines)
- `android/.../queue/`: `LocalQueueLoader` (load/counts/nextDueInstant/forecast24h/sessionPrefix, injected clock), `QueueSettings` (SCHEMA 6.1 int test, StudyDay with DST), `QueueModule` (Koin).
- OUTSIDE OWNED PATHS (flagged): `android/src/main/sqldelight/app/kotobaya/db/app/LocalQueue.sq` (my queries) and one line `kotobaya.android.queue.queueFeature,` in `di/FeatureModules.kt`.
- Rules mirror the server loader: non-live deck memberships dropped, refused cards not candidates, `n:` items follow `merged_into_id` (SYNC 8.5 directly; T303b helper not used), add_kanji exact strings only.

## Acceptance → tests
- Fixture queue equals core builder, refused absent but findable → `loaderQueueEqualsCoreBuilderAndFollowsTheLoaderRules`
- Never-forget/blacklist absent, return when cleared → `neverForgetAndBlacklistComeBackWhenTheStatusIsCleared`
- Day boundary Tokyo and DST → `countsAndForecastAcrossATokyoDayBoundary`, `studyDayAcrossDstChanges`, `loaderCountsUseTheDstDayOfTheUsersZone`
- Also: settings validity, add_kanji presence, today-limit zone.

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Parity test is against hand-built fixture + core builder, not a literal replay of the server's Postgres tests.
- Seams left empty: `SmartDeckResolver` (smart decks need the SQLite search evaluator) and `headwordOf` (dictionary headword when a note has no `expression`).
- Never-forget/blacklist cards are passed as candidates with their override (like the server); the builder drops them.
