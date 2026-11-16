# HANDOFF SHELL-DARK
Status: DONE. `make check MODULE=android` green.
- Cause fixed: `values-night/themes.xml` now overrides `Theme.Kotobaya` with parent `android:Theme.Material.NoActionBar` (dark), so `isLightTheme` follows system; window/status/nav colours already per-mode via kb_* resources (no white flash). Theme.Kotobaya.Sheet inherits; legacy untouched.
- ShellWebHost: `setAlgorithmicDarkeningAllowed(false)` when feature supported.
- Live switching: activity already declares `uiMode` in configChanges, so no recreate; the WebView updates prefers-color-scheme itself and the SPA's matchMedia listener applies. Not verified on device.
- Test: `ShellThemeTest` (night -> isLightTheme false, day -> true).
- No new deps. No contract change.
