# SHELL-L8 — Local struggle and drill: hot words, hot kanji, leeches, drill session

- Phase: SHELL · Model: sonnet · Risk: med
- Deps: SHELL-L3
- Branch: t/SHELL-L8-local-struggle
- Owned paths (only edit these): android/src/main/kotlin/kotobaya/android/shell/api/struggle/**, android/src/test/kotlin/kotobaya/android/shell/api/struggle/**
- Module(s) for CI: android
- Gradle-heavy: yes · First phone test: no

## Read
- Spec: Android app; Web app; Sync and offline
- Design: docs/ANDROID-SHELL.md (ADR-036) — sections 5
- Contracts: openapi.yaml (listHotWords, listHotKanji, listLeeches, createDrillSession)
- Decisions: ADR-036

## Do
`StruggleRepository` + core `DefaultHotScorer`, `HotHistory`, `DrillSelector`. `createDrillSession` stores nothing and mints nothing; items use
the `QueueItem` view of L2 (import it, do not copy). Drill answers already work through L3 (`mode=drill`).

## Acceptance (each bullet → at least one named test)
- [ ] Parity scenarios for the three lists and a `hot_words` drill session
- [ ] `DrillNoOpsTest`: creating a session mints no op and changes no table

## Out of scope
Confusables (proxy).

## Notes from previous attempts
