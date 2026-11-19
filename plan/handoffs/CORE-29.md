# HANDOFF CORE-29

STATUS: DONE
BRANCH: t/CORE-29 (from t/C-29-audio fffcf0c4)
CHECK: `make check MODULE=core` → green (jvm + js)

## Changed (≤ 6 lines)
- New `render/DefaultWordAudioResolver.kt`: resolve (note sound / speech text via `DefaultCardTextRenderer.fieldLines` kana) and effectiveVoice
- `DefaultCardRenderer`: block W, `{{audio:word}}`, built-in-only `{{wordaudio:back}}`, `RenderedCard.wordAudio` / `autoplayAudio`, `audio` referred at W's position
- `BuiltInTemplates`: vocab backs drop `{{audio}}`; W is the last child of `kb-expression` on the answer side; listening front = W
- `JsRenderMapping`: `wordAudio` in, `wordAudio` / `autoplayAudio` out
- Goldens: 4 vocab backs (plain + region) moved the audio span into `kb-expression`; region field order updated

## Acceptance → tests
- Resolver table (jvm+js) → `WordAudioResolverTest` (every row, plus effectiveVoice examples)
- Worked render examples, pre-ADR-047 output for 5 vocab kinds, listening back autoplay null, `{{audio:…}}` reserved → `WordAudioRenderTest`
- Goldens → `RenderGoldenTest`, `FieldRegionTest`, `CoreJsRenderTest`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- `RenderedCard.wordAudio` for a note-sourced W is the first button in W. If an upper-case `[sound:]` comes before a valid one, that hash differs from the resolver's `Note` hash (edge case; contract text followed literally).
- Server/Android parity goldens that embed vocab back HTML will need re-recording (SRV-29/AND-29).
