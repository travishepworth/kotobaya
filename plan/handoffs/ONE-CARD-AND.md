# HANDOFF ONE-CARD-AND

STATUS: DONE
BRANCH: t/ONE-CARD-AND (from t/C-28-ONE-CARD-CORE)
CHECK: `make check MODULE=android` → green

## Changed
- `QueueSettings.readingEnabled` (query `schedulerSettings` also reads `cards.reading_enabled`); LocalQueueLoader passes `QueueInput.readingEnabled`.
- Retired cards excluded from `laterLearning` counts and the `nextDueAfter` / `forecastBuckets` SQL (else the waiting screen waits for a reading card that is never served).
- NoteCreator and DeckEntryPlan pass the setting (read from the replica `settings` row) to `kindsForNewNote`.
- Study settings: "Also create reading cards" toggle (i18n), mints `settings.set` JSON bool. Re-derive comes from core (applier + StartupReplay already wired).

## Acceptance → tests
- Queue/waiting/forecast → `LocalQueueLoaderTest.retiredReadingCardsAreNeitherServedNorWaitedFor`
- Creation → `NoteCreatorTest.readingCardsFollowTheSetting`, `DeckEntriesTest.readingCardsFollowTheSettingNotTheDeck`, `…AreMaterializedWhenTheSettingIsOn`
- Toggle → `StudySettingsModelTest.readingCardsToggle…`, `…ReadByTheBoolTest`
- 9 old failures updated to one card; SameWordTwoDevices and WordCardSuspension turn the setting on to keep their 2-kind coverage.

## New dependencies
- none

## For the reviewer
- Android has no add-card kind picker, so there is nothing to hide there.
- Word suspend still suspends retired cards (they are live cards).
