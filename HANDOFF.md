# HANDOFF T410

STATUS: DONE
BRANCH: t/T410-android-coverage-sentences
CHECK: `make check MODULE=android` → green

## Changed (≤ 6 lines)
- New `android/.../media/browse/`: models, `MediaBrowseRepository` (API + file cache, "last updated" stamp), screens (local media list, coverage, segments, Canvas curve, i+1 sentences with audio via BlobStore/BlobDownloader), feature entry.
- Flagged shared edits: one line in `di/FeatureModules.kt`; `Routes.MEDIA` const in `nav/AppNav.kt` (route itself comes from the feature, top-level bottom-bar item); new `nav_media`/`browse_*` keys in `strings.xml`.

## Acceptance → tests
- Cached coverage on client failure; "needs connection" with no cache → `MediaBrowseRepositoryTest` (servesCachedCoverageWhenClientFails, needsConnectionWithNoCache; plus sentencesSendEncodedFilter for `i%2B1`)
- make check green → done

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Media is a 6th bottom-bar tab (routes are unparameterised, so list/detail is in-screen state). Sentence audio is downloaded on demand (reason MEDIA, no budget cap). Only first 50 i+1 sentences shown. Screens untested (UI).
- Branch name differs from ticket's (t/T410-android-media-browse) per dispatch.
