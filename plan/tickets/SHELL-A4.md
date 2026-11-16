# SHELL-A4 — Native navigation: deep links, intake trampoline, widget/tile/notifications, back, haptics, theme

- Phase: SHELL · Model: sonnet · Risk: med
- Deps: SHELL-A3
- Branch: t/SHELL-A4-native-nav
- Owned paths (only edit these): android/src/main/kotlin/kotobaya/android/shell/nav/**, android/src/main/kotlin/kotobaya/android/MainActivity.kt, android/src/main/kotlin/kotobaya/android/intake/IntakeActivity.kt, android/src/main/kotlin/kotobaya/android/widget/WidgetIntents.kt, android/src/main/kotlin/kotobaya/android/notify/DueNotifications.kt, android/src/main/kotlin/kotobaya/android/push/PushHandler.kt, android/src/main/AndroidManifest.xml, android/src/test/kotlin/kotobaya/android/shell/nav/**
- Module(s) for CI: android
- Gradle-heavy: yes · First phone test: no

## Read
- Spec: Android app; Web app; Sync and offline
- Design: docs/ANDROID-SHELL.md (ADR-036) — sections 4
- Contracts: contracts/api/shell/SHELL.md (navigation, back)
- Decisions: ADR-030, ADR-036

## Do
- `ShellLinks.pathFor(intent)`: the table of design section 4 (kotobaya://review, word/<seq>, struggle, add, pair; SEND / PROCESS_TEXT through
  the existing `IntakeParser`). Output always passes the SHELL.md path regex, else `/`. Query values are percent-encoded.
- `IntakeActivity` becomes a no-UI trampoline (theme NoDisplay): parse → start `MainActivity` with the path → finish. Its Compose sheet is no longer referenced.
- Delivery: `ready` seen → `navigate` message; else initial URL. `onNewIntent` handled the same way.
- Back: `OnBackPressedCallback` enabled from `back.state`; sends `back{id}`, waits 300 ms for `back.result`, falls back to `goBack()` / `moveTaskToBack(true)`.
- `haptic` → `performHapticFeedback` (tick=CLOCK_TICK, confirm=CONFIRM, reject=REJECT with pre-30 fallbacks); `theme` → status/navigation bar colours and light/dark icons.
- Notification and widget PendingIntents keep targeting `MainActivity` with the same URIs.

## Acceptance (each bullet → at least one named test)
- [ ] `ShellLinksTest`: one case per row of the design table, plus hostile inputs (`//evil`, `javascript:`, newline, 10 kB text truncated to 2 000 chars) → safe path
- [ ] `BackProtocolTest`: handled=true consumes; handled=false leaves; no answer in 300 ms uses the fallback; callback disabled when `can_handle=false`
- [ ] `IntakeTrampolineTest` (Robolectric): SEND text/plain starts MainActivity with `/search?q=…` and finishes
- [ ] `make check MODULE=android` green

## Out of scope
SPA handling of navigate/back (W3). Removing IntakeSheet/ViewModel files (Z1).

## Notes from previous attempts
