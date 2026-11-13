# HANDOFF T404c

STATUS: BLOCKED
BRANCH: t/T404c-coverage-engine
CHECK: `make check MODULE=server` → not finished. I stopped it after about 17 minutes, when the orchestrator reported the host disk 100% full. The coverage tests alone (`:server:test --tests 'kotobaya.coverage.*'`) passed: 11/11.

## Changed (≤ 6 lines)
- `server/.../coverage/CoverageModule.kt`: handlers for `getMediaCoverage` and `getDeckCoverage`. Not wired into Main.kt; W-5 adds `CoverageModule(db).handlers`.
- `server/.../coverage/CoverageQuery.kt`: per-request SQL with one round trip per section (entries, segments, difficulty). Curve and percentages use half-up rounding to 2 decimals.
- `fixtures/coverage/`: two CC0 decks, numbers worked out by hand, README.
- Tests in `server/src/test/kotlin/kotobaya/coverage/`.

## Acceptance → tests
- Fixture agreement → CoverageFixtureAgreementTest (both tests)
- Hide filters → eachHideClassIsRemovedFromEveryNumber, anEmptyHideHidesNothing…
- Curve → theCurveIsMonotone…
- No stale cache → aWordStatusChangeIsReflectedInTheNextResponse
- 409/zeros/segment/404/410 → missingAndDeleted…, unparsedMediaIsAllZeros, aSegmentFilter…
- 10k entries: 46 ms (logged)

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- `pos_class` filters apply to each occurrence row. User/list decks ignore names/particles/auxiliary (no pos_class).
- The last curve point is 100% only with unmapped hidden and no learning entries (contract example counts learning as not known).
- `ignored_count` respects pos filters. Difficulty rank uses exact comparison; ties share a rank.

## If BLOCKED
QUESTION: The host disk is full. After it is pruned, can someone rerun `make check MODULE=server` (and rebase onto origin/main) before merging?
