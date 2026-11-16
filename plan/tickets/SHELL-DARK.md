# SHELL-DARK — WebView shell ignores system light/dark mode

- Model: sonnet (writer) · Risk: low · Deps: SHELL-A4 · Gradle-heavy: yes
- Owned: android/src/main/res/values*/themes.xml, android/src/main/kotlin/kotobaya/android/shell/** (WebView settings only), android/src/test/**/shell/**
## User report (APK 0.1.30, 2026-11-17)
"it doesn't take light/dark mode". Cause (orchestrator): `Theme.Kotobaya` parent is `android:Theme.Material.Light.NoActionBar`; on targetSdk ≥ 33 the WebView derives `prefers-color-scheme` from the activity theme's `isLightTheme`, so the SPA always sees light. The SPA (web/src/app.html pre-paint + theme.svelte.ts) already follows prefers-color-scheme / its stored override.
## Do
- Make the shell activity theme day/night (e.g. parent `android:Theme.DeviceDefault.DayNight.NoActionBar` or a `values-night/themes.xml` override) so `isLightTheme` follows the system; window background matches each mode (no white flash in dark mode before the first paint).
- Keep algorithmic darkening OFF (the SPA has a real dark theme): `WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false)` where supported.
- Changing the system theme while the app is open must update the SPA (configChanges/recreate or the page's matchMedia listener; verify the WebView reports the new value).
- Do not break "Kotobaya (old)" (LegacyActivity/Compose) or Theme.Kotobaya.Sheet.
## Acceptance
- [ ] Robolectric test: with night mode on, the shell activity theme resolves `android:isLightTheme` = false (and true in day mode).
- [ ] make check MODULE=android green.
