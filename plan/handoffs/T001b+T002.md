# HANDOFF T001b+T002 (attempt 2)

STATUS: DONE
BRANCH: t/T001b+T002-images-compose
CHECK: `make check MODULE=deploy` → green; `make check MODULE=android` → green

## Changed (≤ 6 lines)
- android/build.gradle.kts: card-asset copy is a typed task registered via `androidComponents.onVariants` → `addGeneratedSourceDirectory`. Lint and merge both depend on it.
- .gitignore: `!deploy/build/` added. release.yml no longer needs `git add -f`.
- docker-compose.yml: `minio` → `objectstore` (SeaweedFS 3.80, S3 8333). api/worker get `S3_*` env.
- Makefile: `images`, `push`, `apk` targets (`ARCH`, `REGISTRY`, `VERSION`, `DRY_RUN`).
- deploy/build/release.yml: tag-only example workflow, not active.

## Acceptance → tests
- `make dev-up` (attempt 1): api, objectstore, postgres, web, worker `Up (healthy)`. S3 8333 returns 200. Torn down with `-v`.
- `make images` (attempt 1): 3 images built, then removed.
- `make push` placeholder → clear failure. `DRY_RUN=1 REGISTRY=…` → prints 3 push commands.
- `make apk` → `android/build/outputs/apk/release/android-release-unsigned.apk`, bundles `assets/card/`.

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- SeaweedFS has no `-s3.config`, so S3 credentials are not enforced (dev only). No bucket is created; the app must create `S3_BUCKET`.
- The APK is unsigned: no signingConfig exists, so signing env vars are not wired. `images` is single-arch (`ARCH`).
