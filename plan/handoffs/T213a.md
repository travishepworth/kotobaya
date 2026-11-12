# HANDOFF T213a

STATUS: DONE
BRANCH: t/T213a-card-renderer-r
CHECK: `make check MODULE=core` → green (jvm + js)

## Changed (≤ 6 lines)
- `core/.../render/`: `DefaultCardRenderer` (templates, fill-in, cloze, pitch, mnemonic, `frontIsEmpty` for T201b), `Sanitizer` + `HtmlTokenizer`, `Furigana`, `Pitch`, `BuiltInTemplates`, `JsRenderMapping`
- `CoreJsImpl.renderCard` now calls the renderer (it threw `NotImplementedError` before)
- `assets/card/card.css`: light/dark variables, Japanese font stack, styles every class in `CardClasses.ALL`

## Acceptance → tests
- Goldens (36 cases, jvm+js) → `RenderGoldenTest.everyBuiltInNoteTypeAndKindMatchesItsGolden`, `CoreJsRenderTest.renderCardMatchesTheGoldensForEveryBuiltInKind`
- Sanitiser → `SanitizerTest.hostile*` (55 payloads, fields + templates), `randomConcatenationsOfHostileFragmentsStaySafe`
- CSS drift → `CardCssDriftTest.everyCardClassIsStyledInCardCss`
- Empty fields, kanji fill-in, pitch, furigana → `CardRendererTest` (32 tests)

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- `CardCssDriftTest` lives in `core/src/jvmTest/.../render/` (outside the owned paths), because common tests cannot read files. Same pattern as `BuiltInNoteTypesDriftTest`.
- Values go through sentinels: placeholders become private-use markers before the template is sanitised, and the separately sanitised values are put back afterwards. Pitch is forced on for `pitch` cards. `{{pitch:}}`/`{{cloze:}}` work only in built-in templates. `frontIsEmpty` ignores the sentence/image extras.
- The cloze hint is `ClozeSentence.translation`. On a sentence note's root, `kb-sentence` clashes with the field class of the same name, so the CSS has an override.
