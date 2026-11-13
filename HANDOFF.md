# HANDOFF T305c

STATUS: DONE
BRANCH: t/T305c-takoboto-store
CHECK: `make check MODULE=android` → green

## Changed (≤ 6 lines)
- `ankiapi/takoboto/`: TakobotoVocab (field table, fixed name-derived id), NoteTypeEnsurer (one `note_type.created`), Koin feature, debug Settings section.
- `ankiapi/store/`: TakobotoAnkiStore (op-backed, via NoteCreator/DuplicateCheck), AnkiIdMap (`ankiapi.db`: `ankiapi_note_map`, decks, client models, counters), DeferredAnkiStore, EntryLookup.
- Out of path: `notes/DuplicateCheck.kt` (the carried-in fix), `app/KotobayaApp.kt` (eager `AnkiStoreHolder.set`), `provider/AnkiProvider.kt` (`@VisibleForTesting attachStore`), `AnkiStoreHolder` (wait is now 2 s), `di/FeatureModules.kt` (+1 line), new `res/values/strings_ankiapi.xml`.

## Acceptance → tests
- S01 replay → TakobotoReplayTest (fixture `takoboto/add_words.json`)
- Duplicate hit/miss, the same word added twice → TakobotoAnkiStoreTest.duplicateQueryMissThenHit…, the replay
- Offline, then synced → TakobotoReplayTest (sync at the end)
- Note type idempotent → NoteTypeEnsurerTest (restarts, two devices)
- T305a/T305b carry-ins → AppStartStoreTest, DuplicateCheckExpressionTest

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Pre-existing bug, not mine to fix: if a SqlDelightDeviceStore transaction is the sync DB's first use, the learning tables stay missing for the whole process. A Takoboto cold start would hit this. My workaround: a plain query when the store is built. The sync owner should fix this at the source.
- The Takoboto field names are seeded (Expression/Kana/Meaning/Sentence/SentenceMeaning). The inbox appears only when the user has no decks. Update, delete and moving cards return false.
