# Android web shell — contract (SHELL-C1, ADR-036)

Normative text for the Android WebView shell: the bridge between the SPA and native code, how every
API operation is answered on the phone, the shell-only operations, navigation and back, shipping
and versioning, and the server-versus-shell parity scenarios. Design rationale: `docs/ANDROID-SHELL.md`.

This contract is **additive**. It changes nothing in `contracts/api/openapi.yaml`, `contracts/sync`,
`contracts/db` or `contracts/core`; where this file and one of those disagree, that file wins and this
one has a bug (return `CONTRACT_CHANGE_REQUEST`).

| File | What |
|---|---|
| `SHELL.md` | This file. |
| `bridge.schema.json` | JSON Schema of every bridge message; `examples/bridge/` (valid), `examples/bridge-invalid/` (must be rejected). |
| `shell.yaml` | OpenAPI 3.1 of the 18 shell-only operations under `/shell/v1`; generated into `clients/typescript-shell`. |
| `ops.json` | `{operationId: class}` for every operationId of `openapi.yaml`: `local`, `local_or_proxy`, `proxy` or `hidden`. A flat object, no other keys. Read by the Android route-table test and by the SPA. |
| `parity.schema.json`, `parity/*.json` | Parity scenario inputs (section 8). Goldens are recorded by SHELL-P1 into `fixtures/shell-parity/golden/`. |
| `check_shell.py` | Run by `contracts/api/check.sh`. |

Words: **native** / **the shell** = the Android app. **SPA** = the SvelteKit build running in the
shell's WebView. **Server** = the Ktor API. **Replica** = the phone's SQLDelight database
(`SqlDelightDeviceStore`). **Paired** = a device token is stored in `TokenStore`.
MUST / MUST NOT / SHOULD / MAY as in RFC 2119.

## 1. Model

The SPA is loaded from `https://appassets.androidplatform.net/` (`WebViewAssetLoader`), so it is
same-origin with its own API base `/api/v1` and no CORS or cookie is involved. It is the same build
nginx serves; shell mode is detected at run time (section 9.3), never by a build flag.

Every API call of the SPA reaches one transport-free dispatcher,
`LocalApi.handle(ShellRequest): ShellResponse`, through one of two entry points:

| Entry point | Mechanism | Carries |
|---|---|---|
| Bridge (section 2) | `WebViewCompat.addWebMessageListener(webView, "KotobayaShell", setOf("https://appassets.androidplatform.net"))`; the SPA passes `fetchApi: shellFetch` to both generated `Configuration`s (`clients/typescript`, `clients/typescript-shell`) | every generated-client call, events, navigation, back, haptics, theme |
| Intercept (section 2.7) | `shouldInterceptRequest` | `GET /api/v1/blobs/{hash}` only (`<audio src>`, `<img src>`) |

The dispatcher answers a request locally from the replica, forwards it to the server with the
device token, or refuses it, as `ops.json` says (section 3). There is no socket and no HTTP server
on the phone.

## 2. Bridge

### 2.1 Transport and security

- The listener object is `window.KotobayaShell`. It exists before any page script runs (the
  WebView injects it for the allowed origin). The SPA sends with
  `window.KotobayaShell.postMessage(text)` and receives through `window.KotobayaShell.onmessage`
  (`event.data` is the text).
- Native MUST dispatch a message only when its source origin is exactly
  `https://appassets.androidplatform.net` **and** `isMainFrame` is true. Anything else is dropped
  without a reply.
- Native MUST NOT use `addJavascriptInterface`. WebView settings: `allowFileAccess = false`,
  `allowContentAccess = false`, mixed content never allowed. `shouldOverrideUrlLoading` sends every
  navigation to another origin to the system browser.
- The device token never enters JavaScript: it is in no message, no header of a `res`, no URL.
- `WebViewFeature.WEB_MESSAGE_LISTENER` not supported: native shows its own "Update Android System
  WebView" screen (i18n string) instead of loading the SPA.

### 2.2 Messages

Every message is one JSON text with `v: 1` and a type `t`. Schema: `bridge.schema.json`
(`#/$defs/WebToNative`, `#/$defs/NativeToWeb`).

| Direction | `t` | Fields besides `v`, `t` | Meaning |
|---|---|---|---|
| web → native | `req` | `id`, `method`, `path`, `query`, `headers`, `body` | One API request. |
| native → web | `res` | `id`, `status`, `headers`, `body` | Its response. |
| web → native | `ready` | `web_version`, `min_local_api` | The SPA has hydrated and listens. |
| native → web | `event` | `event` (`type`, `at`, `data`) | One event (section 2.6). |
| native → web | `navigate` | `path` | Go to an SPA route (section 6.1). |
| native → web | `back` | `id` | Back was pressed (section 6.2). |
| web → native | `back.result` | `id`, `handled` | Outcome of that `back`. |
| web → native | `back.state` | `can_handle` | Whether the SPA has something to do on Back. |
| web → native | `haptic` | `kind` | Haptic effect (section 6.3). |
| web → native | `theme` | `dark`, `surface` | System-bar colours (section 6.3). |

Fields (constraints are those of the schema; this table adds the meaning):

| Message | Field | Type, constraints | Definition | Example |
|---|---|---|---|---|
| all | `v` | integer, const `1` | Bridge protocol version. | `1` |
| all | `t` | string, dotted lower-case identifier, ≤ 40 chars | Message type. | `"req"` |
| `req`, `res` | `id` | lower-case UUID text (any version) | Correlation id, chosen by the SPA, unique among the ids of the current page load. | `"7b1e3c52-9d0a-4f6e-8a51-2f4c8d1e9a01"` |
| `req` | `method` | `GET`, `POST`, `PUT`, `PATCH`, `DELETE` | HTTP method, upper case. `HEAD` and `OPTIONS` are never sent. | `"POST"` |
| `req` | `path` | string ≤ 2048; starts `/api/v1/` or `/shell/v1/`; RFC 3986 path characters; no `?`, `#`, `//`, no `.`/`..` segment, no `%2e`, `%2f`, `%5c`, `%00` | Path exactly as the generated client built it, path parameters percent-encoded. | `"/api/v1/review/answer"` |
| `req` | `query` | string ≤ 8192; RFC 3986 query characters; does not start with `?` | Raw query string, `""` when none. Parsed by native exactly as the server parses a URL query (percent-decoding, `+` is a literal plus). | `"limit=20&keep_going=true"` |
| `req`, `res` | `headers` | object, ≤ 64 members; names lower-case tokens ≤ 64; values single-line strings ≤ 8192 | Header fields. May be `{}`. | `{"content-type":"application/json"}` |
| `req` | `body` | string or null | Body text. MUST be null for `GET` (native ignores a body on `GET`). | `"{\"rating\":3}"` |
| `res` | `status` | integer 200–599 | HTTP status. Never a redirect the SPA would have to follow (the shell follows redirects itself when proxying). | `200` |
| `res` | `body` | string or null | Body text; null for `204`/`304` and for an empty body. | `"{\"items\":[]}"` |
| `ready` | `web_version` | string 1–64 | `web_version` of the bundle's `shell-manifest.json`. | `"0.1.26"` |
| `ready` | `min_local_api` | integer ≥ 1 | Lowest `LOCAL_API_VERSION` the bundle works with. | `1` |
| `event` | `event` | object: `type` string, `at` RFC 3339 UTC ms, `data` object | The `Event` envelope of `GET /events`. | section 2.6 |
| `navigate` | `path` | string ≤ 2048, `^/[A-Za-z0-9\-._~/?&=%+]*$`, no `//` | SPA path with optional query. | `"/dictionary?seq=1358280"` |
| `back`, `back.result` | `id` | lower-case UUID text | Chosen by native per back press. | `"0d6f2a10-51c3-4e7b-9c1d-5a2b7e9f0b01"` |
| `back.result` | `handled` | boolean | true = the SPA consumed the press. | `true` |
| `back.state` | `can_handle` | boolean | false only when the SPA would answer `handled: false`. | `false` |
| `haptic` | `kind` | `tick`, `confirm`, `reject` | Which effect. | `"confirm"` |
| `theme` | `dark` | boolean | true = dark theme (light bar icons). | `true` |
| `theme` | `surface` | `#rrggbb` | Colour of the status and navigation bars. | `"#101418"` |

Worked example (one review answer, answered locally):

```json
{"v":1,"t":"req","id":"7b1e3c52-9d0a-4f6e-8a51-2f4c8d1e9a02","method":"POST","path":"/api/v1/review/answer","query":"","headers":{"content-type":"application/json"},"body":"{\"id\":\"01931f41-0000-7000-8000-000000000f01\",\"card_id\":\"01931f40-1a2b-7c3d-8e4f-000000000c01\",\"rating\":3,\"button\":\"good\"}"}
{"v":1,"t":"res","id":"7b1e3c52-9d0a-4f6e-8a51-2f4c8d1e9a02","status":200,"headers":{"content-type":"application/json","kotobaya-local":"1","x-request-id":"0193a1b2-7000-7000-8000-00000000e002"},"body":"{\"review\":{...},\"card\":{...}}"}
{"v":1,"t":"event","event":{"type":"sync.changed","at":"2026-11-20T10:00:05.120Z","data":{"high_water_seq":48213,"pending_ops":1}}}
```

### 2.3 Compatibility

- **Unknown `t`:** the receiver drops the message silently. **Unknown properties:** ignored.
  New message types and new optional properties are therefore additive (section 7).
- **Invalid message** (not JSON, not an object, `v` ≠ 1, a known `t` whose required fields are
  missing or violate the schema): dropped silently, **no reply** — also for a `req` (a correct SPA
  never sends one; its 30 s timeout turns the bug into a `FetchError`). Native logs it at WARN
  without the body.
- Senders MUST only send messages valid against their directional schema.

### 2.4 Limits and what the bridge cannot carry

Checked by native for every valid `req`, in this order, before routing. Each is answered with a
`res` (`415`, problem `shell_unsupported`, section 5), nothing is executed:

1. `body` is not null and its UTF-8 encoding is longer than **4 MiB** (`4194304` bytes).
2. `body` is not null and the media type of `headers["content-type"]` (the part before `;`,
   trimmed, lower-cased) is not `application/json`. A missing `content-type` with a non-null body
   also fails. Consequence: `putBlob` and every binary upload are unavailable in the shell.

And for responses:

3. The response of a proxied operation (section 4.2) is longer than **8 MiB** (`8388608` bytes),
   or its media type is not `application/json`, `application/problem+json` or `text/*`: the shell
   answers `415 shell_unsupported` instead and discards the upstream body. The upstream call has
   happened; for the affected operations (`exportAll`, `exportAnki`, `getBlob` through the bridge)
   that is a read. Consequence: exports are unavailable in the shell; the SPA hides them.
