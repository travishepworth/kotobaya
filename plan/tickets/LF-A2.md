# LF-A2 — Shell: non-blocking upstream, `cached` class with response cache, no-network invariant test

- Phase: P10 local-first · Model: opus · Risk: high
- Deps: C-31
- Branch: t/LF-A2-cached-upstream
- Owned paths (only edit these): android/src/main/kotlin/kotobaya/android/shell/proxy/, shell/api/LocalApi.kt (dispatch for `cached`), shell/api/ApiOperations.kt (class enum), shell/events/ShellEvents.kt (`cache.updated`), new shell/cache/, android/src/main/sqldelight (generated from the contract DDL only), tests under android/src/test/kotlin/kotobaya/android/shell/{proxy,api}/
- Module(s) for CI: android

## Read
- Spec: Sync and offline
- Contracts: SHELL.md 3, 4.2 (P-1…P-4 as amended), K-1…K-4, 2.6
- Decisions: ADR-036, 050
- Design: scratchpad `local-first-design.md` (orchestrator: copy to `docs/design/local-first.md` or paste the named section into this ticket).

## Do
1. `ServerUpstream.forward`: delete the pre-push (P-2) and the pull hold (P-3); a `2xx` whose `Kotobaya-Seq` is ahead of the cursor is returned at once with `kotobaya-shell-stale: 1` and a background pull is requested.
2. `cached` dispatch by K-1…K-4: `ResponseCache` over the device table (key, body, content-type, fetched_at; 16 MiB LRU); hit → immediate answer + background revalidation; miss → immediate `503 shell_uncached` + one background fetch; `cache.updated` event only on a changed body; warm list after each successful cycle; invalidation hook on `sync.changed`.
3. `UiPathNeverTouchesNetworkTest`: an `Upstream` that fails the test when called; drive every `local` operation of `ops.json` with a minimal valid request over a seeded replica, and every `cached` one with and without a cache row.

## Acceptance (each bullet → at least one named test)
- [ ] `UiPathNeverTouchesNetworkTest` green for every `local` and `cached` operationId (generated from `ops.json`, so a new operation cannot be forgotten).
- [ ] Test: cached hit answers without the upstream; revalidation updates the row and emits exactly one `cache.updated`; identical body emits none.
- [ ] Test: remote write returns without waiting for the cursor and carries `kotobaya-shell-stale: 1`.
- [ ] Test: offline → `cached` hit still answers; miss is `503 shell_uncached`; `remote` is `503 shell_offline` with no network attempt.
- [ ] `make check MODULE=android` green.

## Out of scope
New local handlers (LF-A3…A6); SPA handling of `shell_uncached` (LF-W2).

## Notes from previous attempts
