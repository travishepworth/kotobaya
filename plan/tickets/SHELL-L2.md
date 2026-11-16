# SHELL-L2 — Local getReviewQueue

- Phase: SHELL · Model: opus · Risk: high
- Deps: SHELL-L1, SHELL-P2, SHELL-L9
- Branch: t/SHELL-L2-local-queue
- Owned paths (only edit these): android/src/main/kotlin/kotobaya/android/shell/api/review/Queue*.kt, android/src/main/kotlin/kotobaya/android/shell/api/review/Routes.kt, android/src/test/kotlin/kotobaya/android/shell/api/review/Queue*
- Module(s) for CI: android
- Gradle-heavy: yes · First phone test: yes

## Read
- Spec: Android app; Web app; Sync and offline
- Design: docs/ANDROID-SHELL.md (ADR-036) — sections 5, 6
- Contracts: openapi.yaml (getReviewQueue, ReviewQueue, QueueItem, GrammarCardSentence), contracts/core/README.md (C-27), contracts/sync/SYNC.md 8.1
- Decisions: ADR-031, 032, 033, 034, 035, 036

## Do
Stateless handler over `LocalQueueLoader.load(deckFilter, keepGoing)`; no new queue rule (the core builder decides everything).
- Parameters and validation exactly as the contract: `limit` 1..200 (default 20), `deck_id` live or 404, `kinds`, `keep_going` literal true/false.
- `counts`, `study_day`, `day_ends_at`, `next_learning_due_at` from the normal build even when `keep_going=true` (ADR-033);
  `more_available` per its five-part definition; `to_materialize` after materialization.
- C-27: drawn `PendingEntry`s are materialized through the existing `EntryMaterializer` (= `NoteCreator.materialize`, ops minted as the device),
  gated by `materializeAllowed`, at most 3 rounds, then rebuild. Not allowed → entries counted in `counts.new` and `to_materialize`.
- `QueueItem` view: card + note summary, dictionary entry (null when absent or dictionary not installed), chosen example sentence,
  `review_type`, per-rating preview, `grammar_sentence`; reuse `CardPayload`, `CardRows`, `BackExtras`, `InstalledRenderData`, core scheduler previews.
- `newShownThisSession` is always 0 for this handler; say so in a comment and cover `new_per_session` by the parity scenario.

## Acceptance (each bullet → at least one named test)
- [ ] Parity scenarios green: counts with/without keep_going, waiting state + next_learning_due_at, more_available under new_per_session, kinds filter, reviews_unlimited, reading retired/re-enabled, C-27 count, non-UTC day rollover
- [ ] `MaterializeIdempotentTest`: two consecutive and two concurrent fetches create one note per drawn entry; minted ops validate against op.schema.json
- [ ] `MaterializeConvergeTest`: device materializes offline, then pulls a server-created note for the same entry → one surviving note (SYNC.md 8.1 worked example)
- [ ] `QueueValidationTest`: limit 0/201, bad kinds, `keep_going=yes`, dead deck → the contract's status, code and pointer

## Out of scope
answer/undo (L3). Drill (L8).

## Notes from previous attempts

## Orchestrator note (2026-11-16)
Dictionary reads go through the SHELL-L9 lookup seam (installed → sidecar cache → proxy when online). Never return a null entry while a cached one exists.

## Orchestrator note (from SHELL-A2)
Also owned: delete your ops' lines from PENDING in the android RouteTableTest (shell/api tests). Routes are top-level vals in shell/api/<group>/Routes.kt; get deps via call.services.

## Orchestrator note (from SHELL-P2)
Register any new handler services in the test ParityDevice (android/src/test/.../shell/parity/) — also owned. Scenarios covering your ops must stop being pending and match their goldens.
