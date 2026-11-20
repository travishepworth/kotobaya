# LF-A2b — Shell response cache: review minors from LF-A2

- Phase: P10 local-first · Model: opus · Risk: med
- Deps: LF-A2
- Branch: t/LF-A2b
- Owned paths (only edit these): android/src/main/kotlin/kotobaya/android/shell/cache/, android/src/main/kotlin/kotobaya/android/shell/proxy/ProxySync.kt, android/src/main/kotlin/kotobaya/android/shell/events/ShellEvents.kt (hook fallback only), tests under android/src/test/kotlin/kotobaya/android/shell/{cache,proxy}/
- Module(s) for CI: android

## Read
- plan/handoffs/LF-A2.md, contracts/api/shell/SHELL.md K-1..K-4, ADR-050

## Do (from the LF-A2 review, all minors with failure scenarios)
1. Test that `ResponseCache.fromServices` with all four Koin parts is non-null (silent null => every cached op 503 shell_uncached forever).
2. `settle`: generation check + store write under `lock`; `clear()` takes the same lock (unpair/re-pair race lets old pairing's body survive).
3. `settle`: keep `stale=1` if the row was marked stale after the fetch started (revalidation must not clear a local write's stale mark).
4. Background sync with no WebView: stale/rebuild marks must reach stored rows (ShellEventHub marks entries stale via the DB when no hooks are set).
5. ProxySync.kt:34: use SyncTrigger.MANUAL (not PUSH) and rethrow CancellationException.

## Acceptance (each bullet → at least one named test)
- [ ] 1–5 each have a test that fails before the fix.

## Out of scope
STILL_FORWARDS handlers (LF-A3..A8); strict no-network pass (LF-X1).
