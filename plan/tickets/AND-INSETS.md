# AND-INSETS — Android: content draws under the status bar (edge-to-edge insets)

- Phase: P10 (0.1.10 APK if ready before release, else 0.1.11) · Model: sonnet · Risk: low
- Deps: none
- Branch: t/AND-INSETS-window-insets
- Owned paths: android/src/**/ui/** (screens, nav shell, components), android/src/**/MainActivity*.kt
- Module(s) for CI: android

## Do
User report: with edge-to-edge on, screen content overlaps the top status bar. Apply insets consistently on every screen: nav shell Scaffold `contentWindowInsets` (systemBars), top bars use `windowInsetsPadding(WindowInsets.statusBars)` (or TopAppBar defaults), the bottom bar/rail handles navigationBars, and IME padding on text-entry screens. Immersive Review keeps its own handling, but its content must not sit under the bars when they're shown. Don't double-pad (nested Scaffolds). Check KbSystemBars (UI-APP) for the existing intent.

## Acceptance
- [ ] Every top-level tab and its pushed detail screens start below the status bar (Roborazzi shots with a status-bar inset set, or a stated manual check).
- [ ] make check MODULE=android green.
