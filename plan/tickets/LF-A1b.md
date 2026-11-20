# LF-A1b — Shell: StoreBusy never surfaces to the user; debounce races (LF-A1 review minors)

- Phase: P10 local-first · Model: opus · Risk: med
- Deps: LF-A1, LF-A2
- Branch: t/LF-A1b
- Owned paths (only edit these): android/src/main/kotlin/kotobaya/android/shell/api/LocalApi.kt (exception mapping only), android/src/main/kotlin/kotobaya/android/shell/api/LocalWrites*.kt (retry), android/src/main/kotlin/kotobaya/android/sync/SyncFeature.kt, android/src/main/kotlin/kotobaya/android/sync/SyncWork.kt, tests beside them
- Module(s) for CI: android

## Read
- plan/handoffs/LF-A1.md, contracts/api/shell/SHELL.md (503 shell_rebuilding, Retry-After), ADR-050

## Do (LF-A1 review minors, each with a failure scenario)
1. `StoreBusyException` (writer lock wait > 5 s: Rebuild.reapply, StartupReplay, large bulkCards, Remint) reaches the SPA as 500, and the grade is refused with an error. Fix: retry the local write once inside the write path; if it is still busy, map it to `503 shell_rebuilding` + `Retry-After: 2` (the web outbox retries 503). The user must never see an error for a grade.
2. SyncFeature.localChange race: increment `changes` before `requestDebounced`, or cancel the backstop inside the lock, so a change landing in the gap keeps its backstop.
3. SyncWork.requestDebounced: use APPEND_OR_REPLACE so a RUNNING LOCAL request that pushed before the new change doesn't swallow it.
4. Add the `LOCAL_CHANGE` SyncTrigger value if it's in owned paths; otherwise leave REVIEW_SESSION and note it.

## Acceptance (each bullet → at least one named test)
- [ ] An answer submitted while a > 5 s transaction holds the lock is retried and then returns 503 + Retry-After. Never 500.
- [ ] 2 and 3 each have a test that fails before the fix.

## Out of scope
Web outbox (LF-W1); response cache (LF-A2b).
