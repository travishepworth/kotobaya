# HANDOFF T210a

STATUS: DONE
BRANCH: t/T210a-cards-api
CHECK: `make check MODULE=server` → green

## Changed (≤ 6 lines)
- `server/.../cards/`: CardsModule, CardApi (list/get/create/update/delete/reviews), CardSearch (T108b Postgres compile, tz-aware dayStart), `bulk/BulkApi` (bulk + bulk/schedule via OpApply, ReviewOps.logReview).
- Main.kt: one registration line (`CardsModule(db, sync.opApply).handlers`) plus its import.

## Acceptance → tests
- Bulk ops one-per-card, dry_run, one tx → CardsApiTest.bulkActionsOverAQueueEmitExactlyOneOpPerMatchedCardAndDryRunChangesNothing
- >5000 → 422 bulk_too_large → ...aSelectorMatchingMoreThan5000CardsIs422AndNothingIsApplied
- Residual path → ...aQueryNeverActsOnARowTheSearchRejects
- set_due/reset + review scope → ...scheduleActionsRewriteTheReplayedCacheAndNeedTheReviewScope

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Postgres dialect is never residual; CardSearch `check`s that rather than applying `accepts`. `is:hot` matches nothing (HotScorer inputs not loaded). Cursors are offset-based (opaque), not keyset.
- Repeated `bulk/schedule` adds extra manual rows (no idempotency id): contract matter. Scope `review`-only on bulk/schedule kept as contracted; T105b's review+write CONTRACT_CHANGE_REQUEST candidate stands.
- `move` keeps an existing item's position in the target deck; skips removal when from == to.
