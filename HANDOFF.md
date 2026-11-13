# HANDOFF UI-APP

STATUS: DONE (state-layer requests below)
BRANCH: t/UI-APP-rebuild
CHECK: `make check MODULE=android` → green; `make screenshots-app` → 660 PNGs, clean

## Changed (≤ 6 lines)
- `ui/components/`: buttons, rows, chips, segmented/tabs (reflow at 200 %), fields, states, ensō, stroke icons. Old `ui/Theme.kt`, POC home and Media tab/screen deleted.
- `nav/`: shell (bottom bar <600 dp, rail ≥600), 5 tabs, stored theme override (`Appearance`), Review immersive with close.
- Every screen rebuilt: Review first, then Today, Search+word (list–detail ≥840), Decks, Struggle, Settings (grouped, two-pane), sync, intake sheet.
- Outside ui/nav (glue): MainActivity/IntakeActivity theme, MediaBrowseFeature route dropped, DemoScreens, themes.xml, manifest (translucent intake).

## Acceptance → tests
- Nav/tabs/immersive Review → AppStartsTest, NoDbOnMainThreadTest; Pair→Devices → SettingsTabTest

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Full matrix: `android/screenshots/` (also `docs/design-screenshots/app/screens/`, 176 curated). Review card is a Compose stand-in (Robolectric doesn't draw WebViews).
- State-layer requests: ReviewMeta `remaining` + deck name (progress "12 of 85"), next-due time; HomeUiState overall due/new + 14-day activity; a Stats VM; WordDetail pitch/kanji readings; deck colour.
