# HANDOFF F-11

STATUS: DONE
BRANCH: t/F-11-server-test-heap
CHECK: `make check MODULE=server` → red, but NOT OOM: 310 tests ran, 2 stale-count failures (unrelated, outside owned paths)

## Changed
- server/build.gradle.kts: `tasks.test { maxHeapSize = "2g" }`
- server/.../Spec.kt: `ApiSpec.load()` returns a lazily parsed, per-JVM cached instance (App.kt untouched)

## Acceptance → tests
- no OOM → full server suite completed (2m38s), no host OOM or kill

## New dependencies
- none

## For the reviewer
- Failures: ConformanceTest "contract has 117 operations" (now 120); ServerTest.flywayAppliesV1... (ServerTest.kt:22, expected 1 but was 2, likely migration count). Hardcoded counts outdated by recent contract/migration merges; need a separate ticket.
