# HANDOFF AND-FLAKY-BACKNAV

STATUS: DONE
BRANCH: t/AND-FLAKY-BACKNAV
CHECK: `make check MODULE=android` → green (two forced full reruns, `--rerun`, 0 failures)

## Changed (≤ 6 lines)
- android/src/test/.../nav/BackNavigationTest.kt: @Before registers a global-snapshot write observer that posts `Snapshot.sendApplyNotifications()` to the current main looper (disposed @After). Test-only; no app bug.

## Root cause
- Under the full suite only: at the failure, diagnostics showed `Snapshot.current.hasPendingChanges()==true`, recomposer idle, main looper empty, uptime frozen. Compose's global-snapshot apply goes through a process-wide dispatcher created by an earlier Robolectric test in the SDK 34 sandbox; the apply is lost, so Compose never idles and `waitForIdle` spins 60 s. Not the review countdown or any app ticker.

## Acceptance → tests
- green twice → two `--rerun` full runs; root cause above

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- "Lost apply via stale dispatcher" is inferred from the diagnostics; the fix is verified empirically (it failed 100% of full runs before). Other Compose tests may need the same @Before if they start flaking; I did not touch them.
