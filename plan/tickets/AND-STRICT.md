# AND-STRICT — main-thread disk I/O (StrictMode) on Android

- Phase: P9 hotfix 0.1.11 · Model: sonnet · Risk: low
- Deps: AND-PERF (overlapping android paths; branch from main after it merges)
- Branch: t/AND-STRICT
- Owned paths (only edit these): android/app/src/main/** files named below + their DI wiring, tests
- Module(s) for CI: android

## Evidence (phone logcat 0.1.10, user 10)
StrictMode DiskReadViolation on main thread: ReviewFeature.kt:51 getSharedPreferences; MediaFeature.kt:57 → BlobStore.<init> (BlobStore.kt:26) mkdirs during Koin injection inside composition; StruggleFeature.kt:51 getFilesDir; widget KotobayaWidget.kt:55/113 1.75 s tz-data load; "Choreographer skipped 32 frames".

## Do
Move these to Dispatchers.IO / lazy init off the main thread (e.g. BlobStore dirs created lazily on first IO use; prefs read in a coroutine; tz via java.time ZoneId cached off-main). No behaviour change.

## Acceptance
- [ ] make check MODULE=android green. (UI glue; no new tests required beyond keeping existing ones green.)
