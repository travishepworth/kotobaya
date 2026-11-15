# HANDOFF AND-KEEPGOING-FIX

STATUS: DONE
BRANCH: t/AND-KEEPGOING-FIX (rebased on main 5dfeec90, C-27 merged)
CHECK: `make check MODULE=android` → 389 tests, only the known flake BackNavigationTest.searchBackClosesTheDetail failed

## Changed (≤ 6 lines)
- C-27 exactly, mirroring server DeckEntryPlan: new android `notes/DeckEntryPlan`, shared by planning and minting. card_kinds falls back to default kinds; empty fronts dropped; entries with no kind left are never passed; blocked kanji = live or user-deleted.
- LocalQueueLoader: fills PendingEntry.expressionKanji/newKanjiCards. Per-deck window 2·B+16. Another round (max 3) only if the last round's notes reach `items`. Pull-complete gate kept.
- `headwordOf` is now wired to the installed dictionary (it had no production source), so deck notes (fields {}) get the expressionKanji they were drawn with.
- ReviewSession: rebuilds before ending; in keep going, learn-ahead waits for a fresh build. A config change keeps the session.
- Approval fix: planning reads through the store's reader connections (`LearningReader`), not a write transaction. materialize opens one only when something will be created, so a load that mints nothing fires no commit listener.

## Acceptance → tests
- KeepGoingTest: 300 due + 40 new without a stop; defect-1 regression
- DeckEntriesTest (12), incl. aLoadThatMintsNothingCommitsNothing; add_kanji allowance 1 over 3 rebuilds (no unshown notes; fails with newKanjiCards = 0); deleted kanji not recreated; card_kinds [] → defaults; user-deleted entry; mid-pull gate; refused mint
- ReviewInfoTest.aConfigurationChangeKeepsTheSession

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Headword cache lives for the process: after a dictionary update, a changed headword shows after a restart.
- Convergence: SYNC.md 4 + 8.1 (README C-27).
