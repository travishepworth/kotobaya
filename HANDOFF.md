# HANDOFF PERF-GATE

STATUS: DONE (device table pending: orchestrator runs the script on the phone)
BRANCH: t/PERF-GATE-device-timing
CHECK: `make check MODULE=android` → green

## Changed
- BridgeCore.kt: per-call `CallTiming` (coroutine-context element) and `PerfRecord`; one `KTB-PERF` line `call op= status= gate= lane= mutex= compute= total= encode=`. Logged for every call when `kotobaya.perf=1` or `adb shell setprop log.tag.KTB-PERF DEBUG`; otherwise only calls over 250 ms.
- LocalApi.kt: timing marks only (gate, mutex, lane, compute). SyncFeature.kt: "gate open after N ms" also under `KTB-PERF`.
- `scripts/perf-device.sh`, `make perf-device ARGS=...`, deploy/RELEASE.md step 5 gate line.

## Acceptance → tests
- split adds up → PerfTimingTest.gatePlusLanePlusMutexPlusComputeIsTheTotal
- no flag, fast call logs nothing → PerfTimingTest.aFastCallLogsNothingWithoutTheDebugFlag
- real device table → not done here; run `make perf-device ARGS="--user 10"` and again with `--speed`.

`--from-log` on a synthetic 4-run log printed per-handler median/p90 rows (e.g. cold getReviewQueue total 400 / 430) and a targets table, exit 0; `FAIL` rows give exit 1.

## New dependencies
- none (script uses bash, adb, python3 stdlib)

## For the reviewer
- Clock starts before the gate, so cold `total` includes gate wait. `answerReview` is checked only if answers happen during the runs (else "n/a"). Encode is not part of `total`.
- Live mode is untested on a device (adb commands: am start, force-stop, HOME key).
