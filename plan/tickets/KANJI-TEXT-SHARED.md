# KANJI-TEXT-SHARED — server + android use core's field-text helper for KanjiRequirement expression

- Model: sonnet (writer) · Risk: med · Deps: CORE-28b, SRV-28, AND-28
- Owned: server/src/main/kotlin/kotobaya/review/KanjiNeeds.kt (+test), android KanjiRequirement input builder (+test)
## Do
Replace server KanjiNeeds.PlainText (and any android equivalent) with the public core helper from CORE-28b so WordForm.expression is identical on both (entities, <br>, Anki bracket furigana). Test: `食[た]べる`, `a&amp;b`, `<br>` produce identical expressions server/android and match JMdict form tags.
