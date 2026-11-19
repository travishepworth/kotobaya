# HANDOFF KANJI-TEXT-SHARED

STATUS: DONE
BRANCH: t/KANJI-TEXT-SHARED
CHECK: `make check MODULE=server` exit 0 (JUnit XML: 549 tests, 0 failures, 0 errors, 0 skipped); `make check MODULE=android` exit 0 (JUnit XML: 605 tests, 0 failures, 0 errors, 2 skipped). Gradle -q prints no summary line; counts summed from build/test-results.

## Changed
- Server: removed `PlainText`; `KanjiNeeds` uses core `FieldPlainText.of` (expression: KANJI, reading: KANA); `QueueExtras` keyword text uses KANJI mode.
- Android: `LocalQueueLoader.expressionOf` uses `FieldPlainText.of(.., KANJI)` for field and headword fallback; dropped `HTML_TAG`.
- Recorded 3 new server goldens: queue-kanji-shared-kanji, queue-kanji-keep-going-open-unit, queue-kanji-lead-deck-limit.
- No existing golden changed after CORE-28b (RECORD=1 run produced no diffs in tracked files).

## Attempt 2
- New `WordTextParityTest` on both sides with identical literals: `食[た]べる` -> expr `食べる` / reading `たべる`; `a&amp;b` -> `a&b`; `x<br>y` -> `x\ny`; `<ruby>漢<rt>かん</rt></ruby>字` -> `漢字` (server via `KanjiNeeds.resolve`, android via new `WordText`, used by `LocalQueueLoader.expressionOf` and `QueueViews.wordTexts`).
- Android reading now `FieldPlainText` KANA (fallback: entry reading), same as server; android keyword text also uses `FieldPlainText`.

## New dependencies
- none
