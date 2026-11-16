# SHELL-L6 — Local browse and search: listCards, listNotes

- Phase: SHELL · Model: sonnet · Risk: med
- Deps: SHELL-L1, SHELL-P2
- Branch: t/SHELL-L6-local-browse
- Owned paths (only edit these): android/src/main/kotlin/kotobaya/android/shell/api/browse/**, android/src/test/kotlin/kotobaya/android/shell/api/browse/**
- Module(s) for CI: android
- Gradle-heavy: yes · First phone test: no

## Read
- Spec: Android app; Web app; Sync and offline
- Design: docs/ANDROID-SHELL.md (ADR-036) — sections 5
- Contracts: openapi.yaml (listCards, listNotes, Card, Note), contracts/core/search.ebnf, search-examples.tsv
- Decisions: ADR-026, 035, 036

## Do
`listCards`: `LocalCardSearch` + core `SearchSqlCompiler` with `dictForms` from `InstalledDictionary`; parse errors → the contract's 422 with
positions; sort keys, `limit`, opaque cursor (keyset on the sort key + id; stable under concurrent writes). `listNotes`: `NoteIndex`.
Retired cards are listed unlabelled (ADR-035). A query needing data the phone lacks (documented list in SHELL.md) falls through to proxy.

## Acceptance (each bullet → at least one named test)
- [ ] `SearchExamplesTest`: every row of contracts/core/search-examples.tsv that is local-capable compiles and returns the same ids as the golden parity scenario
- [ ] `CursorTest`: paging over 250 cards returns each id exactly once, also when a card is inserted between pages
- [ ] Parse error → 422 with the contract's pointer and positions

## Out of scope
Bulk actions (L4).

## Notes from previous attempts

## Orchestrator note (from SHELL-A2)
Also owned: delete your ops' lines from PENDING in the android RouteTableTest (shell/api tests). Routes are top-level vals in shell/api/<group>/Routes.kt; get deps via call.services.

## Orchestrator note (from SHELL-P2)
Register any new handler services in the test ParityDevice (android/src/test/.../shell/parity/) — also owned. Scenarios covering your ops must stop being pending and match their goldens.
