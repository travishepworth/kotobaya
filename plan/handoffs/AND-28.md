# HANDOFF AND-28 (attempt 2)

STATUS: DONE
BRANCH: t/AND-28-android (rebased on main incl. SRV-28 and CORE-28b)
CHECK: `make check MODULE=android` → red on 2 tests (604 run), both only because the goldens of the 3 new C-28d scenarios do not exist (`queue-kanji-keep-going-open-unit`, `-lead-deck-limit`, `-shared-kanji`): `LocalParityTest` x2. 35 other scenarios match their goldens; `ParityExpectTest` meets the `expect` of all 38.

## Changed (≤ 6 lines)
- Candidate query = server predicate (started vocab/kanji cards even when suspended or not due); `fields` read only for vocab.
- `KanjiSource` + `KanjiDictionary`: one chunked narrow `IN` read (`InstalledDictionary.formJson` / `kanjiFacts`) per build, misses cached, dropped when the package or gap-fill generation changes; tags unioned per form text; no facts read when exemptions are off.
- `NoteCreator.kanjiKinds` caches kinds per literal across builds (no per-build `dict.kanji`).
- Earlier: kanji-first loader inputs, step A/B minting, `until_stop`, `kanji_parts`, `front_furigana`, `Deck.progress`.

## Acceptance → tests
- suspended started card → `QueueKanjiMintTest.aSuspendedDueStartedReviewCardIsNeitherServedNorCounted`; kanji-first order and server-minted literal converging → `QueueKanjiMintTest`
- race with missing kanji → `MaterializeRaceTest.twoConcurrentFetchesCreateOneLiveKanjiNotePerLiteral`
- retired reading card / tombstoned deck → `DeckParityTest`; 10k-entry batching and cache (timing printed) → `KanjiSourceTest`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Per-(note, updated_at) memoization of the requirement was not added: the dictionary data is cached, so a build only decodes `fields` of vocab notes.
- The kinds cache lives for the process; it is keyed by the kanji note type and `reading_enabled`.
