# HANDOFF AND-KEEPGOING

STATUS: DONE
BRANCH: t/AND-KEEPGOING
CHECK: `make check MODULE=android` → green (BackNavigationTest.searchBackClosesTheDetail is the known Compose-idle flake; see run note)

## Changed (≤ 6 lines)
- LocalQueueLoader/QueueSource/ReviewSession: `keepGoing` build flag, `ReviewMeta.keepingGoing/moreAvailable`, `ReviewSession.startKeepGoing()`; day change drops the flag; session-scoped, never persisted.
- ReviewScreen: finished page shows "Done for now" + Keep going (or "All caught up"); "New" chip on NEW cards. ReviewViewModel/ReviewRoute take `keepGoing`.
- Today: hero becomes Keep going when queue empty and `moreAvailable` (QueueCounts); route `review/keepgoing` in nav/AppNav.kt (outside listed paths, needed). Strings in strings_review/today.xml.

## Acceptance → tests
- Keep going offered iff keepGoing queue non-empty; serves new past daily limit → KeepGoingTest

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- A normal all-decks session now always rebuilds once at its end (the old "build already final" shortcut cannot know moreAvailable); keep-going sessions keep the shortcut.
- No count on the Today Keep going button (not cheap). Keep going is all-decks sessions only; deck sessions ignore it.
