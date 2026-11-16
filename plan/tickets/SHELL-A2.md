# SHELL-A2 — Local API kernel: dispatcher, route table, problems, group stubs

- Phase: SHELL · Model: opus · Risk: med
- Deps: SHELL-C1
- Branch: t/SHELL-A2-kernel
- Owned paths (only edit these): android/src/main/kotlin/kotobaya/android/shell/api/*.kt (kernel files only), android/src/main/kotlin/kotobaya/android/shell/api/<group>/Routes.kt stubs for identity, review, actions, mining, browse, decks, struggle, device, android/src/main/kotlin/kotobaya/android/shell/ShellModule.kt, android/src/main/kotlin/kotobaya/android/di/FeatureModules.kt (one line), android/src/test/kotlin/kotobaya/android/shell/api/**
- Module(s) for CI: android
- Gradle-heavy: yes · First phone test: yes

## Read
- Spec: Android app; Web app; Sync and offline
- Design: docs/ANDROID-SHELL.md (ADR-036) — sections 1, 5
- Contracts: contracts/api/shell/SHELL.md, ops.json, openapi.yaml (sections 1-3, Problem)
- Decisions: ADR-012, ADR-036

## Do
Pure Kotlin, no Android framework types (must run in plain JVM tests).
- `ShellRequest(method, path, query: Map<String, List<String>>, headers, body: String?)`, `ShellResponse(status, headers, body: String?)`.
- `LocalRoute(operationId, method, pathTemplate, handler: suspend (LocalCall) -> ShellResponse)`; `LocalCall` gives path params,
  typed query access with the server's unknown-parameter `422`, body parsed to core `JsonValue` with unknown-property `422`.
- `LocalApi.handle`: match → mode from ops.json (bundled as a resource, copied at build from contracts) →
  `local`: handler; `local_or_proxy`: handler, which may return `Fallthrough`; `proxy`: `Upstream.forward`; `hidden`: `403 insufficient_scope`;
  no match: `404 not_found`. Unpaired gate (L: everything except `/shell/v1/info`, `/shell/v1/pair` → `401 unauthenticated`).
  Readiness gate (`awaitReady`), rebuild gate (`503 shell_rebuilding`). Adds `Kotobaya-Local: 1` to local answers.
- `Problems`: RFC 9457 builder identical in shape to the server's (`type,title,status,code,detail,errors[],request_id`).
- `Upstream` interface with a default implementation that always answers `503 shell_offline` (SHELL-A5 replaces it).
- `LocalWrites`: the single helper every writing handler uses: runs `block` in one `store.transaction`, mints through `OpMinter.mintAll`,
  maps `MintRefusal` to problems (L-3), then calls `SyncTriggers.localChange()` after commit. No handler touches tables directly (CLAUDE rule 8).
- One `Routes.kt` per group exposing `val routes: List<LocalRoute> = emptyList()`; the kernel collects them. Later tickets edit only their own group.

## Acceptance (each bullet → at least one named test)
- [ ] `RouteTableTest`: every operationId marked `local`/`local_or_proxy` in ops.json is either registered or listed in a shrinking `PENDING` set; no route is registered for a `proxy`/`hidden` id
- [ ] `DispatchTest`: hidden → 403 insufficient_scope; proxy with default Upstream → 503 shell_offline; unpaired → 401; unknown query parameter and unknown body property → 422 validation_failed with pointer
- [ ] `LocalWritesTest`: a refused mint leaves the store unchanged and enqueues nothing; a committed write enqueues exactly the minted ops and triggers sync once
- [ ] `make check MODULE=android` green

## Out of scope
Any real handler (L tickets). WebView code (A3). Proxy (A5).

## Notes from previous attempts
