# HANDOFF T001

STATUS: DONE
BRANCH: t/T001-scaffold
CHECK: `make test` → green (core jvmTest, server, worker, web, android; deploy/contracts are documented no-op stubs)

## Changed (≤ 6 lines)
- Gradle 8.11.1 wrapper, Kotlin DSL, `gradle/libs.versions.toml`; modules core (KMP jvm/android/js, explicit API), server (Ktor, `/healthz`), android (app, copies `assets/card/` into assets).
- `core:npmPackage` packages the JS build as `@kotobaya/core`; `web/` links it (`link:../core/build/npm/core`); `make check MODULE=web` builds it first.
- `worker/` (uv, ruff, mypy --strict, pytest, stdlib health endpoint), `web/` (SvelteKit 3 static, vitest, eslint), `docker-compose.yml` + Dockerfiles, Makefile, `.editorconfig`.

## Acceptance → tests
- `make test` green → all smoke tests (SmokeTest, HealthzTest, test_health, web core.test.ts, android SmokeTest)
- web imports `@JsExport` fn → `web/tests/core.test.ts`
- `make dev-up` healthy → PARTIAL: postgres and worker verified healthy. minio could not be pulled (`minio/minio` removed from Docker Hub; `quay.io/minio/minio` returns unauthorized here), and the disk hit 100% so api/web image builds were not run. api/web healthchecks are untested.

## New dependencies
- SvelteKit 3 stack as installed by pnpm; typescript pinned ~5.9 (TS 7 breaks SvelteKit's tsconfig loading); Ktor 3.0.3, AGP 8.7.3, Kotlin 2.1.10.

## For the reviewer (≤ 3 lines)
- MinIO image in compose needs a pullable source (decide registry/tag); the Android SDK is required even for core/server (core applies the android plugin); only `check android` prints SKIPPED without it.
- SvelteKit 3 has no svelte.config.js (config is in vite.config.ts), no `$lib` alias (relative imports used).
