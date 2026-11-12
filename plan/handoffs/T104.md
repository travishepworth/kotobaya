# HANDOFF T104

STATUS: DONE
BRANCH: t/T104-server-skeleton
CHECK: `make check MODULE=server` → green (needs Docker for Testcontainers)

## Changed (≤ 6 lines)
- `server/`: Ktor app (`App.kt`), env `Config`, Hikari+jOOQ `Database`, Flyway `Migrations` (V1 and openapi.yaml copied from `contracts/` by `processResources`), problem+json + `X-Request-Id`, JSON logs (logstash, one `request` line with `token.id`/`token.name`), `/healthz` `/readyz` `/metrics`, `GET /api/v1/health|meta`, other 116 ops as 501 stubs derived from the spec.
- Hooks: `Authenticator` (default `AllowAll`) + `ScopeRequirements` from spec `security`/`x-auth` (`Hooks.kt`); `AppDeps.handlers` (operationId→handler) and `extraReadiness` (T202 adds its check); empty `kotobaya/{auth,dict,jobs}`.
- Startup (`Startup.kt`): lock 7033001; finalize `restore_pending` (new epoch, durable_seq, raise `hlc_last`) else create `log_epoch`; then `core_scheduler_version` check. Readiness waits for both.
- `migrate`: `bin/server migrate` (exit 1 on failure); `MIGRATE_ON_START=true` migrates at startup. Bucket created at startup with retry (not gating readiness).

## Acceptance → tests
- Conformance (routes == spec + 3, status/schema) → `ConformanceTest`
- Postgres 17 C.UTF-8, V1, `/readyz` 503 when DB down → `ServerTest`
- problem+json, request id, JSON logs → `ServerTest.errorsAreProblemJson...`
- Startup order/lock/restore/replay-once → `StartupSequenceTest`

## New dependencies
- Ktor status-pages/metrics-micrometer + micrometer-prometheus (errors, /metrics); jackson (json+yaml spec); HikariCP, postgresql, jOOQ 3.19, Flyway 11 (ADR-007); minio client (bucket create); logstash-logback-encoder (JSON logs); testcontainers 1.21.4 (older fails on Docker 29). Versions declared in `server/build.gradle.kts`, not the shared catalog.

## For the reviewer (≤ 3 lines)
- PROVISIONAL: jOOQ used as plain-SQL DSL (no codegen yet; ADR-007 says generated, add when T202 needs it). Extra codes `not_implemented` (501) and `method_not_allowed` (405) are not in the API error table.
- T203 hook: `SchedulerReplay` (called in the 10.5 tx) and `PLACEHOLDER_SCHEDULER_VERSION="unset"` to be replaced by core's `ReplayEngine.schedulerVersion`; key `hlc_last` reserved in `ServerStateKeys`, T202 fills it.
- Outside my paths, for T111/orchestrator: compose `api` lacks `DATABASE_URL`, `MIGRATE_ON_START`, and Postgres `POSTGRES_INITDB_ARGS=--locale=C.UTF-8`. JVM zone is forced to UTC (pgjdbc rejected zone "Japan").
