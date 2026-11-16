# SHELL-Z1 — Delete the Compose UI

- Phase: SHELL · Model: sonnet · Risk: med
- Deps: SHELL-A4, SHELL-A5, SHELL-A7, SHELL-L4, SHELL-L5, SHELL-L6, SHELL-L7, SHELL-L8, SHELL-W2, SHELL-W3, SHELL-W4
- Branch: t/SHELL-Z1-delete-compose
- Owned paths (only edit these): android/** (deletions; build.gradle.kts; AndroidManifest.xml), gradle/libs.versions.toml, Makefile (tokens target: drop the Compose theme output), docs/SPEC.md (Clients section wording)
- Module(s) for CI: android
- Gradle-heavy: yes · First phone test: no

## Read
- Spec: Android app; Web app; Sync and offline
- Design: docs/ANDROID-SHELL.md (ADR-036) — sections 7
- Contracts: none
- Decisions: ADR-002 (amended by 036), ADR-036

## Do
Deletion only; no behaviour change. Changed-line budget applies to edits, not deletions; if the diff is unreviewable split into Z1a (screens,
ViewModels, nav, demo, legacy activity) and Z1b (ui/components, ui/theme, dependencies).
- Delete: every file importing `androidx.compose` except Glance widgets (`widget/**`), all `*/ui/` packages, `*ViewModel.kt`, `nav/`, `demo/`,
  `vm/`, `ui/`, `legacy/LegacyActivity` and its launcher entry, `review/ReviewSession.kt`, `ReviewViewModel.kt`, `ReviewBackPolicy.kt`, Compose tests and Roborazzi.
- Keep: everything referenced from `shell/`, `sync/`, `queue/`, `notes/`, `dict/`, `media/`, `search/` (non-UI), `ankiapi/`, `ankiconnect/`,
  `widget/`, `notify/`, `push/`, `pairing/` (non-UI), `intake/IntakeParser` + trampoline, `report/`.
- Dependencies: remove compose-ui, material3, navigation-compose, activity-compose, koin-compose, tooling, roborazzi; keep what Glance needs
  (compose runtime + compiler plugin). `Theme.Kotobaya.Sheet` removed.
- SPEC "Android app" paragraph: "WebView shell around the web app; native: sync, local API, widgets, notifications, integrations".

## Acceptance (each bullet → at least one named test)
- [ ] `grep -r androidx.compose android/src/main` matches only `widget/`
- [ ] `RouteTableTest` PENDING set is empty
- [ ] All remaining android tests green (`make check MODULE=android`); APK installs over the previous version and keeps pairing + data (HUMAN_CHECKS)
- [ ] Widgets, tile, AnkiProvider insert, AnkiConnect, due alarm still covered by their existing tests

## Out of scope
Any new feature. OTA.

## Notes from previous attempts

## Orchestrator note (user, 2026-11-17)
User approved deleting the old Compose UI; no separate phone-check gate. Still runs after the other SHELL deps are merged.
