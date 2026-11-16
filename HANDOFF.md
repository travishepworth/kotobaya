# HANDOFF CARD-KIND-BADGE-AND + RC-FOLLOW-AND

STATUS: DONE
BRANCH: t/CARD-KIND-BADGE-AND+RC-FOLLOW-AND
CHECK: `make check MODULE=android` → green

## Changed (≤ 6 lines)
- Badge: ShownCard.noteType (note.type); ReviewScreen top-centre KbTagChip (30% width, ellipsis); strings review_kind_*.
- ReviewViewModel: refreshIfEmpty re-arms the day-roll timer after reload; a throwing start() → failLoad() (Failed + Retry, no inline error).
- ReviewSession: when stale, next in-memory card is shown without rebuild if its row still matches (state fits kind, still due); else rebuild.
- SettingsViewModels: reviews-unlimited toggle saved first, independent of number-field validation.

## Acceptance → tests
- 1 → `ReviewSummaryReloadTest.theDayRollTimerIsRearmedAfterAReloadIntoTheSameSummary`
- 3 → `ReviewHardeningTest.afterSyncAnUnchangedNextCardStillComesFromMemory` (existing stale test still passes)
- 4 → `LocalQueueLoaderTest.reviewsUnlimitedReachesTheQueueLimits`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Item 3 validity check (nextInMemoryStillValidLocked) only inspects the next card; later in-memory cards are re-checked on their own turn while stale stays set.
- Item 5 has no test (UI glue); item 2 has no test.
- Unknown note types show no badge.
