# HANDOFF JLPT-1b

STATUS: DONE
BRANCH: t/JLPT-1b-server-deck-publish
CHECK: `make check MODULE=server` → green

## Changed (≤ 6 lines)
- `decks/DeckLists.kt`: catalogue per VOCAB_LISTS 4.1/4.2. Vocab lists come from `ref.vocab_list` (entry_count > 0). Freq bands count only live entries, skip colliding slugs, and are hidden when the count is 0. Sorted in byte order. createDeck's 422 follows from this.
- `decks/DeckPublishRunner.kt` + `DeckPublishPayload.kt`: new runner per 5.2. It installs items and mints `deck.content_published` only inside `OpApply.transactionBlocking`.
- `media/publish/PublishBlobs.kt`: blob fetch moved out of MediaPublishRunner (no behaviour change) and shared by both runners.
- `server/Main.kt`: `ComposedApp.deckPublish` is started next to mediaPublish.
- V3 migration: nothing to do. `processResources` already copies `contracts/db/V*__*.sql`.

## Acceptance → tests
- /deck-lists counts + createDeck enqueues deck-build → `DecksApiTest.deckListsCountVocabListsAndLiveFrequencyBandsAndCreatingAJlptDeckEnqueuesDeckBuild`
- §6 end to end → `DeckPublishRunnerTest.workedExampleInstallsTheArchiveAndMintsOneDeckContentPublished`
- idempotent re-run → `reRunningTheSameChildIsIdempotent`
- stale/out-of-order base → `aStaleOrFutureBaseFailsStaleBaseAndInstallsNothing`
- 5.2 error codes → `eachCheckOfFiveTwoFailsWithItsCodeAndChangesNothing`, `aNonListDeckIsInvalidPayload`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- An `OpRefused` ends as `internal`, because 5.2 has no `invariant_violation`. Archive header mismatches end as `invalid_snapshot`, per 5.2 step 3. Media uses `invalid_payload` for those.
- Two existing DecksApiTest tests now load a `jlpt-n3` vocab_list row, because unloaded JLPT keys are no longer offered.
