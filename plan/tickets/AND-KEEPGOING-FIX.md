# AND-KEEPGOING-FIX — Keep going must never stop while anything is due or new

- Phase: P9 · Risk: high (review session) · Model: writer-senior
- Branch: t/AND-KEEPGOING-FIX
- Owned paths: android/src/main/kotlin/kotobaya/android/review/**, android/src/main/kotlin/kotobaya/android/queue/**, android/src/test/** (review/queue tests)
- Module for CI: android

## User report (2026-11-15, verbatim intent)
"Keep going worked for a little bit but then after doing a few reviews it stopped working. I should be
allowed to continue through any amount of due cards until they are all cleared out (regardless of the
artificial 200 cap, if a card has a scheduled review, I need to be able to review it, and have an
unlimited ability to keep adding cards.)"

## Requirement (normative)
Once the user taps Keep going, the session is an endless stream for the rest of that review-screen
visit: every card due today (no review cap, global or per deck) and then new cards in batches, refilled
automatically. The session may show the end screen ONLY when a keep-going build (keepGoing=true)
returns no items at all (nothing due, no new cards left in any enabled deck). No "done" screen at batch
boundaries, no second tap needed.

## Defects found by the orchestrator (fix all; look for others)
1. `ReviewSession.settleLocked` (~line 492): in keep-going mode, when the in-memory queue empties and the
   last build saw all answers but one (e.g. after a "refresh"/"ahead" rebuild), it calls `finishLocked()`
   WITHOUT rebuilding. A keep-going build only holds one new batch (keepGoingNewBatch=10), so it is
   never "final". Always rebuild with keepGoing=true when the memory queue empties; finish only if that
   build is empty.
2. `ReviewSession.rebuild` sets `buildMoreAvailable = built.moreAvailable` from a keep-going build, but
   core only computes `moreAvailable` when keepGoing=false (always false in keep-going builds). So when a
   keep-going session ends the screen says "All caught up" with no button even if cards remain. With
   (1) fixed, end-of-keep-going must mean truly nothing left; make the end screen consistent.
3. Keep-going mode is lost if the ReviewViewModel is recreated (process death / config change /
   background) after tapping Keep going on the done screen (only the `review/keepgoing` route survives).
   Keep it for the visit (e.g. SavedStateHandle) so the stream doesn't silently revert to the capped queue.
   Persisting within the screen's saved state is fine; no preference/sync state.
4. Check the rebuild path when keep-going pulls the next batch while a card is current/pending (excluded
   ids) so the stream never stalls.

## Acceptance
- [ ] Robolectric/unit test: device store with 300 cards due today (reviewsPerDay=200), 40 new cards,
      newPerDay=20, newPerSession=10. Normal session ends after the capped queue with moreAvailable;
      Keep going then serves ALL remaining due reviews and ALL 40 new cards in one uninterrupted stream
      (answer each card Good; count answers), and only then shows the end screen. Include periodic
      "refresh" rebuilds (rebuildEveryMs small) so defect 1 is exercised.
- [ ] make check MODULE=android green (BackNavigationTest.searchBackClosesTheDetail is a known flake).
