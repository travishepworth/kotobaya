# HANDOFF ARCH-CARD-TEXT (attempt 2)

STATUS: DONE
BRANCH: t/ARCH-CARD-TEXT
CHECK: `make check MODULE=contracts` → green · `core` → green · `server` → green

## Changed (≤ 6 lines)
- Contract `core/.../contracts/render/CardTextRenderer.kt` + `DefaultCardTextRenderer.kt`: vocab/pitch front = expression + reading[kana], back = pitch line only (no "else reading"); out-of-range drops skipped, patterns deduped as strings
- De-duplication only in the custom-template fallback; cloze back no longer lists the translation (it is the front hint), spot back still does
- `openapi.yaml` CardText: same table; "context sentence the built-in front shows"; HTML-mode fields keep bracket furigana as typed (as the HTML `{{key}}`); future fields optional; don't print `reading` plus all of `back`
- Server `QueueText`: pitch only from the headword_reading row; unknown note type → item without `text` (no 500)

## Acceptance → tests
- Goldens, every kind (jvm=js) → `TextRenderGoldenTest` (pitch goldens, `tableRulesDoNotDeDuplicate`, pitch range)
- Server, each kind + Takoboto note → `QueueTextTest` (+ `pitchComesOnlyFromTheHeadwordReadingsRow`)

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Bracket furigana kept as typed in HTML-mode fields (matches the HTML render) rather than stripped.
- FOLLOWUPS (not done, as instructed): sentence_translation; core DictionaryData.fromEntry; contracts/core README entry (C-25).