4. `getBlob` sent as a `req`: always `415 shell_unsupported` (blobs travel only through the
   intercept, section 2.7).

There is no limit on the number of concurrent requests; native MUST accept at least 32 in flight.

### 2.5 Invariants

- **One `res` per `req`.** Every valid `req` whose `id` is new gets exactly one `res` with that
  `id`, whatever happens (handler exception → `500 internal`).
- **Duplicate `id`.** A `req` whose `id` was already seen in the current page load is dropped
  silently: not executed again, no second `res`. (Retries use a new bridge `id`; the API-level
  idempotency ids — `answerReview.id`, create `id`s — are what make a retry safe.)
- **Ordering.** Requests run concurrently on `Dispatchers.IO`. A `res` is posted on the main
  thread when its request completes: completion order, **not** request order. The SPA matches by
  `id` only. Messages other than `res` are delivered in the order they were posted. There is no
  ordering guarantee between the `res` of a write and the `sync.changed` event it causes.
- **Readiness.** A `req` that arrives before the startup gate has opened (`SyncBootstrap.ready`:
  the startup replay of SYNC.md 10.5) **waits**; it is not rejected. Exempt (answered at once):
  `getShellInfo`, `pairShell`, `unpairShell`, `getSyncStatus`, `syncNow` — the same five that
  rule L-8 exempts from `shell_rebuilding`; none of them reads the replica's learning data (`unpairShell`
  counts `pending_op` rows, which the startup replay does not touch). If the gate is still closed 20 s after the `req`
  arrived, native answers `503 shell_rebuilding` with `Retry-After: 2` (so the SPA's 30 s timeout
  is never what the user sees).
- **Timeout.** The SPA rejects a request with `FetchError` (`TypeError`) when no `res` arrived
  within 30 s, and forgets the `id`; a `res` for an unknown `id` is ignored.
- **Reload.** A page load (reload, renderer restart, OTA activation) starts a new page life:
  native forgets the set of seen ids, drops every `res` of the previous page life instead of
  posting it, and treats the SPA as not ready until the next `ready`. Requests already running are
  **not** cancelled: a write whose `res` was dropped may have been applied. The SPA refetches what
  it shows after loading (the `hello` event, section 2.6), exactly as after an SSE reconnect.
- **`ready`.** Sent by the SPA exactly once per page life, after hydration. `req` messages may be
  sent before it. On `ready` native: (1) checks `min_local_api` (section 7.2); (2) sends
  `event{hello}`; (3) sends the pending `navigate`, if an intent arrived while the page loaded
  (section 6.1). Native sends no `event`, `navigate` or `back` before `ready`.

### 2.6 Events

`EventSource` is not used in shell mode: the SPA's `openEventStream` subscribes to `event`
messages and applies its `types` filter itself (`hello` always passes). Payload schemas:
`shell.yaml` `ShellEvent*`.

| `event.type` | `data` | Sent when |
|---|---|---|
| `hello` | `ShellEventHello` | Once per page life, in answer to `ready`. The SPA treats it as "(re)connected: refetch what is shown". |
| `sync.changed` | `ShellEventSyncChanged` | Learning data in the replica changed: after every committed local write (section 4.1 rule L-2), after every pull page that applied at least one op, after a rebuild finished. Coalesced to at most one per second; the last state is always delivered. |
| `note.created` | `ShellEventNoteCreated` | A `note.created` op was applied that the SPA did not cause: AnkiProvider / Takoboto, on-device AnkiConnect, or a pulled op. Once per note. Not sent for `createNote` calls of the SPA itself or for C-27 materialization. |
| `shell.connectivity` | `ShellEventConnectivity` | `NetworkMonitor` changed `online`. |
| `shell.sync` | `ShellSyncStatus` | Any property of `getSyncStatus` changed. At most one per second; the last state is always delivered. |
| `shell.dict` | `ShellDictInstall` | Any property of `getDictInstall` changed. At most two per second while an install runs; the final state is always delivered. |

Differences from the server's stream, all deliberate: `hello.high_water_seq` and
`sync.changed.high_water_seq` are the **device's pull cursor**, may be `0`, and do not move on a
local write; `hello.server_time` is the device clock; `hello.log_epoch` is the nil UUID until known;
`note.created.seq` is `0` for a note minted on this device. `job.updated`, `device.paired` and
`device.revoked` are never sent: jobs are observed by polling the proxied `listJobs` / `getJob`
while online. Delivery is best effort; there is no replay.

### 2.7 Blob intercept

`shouldInterceptRequest` answers `GET https://appassets.androidplatform.net/api/v1/blobs/{hash}`
(`getBlob`) and nothing else under `/api/`:

| Case | Answer |
|---|---|
| `{hash}` is not 64 lower-case hex digits | `404`, problem `not_found` |
| Not paired | `401`, problem `unauthenticated` |
| Blob in `BlobStore` | `200`, the bytes, the stored `Content-Type`, `Content-Length`, `Cache-Control: private, max-age=31536000, immutable`; the blob's last-played time is updated |
| Not cached, online | downloaded with the device token (`BlobDownloader`), stored, then as above; download fails → `404 not_found` |
| Not cached, offline | `404`, problem `not_found` |

`Range` is ignored (always the full `200` body); `HEAD`, `302` and `304` do not occur. Any other
request to a URL under `/api/` or `/shell/` that reaches the WebView's network layer (a stray
`fetch`, an `<a href>`) is answered `404 not_found` by the intercept: never served from the bundle
(no SPA fallback under these prefixes) and never forwarded.

## 3. Dispatch

### 3.1 Check order

For every `req` that passed section 2.3–2.5, first match wins:

1. Section 2.4 limits → `415 shell_unsupported`.
2. No operation matches `method` + `path` (templates of `openapi.yaml` under `/api/v1`, of
   `shell.yaml` under `/shell/v1`; a literal segment beats a parameter) → `404 not_found`.
3. Not paired, and the operation is not `getShellInfo`, `pairShell`, `getDisplayPrefs` or `updateDisplayPrefs` → `401 unauthenticated`.
4. Class `hidden` → `403 insufficient_scope` (rule P-4).
5. Class `proxy` → section 4.2.
6. Class `local`, `local_or_proxy`, and shell operations: the readiness gate (2.5), then the
   rebuild gate (rule L-8), then the handler. Inside the handler the server's own order applies
   (`openapi.yaml` section 5): `404`/`410`, then `400`, then `422`, then `409`.

A device token holds `read`, `write` and `review`, and every `local` / `local_or_proxy` operation
needs one of those, so the handler performs no scope check (`check_shell.py` enforces that
premise).

### 3.2 Classes (`ops.json`)

| Class | Count | Answered |
|---|---|---|
| `local` | 38 | By the shell from the replica, online or not (rules L-1 … L-8). |
| `local_or_proxy` | 7 | Dictionary reads: from the installed package or the sidecar cache, else forwarded, else `404 not_found` / `503 shell_dict_missing` (rule L-7). |
| `proxy` | 53 | By the server, through the shell, with the device token (rules P-1 … P-3). |
| `hidden` | 25 | Never: `403 insufficient_scope` without touching the network (rule P-4). |

**`local`:** `getPrincipal`, `getMeta`, `reportClientErrors`, `listSettings`, `updateSettings`,
`listPresets`, `getPreset`, `listNoteTypes`, `getNoteType`, `getBlob` (intercept only, 2.7),
`getReviewQueue`, `answerReview`, `undoReview`, `createDrillSession`, `bulkCards` (see 4.3),
`getCard`, `listCardReviews`, `listCards`, `listNotes`, `createNote`, `getNote`, `updateNote`,
`deleteNote`, `getWordStatus`, `setWordStatus`, `clearWordStatus`, `listDecks`, `createDeck`,
`getDeck`, `updateDeck`, `deleteDeck`, `listDeckItems`, `addDeckItems`, `putDeckItem`,
`removeDeckItem`, `listHotWords`, `listHotKanji`, `listLeeches`.

**`local_or_proxy`:** `searchDictionary`, `getDictEntry`, `listEntryExamples`, `listDictHeadwords`,
`getDictRelease`, `getKanji`, `listKanjiWords`.

**`proxy`:** `getHealth`, `getOwnDevice`, `updateOwnDevice`, `createPreset`, `updatePreset`,
`deletePreset`, `createNoteType`, `updateNoteType`, `deleteNoteType`, `createCard`, `updateCard`,
`deleteCard`, `bulkScheduleCards`, `listDeckLists`, `getDeckCoverage`, `listMedia`, `getMedia`,
`updateMedia`, `deleteMedia`, `getMediaCoverage`, `listMediaSentences`, `listJpdbVocab`,
`listSentences`, `getSentence`, `listWordConfusables`, `listConfusables`, `setConfusable`,
`listGrammarPoints`, `createGrammarPoint`, `getGrammarPoint`, `updateGrammarPoint`,
`deleteGrammarPoint`, `testGrammarRules`, `listGrammarExamples`, `putGrammarSpan`,
`removeGrammarSpan`, `getStatsSummary`, `getStatsHeatmap`, `getStatsRetention`, `getStatsForecast`,
`getStatsKnownWords`, `getStatsCalibration`, `parseText`, `listParseOverrides`, `putParseOverride`,
`deleteParseOverride`, `listJobs`, `getJob`, `synthesizeSpeech`, `queryBlobs`, `putBlob`
(unreachable: 2.4 rule 2), `exportAll`, `exportAnki` (unreachable in practice: 2.4 rule 3).

**`hidden`:** needing `admin` or `import`, which a device token never has — `listDevices`,
`createPairing`, `getDevice`, `updateDevice`, `revokeDevice`, `listTokens`, `createToken`,
`getToken`, `revokeToken`, `optimizePreset`, `refreshDictionary`, `importMedia`, `importJpdbDeck`,
`importJpdbHistory`, `putJpdbVocabResolution`, `clearJpdbVocabResolution`, `reparseMedia`,
`cancelJob`; replaced by something native — `login`, `logout` (pairing, section 9), `pairDevice`
(`pairShell`), `syncPush`, `syncPull` (the sync engine; the SPA must never push or pull),
`streamEvents` (bridge events, 2.6), `ankiConnect` (the on-device server).

`check.sh` fails when `ops.json` misses an operationId of `openapi.yaml`, names one that does not
exist, uses another class, or gives a class other than `hidden` to an operation a device token
cannot call. A new operation in `openapi.yaml` therefore cannot be merged without classifying it.

### 3.3 Headers

