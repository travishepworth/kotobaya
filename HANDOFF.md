# HANDOFF ONE-CARD-SRV

STATUS: DONE
BRANCH: t/ONE-CARD-SRV (from t/C-28-ONE-CARD-CORE)
CHECK: `make check MODULE=server` → green (490); `make check MODULE=acceptance` → green

## Changed
- `cards/ReadingSetting.kt` reads the `cards.reading_enabled` setting with the 6.1 bool test. The value goes to NoteApi, AnkiConnect, DeckEntryPlan, QueueLoader (`QueueInput`) and knownSeries.
- `createCard` with a retired kind → 422 `/kind`. `updateSettings` checks the key as bool.
- CoreAutoStatusHook passes `kind` and the setting. Applying `settings.set` of the key (won or lost) re-derives every entry.
- Startup re-derives every entry once, under the `server_state.word_status_rules` marker = core `WORD_STATUS_RULES_VERSION`.
- Tests: 7 old tests updated (default cards are now meaning only, or the setting is turned on); MergeScenarioTest and acceptance ScenarioFilesTest 16→17.

## Acceptance → tests
- Server/device convergence with a reviewed reading card, both orders and a losing op → AutoStatusHookTest `reviewedReadingCardConverges…` ×2, `aLosingSettingsSet…`. With `kind` removed, these fail.
- Startup marker → `startupRederivesEveryEntryOnce…`
- Queue, Today and materialization → DeckMaterializeTest `readingCardsAreCreatedAndServedOnly…`
- createNote/createCard → CardsApiTest `readingCardsAreRetired…`. Bool check → SettingsApiTest.

## New dependencies
- none

## For the reviewer
- knownSeries applies the setting's current value to past points in the series.
- LegacyStatsQueries (the test oracle) got the same `kind`/setting change.
