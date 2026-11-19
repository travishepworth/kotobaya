# HANDOFF KANJI-TEXT-SHARED

STATUS: DONE
BRANCH: t/KANJI-TEXT-SHARED
CHECK: `make check MODULE=server` and `make check MODULE=android` -> both green (exit 0)

## Changed
- Server: removed `PlainText`; `KanjiNeeds` uses core `FieldPlainText.of` (expression: KANJI, reading: KANA); `QueueExtras` keyword text uses KANJI mode.
- Android: `LocalQueueLoader.expressionOf` uses `FieldPlainText.of(.., KANJI)` for field and headword fallback; dropped `HTML_TAG`.
- Recorded 3 new server goldens: queue-kanji-shared-kanji, queue-kanji-keep-going-open-unit, queue-kanji-lead-deck-limit.
- No existing golden changed after CORE-28b (RECORD=1 run produced no diffs in tracked files).

## Not done
- No new server/android tests for `食[た]べる`, `a&amp;b`, `<br>`, ruby: the behaviour is the core function itself (covered by CORE-28b goldens); parity goldens pass on both sides.

## New dependencies
- none
