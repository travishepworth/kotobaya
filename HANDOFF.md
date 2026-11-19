# HANDOFF SRV-29

STATUS: DONE
BRANCH: t/SRV-29
CHECK: `make check MODULE=server` → green

## Changed (≤ 6 lines)
- `server/.../tts/WordAudioStore.kt`: resolver inputs, tts_audio lookup, back-off, enqueue capped at 300 / 340 under an advisory lock
- `TtsModule.kt`: `resolveSpeech`; `synthesizeSpeech` default voice via core; `WordAudioSweep.kt` started in `Main`; `SettingsApi`: the 3 keys
- `ReviewViews`/`ReviewApi`/`DrillApi`: `word_audio` on every item. The served page queues urgent jobs and never waits for the lock (`pg_try_advisory_xact_lock`; if the lock is busy it skips)
- Attempt 2: the back-off lookup reads only jobs created in the last day (`job_created_idx`). Inside the lock it reads only queued/running jobs. The orphan delete matches the blob hash it read. `enqueue` returns how many jobs it created.
- `JobRepo.enqueue`: `run_after` bind cast `?::timestamptz` (it was broken whenever `runAfter` was non-null)
- 31 parity goldens re-recorded; the only change is the added `word_audio`

## Acceptance → tests
- states → `WordAudioApiTest.resolveSpeechAnswersReadyPendingFailedAndNone`
- scope, both shapes → `resolveSpeechNeedsWriteForEveryRequestShape`
- 300/340 limits → `at340QueuedJobsNothingIsEnqueuedOrReprioritised`, `nonUrgentStopsAt300AndUrgentAt340`
- sweep → `theSweepEnqueuesPairsOfCardsDueInTheWindowInDueOrderUpTo300`
- back-off window, lock → `backOffHoldsForJobsInsideTheLookupWindowOnQueueAndResolve`, `aServedPageNeverWaitsForTheEnqueueLock`
- settings → `SettingsApiTest.wordAudioKeysAreValidatedAsTheirTypes`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Back-off gap: if a job was created more than 1 day ago and failed within the last 600 s, it is not seen as backing off. That needs a retry chain longer than a day.
- The sweep does not filter buried cards; the spec does not mention them.
