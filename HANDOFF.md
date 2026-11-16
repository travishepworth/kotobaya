# HANDOFF SHELL-L2

STATUS: DONE
BRANCH: t/SHELL-L2-local-queue
CHECK: `make check MODULE=android` → green

## Changed (≤ 6 lines)
- `shell/api/review/QueueRoute.kt`: `getReviewQueue` = validation + `LocalQueueLoader.load(0, deck, keepGoing)` + the server's shaping (ADR-033 counts from the normal build, `more_available`, `to_materialize`).
- `shell/api/review/QueueViews.kt`: `QueueItem` view (card+note, entry/kanji via `InstalledDictionary` L-7 steps 1-2 with overlay, decks, kanji_examples, grammar_sentence, text, preview); `Routes.kt` registers it.
- C-27: `ShellEntryMaterializer` wraps the app's `NoteCreator`; materializes only if `materializeAllowed` and `new` in kinds, else entries stay counted in `counts.new`/`to_materialize`.
- Parity: `ParityDevice` registers AppDatabase, InstalledDictionary, NoteCreator, idle SyncCoordinator, sets `last_sync_at` per pull; `LocalParityTest` on Robolectric (6 queue scenarios now match); PENDING line removed.

## Acceptance → tests
- Parity scenarios → `LocalParityTest`, `QueueParityTest` (whole runs; golden prefixes of pending ones; Tokyo rollover; reading re-enabled)
- Idempotent → `QueueMaterializeIdempotentTest`; converge → `QueueMaterializeConvergeTest`; validation → `QueueValidationTest` (Queue* prefix = owned path)

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Keep-going batch stays core default 10 (loader has no param; contracts/core README C-26 says so); server uses `limit`. FOLLOWUP: loader param.
- Full scenarios with answer/createNote run when L3/L5 register; kanji_examples does 3 replica lookups per candidate word.
