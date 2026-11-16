# HANDOFF CARD-KIND-BADGE-AND + RC-FOLLOW-AND

STATUS: DONE
BRANCH: t/CARD-KIND-BADGE-AND+RC-FOLLOW-AND
CHECK: `make check MODULE=android` → green

## Changed (≤ 6 lines)
- Badge: ShownCard.noteType (note.type); ReviewScreen top-centre KbTagChip (30% width, ellipsis); strings review_kind_*.
- ReviewViewModel: refreshIfEmpty re-arms the day-roll timer after reload; a throwing start() → failLoad() (Failed + Retry, no inline error).
- ReviewSession item 3 REVERTED to always rebuilding when stale (RC-AND behaviour): a safe shortcut would need the loader's full candidate predicate (suspended, buried, refused, live note, word overrides) shared with the session; not clean, so not done. Flicker on sync stays.
- ReviewViewModel: rollJob armed/cancelled under a lock.
- SettingsViewModels: reviews-unlimited toggle saved first, independent of number-field validation.

## Acceptance → tests
- 1 → `ReviewSummaryReloadTest.theDayRollTimerIsRearmedAfterAReloadIntoTheSameSummary`
- 3 (now safety tests) → `ReviewHardeningTest.aCardSuspendedElsewhereIsNotShownAfterSync`, `aCardBuriedElsewhereIsNotShownAfterSync`, `aDeletedCardFollowedByAnAnsweredElsewhereCardShowsNeitherAndLogsNoDuplicate` (word known/blacklist not tested: loader owns it, session always rebuilds)
- 4 → `LocalQueueLoaderTest.reviewsUnlimitedReachesTheQueueLimits`

## New dependencies
- none

## For the reviewer
- Item 3 optimisation dropped for correctness; the test queue in ReviewHardeningTest now filters suspended/buried/deleted like the loader.
- Items 2 and 5 untested (glue); test 1 uses an ~8s real-time wait.
