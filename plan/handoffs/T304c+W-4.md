# HANDOFF T304c + W-4

STATUS: DONE
BRANCH: t/T304c+W-4-review-actions-media
CHECK: `make check MODULE=android` → green

## Changed
- T304c: `review/actions/ReviewActions.kt` (suspend, bury, flag, never-forget, blacklist, edit note; each one `OpMinter` op, inverse op kept for Undo) and `ReviewActionsBar.kt` (overflow menu, edit bottom sheet, undo offer, empty-front warning).
- W-4: `media/CardAudioNeeds.kt` (AudioNeedsSource from rendered due cards), `media/MediaSettings.kt` (breakdown, Clear cache, window/budget/metered), `prefetchAfterSync` in `sync/SyncFeature.kt` enqueues `AudioPrefetchWork` after a Completed cycle.
- `BlobStore.touch` on play was already done by T304b (BlobStoreResolver.played); skipped.
- Out-of-path edits: `review/ReviewSession.kt` (+`refreshCurrent()`), `ReviewBackend.kt` (+`nextStudyDayStart`), `ReviewFeature.kt` (wires it from scheduler settings), `ReviewScreen.kt` (one hook line), new `res/values/strings_review_actions.xml`. `FeatureModules.kt` untouched (syncFeature order unchanged).

## Acceptance → tests
- Each action op/queue effect, edit round trip with replica pull → `ReviewActionsTest`
- Sync enqueues prefetch; needs for due cards in window → `CardAudioNeedsTest`

## New dependencies
- none

## For the reviewer
- Word-status ops use `origin: "manual"` (contract enum has no `user`). Bury horizon = end of the current study day from scheduler settings.
- Field edits never create or delete cards (kinds only change with the note type); emptied fronts only warn. Edits send only changed fields.
- New cards (no due_at) count as due now for prefetch, capped at 100.