| Header (lower-case in `res.headers`) | On | Value |
|---|---|---|
| `content-type` | every `res` with a body | `application/json`, or `application/problem+json` for a problem; proxied: the server's. |
| `kotobaya-local` | every `res` the shell produced itself: `local` answers, `local_or_proxy` answered locally, shell operations, and every problem the shell generates (`401` unpaired, `403` hidden, `404` no route, `415`, `503 shell_*`) | `1`. Never on a response forwarded from the server. |
| `x-request-id` | every `res` | The `x-request-id` of the `req` when it matches `^[A-Za-z0-9._-]{1,64}$`, else a fresh UUIDv7. Proxied: the server's response header. Equals `Problem.request_id`. |
| `kotobaya-seq` | proxied `2xx` only | The server's `Kotobaya-Seq`, unchanged. **Never** on a local answer (a local write has no `change.seq` yet). |
| `kotobaya-shell-stale` | proxied `2xx` whose effect is not yet in the replica (rule P-3) | `1` |
| `retry-after` | `503 shell_rebuilding` (`2`); proxied: the server's | seconds |
| `content-location` | as the server (answer for a merge survivor, `openapi.yaml` section 3) | path under `/api/v1` |

No other header is promised. Request headers native reads: `content-type` (2.4), `x-request-id`.
Request headers native MUST ignore locally and MUST NOT forward: `authorization`, `cookie`,
`x-kotobaya-csrf`.

## 4. Semantics

### 4.1 Local operations — rules L-1 … L-8

**L-1 Always local.** A `local` operation is answered from the replica whether the device is
online or not. There is one source of truth on the phone: a write is visible to the next read at
once (read-your-writes), and the phone may differ from the server until the next sync, exactly as
the native app did. Two operations are local only in part, because some of their requests need
an op only the server may mint: `bulkCards` and `createDeck` (4.3).

**L-2 Writes are device ops.** A writing handler builds the op(s) its operation's `x-emits-ops`
names, with the payload the server would build for the same request, and mints them with
`OpMinter` **as the paired device** (its `device_id`, its HLC, device clock) — never as the server
device `00000000-0000-7000-8000-000000000001`. Mint, local apply (the same apply path as pulled
ops) and enqueue in `pending_op` happen in **one** `store.transaction`; after commit the handler
calls `SyncTriggers.localChange()` and a `sync.changed` event follows. Ids the server would
generate (card ids of `createNote`, a note `id` the request omitted, materialized notes and cards)
are UUIDv7 from the device clock. No handler writes a learning-data table except through the op
apply path (CLAUDE.md rule 8). The response carries no `kotobaya-seq`. A handler that changes
nothing (documented per operation in `openapi.yaml`: deleting a deleted note, …) mints nothing and
still succeeds; a property present with the value the row already has still mints a write.

**L-3 Same answers as the server.** Status, problem `code`, `errors[].pointer` and
`errors[].code`, response body and documented response headers equal the server's for the same
request against the same data (section 8 tests this). In particular: unknown request properties
and unknown query parameters → `422 validation_failed` (`unknown_field`); the "never an invalid
op" rule (C-6: whitespace in a tag, U+0000, int4 overflow, instants after year 9999 → `422` at the
request property, nothing minted); cursor pagination with the keyset semantics of `openapi.yaml`
section 4 (cursors are opaque and **not interchangeable** between shell and server: a cursor is
only passed back to the party that issued it, which holds because the class of an operation never
changes while the app runs — for `local_or_proxy`, see L-7).
`title` and `detail` of problems are English texts and need not equal the server's.
`MintRefusal` (core `OpMinter`) maps as follows; in every case nothing is minted or changed:

| `MintRefusal` | Answer |
|---|---|
| `NotPaired` | `401 unauthenticated` (cannot occur after check 3.1 step 3) |
| `RebuildPending` | `503 shell_rebuilding`, `Retry-After: 2` |
| `ClockGuard(G1)` — device clock before 2020 | `422 validation_failed`, `errors[0] = {pointer: "/reviewed_at", code: "before_floor"}` |
| `ClockGuard(G2)` — device clock more than 24 h behind a time it has seen | `422`, `{pointer: "/reviewed_at", code: "before_card"}` |
| `ClockFarFromServer` — clock more than 24 h off the server's (SYNC.md 3.4) | `422`, `{pointer: "/reviewed_at", code: "in_future"}` when the device clock is ahead of the server's (`skewMs < 0`), else `code: "before_card"` |
| `TooLarge` | `413 payload_too_large` |
| `Invalid` | `422 validation_failed`, `code: "invalid_value"`, pointer = the request property the op pointer maps to, or `""` |
| `ServerOnly` | `500 internal` (a handler bug) |

