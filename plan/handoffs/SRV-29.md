# HANDOFF SRV-29

STATUS: DONE
BRANCH: t/SRV-29
CHECK: `make check MODULE=server` → green

## Changed (≤ 6 lines)
- `server/.../tts/WordAudioStore.kt`: core resolver inputs, tts_audio lookup (deletes rows whose blob is gone), back-off, enqueue capped at 300 / 340 under an advisory lock
- `TtsModule.kt`: `resolveSpeech`; `synthesizeSpeech` default voice = core `effectiveVoice`
- `ReviewViews`/`ReviewApi`/`DrillApi`: `word_audio` on every item; urgent jobs for the first 20 missing pairs, in a separate tx after the page (a failure is logged, the request still succeeds)
- `WordAudioSweep.kt` (60 s, then every 600 s; started in `Main`); `SettingsApi`: the three C-29 keys
- `JobRepo.enqueue`: `run_after` bind now cast `?::timestamptz`. It was broken for any non-null `runAfter`.
- Parity goldens re-recorded: 31 files, `word_audio` added only. The goldens hold no HTML, so no back-HTML diff.

## Acceptance → tests
- states ready/pending/failed/none → `WordAudioApiTest.resolveSpeechAnswersReadyPendingFailedAndNone`
- scope, both shapes → `resolveSpeechNeedsWriteForEveryRequestShape`
- 340 limit → `at340QueuedJobsNothingIsEnqueuedOrReprioritised`, `nonUrgentStopsAt300AndUrgentAt340`
- sweep cap/window/order → `theSweepEnqueuesPairsOfCardsDueInTheWindowInDueOrderUpTo300`
- settings → `SettingsApiTest.wordAudioKeysAreValidatedAsTheirTypes`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- The back-off lookup scans `app.job` by `dedupe_key` across all statuses, and no index covers that. Adding one needs a contract migration.
- The sweep uses `notRetiredSql` for "retired". It does not filter buried cards: the spec does not mention burying.
