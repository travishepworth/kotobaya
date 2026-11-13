# HANDOFF T310

STATUS: DONE
BRANCH: t/T310-audio-prefetch
CHECK: `make check MODULE=android` → green

## Changed (≤ 6 lines)
- android/.../media/: PrefetchPlanner (pure), BlobStore (blob_cache via raw SQL on a SqlDriver + files by hash, breakdown, clearCache, touch), BlobDownloader (Range resume, SHA-256, atomic rename), AudioPrefetcher (+MediaPrefs: 2 days, 500 MB, metered off), MediaFeature (WorkManager, Koin).
- di/FeatureModules.kt: one line `kotobaya.android.media.mediaFeature` (flagged).

## Acceptance → tests
- Planner → PrefetchPlannerTest (window, budget, LRU order, protected, over-budget skip reason)
- Worker → AudioPrefetcherTest (mismatch x3 then missing, resume, 404, metered, clear/touch)

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Not done: (1) card-audio hash source: `AudioNeedsSource` is bound to empty (audio field format is not in contracts; T304b/T304a must supply it); (2) Settings UI section (needs strings.xml, not owned): use `BlobStore.breakdown(dictBytes)`/`clearCache()`; (3) sync must call `AudioPrefetchWork.enqueue(ctx, prefs.allowMetered)` after success; (4) T304b calls `BlobStore.touch`.
- Hash mismatch / 404 both set state `missing` (no `failed` state in schema). BlobStore uses T303c's durable driver via Koin qualifier named("syncAppDriver") (string copy; SYNC_DRIVER is private). mediaFeature sits before syncFeature, which stays last.
