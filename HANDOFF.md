# HANDOFF SHELL-A5

STATUS: DONE
BRANCH: t/SHELL-A5-proxy (from t/SHELL-A3-webview-host)
CHECK: `make check MODULE=android` → green

## Changed (≤ 6 lines)
- `shell/proxy/ServerUpstream.kt`: P-1..P-3 on OkHttp (own client, redirects followed by hand without Authorization, 12 s budget, 8 MiB/text-only → 415, header whitelist of SHELL.md 3.3, dot-segment paths → 404 with no network).
- `shell/proxy/ProxySync.kt`: `CoordinatorProxySync` — push/pull via the single-flight `SyncCoordinator` (push skipped when nothing is pending), cursor = `pull_seq`.
- `ShellModule.kt`: binds `Upstream` to `ServerUpstream` (SettingsStore URL, TokenStore, NetworkMonitor).

## Acceptance → tests
- bearer/cookie/CSRF; 401/403/422 byte-identical → `ServerUpstreamTest.bearerIsAdded…`, `problemsPassThroughByteIdentical401And403And422WithRetryAfter`
- Offline → `offlineAnswers503ShellOfflineWithZeroRequestsAndNoPush`, `connectFailure…`, `noCompleteAnswerWithinTheCallBudget…`
- Push first / held for seq / stale → `pendingOpsArePushedBeforeAForwardedGet`, `aMutatingReplyWithKotobayaSeqReturnsOnlyAfterThePullCursorPassedIt`, `aPullThatNeverReachesTheSeqSetsTheStaleHeaderAfterTheBudget`
- Revoked token → `RevokedTokenTest` (real TokenStore + real engine push over HTTP)

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Unregistered `local` ops are already forwarded by A2's `LocalApi` (not this ticket), so the APK gets them online. SHELL.md L-1 says `local` is always local; this is a temporary deviation until L4–L8 land, not contract-sanctioned.
- Query is re-encoded from the decoded map (interleaved repeated names lose their relative order). A proxy push/pull runs a full coordinator cycle.
