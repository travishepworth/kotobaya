# HANDOFF SHELL-A3

STATUS: DONE
BRANCH: t/SHELL-A3-webview-host (from t/SHELL-L3-local-answer)
CHECK: `make check MODULE=android` → green

## Changed (≤ 6 lines)
- shell/web/: BridgeCodec (schema-exact decode/encode), BridgeCore (origin/main-frame filter, dedupe, page lives, ready→hello→pending navigate, `send`), BridgeTransport (WebMessageListener), AssetPaths/NavPath, BlobIntercept, ShellWebClient, ShellWebHost, ShellWiring
- MainActivity = WebView host; old body verbatim in legacy/LegacyActivity ("Kotobaya (old)" launcher, own taskAffinity)
- build.gradle.kts: `ignoreAssetsPattern` without `<dir>_*`. AAPT was silently dropping `web/_app/`, so the SPA had no JS. Both APKs now contain all 439 files
- Outside owned paths (needed): 4 Compose tests switched from `MainActivity` to `LegacyActivity`

## Acceptance → tests
- examples decode / invalid ignored / dup id once → BridgeCodecTest
- non-main-frame, other origin, 415 → BridgeTransportTest
- asset paths → AssetPathTest; blobs → BlobInterceptTest
- airplane-mode Today → HUMAN_CHECKS (manual)

## New dependencies
- androidx.webkit:webkit@1.12.1, for WebViewAssetLoader and addWebMessageListener (already in the Gradle cache)

## For the reviewer (≤ 3 lines)
- `pairShell` has no route yet. On a fresh install, pair through "Kotobaya (old)". After that the SPA is signed in via `getPrincipal`.
- A new page life starts on `onPageStarted`. Back still finishes the activity until A4 handles it.
