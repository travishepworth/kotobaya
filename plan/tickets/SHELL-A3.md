# SHELL-A3 — Shell activity: WebView host, asset loader, bridge transport, blob intercept

- Phase: SHELL · Model: opus · Risk: high
- Deps: SHELL-A1, SHELL-A2, SHELL-C1
- Branch: t/SHELL-A3-webview-host
- Owned paths (only edit these): android/src/main/kotlin/kotobaya/android/shell/web/**, android/src/main/kotlin/kotobaya/android/MainActivity.kt, android/src/main/kotlin/kotobaya/android/legacy/LegacyActivity.kt (new: today's Compose MainActivity body moved verbatim), android/src/main/AndroidManifest.xml, android/src/main/res/values/strings.xml (shell strings), android/build.gradle.kts (androidx.webkit), android/src/test/kotlin/kotobaya/android/shell/web/**
- Module(s) for CI: android
- Gradle-heavy: yes · First phone test: yes

## Read
- Spec: Android app; Web app; Sync and offline
- Design: docs/ANDROID-SHELL.md (ADR-036) — sections 1-3
- Contracts: contracts/api/shell/SHELL.md (bridge, security), bridge.schema.json
- Decisions: ADR-036

## Do
- `MainActivity` hosts one WebView loading `https://appassets.androidplatform.net/<path>`; `WebViewAssetLoader` path handler serves
  `assets/web/**` with correct MIME types and answers extension-less paths with `index.html`.
- `BridgeTransport`: `addWebMessageListener("KotobayaShell", {origin})`; decodes `req`, calls `LocalApi.handle` on `Dispatchers.IO`,
  posts `res` on the main thread; drops duplicate ids; enforces the 4 MiB / JSON-only limit; requires `isMainFrame`; ignores unknown `t`.
  Sends `event{hello}` when `ready` arrives. Exposes `send(message)` for A4/A6.
- `shouldInterceptRequest`: `GET /api/v1/blobs/{hash}` → `BlobStore` file (call `played`), miss + online → `BlobDownloader`, else 404. Nothing else is intercepted for `/api/`.
- Settings: JS on, DOM storage on, file/content access off, `mediaPlaybackRequiresUserGesture=false`, safe browsing default, text zoom 100;
  external origins open in the browser; WebView state and last path saved/restored; renderer crash (`onRenderProcessGone`) recreates the WebView.
- `WEB_MESSAGE_LISTENER` unsupported → native message screen (i18n string).
- Manifest: `MainActivity` stays LAUNCHER; add `LegacyActivity` with its own launcher entry labelled "Kotobaya (old)". Insets: pad for system bars + IME.
- New dependency `androidx.webkit:webkit` (state why in HANDOFF.md).

## Acceptance (each bullet → at least one named test)
- [ ] `BridgeCodecTest` (JVM): every example in contracts/api/shell/examples/bridge decodes; invalid examples are ignored without a reply; duplicate id answered once
- [ ] `BridgeTransportTest` (Robolectric): a message from a non-main frame or other origin is not dispatched; oversize body → 415 shell_unsupported
- [ ] `AssetPathTest`: `/review`, `/decks/abc` → index.html; `/_app/x.js` → that file; `/api/v1/notes` is never served from assets
- [ ] `BlobInterceptTest`: cached hash → 200 with bytes and content type; uncached + offline → 404
- [ ] Manual (HUMAN_CHECKS): APK opens to the SPA Today screen in airplane mode

## Out of scope
Deep links, back, haptics, theme (A4). Shell ops and events other than hello (A6). Deleting Compose (Z1).

## Notes from previous attempts
