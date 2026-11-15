# HANDOFF AND-REVIEW-INFO (attempt 3)

STATUS: DONE
BRANCH: t/AND-REVIEW-INFO (rebased on main)
CHECK: `make check MODULE=android` → green (the first run failed only on the known-flaky BackNavigationTest Compose-idle hang; the rerun was green)

## Changed (≤ 6 lines)
- review/BackExtras.kt: new `BackExtrasIndex`: non-NEW word_status (sorted LongArray + statuses), live-note seqs, seqs of live deck_items in live decks, note→merged-ids map. `BackExtrasBuilder` uses it, so there are no learning queries per candidate and no merged_into_id scan.
- review/SqlBackExtrasIndex.kt: builds the index on the device with 4 narrow-column queries on the sync driver.
- ReviewSession: the index is loaded in `rebuild` (start, sync-stale, end of queue, all decks) and shared by every `load` (prefetch and tap). A failed read degrades only the extras. Wired via `ReviewBackend.extrasIndex` / ReviewFeature.
- UI, keepOnLeave and the no-`all()` tests are unchanged.

## Acceptance → tests (ReviewInfoTest)
- 3 words incl. ON+KUN; chips in priority order, now incl. a merged-in note → examples..., deckChips...
- open-in-dictionary → back, same revealed card, no review change → openInDictionary...
- O(1) per-card queries: 3 vs 2,000 candidates give equal counts (0 for kanji, ≤4 for vocab), ≤3 gloss lookups → theTapPathIssuesTheSameFewQueries...
- Same picks as the old per-candidate lookups (25 random replicas) → theIndexGivesTheSamePicks...
- Device SQL matches the replica rules (JDBC fixture DB) → theDeviceIndexReadsTheSame...

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Between rebuilds (≤5 min) the index does not see statuses changed by this session's own answers. It only affects which example words are shown.
- deck_seq follows C-25 ("live deck"). It does not filter smart decks, which have no items.
