# AND-WIDGET — Glance widget crash: Row container cannot have more than 10 elements

- Phase: P9 hotfix 0.1.11 · Model: sonnet · Risk: low
- Deps: none
- Branch: t/AND-WIDGET-row10
- Owned paths (only edit these): android/app/src/main/**/widget/** (KotobayaWidget.kt and its helpers), widget tests if any
- Module(s) for CI: android

## Evidence
Live phone 0.1.10 logcat: `GlanceAppWidget: IllegalArgumentException: Row container cannot have more than 10 elements` at KotobayaWidget.kt:113 (Progress composable emits 48 children in one Row).

## Do
Render the progress bar with ≤10 children per Row (e.g. a two-segment filled/unfilled bar using weights or a fixed-width Box pair, or a bitmap/ImageProvider). Same visual intent. Strings via i18n.

## Acceptance
- [ ] A unit test (or Glance test) that the Progress composable's Row has ≤10 children for any value/total incl. total 48+.
- [ ] make check MODULE=android green.

## Out of scope
todayReviews perf (AND-PERF).
