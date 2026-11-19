# HANDOFF CORE-28c

STATUS: DONE
BRANCH: t/CORE-28c (on top of C-28e d1529c15)
CHECK: `make check MODULE=core` green (jvm 659/0 fail, js 645/0 fail); `server` green (549/0 fail); `android` green (605, 2 skipped, 0 fail)

## Changed (≤ 6 lines)
- `core/.../cards/DefaultKanjiRequirement.kt`: learnable kanji ignore word-level tags; `frontFurigana = wordExempt || learnable.isEmpty()`.
- `KanjiRequirementTest`: every KDoc triple (on/off) + `taggedAndUntaggedWordsRequireTheSameKanji`; `QueueParityScenarioTest.queueKanjiExemptions` mirrors the new step 0 (寿 司 寿司 …, 9).
- `fixtures/shell-parity/golden/queue-kanji-exemptions.json` re-recorded (`RECORD=1`, parity tests); no other golden changed.
- Server/android: no code change; they only pass through core's result (`KanjiNeeds`, `LocalQueueLoader`, `QueueViews`).

## Acceptance → tests
- ADR-045 worked examples → `KanjiRequirementTest.workedExamples`
- Parity scenario → `QueueParityScenarioTest.queueKanjiExemptions`, server `ParityGoldenTest`, android parity tests

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- `FuriganaFrontTest` needed no change (takes `frontFurigana` as input).

# C-28e (contract handoff, kept)
- `KanjiRequirement.kt` KDoc: learnable kanji regardless of tags; furigana = tagged OR none learnable. `QueueBuilder` KDoc, SCHEMA 6/6.5, core README, openapi, SHELL.md, REVIEW-LOOP.md, clients regenerated.
- `queue-kanji-exemptions.json` step 0: 9 items, until_stop 9; step 2 unchanged.
- Names `wordExempt`/`EXEMPT_FORM_TAGS` kept (read as "word-level tagged"); 老, 林, 檎, 今 grades in KDoc from memory.
