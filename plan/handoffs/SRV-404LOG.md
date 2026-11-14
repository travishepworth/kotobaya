# HANDOFF SRV-404LOG

STATUS: DONE
BRANCH: t/SRV-404LOG
CHECK: `make check MODULE=server` → green (459 tests, 0 failures)

## Changed (≤ 6 lines)
- `server/src/main/kotlin/kotobaya/server/App.kt`: AccessLog plugin writes at most one `kotobaya.request` line per call (per-call AtomicBoolean attribute guards the ResponseSent hook).
- `server/src/test/kotlin/kotobaya/server/ServerTest.kt`: new test `everyRequestLogsExactlyOnceIncludingUnknownRoutes`.

## Acceptance → tests
- Unknown route logs exactly one 404 line; known route logs exactly one line → `everyRequestLogsExactlyOnceIncludingUnknownRoutes`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Reproduced the double log first (test failed with 2 lines for `/api/v1/version`). Root cause in Ktor's 404 path not traced; the fix is a per-call guard, so it holds whatever fires twice.
- Guard keeps the first ResponseSent event; it asserts status 404 in the test.
