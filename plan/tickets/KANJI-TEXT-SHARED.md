# KANJI-TEXT-SHARED — server+android use core FieldPlainText; server goldens for C-28d scenarios

- Model: sonnet (writer) · Risk: med · Deps: CORE-28b (merged), AND-28 (merged, for the android half)
- Branch: t/KANJI-TEXT-SHARED (starts from t/AND-28-android attempt 2, d20fee49; AND-28 merges together with this ticket once android is green) · Owned: server/**, android/** (KanjiSource/expressionOf only + tests), fixtures/shell-parity/golden/**
- CI: server, android
## Do
1. Replace server KanjiNeeds.PlainText and android KanjiSource.expressionOf with core `render/FieldPlainText.of(value, PlainTextMode.KANJI)` so WordForm.expression is identical on both. Tests: `食[た]べる`, `a&amp;b`, `<br>`, `<ruby>…<rt>…</rt></ruby>` identical server/android and match JMdict form tags.
2. Server: record goldens for the new parity scenarios queue-kanji-shared-kanji, -keep-going-open-unit, -lead-deck-limit; re-record any golden whose order changed with CORE-28b (diff items/counts/loop vs main and explain each change in HANDOFF).
3. Android: parity tests green against the new goldens.
4. Raise core QueueMixUnitsTest.rebuildIsTail seeds only if runtime allows — NOT owned here, skip.
## Acceptance
- [ ] make check MODULE=server and MODULE=android green.
