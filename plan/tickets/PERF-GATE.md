# PERF-GATE — On-device timing harness and release gate

- Phase: P11 perf · Model: sonnet · Risk: low
- Deps: PERF-LF (merged to main)
- Branch: t/PERF-GATE-device-timing
- Owned paths (only edit these): android/src/main/kotlin/kotobaya/android/shell/web/BridgeCore.kt, android/src/main/kotlin/kotobaya/android/shell/api/LocalApi.kt (timing marks only), android/src/main/kotlin/kotobaya/android/sync/SyncFeature.kt (timing log only), scripts/perf-device.sh (new), Makefile (new `perf-device` target), deploy/RELEASE.md (one gate step), tests under android/src/test/ for the timing split
- Module(s) for CI: android

## Why
PERF-LF hit its targets on the desktop JVM (queue p95 about 155 ms) but took 500–1830 ms on the phone. Desktop numbers can't be the gate. Today the only signal on the device is the "slow handler" log line (over 250 ms). Its clock starts before the readiness gate, the lane queue and the same-request mutex (BridgeCore.kt:98-112), so waiting and computing are mixed together. Independent review: plan/handoffs/PERF-REVIEW-2026-11-21.md.

## Do
1. Split each handler's time into gate wait, lane wait, mutex wait, compute, and JSON encode plus post to the WebView. Log one structured line per call (tag `KTB-PERF`). Log every call when a debug flag is set (a system property or a build config field, off in a normal release). Log only slow calls otherwise, as today. Make sure the "gate open after N ms" line is still there and carries the same tag.
2. `scripts/perf-device.sh` (target `make perf-device`) uses adb and the installed APK:
   - N cold runs (`am force-stop`, then start the app to Home) and N warm runs (reopen Home), N=5 by default.
   - Scrape `KTB-PERF` from logcat.
   - Print a median and p90 table per handler and per phase, and save it to `plan/perf/device-<versionName>-<date>.md` (written when the script runs; the writer does not commit anything under plan/).
   - Optional `--speed` flag: run `cmd package compile -m speed -f <pkg>` first, then the same runs. This separates JIT cost from everything else.
   - Use `--user` for the Private Space user.
3. Targets live in the script, as a table at the top:
   - Cold, first getReviewQueue: 400 ms or less.
   - Warm getReviewQueue: 150 ms or less.
   - Warm Home handlers: 300 ms or less each.
   - Answer to next card: 50 ms or less.
   The script exits non-zero if a median misses its target.
4. Add a step to deploy/RELEASE.md: no APK release without a `make perf-device` table attached to the LOG line, unless the user waives it.

## Acceptance (each bullet → at least one named test)
- [ ] Unit test: the timing split adds up (gate + lane + mutex + compute equals total, within 1 ms) for a fake handler that has a delayed gate and a busy lane.
- [ ] Unit test: without the debug flag, a fast call logs nothing.
- [ ] The script runs against the phone. The writer attaches one real table (cold and warm, with and without `--speed`) to HANDOFF.md. The orchestrator runs it on the device; it is not a unit test.
- [ ] `make check MODULE=android` green.

## Out of scope
Any speed-up. Macrobenchmark and Perfetto modules.
