# HANDOFF F-11

STATUS: DONE
BRANCH: t/F-11-server-test-heap
CHECK: `make check MODULE=server` → green (no OOM)

## Changed
- server/build.gradle.kts: `tasks.test { maxHeapSize = "2g" }`
- Spec.kt: `ApiSpec.load()` cached once per JVM (lazy)
- ConformanceTest.kt: operation count derived by parsing contracts/api/openapi.yaml (independent of ApiSpec)
- ServerTest.kt: test renamed `flywayAppliesAllContractMigrationsAndReadyz...`; migrations executed == count of contracts/db V*__*.sql, and max applied version in flyway_schema_history == highest file version

## Acceptance → tests
- no OOM, green suite → full server check
- stale counts → derived, not bumped

## New dependencies
- none

## For the reviewer
- Tests read `../contracts/...` relative to Gradle's test working dir (server/).
