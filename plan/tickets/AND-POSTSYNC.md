# AND-POSTSYNC — Android: after a successful sync, "nothing really loads"

- Phase: P10 · Model: opus (writer-senior) · Risk: high (sync/replay gate) → reviewer
- Deps: C-22 merged (core queue)
- Branch: t/AND-POSTSYNC
- Owned paths: android/** (no core/contracts edits; CONTRACT_CHANGE_REQUEST if needed)
- Module(s) for CI: android

## Evidence (live 0.1.9, run 21 scout)
- The Android token "Pixel 9 Pro" made 98 GET /api/v1/sync/pull and 5 GET /api/v1/meta between 00:09Z and 00:32Z, all 200. There were no /sync/push calls and no other Android routes (no /cards, /dict/*, /drill). No server errors. So the failure is client-side: screens never issue their requests, or they fail locally.
- ApiClient (net/ApiClient.kt:34-41) and HttpSyncTransport share the base URL and token, and /meta succeeds, so auth and URL are probably fine. ApiClient refuses requests when it thinks the device is offline (ApiClient.kt:35).
- The user also reported a crash, with no log (HUMAN_CHECKS), possibly related.

## Hypotheses to test, in order
1. Readers are gated on startup or deferred replay (BUG-8 chunked pull + deferred replay per chunk). After a large pull (~100k reviews) the gate never opens, or opens only after a very long replay, so every screen stays loading. Is 98 pulls in 23 min a loop that never completes (pull_seq not advancing, or re-pulling from 0)?
2. The offline flag sticks after sync, so ApiClient refuses every online call.
3. A crash or OOM in replay or a reader kills the work, and the retry loops.
Reproduce in Robolectric/JVM tests with a large pull (use the 100k pull bench from BUG-8 or demo data). Check that sync terminates, the gate opens, and Home/Decks/Search/Review load.

## Acceptance
- [ ] Root cause named with evidence. A test that fails before the fix: a large multi-chunk pull, then the gate opens and the screen state loads.
- [ ] If sync is busy, screens show progress, not an endless blank.
- [ ] make check MODULE=android green.
