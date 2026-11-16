# HANDOFF C-28+ONE-CARD-CORE

STATUS: DONE
BRANCH: t/C-28-ONE-CARD-CORE
CHECK: `make check MODULE=contracts` → green; `make check MODULE=core` → green

## Changed
- `contracts/cards/CardKinds.kt`: the rule `isRetired`, key, `readingEnabled(JsonValue?)`. Missing key = **false**, reversed by storing `true` (SCHEMA.md 6.4).
- `kindsForNewNote`/`missingKinds(…, readingEnabled = false)`; `QueueInput.readingEnabled = false` (builder rule 0); `WordCardState.kind`, `autoStatus(…, readingEnabled = false)`.
- Device apply: `settings.set` of the key re-derives all entries; `StartupReplay` re-derives once (`client.word_status_rules`).
- Docs: SCHEMA 6/6.4, OPS `settings.set`, openapi (createNote, createCard 422 `/kind`, queue), core README/SUMMARY, SPEC table. Scenario `reading-retired-offline-review`. Clients regenerated.

## Acceptance → tests
- Builder → `QueueRetiredCardsTest`. Generator `["reading"]` → `CardGeneratorTest`. Learning reading card → `WordStatusRulesTest`, `ReadingRetiredApplyTest`, `StartupReplayTest`.

## New dependencies
- none

## For the reviewer
- SRV must: pass the setting to QueueInput and every generator call; `kind` + setting in CoreAutoStatusHook/StatsQueries; re-derive on the key and once at startup; update 5 tests incl. MergeScenarioTest 16→17. `acceptance/ScenarioFilesTest:45` 16→17 (no owner).
- AND: pass the setting to LocalQueueLoader, NoteCreator, DeckEntryPlan; fix 9 failing tests (NoteCreator, Takoboto, LocalQueueLoader, …).
