# UI-FOUND-APP — Android ViewModels + UiState, demo data switch, Roborazzi screenshot harness (B1, Android half)

- Phase: P9 · Model: opus (writer-senior) · Risk: med
- Deps: BUG-8, UI-FOUND-WEB (demo dataset in fixtures/demo/)
- Branch: t/UI-FOUND-APP-viewmodels-harness
- Owned paths: android/**, fixtures/demo/ (add Android-specific loaders only; don't change the shared format without noting it), Makefile (only a `screenshots-app` target)
- Module(s) for CI: android (flock /tmp/kotobaya-gradle.lock)

## Read
- Brief: plan/handoffs/INBOX-0.1.6.md › "Run 17 brief" › Track B › B1 and "UI rework notes"; BUG-8 handoff in plan/handoffs/BUG-8.md

## Do
**No visual changes. Behaviour stays identical.**
1. One ViewModel per screen exposing an immutable UiState (StateFlow) and intents. Composables take state + callbacks
   only: no Koin, no DB, no repositories inside composables. All IO on Dispatchers.IO (keep BUG-8's StrictMode guarantees).
2. Debug "demo data" switch that loads fixtures/demo/ into an isolated demo store; Compose previews per screen using it.
3. Roborazzi (preferred; Robolectric already used) or Paparazzi screenshots of every screen × phone/foldable/tablet ×
   light/dark × font scale 1.0/2.0 → android/screenshots/ (gitignored except README). `make screenshots-app`.
   Not part of make check. Note new dev deps in HANDOFF.

## Acceptance
- [ ] make check MODULE=android green
- [ ] Test/lint check that no @Composable in ui/ references Koin/DB/repositories (e.g. a source scan test)
- [ ] `make screenshots-app` produces the matrix (report count)

## Out of scope
Visual changes (UI-DS / UI-APP); sync engine (BUG-8, merged).
