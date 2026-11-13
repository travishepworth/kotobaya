# HANDOFF UI-DS-APP

STATUS: DONE
BRANCH: t/UI-DS-APP-styleguide
CHECK: `make check MODULE=android` → green

## Changed (≤ 6 lines)
- Theme API in `ui/theme/`: `KotobayaTheme(mode: ThemeMode = System, sans, serif)` and `KbTheme.{colors,type,isDark,reducedMotion,space,shapes,…}`. Also `ThemeMode` (storageKey), `KbTextStyles` (Japanese roles set `ja`; `ruby()`), tone helpers `grade/state/deck/pitch/feedback`, `KbShapeSet`, `kbTween`/`kbDistance`.
- Old `ui/Theme.kt` is untouched, so the real screens still look the same. UI-APP switches them over.
- Debug styleguide in `src/debug/.../styleguide/`: 10 pages, opened from Settings › Styleguide (debug). There is an empty stub in `src/release`, plus one line in `FeatureModules.kt`.
- `StyleguideScreenshotTest` adds 120 PNGs to `make screenshots-app` under `styleguide/`. A curated set of 26 is in `docs/design-screenshots/app/`.

## Acceptance → tests
- Renders, light and dark → `StyleguideTest.everyPageRendersInLightAndDark`, `fullScreenRendersWithModeSwitch`
- Every token shown → `StyleguideTest.catalogueListsEvery*`; theme API → `ThemeApiTest`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Components are stock M3 specimens with icons drawn as text glyphs. At 200 % font, M3 segmented buttons and tabs clip; UI-APP's components must reflow.
- Stock M3 buttons default to pill shape, so pass `KbTheme.shapes.md` explicitly.
- `StyleguideTest` uses the default SDK sandbox: in sdk 34 it hung after the app-start tests (leaked main-looper work).
