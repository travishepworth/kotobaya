# HANDOFF T501

STATUS: DONE
BRANCH: t/T501-struggle
CHECK: `make check MODULE=core` green; `make check MODULE=server` green

## Changed
- core/struggle: `DefaultHotScorer` (implements contracts `HotScorer`), `HotHistory` (replay -> HotReview with stateBefore), `Leeches` (default threshold 8).
- server/struggle: `StruggleModule` (`listHotWords`, `listLeeches`); registered in Main.kt; both ops removed from the WiringAuditTest allowlist.

## Acceptance -> tests
- Scoring factors, decay, window -> core `DefaultHotScorerTest` (eachFactorMovesTheScoreTheRightWay, olderFailuresWeighLessAndLeaveTheWindow, contractExample)
- Drops off after recovery -> core `recoveredWordDropsOff`; server `recentFailuresAppearAndRecoveryDropsTheWordOff`
- Hot kanji aggregation -> core `hotKanjiNeedsTwoWordsSharingIt` (scoring only; no listHotKanji, out of scope)
- Server shape/limit/scope -> `StruggleApiTest` (4 tests, incl. 401/403)

## New dependencies
none

## For the reviewer
- Leech threshold comes from `scheduler.leech_threshold` (ReviewSettings); leeches query is direct SQL (live card+note, lapses desc, id), not the search compiler. No tagging op minted.
- listHotWords lists only `hot` notes (recovered ones excluded although score > 0). Replays full history of cards that failed in 14d to get stateBefore; cost scales with those cards only.