The three clock rows use these codes for **every** writing operation, also one whose body has no
`reviewed_at` (the pointer then names a property the request did not send; it stands for "the
time of this change"). The SPA recognises the situation by `ShellSyncStatus.phase = clock_wrong`
/ `minting_blocked = true` and shows the "fix your device clock" message; reads keep working.

**L-4 Idempotency as the server.** Create with an `id` that already exists in the replica (live,
deleted or merged) → `200` with the existing resource (or its survivor), nothing minted, whatever
the rest of the body says. `answerReview.id` is required; an existing `id` → `200` with the stored
review and the card's current state, nothing minted, also when the stored review belongs to
another card. `undoReview` of an already undone review → `200`, nothing minted. The existence
check and the mint share **one** `store.transaction` (the device's equivalent of advisory lock
`7033001`): two concurrent retries of one create produce one set of ops.
"Already exists" is judged against the replica only: an `id` or a word another replica created
and this device has not pulled yet is not seen. That is the offline case SYNC.md 7.2 (same `id`:
the second `*.created` is a no-op) and 8.1 (same word: the server merges) already resolve.

**L-5 Device clock.** "Now" is the device clock wherever the server uses its own: the default of
`reviewed_at`, the `reviewed_at` bounds, the study day, `due` comparisons, bury `until` in the
future, `generated_at`. The `answerReview` bounds are checked in this order, each `422` at
`/reviewed_at`: more than 5 minutes after now → `in_future`; before `1577836800000`
(2020-01-01T00:00:00Z) → `before_floor`; before `min(card.created_at, now) − 24 h` →
`before_card`. A device clock that is wrong is caught by guards G1/G2 and the server-skew block
(SYNC.md 3.4) through the `MintRefusal` table above; a clock that is ahead by less than a day is
not detectable offline and is handled as today (the server's `clock_ahead`, Re-mint, SYNC.md 10.4).
See 10.3–10.4.

**L-6 Deleted and merged parents.** A tombstoned note or card answers `410 gone` locally
(`include_deleted=true` reads it, as on the server). A merge loser (`merged_into_id` set) is
answered with its survivor, with `content-location`. If the delete of a row arrives **later** by
pull, an edit this device already minted loses by the existing rule (SYNC.md 7.3: a delete beats
every edit, older or newer); a review this device already logged stays in `review_log` on a hidden
card. Nothing new is defined here. See 10.5.

**L-7 Dictionary data: one lookup.** The dictionary on the phone is two stores: the **installed
package** (`ShellDictInstall.installed`; scope `top60k` or `all`, so it may lack entries the user's
notes and decks reference) and the **sidecar cache** (entries and kanji fetched from the server one
by one for references the package lacks: "gap fill", SHELL-L9; it lives in the learning database
and is not part of the read-only package file). Every consumer of one entry (by `seq`) or one kanji (by literal)
uses the same lookup, first hit wins:

1. the installed package;
2. the sidecar cache;
3. **only** for a dictionary read (a `local_or_proxy` operation) and for `createNote`, and only
   when online: the server (`getDictEntry` / `getKanji`, by rules P-1 and P-2). A hit is stored in
   the sidecar cache;
4. a **miss**.

Steps 1–2 never touch the network. Everything that renders or schedules — `QueueItem.entry`,
`QueueItem.kanji` and the other embedded dictionary data of `getReviewQueue`, back-side extras,
`getNote` / `listNotes` / `listCards` summaries, C-27 pending entries and their kanji — uses steps
1–2 **only**: a review never waits for the network and never fails for a missing entry; a miss is
`null` there, exactly as the server answers for an entry missing from `ref.dict_entry`.

**Authoritative.** What a miss means when the answer needs to say whether the entry exists. The
shell keeps the **last known server release**: the `dict_release.id` of the last `getMeta` /
`getDictRelease` answer it received from the server (it asks in every sync cycle; kept across
restarts; unknown before the first answer). The installed package is **authoritative** iff all of:
its scope is `all`; the last known server release is known; and `installed.release_id` ≥ that
release. Only then is a miss "no such entry". In every other case — scope `top60k`, no package, a
server release not yet known, or a package **older than the server's release** (the phone has
`all`@N, the server is on N+1) — a miss is "not known on this device": gap fill fetches the
reference, and nothing answers `404` or `422 not_found` from the device's own knowledge.

**Negative results.** When the server itself answers `404` for an entry or kanji (step 3, or a
gap-fill fetch), the shell records that as a negative result **for that server release**. While it
stands, the reference is a known miss: `null` in rendering, `404` / `422 not_found` without a new
request. Every negative result is dropped when the last known server release changes (the word may
exist in the new release) and gap fill asks again; it is never permanent.

Per `local_or_proxy` operation:

| Operation | Answer |
|---|---|
| `getDictEntry`, `getKanji` | Step 1 or 2 hit → local `200` (L-1/L-3). Otherwise online → the request is forwarded (P-1 … P-3) and the server's answer passed through (`200`, or `404 not_found`); a `200` is also stored in the cache. Otherwise (offline, or the forward failed as in P-1): `404 not_found` when the installed package is authoritative or a negative result stands, else `503 shell_dict_missing`. An authoritative package or a standing negative result answers `404` without asking the server even when online. |
| `listDictHeadwords` | Each requested `seq` by steps 1–2. All found → local `200`. Some missing and online → the whole request is forwarded. Some missing and offline: the package is authoritative (or every missing one has a standing negative result) → local `200` with the missing ones omitted (as the server omits unknown seqs); else `503 shell_dict_missing`. With an authoritative package nothing is forwarded. |
| `searchDictionary`, `listKanjiWords` | **Local** (over the installed package plus the sidecar cache's entries) when a package is installed and: the device is offline; or the package is authoritative and the local first page is not empty. **Forward and merge** when online and (no package, or the package is not authoritative, or the local first page is empty): the request is forwarded (P-1 … P-3) and the answer is the server's page — its items, order and `next_cursor` — with each item's per-user overlay (`status`, `note_id` and the like) **recomputed from the replica**, so a word just added on the phone shows as added (read-your-writes); the server's is a superset of what the package holds, so nothing local is lost. This is how a word outside `top60k`, or new in a release the phone does not have yet, is found and mined on the phone; opening it (`getDictEntry`) or adding it (`createNote`) then caches its entry. The forward fails as in P-1 → the local answer when a package is installed, else `503 shell_dict_missing`. No package and offline → `503 shell_dict_missing`. Offline with `top60k`, a word outside the package is simply not found (documented difference). A local cursor starts with `l.`; any other cursor is the server's and its request is forwarded (offline: `422`, pointer `/cursor`). |
| `listEntryExamples` | The entry by the lookup as for `getDictEntry` (same `404` / `503` outcomes); when it is found locally the answer holds sentences of media installed on the device only (may be empty). |
| `getDictRelease` | A package is installed → describes it. None: online → forwarded; offline → `503 shell_dict_missing`. |

Cursors of paged dictionary lists are valid only with the party that issued them (the `l.` prefix
tells them apart): a cursor that cannot be honoured — a server cursor while offline, a local cursor
of a package that has since been replaced — is `422 validation_failed` (pointer `/cursor`) and the
SPA restarts the list.
How `local` operations use the lookup: 4.3 (`getReviewQueue`, `createNote`) and 10.7.

**L-8 Rebuild.** While `sync_state.rebuild_pending = "1"` (SYNC.md 10.3, 10.5), every `local`
operation, every `local_or_proxy` operation answered locally and every shell operation except
`getShellInfo`, `pairShell`, `unpairShell`, `getSyncStatus` and `syncNow` answers `503 shell_rebuilding` with
`Retry-After: 2`. Nothing is read from the half-built replica and nothing is minted. `proxy`
operations are forwarded as usual, without the push of rule P-2. The SPA retries once after
`Retry-After` and otherwise shows the rebuilding state from `shell.sync`.

### 4.2 Proxied and hidden operations — rules P-1 … P-4

**P-1 Offline.** `NetworkMonitor` says offline → `503 shell_offline`, **no network attempt**.
Connect failure, TLS failure, or no complete response within **12 s** → `503 shell_offline`. A
timed-out write may have been applied by the server; the API's own idempotency (client ids)
covers the retry, and the effect arrives by pull either way. There is no cached fallback for
proxied reads.

**P-2 Forwarding.** Before forwarding, the shell runs one push of pending ops, bounded to
**5 s**; when it fails or times out the request is forwarded anyway (the server then answers from
older data). The upstream URL is `ShellInfo.server_url` + the `path` without its `/api/v1` prefix
+ `?` + `query` (when not empty). `method`, `body`, `content-type`, `accept` and `x-request-id`
are forwarded; `cookie`, `authorization` and `x-kotobaya-csrf` from the SPA are dropped;
`Authorization: Bearer <device token>` is added. Redirects are followed by the shell without the
`Authorization` header. The server's status, body, `content-type`, `x-request-id`, `retry-after`,
`content-location` and `kotobaya-seq` are passed back unchanged, **including `401` and `403`**
(10.2). The token is never logged and never returned.

**P-3 Read-your-writes through the proxy.** A proxied `2xx` carrying `Kotobaya-Seq: n` is held
until the device's pull cursor is ≥ `n` (the shell starts a pull at once), for at most **10 s**.
Then the `res` is sent; on timeout it is sent with `kotobaya-shell-stale: 1`, and the SPA knows
local reads may not show the change yet (a `sync.changed` event follows when they do). This is
how tables that are only written through the proxy (section 11) reach the replica. Budget:
5 s + 12 s + 10 s = 27 s, inside the SPA's 30 s timeout.

**P-4 Hidden.** `403 insufficient_scope`, generated by the shell, no network. `required_scope`
is `admin` or `import` when the operation needs that scope; it is omitted for the operations that
are hidden because something native replaces them. The SPA never offers these in shell mode
(section 9.4), so a `403` here is a bug to report, not a state to design for.

### 4.3 Per-operation notes for local handlers

Only what `openapi.yaml` cannot say because it describes the server:

| Operation | Local rule |
|---|---|
| `getPrincipal` | From the stored pairing: `kind: "device"`, `scopes: ["read","write","review"]`, `device_id`, `name` = device name, `token_id` = the `token_id` of `PairResponse`, `expires_at: null`, `totp_enabled: false`. No cookie, no CSRF. |
| `getMeta` | `api_version: "v1"`; `server_version` = value of the last `/meta` fetched from the server, `"unknown"` before the first; `core_version` = the app's core; `server_device_id` = the fixed server device id; `high_water_seq` = pull cursor; `log_epoch` = `sync_state.log_epoch` or the nil UUID; `server_time` = device clock; `dict_release` = the installed package or null; `limits` = last fetched, else the contract defaults. |
| `reportClientErrors` | Validated and limited exactly as the server (so `429`, `413`, `422` are identical), then queued (`ClientErrorQueue`) and flushed when online; `202` means "queued". |
| `getReviewQueue` | ADR-037: the handler is the core `ReviewLoop.evaluate` over the replica plus the views, exactly as the server: `keepGoing` = the request's `keep_going`, `keepGoingNewBatch` = the request's `limit`, learn-ahead 0, no session counter (`newPerSession` / `newShownThisSession` are passed as 0 and ignored). The response carries `loop` (from `LoopState`) and the deprecated `counts`, `more_available`, `next_learning_due_at` (from `LoopState.legacy`), like the server's. Only `LoopResult.queue.toMaterialize` (the served build) is materialized, never the other build's. The SPA follows client rules C1-C5 of `getReviewQueue` in `openapi.yaml` unchanged; keep going (C2, ADR-039) lasts one review visit and lives in the SPA's memory only: nothing is stored in the WebView (`localStorage["kotobaya.keepGoingDay"]` is no longer used) and native code keeps no keep-going state. `loop.until_stop` (ADR-038) is `LoopState.untilStop`. Native surfaces (widget, due notification, nudge threshold) show `readyLearning + readyReviews` of `ReviewLoop.evaluate` and never count cards themselves; that is the SPA's headline number too (`loop.ready.total`, rule C3, the same with and without keep going), so they need no keep-going state. C-27 materialization is performed by the device (ops minted by rule L-2, at most 3 rounds), since a device token holds `review` and `write`; `to_materialize` is then 0. ADR-040 / ADR-042: the loader passes `requiredKanji`, `missingKanji` and (for `deck_id`) companions exactly as contracts/core README "C-27" says, and materialization runs step A (kanji notes of `kanjiToMaterialize`, `origin` `auto_kanji`) before step B, as the server does; `kanji_parts` come from core `KanjiParts.build` and `front_furigana` from core `KanjiRequirement` (setting `cards.kanji_exemptions` from the replica), both over the local dictionary. Dictionary data by L-7 steps 1–2 only (installed package, then sidecar cache; never the network): `QueueItem.entry` / `kanji` are `null` on a miss and the card is still served; a deck entry `e:<seq>` is passed to the builder as a pending entry only when steps 1–2 find it, so an entry the device does not have is neither materialized nor counted until gap fill has cached it (10.7). |
| `answerReview`, `undoReview` | Stateless (no session object). Card state is recomputed by the replay path, never by handler arithmetic. After commit the due alarm and widgets are refreshed. |
| `bulkCards` | Action `suspend`, `unsuspend`, `bury`, `unbury`, `flag`: local, for both selectors (`card_ids`, `query`) and for `dry_run`; all-or-nothing as the server. Any other action (`set_preset`, `add_tags`, `remove_tags`, `move`, `delete`): the handler forwards the request by rules P-1 … P-3, so it is `503 shell_offline` offline. The request is validated locally first in both cases. |
| `createNote` | Duplicate check against the replica (L-4); kinds by core `CardGenerator` / `CardKinds.isRetired` (ADR-035); `created_by_device_id` is this device. `entry_seq` is resolved by the full L-7 lookup: installed package → sidecar cache → when online, the shell fetches the entry from the server (`getDictEntry`, 12 s limit of P-1) and stores it in the cache; the note is then created **locally** as always (minted by the device, never forwarded). The server answers that fetch `404` (now or earlier: a standing negative result), or the installed package is authoritative (L-7: scope `all` **and** not older than the last known server release) and lacks the entry: `422 validation_failed`, `errors[0] = {pointer: "/entry_seq", code: "not_found"}`, as the server. In every other miss — offline or unreachable with `top60k`, no package, or a package older than the server's release — `503 shell_dict_missing`, nothing minted: the device cannot say the word does not exist, only that it must connect to look it up. **`createNote` creates no kanji notes**, whatever a deck's `add_kanji` setting says (also with `deck_id`): like the server's handler it mints exactly `note.created` (the requested note), one `card.created` per generated kind and, with `deck_id`, one `deck.item_put` — nothing else. The `auto_kanji` notes that `add_kanji` asks for are minted only by C-27 materialization in `getReviewQueue` (row above); kanji data is looked up there, by L-7 steps 1–2 only (a miss means "the literal alone", as on the server when `ref.kanji` lacks it). |
| `updateNote` | Setting `entry_seq` to an entry another live note already holds (**re-match collision**): the server merges and answers with the survivor; a device never merges (SYNC.md 8: the server decides). Locally the handler mints the one `note.updated`, and the answer is **the note itself** with its new `entry_seq`, `200`, no `content-location`; two live notes hold the entry on the phone until the server's `note.merged` (and `card.merged`) arrive by pull, after which the id answers with the survivor (L-6). A documented difference from the server's response, not parity-tested. The new `entry_seq` is checked like `createNote`'s. |
| `listDecks`, `getDeck`, `createDeck`, `updateDeck` (answers) | ADR-041: every `Deck` carries `progress`, computed by core `DeckProgress.count` over the replica (deck items, notes, cards, `word_status`, setting `cards.reading_enabled`) exactly as the server computes it; `null` for smart and tombstoned decks. It needs no dictionary data, so it is always available offline. |
| `getDeckCoverage` | **Stays `proxy`** (ADR-041 decision, C-28). The deck page's "N of M words learned" line is `Deck.progress` and works offline; the raw coverage and words bar of media decks need the server (`segments`, `curve`, `difficulty`, per-token counts and the `hide` classes come from media tables and analysis that are not replicated). Offline the SPA shows the progress line and hides the coverage block; `503 shell_offline` from this operation is not an error state. |
| `createDeck` | `type: user` and `type: smart`: local (one `deck.created` minted by the device). `type: list`: `deck.created` for a list deck is server-only (OPS.md `deck.created`: a pushed one is rejected `server_only`) and needs the server's `deck-build` job, so the handler forwards the request by rules P-1 … P-3 — `503 shell_offline` offline; the deck arrives by pull (P-3). `type: media` is `422` locally, as on the server. The request is validated locally first in every case. |
| `updateSettings` | One `settings.set` per present key. Applying `cards.reading_enabled` re-derives vocab word statuses through the same code as the pull path. `cards.kanji_exemptions` (ADR-042) is validated as `bool` and needs no re-derivation: the next `getReviewQueue` reads it. |
| `createDrillSession` | No op (as the server). |
| `getBlob` | Section 2.7. |

## 5. Problems

Every non-2xx `res` has `content-type: application/problem+json` and a body with the `Problem`
schema of `openapi.yaml` (`type`, `title`, `status`, `code`, `request_id` required; `type` =
`urn:kotobaya:problem:<code>`; `instance` = the `req.path`). The codes of `openapi.yaml` section 5
keep their meaning. The shell adds:

| Status | `code` | `title` | When | Client action |
|---|---|---|---|---|
| 503 | `shell_offline` | Offline | A `proxy` operation (or a forwarded `bulkCards` action, `createDeck` with `type: list`, `pairShell`, `syncNow`, `startDictInstall`) while offline or unreachable (rule P-1). | One calm message; retry when `shell.connectivity` says online. Not reported to `reportClientErrors`. |
| 503 | `shell_dict_missing` | Dictionary data not on this device | The L-7 lookup ended in a miss that is not authoritative and the server could not be asked: a dictionary read, or `createNote` / `updateNote` with an `entry_seq`, for an entry that is in neither the installed package nor the sidecar cache, while offline or unreachable; a search or `getDictRelease` with no package installed and no connection. Never for `getReviewQueue` or any other rendering (a miss is `null` there). | Offer `startDictInstall` when no package is installed; otherwise "connect to look this word up". |
| 503 | `shell_rebuilding` | Rebuilding local data | Rule L-8, and the 20 s readiness limit (2.5). Always with `Retry-After: 2`. | Retry once after `Retry-After`; then show the rebuilding state. |
| 415 | `shell_unsupported` | Not supported in the app | Section 2.4: request body over 4 MiB or not JSON; proxied response over 8 MiB or not text; `getBlob` through the bridge. | None (a feature the SPA should have hidden). |
| 409 | `shell_pending_ops` | Unsynced changes | `unpairShell` without `force` while ops are pending. | Ask, then repeat with `force: true`, or sync first. |

`shell_pending_ops` is the one code beyond the four of ADR-036; it exists so that the refusal of
`unpairShell` cannot be confused with a server conflict.

## 6. Navigation, back, haptics, theme

### 6.1 Native → SPA navigation

`MainActivity` hosts the WebView (widget, tile and notification `PendingIntent`s keep targeting
it). Each launch source maps to one SPA path:

| Source | SPA path |
|---|---|
| Launcher | the last path saved in `onSaveInstanceState`, else `/` |
| Widget, Quick Settings tile, due notification, `kotobaya://review` | `/review` |
| Hot-word widget, `kotobaya://word/<seq>` | `/dictionary?seq=<seq>` |
| `kotobaya://struggle` | `/struggle` |
| Share sheet (`SEND` text/plain), `PROCESS_TEXT`, `kotobaya://add?word=&sentence=` — via `IntakeParser` | `/search?q=<word or text>` plus `&sentence=<s>` when a sentence is known |
| `kotobaya://pair?server=…&code=…` | `/login?pair=<the whole URI, percent-encoded>` |
| "Sync stale" notification | `/settings` |
| "Import finished" notification | `/media` |

- **Path rule.** A path MUST match `^/[A-Za-z0-9\-._~/?&=%+]*$`, MUST NOT contain `//` and MUST
  be at most 2048 characters. Query values are percent-encoded with UTF-8 (`encodeURIComponent`
  rules; a space is `%20`); shared text is truncated to 2000 characters **before** encoding, and
  when the encoded path is still longer than 2048 the `sentence` parameter is dropped, then `q` is
  shortened. `<seq>` must be decimal digits. A path that fails the rule is replaced by `/`.
  Native applies the rule before sending or loading; the SPA applies it again on receipt (and
  ignores a `navigate` that fails it).
- **Delivery.** `ready` already seen in this page life → a `navigate` message. Otherwise the path
  is the initial URL (`https://appassets.androidplatform.net<path>`), or, when a page is loading,
  it is kept and sent as `navigate` right after `hello`. Only the latest pending path is kept.
- The asset handler serves files of the bundle; a path with no file extension in its last
  segment is answered with `index.html` (SPA fallback), except under `/api/` and `/shell/` (2.7).
- The SPA handles `navigate` with `goto(path)`. Unpaired, the SPA routes everything but
  `/login…` to `/login`.

### 6.2 Back (ADR-030 preserved)

Native registers an `OnBackPressedCallback` that is **enabled iff the last `back.state` of the
current page life had `can_handle: true`** (disabled before the first one, so predictive
back-to-launcher still animates on Home).

On a back press with the callback enabled, native sends `back{id}` and waits. The SPA runs the
first matching step and answers `back.result{id, handled}`:

1. A dialog or sheet is open → close it. `handled: true`.
2. On `/review`, an answer given during this visit has not been undone, and no undo-by-back
   happened in the last 3 s → undo it once (`undoReview` with the kept review `id`), show the
   toast. `handled: true`. (TypeScript port of `ReviewBackPolicy` with its test cases: a second
   back within 3 s, or with nothing to undo, falls through.)
3. In-app history allows going back → `history.back()`. `handled: true`.
4. On a top-level tab other than Home → `goto('/')`. `handled: true`.
5. Home → `handled: false`.

Native, on `back.result`: `handled: true` → nothing more; `handled: false` →
`moveTaskToBack(true)`. **No `back.result` with that `id` within 300 ms** → fallback:
`webView.canGoBack() ? webView.goBack() : moveTaskToBack(true)`; a late `back.result` for that
`id` is ignored. A second back press while one is pending is handled by the fallback at once.
The SPA sends `back.state` after `ready` and whenever the answer to "would step 1–4 match" changes.

### 6.3 Haptics, theme, insets, orientation

- `haptic`: `View.performHapticFeedback` — `tick` → `CLOCK_TICK`, `confirm` → `CONFIRM`, `reject`
  → `REJECT` (pre-API-30 fallbacks chosen by native). No permission, no reply, dropped when the
  capability `haptics` is absent.
- `theme`: native colours the status and navigation bars with `surface` and picks light or dark
  bar icons from `dark`. Until the first `theme` of a page life native uses the system
  light/dark default.
- Insets: native pads the WebView with the system-bar and IME insets, so `100dvh` shrinks with
  the keyboard and `env(safe-area-inset-*)` is `0`. `mediaPlaybackRequiresUserGesture = false`
  (audio autoplay on reveal), overscroll glow off, text zoom 100.
- Orientation (capability `display`; `getDisplayPrefs` / `updateDisplayPrefs`, `ShellDisplay`):
  the host activity sets `requestedOrientation = SCREEN_ORIENTATION_PORTRAIT` when
  `lock_portrait` is true and `SCREEN_ORIENTATION_UNSPECIFIED` when false - at activity create
  (before the WebView is shown, from the stored value; default false) and immediately on a
  successful `updateDisplayPrefs`, before its answer. The value is stored in device preferences
  (not in the replica), survives restart and `unpairShell`, is not synced and mints no op; both
  operations work unpaired (so the Pair screen honours the lock) and answer `503
  shell_rebuilding` during a rebuild. A
  rotation caused by the change does not reload the page (the activity handles the configuration
  change). Without the capability the SPA hides the settings row and never calls the operations.

## 7. Shipping and versions

### 7.1 Bundle and manifest

The APK contains the output of the web build (the same build the server serves) under
`assets/web/`. The web build writes `shell-manifest.json` at the bundle root:

| Field | Type | Definition | Example |
|---|---|---|---|
| `web_version` | string | The web app's version. | `"0.1.26"` |
| `build_sha` | string | Git commit of the build. | `"b7048b32"` |
| `min_local_api` | integer ≥ 1 | Lowest `LOCAL_API_VERSION` this bundle works with. `2` for every bundle built from the ADR-037 SPA. | `2` |
| `files` | object, path → lower-case hex SHA-256 | Every file of the bundle except the manifest itself. | `{"index.html":"9f86d0…"}` |

Over-the-air bundles (capability `ota`, SHELL-Z2) use the same manifest: downloaded to
`files/web/<web_version>/`, every hash verified before activation, activated on the next cold
start, and reverted to the bundled copy when `ready` is not received within 10 s on two
consecutive starts.

### 7.2 `LOCAL_API_VERSION`

An integer constant of the shell, **`2`** for this contract, reported as
`ShellInfo.local_api_version`.

| Value | Since | Why |
|---|---|---|
| `1` | SHELL-C1 (ADR-036) | First version. |
| `2` | LOOP-C (ADR-037) | `getReviewQueue` answers with `ReviewQueue.loop`, which the ADR-037 SPA cannot render without; that SPA's bundle has `min_local_api: 2`. Capability `display` appears with it. |

- **Bump it** only when a change could break an SPA built against the previous value: an
  operation leaves class `local` or `local_or_proxy` (it would stop working offline); a bridge
  message, a field or a shell operation is removed or changes meaning; a rule of this file changes
  incompatibly; **or a field a new SPA requires is added** to a local operation's answer (the
  bump is what lets that SPA's `min_local_api` demand it; an older SPA on the newer shell keeps
  working, here through the deprecated legacy fields).
- **Do not bump it** for additive changes: a new bridge message type or optional field, a new
  shell operation, capability, event type or problem code, an operation moving *into* `local`.
  The SPA detects those by capability (`ShellInfo.capabilities`) or by ignoring what it does not
  know.
- **Refusal.** The shell never loads a bundle whose manifest has `min_local_api >
  LOCAL_API_VERSION` (an OTA bundle is skipped and the bundled one is used; the bundled one can
  never fail this, enforced at build time). If a `ready` nevertheless carries a higher
  `min_local_api`, native reloads the bundled copy. The SPA, for its part, shows a blocking
  "update the app" state when `getShellInfo.local_api_version < min_local_api`.
- The bridge protocol version `v` is separate and stays `1` until a message cannot be made
  backward compatible; a receiver drops other values.

## 8. Parity: the server is the oracle

The same request against the same data must give the same answer from the server and from the
shell's `LocalApi`. `parity/<id>.json` (schema `parity.schema.json`) are the inputs; SHELL-P1
records the server's answers as goldens; SHELL-P2 requires the shell to reproduce them.

### 8.1 Scenario file

| Field | Definition |
|---|---|
| `id`, `title`, `narrative` | Identity and explanation. `id` = file name. |
| `covers` | Coverage tags (8.4). |
| `clock_ms` | The instant the seed is applied at. |
| `device_id` | The device under test (`01931f3e-0000-7000-8000-0000000000d4` in every scenario so far). |
| `seed.device_id`, `seed.log` | Another device (`…00a1`, "A") and the ops it minted, in pushed form, hlc ascending. |
| `settings` | Index of the effective settings after the seed. **Not an input**: the seed log already contains the `settings.set` ops; harnesses MUST NOT apply `settings` again. |
| `dict_entries` | JMdict seqs the scenario relies on; all exist in `fixtures/dict/JMdict_subset.xml`. |
| `dict_missing` | Optional. JMdict seqs the scenario uses that MUST NOT exist in that file: a per-entry miss on both sides. |
| `operations` | operationIds the steps call (derived). |
| `steps[]` | Request step `{at_ms, request{method, path, query, body}, ignore?, expect?}` or apply step `{at_ms, apply:[ops]}`. |
| `invariants[]` | Cross-step equalities. |

### 8.2 Running a scenario

Both harnesses load the dictionary built from `fixtures/dict/JMdict_subset.xml` and
`kanjidic2_subset.xml` (server: `ref.*`; shell: an installed package of scope **`all`** holding
every entry of the fixture, the last known server release set to that package's `release_id` so
that it is authoritative, an **empty** sidecar cache with no negative results, and offline), start
from an empty replica with only the built-in rows, and use an injected clock. With that setup the L-7 lookup is
steps 1–2 followed by an authoritative miss, which is what the server does with `ref.dict_entry`:
an entry absent from the fixture (`dict_missing`) is `null` in rendering, is not materialized, and
is `422` `/entry_seq` `not_found` in `createNote` on both sides. The non-authoritative outcomes
(`top60k`, sidecar cache hit, online fetch, `503 shell_dict_missing`) have no server equivalent
and are tested by SHELL-L5 / SHELL-L9, not by parity.

| | Server (SHELL-P1) | Shell (SHELL-P2) |
|---|---|---|
| Devices | Registers **two** devices: `seed.device_id` and `device_id`, each with a device token. | The store's `sync_state.device_id` is `device_id`; paired with a fake token. |
| Seed | Clock = `clock_ms`. `POST /sync/push` of `seed.log` as `seed.device_id`, in order. Every op MUST be `accepted`; anything else fails the scenario loudly. | Clock = `clock_ms`. Every op applied in order through the core pull-apply path (the production path), with `seq` = 1, 2, … and `caused_by_op_id: null`. |
| Request step | Clock = `at_ms`. The request over HTTP with the token of `device_id` (`content-type: application/json` when `body` is not null). The server mints as the server device. | Clock = `at_ms`. The same request to `LocalApi.handle`. The shell mints as `device_id`. |
| Apply step | Clock = `at_ms`. `POST /sync/push` as `seed.device_id`; every op `accepted`. | The ops applied through the pull-apply path, `seq` continuing. |
| Result | Normalise (8.3). `RECORD=1`: check `expect` and `invariants`, write the golden `{id, steps:[{status, headers, body}]}` (apply steps: `null`). Otherwise assert equality with the stored golden. | Normalise (8.3), check `invariants`, require equality with the golden. A scenario runs only when all its `operations` are registered. |

The server-side merge ops of SYNC.md 8.1 are not reproduced on the shell side (a device does not
run collision detection), so a scenario MUST NOT contain a seed or apply op that makes the server
emit one (two live notes for one entry, two live cards of one kind); `check.sh` enforces this
with the sync reference model. Scenarios never go online:
`proxy` operations and `/shell/v1` operations are not parity-tested.

### 8.3 Normalisation

Applied identically to the server's answers (before they are stored as goldens) and to the
shell's answers. Per scenario, with two counters (`id`, `hlc`) starting at 1 that run across all
its steps:

1. **Status**: compared as is.
2. **Headers**: keep only `content-type` (media type only: parameters dropped, lower-cased),
   `retry-after`, `content-location`. Everything else is dropped — in particular `kotobaya-seq`,
   `kotobaya-local`, `kotobaya-shell-stale`, `x-request-id`, `date`, `content-length`.
3. **Body**: parsed as JSON (a body that is not JSON is compared as a string). Then:
   - remove every object member named `generated_at`;
   - in a problem body (`content-type` `application/problem+json`): remove `request_id`, `title`,
     `detail`, and `detail` of every element of `errors`; sort `errors` by (`pointer`, `code`).
4. **Minting device**: the string that equals the id of the device that minted during the steps
   — the server device id `00000000-0000-7000-8000-000000000001` on the server side, `device_id`
   on the shell side — becomes `"<device>"`.
5. **Minted ids and clocks**, in one traversal per step, steps in order; within a step first
   `content-location`, then the body depth-first with **object members in ascending key order**
   (code points) and arrays in index order:
   - a string matching the HLC pattern (54 characters) whose device part is the minting device of
     rule 4 becomes `"<hlcN>"`;
   - every occurrence of a canonical lower-case UUID inside any string value (whole value,
     `n:<uuid>` item keys, paths) that is **not known** becomes `<idN>`. Known = appears anywhere
     in the scenario file, or starts with `00000000-0000-7000-8000-` (built-in rows).
   The same original value always maps to the same placeholder; `N` is assigned on first
   appearance. Object keys are not rewritten.
6. **`ignore`**: each JSON Pointer of the step is removed from the body (a missing target is
   fine). A reference token `*` stands for every member of an object and every element of an array.

After normalisation the two sides must be equal as JSON values (object member order irrelevant,
numbers compared as binary64). Timestamps are **not** normalised: with the injected clock,
`created_at`, `updated_at`, `reviewed_at`, `due_at` and `undone_at` are equal on both sides, and a
difference is a finding.

### 8.4 Mandatory coverage

`check.sh` fails unless every tag below is in the `covers` of at least one scenario.

| Tag | Scenario | Shows |
|---|---|---|
| `queue.counts_keep_going` | `queue-counts-keep-going` | `counts`, and `loop` minus `phase` / `keep_going`, equal with and without `keep_going` (ADR-033, ADR-037). |
| `queue.learning_wait` | `queue-learning-wait` | Only a not-yet-due learning card left → empty `items`, `loop.phase` `waiting`, `next_learning_due_at`. |
| `queue.loop_never_waits` | `queue-loop-unlimited-later-new` | ADR-037 A1: reviews unlimited, nothing ready, learning cards later, new cards exist → `reviewing` with new cards, never `waiting`; a learning card that comes due is first. |
| `queue.loop_done_backlog_first` | `queue-loop-cap-keep-going-backlog` | ADR-037 B1-B4: the proportional mix under the cap, `done_for_today` at both limits, Keep going serves the backlog before any new card. |
| `queue.loop_goal_reached` | `queue-loop-goal-reached` | ADR-037 A3: reviews unlimited, new goal reached → `goal_reached`; Keep going serves new cards past the goal. |
| `queue.loop_waiting`, `queue.loop_empty` | `queue-loop-waiting-empty` | ADR-037 B6: nothing ready, no new card anywhere, learning later → `waiting`; nothing later → `empty`; with and without `keep_going`. |
| `queue.kinds_counts` | `queue-kinds-filter-counts` | The `kinds` filter leaves `counts` unchanged. |
| `queue.reviews_unlimited` | `queue-reviews-unlimited` | `scheduler.reviews_unlimited` (ADR-034), toggled by a local `updateSettings`. |
| `cards.reading_retired`, `cards.reading_reenabled`, `notes.create_no_reading_card` | `reading-retired-reenabled` | Reading cards retired, re-enabled; `createNote` makes no reading card while retired (ADR-035). |
| `queue.materialize_count` | `queue-materialize-count` | C-27: how many deck entries one fetch materializes; a second fetch creates nothing. |
| `queue.until_stop` | `queue-until-stop` | ADR-038: `loop.until_stop` counts ready and later learning, ready reviews and the new cards left to the goal; Again keeps it, Good lowers it, at a stop screen it is the later-today count; with `keep_going` it is the backlog plus the batch. |
| `queue.kanji_first_deckless` | `queue-kanji-first-deckless` | ADR-040: a deckless word and a deck word (deck `add_kanji` `off`) get their kanji notes minted in the fetch and served immediately before them; the goal counts the kanji. |
| `queue.kanji_disabled_home_deck` | `queue-kanji-disabled-home-deck` | ADR-040: a new kanji card whose only deck is disabled still comes before its word; a started and a suspended kanji do not block. |
| `queue.kanji_group_not_split` | `queue-kanji-group-not-split` | ADR-040: the proportional mix never puts a review inside a kanji group, also across answers (open group first). |
| `queue.kanji_backfill` | `queue-kanji-backfill` | ADR-040, ADR-043: the required kanji of words already studied come ahead of other new cards, oldest word first: existing new kanji cards, then the kanji notes minted for a word that has none. |
| `queue.kanji_backfill_share` | `queue-kanji-backfill-share` | ADR-044: `new_per_day` 4, three backfill kanji and two new deck words: two kanji (half the goal) then the two words on the first study day, the same after each answer; the third kanji on the next day. |
| `queue.kanji_order_stable` | `queue-kanji-order-stable` | C-28c: new cards are served in draw order; a word's unit is not pulled forward over a backfill kanji; after each answer the rebuild is the tail of the build before. |
| `queue.kanji_deck_limit` | `queue-kanji-deck-limit` | C-28c: deck limit 1, a word and its kanji card in the same deck: the kanji alone today (the word is never shown and withdrawn), the word on the next study day. |
| `queue.kanji_turn_order` | `queue-kanji-turn-order` | C-28c: a deck and the deckless source in one tier (the default): after the deck's card is answered the rebuild goes on with the deckless word's unit instead of the deck's next card; the word is not withdrawn. |
| `queue.kanji_shared_kanji` | `queue-kanji-shared-kanji` | C-28d: a deck and the deckless source in one tier whose next words require the same new kanji: the deck (first in source order) draws the kanji's unit, in the build and after each answer; the other word follows and is never shown and withdrawn. |
| `queue.kanji_keep_going_open_unit` | `queue-kanji-keep-going-open-unit` | C-28d: with `keep_going`, a backfill kanji is not served between the cards of a half-answered unit: `学 校 学校 日`, after 学 `校 学校 日`. |
| `queue.kanji_lead_deck_limit` | `queue-kanji-lead-deck-limit` | C-28d: deck limit 1, a word with one kanji card in that deck and one in a disabled deck: only the first kanji today (the lead ends when the limit is used up), the other kanji and the word on the next study day. |
| `queue.kanji_partial_unit` | `queue-kanji-partial-unit` | ADR-043: `new_per_day` 2 and a word with three new kanji: two kanji on the first study day (goal reached, not overshot), the third kanji and the word on the next; the card behind the word in the same source is not drawn ahead of it. |
| `queue.kanji_exemptions_on`, `queue.kanji_exemptions_off` | `queue-kanji-exemptions` | ADR-042: with `cards.kanji_exemptions` on, an ateji word and a word without learnable kanji get no kanji cards and `front_furigana`, a word with learnable kanji gets them and no furigana, a mixed word only its learnable one; switched off by a local `updateSettings`, every kanji is required and no front has furigana. |
| `queue.day_rollover_timezone` | `queue-day-rollover-timezone` | Day rollover with `scheduler.timezone` = `Asia/Tokyo`. |
| `answer.retry_same_id` | `answer-retry-same-id` | Answer retried with the same `id`. |
| `answer.suspended_409`, `answer.deleted_410` | `answer-suspended-deleted`, `parent-deleted-by-pull` | `409 card_not_reviewable`, `410 gone`. |
| `answer.reviewed_at_in_future`, `answer.reviewed_at_before_floor`, `answer.reviewed_at_before_card` | `answer-reviewed-at-bounds` | The three `reviewed_at` bounds and their order. |
| `answer.button_rating_mismatch` | `answer-button-rating-mismatch` | `button` not matching `rating`. |
| `undo.twice`, `undo.unknown_review` | `undo-twice-unknown` | Undo twice; unknown `review_id`. |
| `notes.create_duplicate`, `notes.create_retry_same_id` | `create-note-duplicate-retry` | Duplicate reference; same-`id` retry. |
| `replica.empty` | `empty-replica` | A paired device that has pulled nothing. |
| `sync.parent_deleted_by_pull` | `parent-deleted-by-pull` | A note deleted elsewhere after this device answered its card (L-6). |
| `dict.entry_missing` | `dict-entry-missing` | Per-entry miss (L-7): a note whose entry is not in the dictionary is still served, a deck entry without dictionary data is not materialized, `createNote` for it is `422` at `/entry_seq`. |

C-28 (ADR-040): the scenarios written before it that fetch the queue with new or already studied vocab cards now
use kana-only entries (うっかり, えっと, …) where they used 明白, あの人, …, so that they keep showing
what they were written for and mint no kanji notes; their `expect`s are unchanged apart from
`until_stop` inside whole-`/loop` expectations. Kanji behaviour is covered by the `queue.kanji_*`
scenarios only. The ADR-042 scenario uses the fixture's 寿司, 昨日 and 鱈 and a note 鱈子 without an
entry, because 海老, 林檎, 薔薇 and 今日 are not in `fixtures/dict/JMdict_subset.xml`.

`queue.session_cap_more_available` (scenario `queue-session-cap-more-available`) was retired by
ADR-037: there is no session cap. Every golden whose steps call `getReviewQueue` is re-recorded by
LOOP-SRV (the body gains `loop`; the order follows the proportional mix).

Further scenarios (one per remaining `local` operation) are added by the L tickets through a
`CONTRACT_CHANGE_REQUEST`; they need no schema change.

Parity does not replace the op-level tests CLAUDE.md requires. Every ticket that implements a
writing handler also tests that (a) its minted ops validate against
`contracts/sync/op.schema.json`, (b) repeating the request is idempotent where L-4 says so, and
(c) applying its ops out of order on a second in-memory store converges (the core fuzz harness).

## 9. Auth, pairing, shell mode

### 9.1 Pairing state

- The device token is stored in the Keystore-backed `TokenStore` and nowhere else. In shell mode
  the SPA is "logged in" by `getPrincipal` (4.3) alone: no cookie, no CSRF header, no login form.
- **Unpaired**: every operation except `getShellInfo`, `pairShell` and the display
  preferences (`getDisplayPrefs`, `updateDisplayPrefs`) answers
  `401 unauthenticated` (3.1 step 3). The SPA's `/login` route renders the Pair screen in shell
  mode: server URL + code, or a pasted / scanned / deep-linked `kotobaya://pair?…` URI
  (`pairShell`).
- **Unpair** (`unpairShell`) deletes the token only. Replica, pending ops, blobs, dictionary and
  `device_id` stay, so the same install pairs again and pushes what it holds.
- `logout` is `hidden`; the SPA offers "Unpair" in the device settings instead.

### 9.2 Token revoked or expired mid-session

The shell learns it from a `401 unauthenticated` of the server (a proxied call, or the sync
engine). Then:

- that proxied `401` is passed through to the SPA unchanged (rule P-2);
- `ShellSyncStatus.phase` becomes `auth_required` (a `shell.sync` event follows) and
  `ShellInfo.auth_required` is true; the stored token is **kept** and `paired` stays true;
- **local operations keep working**: reads, reviews and edits continue, ops accumulate in
  `pending_op`. Nothing is deleted, nothing is lost;
- every later `proxy` operation is still forwarded (and answers `401` until re-paired);
- the SPA shows the Pair screen (a `401` leads to `/login`, as on the web) with a way back to
  the local app; `pairShell` in this state replaces the token for the **same** `device_id`
  (sending `hlc_last`), after which the pending ops are pushed. `pairShell` answers
  `409 already_exists` only when paired and **not** `auth_required`.

### 9.3 Detecting shell mode

1. `window.KotobayaShell` exists → shell mode: the SPA installs `shellFetch`, uses no
   `EventSource`, sends no credentials and no `X-Kotobaya-Csrf`.
2. `getShellInfo` → `local_api_version`, `paired`, `auth_required`, `online`, `capabilities`.
3. `shell.connectivity` events keep `online` fresh.

The UI gates features on a **capability** or on an operation's class in `ops.json`, never on
"is Android".

### 9.4 What the SPA hides

Three independent rules:

- **Scope** — anything whose operations need a scope the principal lacks is hidden (Import,
  Tokens, other devices, optimizer). This is the same rule a personal token gets on the web and
  covers every `hidden` operation that needs `admin` or `import`.
- **Mode** — a route whose operations are all `proxy` shows a "needs connection" state while
  `online` is false, instead of failing request by request.
- **Unsupported** — exports and blob uploads (2.4) are not offered in shell mode.

## 10. Edge cases

Each is normative; the number is referenced from the rules above.

| # | Case | Behaviour |
|---|---|---|
| 10.1 | **Unpaired** | `401 unauthenticated` for everything but `getShellInfo`, `pairShell`, `getDisplayPrefs`, `updateDisplayPrefs`; blob intercept `401`. No local read works unpaired, even with a kept replica (after `unpairShell`): the replica belongs to a pairing. Native → SPA navigation still happens; the SPA redirects to `/login`. |
| 10.2 | **Token revoked mid-session** | Section 9.2. Proxied `401` passed through; local operations continue; pending ops kept; `pairShell` re-pairs the same device. A proxied `403` (should not occur) is passed through too. |
| 10.3 | **Device clock behind** | Before 2020 (G1) or more than 24 h behind a time the device has seen (G2), or more than 24 h behind the server: every write is refused with the `422` of the L-3 table, nothing minted; reads work; `phase: clock_wrong`. Behind by less: writes are minted with the device's time; `clock_warning` above 5 minutes; a server `clock_behind` rejection is handled by the sync engine (SYNC.md 10.4), not by the SPA. |
| 10.4 | **Device clock ahead** | Not detectable offline. Reviews get future `reviewed_at`, edits get future HLCs and win last-writer-wins for up to a day (SYNC.md 3.4). More than 24 h ahead of the server once it is known: writes refused (`in_future`). An explicit `reviewed_at` more than 5 minutes after the device's now is `422 in_future` regardless. The study day and `due` comparisons follow the device clock, so the queue may differ from the web's until the clock is fixed. |
| 10.5 | **Offline creation, then the parent is deleted on the server** | A note edited, a card answered or a deck item added offline whose note / card / deck was deleted elsewhere: the local ops are minted and pushed; the server accepts ops addressed to a tombstone (SYNC.md 7.3); when the delete arrives by pull the row is hidden locally and further requests answer `410 gone`. The edit is lost by rule (delete beats edit); the review stays in `review_log`. Parity: `parent-deleted-by-pull`. If the *create itself* was offline (`createNote`, then the note type or deck is deleted elsewhere): accepted the same way (SYNC.md 7.3 "tombstoned parent"). |
| 10.6 | **Duplicate create ids / duplicate words** | Same `id` again on this device: `200`, nothing minted (L-4), one transaction. Same `id` created on another replica and not yet pulled: both `*.created` ops reach the log, the second is a no-op (SYNC.md 7.2). Same word (entry, kanji, grammar point) created offline on two replicas: `409 already_exists` cannot be detected locally; the server merges (SYNC.md 8.1) and both replicas converge. Two bridge `req`s with the same bridge `id`: the second is dropped (2.5). |
| 10.7 | **Dictionary data missing on the device** | Three situations, all by the one lookup of rule L-7. (a) **No package installed**: searches and `getDictRelease` are forwarded when online, else `503 shell_dict_missing`; everything in (b) applies to every entry. (b) **Package installed but an entry is not in it** (scope `top60k` and a word outside it, e.g. from a list deck): the sidecar cache is asked next; gap fill (SHELL-L9) fills it after each sync for every entry and kanji that local notes, deck items and queue-relevant cards reference. Until an entry is cached: its card is **still served** by `getReviewQueue` with `entry: null` (never dropped, never an error, never a network wait); a deck entry `e:<seq>` without a note is not passed to the queue builder (contracts/core README C-27: "a device without it skips it until it has it"), so it is neither materialized nor counted in `counts.new` yet; `getDictEntry` / `getKanji` / `createNote` reach the server when online and cache the answer, and answer `503 shell_dict_missing` offline. A package of scope `all` that is **older than the last known server release** (or whose server release is not known yet) is treated exactly like `top60k` here: a word new in the server's release is fetched by gap fill, served with `entry: null` and not materialized only until then, and is `503 shell_dict_missing` — never `404` / `422` — offline. `counts.new` can therefore lag the server's by the deck entries not yet cached; the lag ends with the next gap-fill run or package update. (c) **Known miss**: the installed package is authoritative (L-7), or the server answered `404` for this reference under its current release (negative result): `null` in rendering, `404 not_found` for the keyed reads, `422` `/entry_seq` `not_found` for `createNote` / `updateNote`, without asking again. A negative result is **not permanent**: all of them are dropped when the last known server release changes, and an authoritative package stops being authoritative the moment a newer server release is seen; the references are then looked up again as in (b). Notes without `entry_seq` are never affected. `ShellDictInstall.gap_fill` reports how many references are cached and how many are still missing. Parity: `dict-entry-missing` (case c). **Documented difference (ADR-042, ADR-043):** core `KanjiRequirement` needs the entry's form tags and per-sense `uk` and the kanji's grade and frequency rank. While the device lacks the entry it sees no tags, and while it lacks a kanji row it sees no facts: such a word is not exempt, such a kanji is not learnable. So, until gap fill has cached them, the phone may require other kanji for that word than the server (none where the server requires some, or a jōyō kanji of a `uk` word the server exempts), may mint a kanji note the server would not, and shows `front_furigana` where the server does not or the reverse. Nothing is lost: a kanji note minted either way is an ordinary note that syncs (one live note per literal, SYNC.md 8.1), and after gap fill both sides evaluate the same rule on the same data. Not parity-tested (parity runs with a full package). |
| 10.8 | **Snapshot / replica rebuild** | Rule L-8: `503 shell_rebuilding` + `Retry-After: 2` for local operations while `rebuild_pending`; nothing minted; proxy operations still forwarded. Installing a media or deck snapshot (SYNC.md 7.6) is one transaction and blocks nothing: readers see the old rows or the new rows. The startup replay is the readiness gate (2.5), not `shell_rebuilding`, unless it exceeds 20 s. |
| 10.9 | **Empty replica** | Paired, nothing pulled yet: every local read answers `200` with empty lists / zero counts and the built-in rows (note types, Default preset, default settings); `getReviewQueue` → `items: []`, counts 0, `more_available: false`. Never `500`, never `404` for a list. Parity: `empty-replica`. The SPA shows "syncing" from `shell.sync`. |
| 10.10 | **Reload / renderer crash** | 2.5 "Reload". In-flight writes complete; their `res` is dropped; the SPA refetches on `hello`. |
| 10.11 | **Response lost, request retried** | A new bridge `id` with the same API-level id: L-4 makes it safe for creates, `answerReview` and `undoReview`; register writes (`updateNote`, `updateSettings`, suspend, …) are safe to repeat (a second op with a later HLC and the same value). |
| 10.12 | **Out-of-order answers** | The SPA sends answer 1 then answer 2; their `res` may arrive 2 then 1. Each request is one transaction, so the replica is consistent; the SPA awaits a write before the read that depends on it. |
| 10.13 | **Concurrent native writers** | AnkiProvider, on-device AnkiConnect, widgets and the sync engine write the same replica while the SPA runs. All go through the same op path and store transactions; the SPA learns of them by `sync.changed` / `note.created`. |
| 10.14 | **Proxied write while local ops are pending** | P-2 pushes first, so the server sees the phone's earlier changes before the proxied one in the usual case; when the push fails the proxied write is applied on older server data and the pending ops merge later by the normal rules. |

## 11. Cross-check: table ↔ sync op ↔ operation in the shell

Every learning-data table of `contracts/db/SCHEMA.md` 4.1 (plus `media`, `parse_override`), the
ops that write it, and how the SPA reads and writes it on the phone. "Local" operations mint as
the device (L-2); "proxied" ones are minted by the server and reach the replica by pull (P-3).

| Table | Sync ops | Local operations | Proxied operations |
|---|---|---|---|
| `note_type` | `note_type.created`, `note_type.updated`, `note_type.deleted` | read: `listNoteTypes`, `getNoteType` | write: `createNoteType`, `updateNoteType`, `deleteNoteType` |
| `note` | `note.created`, `note.updated`, `note.deleted`, `note.merged` (server) | `createNote`, `updateNote`, `deleteNote`, `getReviewQueue` (C-27 materialization); read: `listNotes`, `getNote` | `bulkCards` actions `add_tags`, `remove_tags`, `delete` |
| `card` | `card.created`, `card.suspended`, `card.unsuspended`, `card.buried`, `card.unburied`, `card.flag_set`, `card.preset_set`, `card.deleted`, `card.merged` (server) | `createNote`, `getReviewQueue` (C-27), `bulkCards` (suspend, unsuspend, bury, unbury, flag); read: `listCards`, `getCard` | `createCard`, `updateCard`, `deleteCard`, `bulkCards` (`set_preset`, `delete`) |
| `review_log` | `review.logged`, `review.undone` | `answerReview`, `undoReview`; read: `listCardReviews`, `listHotWords`, `listHotKanji`, `listLeeches`, `createDrillSession` | `bulkScheduleCards` |
| `word_status` | `word.status_set`, `word.status_cleared` | `setWordStatus`, `clearWordStatus`; read: `getWordStatus` | — |
| `deck` | `deck.created`, `deck.updated`, `deck.deleted`, `deck.content_published` (server) | `createDeck` (`type` `user`, `smart`), `updateDeck`, `deleteDeck`; read: `listDecks`, `getDeck` | `createDeck` with `type: list` (forwarded: server-only op and `deck-build` job), `deleteMedia` (its media deck), `listDeckLists`, `getDeckCoverage` |
| `deck_item` | `deck.item_put`, `deck.item_removed` | `addDeckItems`, `putDeckItem`, `removeDeckItem`, `createNote` (with `deck_id`); read: `listDeckItems` | `bulkCards` action `move` |
| `confusable` | `confusable.suggested` (server), `confusable.confirmed`, `confusable.rejected` | — | `setConfusable`; read: `listWordConfusables`, `listConfusables` |
| `grammar_point` | `grammar.created`, `grammar.updated`, `grammar.deleted` | — | `createGrammarPoint`, `updateGrammarPoint`, `deleteGrammarPoint`; read: `listGrammarPoints`, `getGrammarPoint` |
| `sentence_grammar_span` | `grammar.span_put`, `grammar.span_removed` | — | `putGrammarSpan`, `removeGrammarSpan`; read: `listGrammarExamples` |
| `settings` | `settings.set` | `updateSettings`; read: `listSettings` | — |
| `fsrs_params` | `fsrs_params.created`, `fsrs_params.updated`, `fsrs_params.deleted`, `fsrs_params.published` (server) | read: `listPresets`, `getPreset` | `createPreset`, `updatePreset`, `deletePreset` (`optimizePreset` is hidden) |
| `media` | `media.created` (server), `media.updated`, `media.deleted`, `media.content_published` (server) | — | `updateMedia`, `deleteMedia`; read: `listMedia`, `getMedia`, `getMediaCoverage`, `listMediaSentences`, `listSentences`, `getSentence` |
| `parse_override` | `parse_override.put`, `parse_override.deleted` | — | `putParseOverride`, `deleteParseOverride`; read: `listParseOverrides` |

Tables with no local write (`note_type`, `confusable`, `grammar_point`, `sentence_grammar_span`,
`fsrs_params`, `media`, `parse_override`) are written through the proxy while online and are
unavailable for writing offline; this is the price of not re-implementing their handlers and is
visible to the user as the "needs connection" state (9.4).

Not learning data, listed for completeness — `proxy`: `getHealth`, `getOwnDevice`,
`updateOwnDevice`, the six `getStats*` operations, `parseText`, `testGrammarRules`, `listJobs`,
`getJob`, `synthesizeSpeech`, `queryBlobs`, `putBlob`, `listJpdbVocab`, `exportAll`, `exportAnki`.

## 12. Shell operations (`shell.yaml`)

| Operation | Method, path | Unpaired | During rebuild | Changes |
|---|---|---|---|---|
| `getShellInfo` | `GET /shell/v1/info` | works | works | — |
| `pairShell` | `POST /shell/v1/pair` | works | works | stores the token |
| `unpairShell` | `POST /shell/v1/unpair` | `401` | works | deletes the token |
| `getSyncStatus` | `GET /shell/v1/sync` | `401` | works | — |
| `syncNow` | `POST /shell/v1/sync` | `401` | works | starts a cycle |
| `getDictInstall` | `GET /shell/v1/dict` | `401` | `503` | — |
| `startDictInstall` | `POST /shell/v1/dict/install` | `401` | `503` | starts an install |
| `getStorage` | `GET /shell/v1/storage` | `401` | `503` | — |
| `updateStorage` | `PATCH /shell/v1/storage` | `401` | `503` | device preferences, blob cache |
| `getNotificationState` | `GET /shell/v1/notifications` | `401` | `503` | — |
| `updateNotificationPrefs` | `PATCH /shell/v1/notifications` | `401` | `503` | device preferences |
| `requestNotificationPermission` | `POST /shell/v1/notifications/permission` | `401` | `503` | system permission |
| `getPushState` | `GET /shell/v1/push` | `401` | `503` | — |
| `updatePushState` | `PATCH /shell/v1/push` | `401` | `503` | UnifiedPush registration |
| `getAnkiConnectState` | `GET /shell/v1/ankiconnect` | `401` | `503` | — |
| `updateAnkiConnectState` | `PATCH /shell/v1/ankiconnect` | `401` | `503` | device preferences, restarts the server |
| `getDisplayPrefs` | `GET /shell/v1/display` | works | `503` | — |
| `updateDisplayPrefs` | `PATCH /shell/v1/display` | works | `503` | device preferences, activity orientation (6.3) |

None of them mints a sync op or writes a learning-data table. Their state is device-local and
not synced.

## 13. Known gaps (not defined here)

- **Pairing an install that holds a replica with a different server.** `unpairShell` keeps the
  replica; nothing in this contract stops `pairShell` from then naming another server, whose log
  the kept replica and pending ops do not belong to. Today's native pairing has the same gap
  (the log-identity check of SYNC.md 6.2 is the only guard). Needs a decision before the Pair
  screen offers a free-text server URL to an install with data.
- **Resolving rejected ops** (Discard / Retry / Re-mint, SYNC.md 10.2) has no shell operation;
  `ShellSyncStatus.rejected_ops` only counts them. It stays native UI.
- **Exports and blob uploads** in shell mode (2.4).
- **Job progress events** (`job.updated`) are not bridged (2.6).

## 14. Verifying

```
contracts/api/check.sh        # or: make check MODULE=contracts
```

lints `shell.yaml`, then runs `shell/check_shell.py`, whose named checks are listed at the top of
that file: bridge examples valid / invalid ones rejected; `ops.json` complete, nothing invented;
the 18 shell operations; this file's cross-check; every parity seed op against
`contracts/sync/op.schema.json`; every seed pushed to the sync contract's reference server
(`contracts/sync/tools/model.py`), which must accept each op and emit none of its own; the mandatory
coverage of 8.4; and negative self-tests (a removed operationId, an invented one, a bad class, a
broken seed op, a seed the server would reject and a missing mandatory scenario must each make the
check fail).
