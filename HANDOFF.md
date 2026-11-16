# HANDOFF REVIEW-UNLIMITED

STATUS: DONE
BRANCH: t/REVIEW-UNLIMITED
CHECK: `make check MODULE=contracts|core|server|web` → green; android → 409/410, only the known flake BackNavigationTest.searchBackClosesTheDetail (FOLLOWUPS:479; passes alone)

## Changed (≤ 6 lines)
- contracts: SCHEMA.md §6 row + new §6.3 `scheduler.reviews_unlimited` (bool, default false); openapi getReviewQueue "No review limit (ADR-034)"; core SUMMARY; clients regenerated (doc text only).
- core: `QueueLimits.reviewsUnlimited` (default false, KDoc + rule 3 text); DefaultQueueBuilder rule 3 lifts global + per-deck review caps when set.
- server: WELL_KNOWN bool validation, ReviewSettings reads key, QueueLoader passes it.
- android: QueueSettings/LocalQueueLoader wiring; StudySettingsModel.setReviewsUnlimited (mints bool `settings.set`); Study settings switch (i18n) greys out reviews-per-day.
- web: bool setting def + switch in Limits group, reviews-per-day field disabled with hint (i18n).

## Acceptance → tests
- 300 due, cap 200 off/on → QueueReviewsUnlimitedTest.threeHundredDueCapTwoHundredOffServesTwoHundredOnServesThreeHundred
- per-deck cap ignored, new limit kept → QueueReviewsUnlimitedTest.perDeckReviewCapIgnoredWhenOnButNewLimitStillApplies
- server round-trip + counts → ReviewApiTest.reviewsUnlimitedRoundTripsAndLiftsTheReviewCap; SettingsApiTest bool cases

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Rule 5 interleaving unchanged when on (new cards spread among a big backlog); keep going still puts reviews first.
- UIs skip validating/saving the greyed reviews-per-day field while the toggle is on (value kept for toggling back).
