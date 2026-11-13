# HANDOFF T503

STATUS: DONE
BRANCH: t/T503-drill
CHECK: `make check MODULE=core` → green; `make check MODULE=server` → green

## Changed (≤ 6 lines)
- core/drill/DrillSelector.kt: pure seeded selection for hot words, leeches, single word, confirmed confusable pairs (partners adjacent).
- server/drill/DrillSources.kt: loads candidates, hot notes (mirrors listHotWords), confirmed pairs.
- review/DrillApi.kt: hot_words and confusables no longer 501; leeches use the selector; seed = session id.

## Acceptance → tests
- Replay with interleaved drill/cram unchanged → DrillSelectorTest.interleavedDrillReviewsDoNotChangeTheSchedule
- Server: drill answer leaves card cache, logs type drill → existing ReviewApiTest.drillAndCramAnswersAreLoggedAndNeverChangeTheSchedule (T206)
- Selection per source incl. empty → DrillSelectorTest (core), DrillSessionTest (server)

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Hot scoring is duplicated from StruggleModule (its HotWords class is private, not my path); dedupe later.
- Request schema has no single-word source, so `DrillSelector.word` is core-only, unused by the server.
