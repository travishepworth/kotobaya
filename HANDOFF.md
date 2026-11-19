# HANDOFF CORE-28b

STATUS: DONE
BRANCH: t/CORE-28b-core
CHECK: `make check MODULE=core` → green (jvm + js, 1302 tests)

## Changed (≤ 6 lines)
- `DefaultQueueBuilder`: C-28d turn order (claims, (a) first claimer in source order, (c) earliest claimer), lead ends at the deck limit, keep-going share step waits while a unit is open
- Perf: `backfillMintSize` map built once per build (was a scan of introducedWords per part)
- New public `render/FieldPlainText.of(value, PlainTextMode.KANJI|KANA)`; the text renderer's `fieldLines` now uses it. Not exported to JS (the web does not need it)
- Tests: `QueueRandom.rebuildExcluded` and `newWordKanji` deleted; the rule (a) test now expects source order

## Acceptance → tests
- 3 parity mirrors → `QueueParityScenarioTest` (sharedKanji, keepGoingOpenUnit, leadDeckLimit)
- property, no exclusions, seeds 506/1471 → `QueueMixUnitsTest.rebuildIsTail`, `QueueBackfillShareTest.answeringTheHeadAndRebuildingYieldsTheTail` (also ran 6000 seeds per mode locally: 0 failures)
- README C-28d items → `ruleAPicksTheFirstClaimer…`, `kanjiThatTwoSourcesNeed`, `ruleC…`, `aSourceAtItsLimitOrStoppedByALeadClaimsNothing`, `aLeadEndsAtTheDecksLimit`, `untilStopNeverDropsByMoreThanOne…`, `keepGoingWithAnOpenUnit`, `aShareStepEnds…`
- escaping → `FuriganaFrontTest.markupAndEntitiesInExpressionAndReadingAreEscaped`, `FieldPlainTextTest`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- In the item-order output, rule (c) for an own-turn kanji card looks the same as without it. The test checks the build and the rebuild instead.
- Follow-up: server `PlainText` and android need to switch to `FieldPlainText.of`.
