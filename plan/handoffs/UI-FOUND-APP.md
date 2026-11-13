# HANDOFF UI-FOUND-APP

STATUS: DONE
BRANCH: t/UI-FOUND-APP-viewmodels-harness
CHECK: `make check MODULE=android` → green (NoDbOnMainThreadTest still passes)

## Changed (≤ 6 lines)
- One ViewModel per screen/settings section (`StateViewModel`: StateFlow UiState, IO on `io`). Each reads through a port interface. Screens moved to `**/ui/`, taking state plus an `XIntents`.
- Route glue (viewModel{} + Koin) lives in `*Feature.kt` and `nav/`. Settings sections and the Home chip each get their own ViewModel.
- Demo: `demo/` loads fixtures/demo (debug-only assets), in-memory DemoStore, Koin overrides via debug Settings "Demo data" switch (restarts app); `DemoScreens` + `DemoPreviews`.
- BUG-8 notes: dict DB guarded by DbThreadGuard; SyncStatusRepository publishes with `update{}`; unpair awaited; the read{} snapshot caveat is documented on SyncFlowViewModel. Intake no longer reads decks in composition.
- `make screenshots-app` (not part of `make check`) → 516 PNGs, gitignored.

## Acceptance → tests
- No Koin/DB/repositories in ui/ → UiLayerPurityTest (both rules failed before)
- Demo data, nothing dropped → DemoDataTest; ViewModel behaviour/BUG-8 fixes → ViewModelBehaviourTest
- Screenshot matrix: 516 = (34 screens + 9 empty-scenario) × 12

## New dependencies
- roborazzi + roborazzi-compose 1.40.1 (test): screenshot harness
- ui-tooling-preview / ui-tooling (BOM): @Preview
- lifecycle-viewmodel-compose 2.8.7 (already transitive via Koin): ViewModels

## For the reviewer (≤ 3 lines)
- Small behaviour differences: settings prefs and the review session now load on IO (defaults can show for a frame); a grammar-capture panel keeps its state while scrolled off screen.
- The screenshots don't show the review card (Robolectric doesn't draw the WebView). Dark = light until UI-DS ships the dark theme. Due900 is not rendered; only default and empty are.
- `buildConfig = true` enabled (BuildConfig.DEBUG gates the demo section).
