# HANDOFF BUG-10

STATUS: DONE
BRANCH: t/BUG-10-hot-queries
CHECK: `make check MODULE=server` → green

## Changed
- StruggleModule.kt `HotWords.loadHistory`: the 7.5 s cost. It filtered `coalesce(c.merged_into_id, c.id) = any(2.3k ids)` over a join of all review_log rows (no index usable). Now: survivors+losers via card pk/merged_into index, then `review_log.card_id = any(...)` (review_log_card_idx). Also hoisted per-note `ReviewSettings.load`.
- No migration added: server migrations come only from `contracts/db` (processResources), so none is allowed. No contracts change needed.
- New `HotPerfTest` (seed: ~90k reviews, 9,975 cards, 200k-entry dict).

## Timings (seeded, ms cold/warm)
- /words/hot: 7742/7221 before -> 715/110 after; ?limit=200: 295/209
- /kanji/hot: ~7.7 s before (same scoring) -> 132/94
- /kanji/{lit}/words: 18-37 ms before and after; could not reproduce 1.4 s (EXPLAIN: sort over dict_entry 27 ms, uses note_live_entry_uq). Left unchanged. Cold numbers are JVM warm-up.

## Acceptance -> tests
- each endpoint fast -> HotPerfTest.hotAndKanjiWordsEndpointsAreFast (bound 1 s, not 200 ms: CI noise/cold JIT)
- outputs unchanged -> HotPerfTest.historyLoaderMatchesTheLegacyJoin (legacy SQL vs new, same rows/survivor mapping) plus existing StruggleApiTest

## New dependencies
- none

## For the reviewer
- `HotWords`/`loadHistory` made `internal` for the equality test.
