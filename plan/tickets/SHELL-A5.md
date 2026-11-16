# SHELL-A5 — Server proxy for non-local operations

- Phase: SHELL · Model: opus · Risk: med
- Deps: SHELL-A2
- Branch: t/SHELL-A5-proxy
- Owned paths (only edit these): android/src/main/kotlin/kotobaya/android/shell/proxy/**, android/src/main/kotlin/kotobaya/android/shell/ShellModule.kt (bind Upstream), android/src/test/kotlin/kotobaya/android/shell/proxy/**
- Module(s) for CI: android
- Gradle-heavy: yes · First phone test: no

## Read
- Spec: Android app; Web app; Sync and offline
- Design: docs/ANDROID-SHELL.md (ADR-036) — sections 5
- Contracts: contracts/api/shell/SHELL.md (P-1..P-4), openapi.yaml section 3 (`Kotobaya-Seq`)
- Decisions: ADR-012, ADR-036

## Do
`ServerUpstream : Upstream` on the existing OkHttp client and `TokenStore`.
- P-1: `NetworkMonitor` offline or `IOException` → `503 shell_offline` (no retry).
- P-2: before forwarding run one bounded push of pending ops (≤ 5 s, failure ignored). Drop `cookie`, `authorization`, `x-kotobaya-csrf`; add the bearer; pass `x-request-id`.
- P-3: on 2xx with `Kotobaya-Seq: n`, pull until the cursor ≥ n (≤ 10 s) before answering; on timeout add `Kotobaya-Shell-Stale: 1`.
- Server status, problem body and `Retry-After` pass through unchanged, including 401 and 403. Response body limit 8 MiB text; larger or non-text → `415 shell_unsupported`.
- The device token is never logged and never returned in a header.

## Acceptance (each bullet → at least one named test)
- [ ] `ServerUpstreamTest` (MockWebServer): bearer added, cookie/CSRF stripped; 401/403/422 bodies pass through byte-identical
- [ ] Offline → 503 shell_offline with zero requests sent
- [ ] Pending ops are pushed before a forwarded GET; a mutating reply with `Kotobaya-Seq` returns only after the pull cursor passed it; timeout sets the stale header
- [ ] Auth: a revoked token (server 401) is surfaced as 401 and the stored pairing and pending ops are untouched

## Out of scope
Binary uploads, exports, SSE relay of server job events.

## Notes from previous attempts
