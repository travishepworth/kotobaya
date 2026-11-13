# HANDOFF C-19

STATUS: DONE
BRANCH: t/C-19-render-field-regions
CHECK: `make check MODULE=core` → green (jvm + js) · `MODULE=contracts` → green · `MODULE=web` → green

## Changed (≤ 6 lines)
- Contract (`contracts/render/CardRenderer.kt`, `js/CoreJs.kt`): `RenderOptions.fieldRegions` (default false), `RenderedCard.fields`, `RenderedField`, `FieldSource`; `JsRenderInput.fieldRegions`, `JsRenderedField`.
- `DefaultCardRenderer`: wrapper span gains `data-field="<key>" data-field-source="note|dictionary|kanji"` when on.
- `web/src/lib/core/index.ts`: `options.fieldRegions?`, `RenderedCard.fields` typing.
- `contracts/core/README.md` (host usage for UI-WEB), `SUMMARY.md` (C-19 section).

## Acceptance → tests
- Goldens unchanged off / new on → `RenderGoldens.kt` untouched; `RenderRegionGoldens.kt`; `FieldRegionTest.everyBuiltInNoteTypeAndKindMatchesItsRegionGolden`, `regionGoldensAreThePlainGoldensPlusTheTwoAttributes`
- Hostile payloads, no forged region → `SanitizerTest` (all suites, option off and on, via `RegionChecker`); `FieldRegionTest.aFieldValueCannotForge…`, `aCustomTemplateCannotWriteARegionItself`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Sanitiser allowlist deliberately NOT changed (ticket said "keep these attributes"): regions are written after sanitising, so content can never carry them.
- Empty fields get no in-card region; `fields` lists them (`empty`, `rendered=false`, including hidden sections) for an "add" row. Mnemonic is not a region.
- `RenderedCard.fields` is non-empty for web callers only when they opt in; existing constructors compile unchanged.
