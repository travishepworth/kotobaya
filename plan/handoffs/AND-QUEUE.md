# HANDOFF AND-QUEUE

STATUS: DONE
BRANCH: t/AND-QUEUE-due-cards
CHECK: `make check MODULE=android` → green (core untouched)

## Changed (≤ 6 lines)
- Root cause: Today's hero added up per-deck counts of enabled user decks (TodayScreen.Hero, 9bb0f573). On the phone DB copy the queue builds 183/10/1, the same as the server, and a real ReviewSession shows a card. But every due card is in no deck, so the deck sum was 0 and Home said "caught up".
- `DeckSource.overview()` returns the decks plus the whole-queue `QueueCounts` from one load. DecksUiState.queue carries it.
- The hero uses `todayHero(queue)`. The "add first words" page needs no decks AND an empty queue. Hero string now counts cards.

## Acceptance → tests
- Real-shaped regression → HomeQueueAgreementTest.homeShowsTheDecklessReviewsThatTheReviewTabStartsWith. It asserts the old deck sum is 0. Pre-fix it fails to compile (new API).
- Home == Review queue size → same test, plus homeIsCaughtUpOnlyWhenTheWholeQueueIsEmpty.

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- /meta x7: benign. Each offline ServerReachability probe logs a network api_failure, queued and flushed later. Noise, not a storm. Follow-up: don't report probe network failures.
- Zone: Android uses scheduler.timezone (default UTC), not the device zone. Like the server it rolls over at 04:00Z, so the two agree and zone played no part. But the day changes at 13:00 JST.
