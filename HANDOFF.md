# HANDOFF T001b+T002

STATUS: DONE
BRANCH: t/T001b+T002-images-compose
CHECK: `make check MODULE=deploy` → green (stub; no checks until T009)

## Changed (≤ 6 lines)
- docker-compose.yml: `minio` → `objectstore` (`chrislusf/seaweedfs:3.80`, `server -s3`, S3 on 8333, volume `objectdata`). api and worker get `S3_ENDPOINT`, `S3_BUCKET`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`.
- Makefile: `images`, `push`, `apk` targets; vars `ARCH`, `REGISTRY`, `VERSION`, `DRY_RUN`.
- deploy/build/release.yml: tag-only example workflow, not active.
- Existing Dockerfiles reused in place (server/, worker/, web/).

## Acceptance → tests
- `make dev-up` → api, objectstore, postgres, web, worker all `Up (healthy)`. S3 port 8333 returns HTTP 200. Torn down with `-v`.
- `make images` → 3 images built locally. Removed after.
- `make push` with placeholder → clear failure. `DRY_RUN=1 REGISTRY=…` → prints 3 push commands.
- `make apk` → not run (needs Android SDK).

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- `.gitignore` `build/` ignores deploy/build/, so release.yml was `git add -f`. Add `!deploy/build/`.
- SeaweedFS has no `-s3.config`, so S3 credentials are not enforced (dev only). No bucket is created; the app must create `S3_BUCKET`.
- `apk` is unsigned: android/ has no signingConfig. `images` is single-arch (`ARCH`).
