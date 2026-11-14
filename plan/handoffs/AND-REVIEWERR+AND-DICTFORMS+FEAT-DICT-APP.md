# HANDOFF OBS-1c-and

STATUS: DONE
BRANCH: t/OBS-1c-and
CHECK: `make check MODULE=android` → green

## Changed (≤ 6 lines)
- New `android/.../report/`: `ClientErrorQueue` (pre-validate/truncate per item, dedupe via count, ≤100 persisted, dropped counter, 20-batches, 413 split, 429 backoff, 4xx drop), `ClientErrors` sink, `CrashReportHandler` (durable write, chains to previous handler, sent on next start), `ClientErrorFlusher`, `reportFeature`.
- Hooks: `ApiClient`/`HttpSyncTransport` (5xx, network), `StateViewModel` failures, nav screen name (route pattern), flush on app start and after a sync that reached the server. `/client-errors` failures never reported. Messages scrubbed (quoted text removed), stacks have no messages. Tags: KotobayaLoad/Sync/Report. Main merged in for C-23.
- Review fixes (separate commit): Review onFailure cancels sessionJob; requeue start() failure shows Failed; Struggle write failure marks only the issuing pane; `LocalCardSearch(dictFormsMaxSeqs)` + cap test.

## Acceptance → tests
- queue cap/dedupe/413 split/no-recursion/batching/scrub → `ClientErrorQueueTest`; cap path → `DictFormsSearchTest`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Used the app's org.json `ApiClient`, not the generated Kotlin client. Crash path uses SharedPreferences `commit()` on the dying thread (needed to persist).
- Base branch not yet in main, so no rebase done; this branch = base + merge of main + 2 commits.
