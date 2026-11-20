# Android web shell — contract (SHELL-C1, ADR-036)

Normative text for the Android WebView shell: the bridge between the SPA and native code, how every
API operation is answered on the phone, the shell-only operations, navigation and back, shipping
and versioning, and the server-versus-shell parity scenarios. Design rationale: `docs/ANDROID-SHELL.md`.

C-31 (ADR-050, local-first): no operation of the SPA's UI path waits for the network. Section 3.2
gained the class `cached`, rules L-7, P-2 and P-3 were rewritten, rules L-9 and K-1 … K-4 are new,
and `LOCAL_API_VERSION` is 3 (7.2). Text marked "C-31" replaces what stood there before.

This contract is **additive**. It changes nothing in `contracts/api/openapi.yaml`, `contracts/sync`,
`contracts/db` or `contracts/core`; where this file and one of those disagree, that file wins and this
one has a bug (return `CONTRACT_CHANGE_REQUEST`).

| File | What |
|---|---|
| `SHELL.md` | This file. |
| `bridge.schema.json` | JSON Schema of every bridge message; `examples/bridge/` (valid), `examples/bridge-invalid/` (must be rejected). |
| `shell.yaml` | OpenAPI 3.1 of the 18 shell-only operations under `/shell/v1`; generated into `clients/typescript-shell`. |
| `ops.json` | `{operationId: class}` for every operationId of `openapi.yaml`: `local`, `local_or_proxy`, `cached`, `proxy` or `hidden`. A flat object, no other keys. Read by the Android route-table test and by the SPA. |
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

The dispatcher answers a request locally from the replica, answers it from the device's response
cache (C-31, class `cached`), forwards it to the server with the device token, or refuses it, as
`ops.json` says (section 3). Only the class `proxy` (and the one forwarded request of rule L-9)
puts the network between a request and its answer. There is no socket and no HTTP server
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
| `cache.updated` (C-31) | `ShellEventCacheUpdated` (`operation_id`, `path`, `query`, `status`) | Rule K-2 / K-3: a background fetch of a `cached` operation stored a body that **differs** from the stored one (or stored the first one), or ended a miss with a final non-`2xx` answer. Never for a revalidation that found the same bytes. At most one per cache key per second; the last one is always delivered. A duplicate is harmless: the SPA's reaction (request the operation again) is idempotent. |

Differences from the server's stream, all deliberate: `hello.high_water_seq` and
`sync.changed.high_water_seq` are the **device's pull cursor**, may be `0`, and do not move on a
local write; `hello.server_time` is the device clock; `hello.log_epoch` is the nil UUID until known;
`note.created.seq` is `0` for a note minted on this device. `job.updated`, `device.paired` and
`device.revoked` are never sent: jobs are observed by polling the proxied `listJobs` / `getJob`
while online. `cache.updated` has no server counterpart (a browser has no response cache). Delivery is best effort; there is no replay.

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
6. Class `cached` (C-31) → section 4.4. Neither the readiness gate nor the rebuild gate applies:
   the response cache is not part of the replica.
7. Class `local`, `local_or_proxy`, and shell operations: the readiness gate (2.5), then the
   rebuild gate (rule L-8), then the handler. Inside the handler the server's own order applies
   (`openapi.yaml` section 5): `404`/`410`, then `400`, then `422`, then `409`.

A device token holds `read`, `write` and `review`, and every `local` / `local_or_proxy` / `cached`
operation needs one of those, so the handler performs no scope check (`check_shell.py` enforces that
premise: check `ops_map_scopes`).

### 3.2 Classes (`ops.json`)

| Class | Count | Answered |
|---|---|---|
| `local` | 43 | By the shell from the replica, online or not, never through the network (rules L-1 … L-9). |
| `local_or_proxy` | 7 | Dictionary reads: from the installed package or the sidecar cache, else answered as a miss at once (`404 not_found` / `503 shell_dict_missing`) while gap fill fetches the reference in the background (rule L-7). C-31: **never forwarded on a request**; the class keeps its name so that an SPA reading `ops.json` needs no change. |
| `cached` | 14 | C-31. Server data the replica does not hold, answered at once from the device's response cache and revalidated in the background; a miss is an immediate `503 shell_uncached` (rules K-1 … K-4, L-9). Reads only. |
| `proxy` | 35 | By the server, through the shell, with the device token (rules P-1 … P-3). The only class whose answer waits for the network; the SPA reaches it only from explicit "needs connection" surfaces (9.4). |
| `hidden` | 25 | Never: `403 insufficient_scope` without touching the network (rule P-4). |

**`local`:** `getPrincipal`, `getMeta`, `reportClientErrors`, `listSettings`, `updateSettings`,
`listPresets`, `getPreset`, `listNoteTypes`, `getNoteType`, `getBlob` (intercept only, 2.7),
`getReviewQueue`, `answerReview`, `undoReview`, `createDrillSession`, `bulkCards` (see 4.3),
`getCard`, `listCardReviews`, `listCards`, `listNotes`, `createNote`, `getNote`, `updateNote`,
`deleteNote`, `getWordStatus`, `setWordStatus`, `clearWordStatus`, `listDecks`, `createDeck`,
`getDeck`, `updateDeck`, `deleteDeck`, `listDeckItems`, `addDeckItems`, `putDeckItem`,
`removeDeckItem`, `listHotWords`, `listHotKanji`, `listLeeches`; C-31: `getStatsSummary`,
`getStatsHeatmap`, `getStatsRetention`, `resolveSpeech`, `bulkScheduleCards` (4.3).

**`cached`** (C-31): `listDeckLists`, `getDeckCoverage`, `getStatsForecast`, `getStatsKnownWords`,
`getStatsCalibration`, `listConfusables`, `listWordConfusables`, `listSentences`, `listMedia`,
`getMedia`, `getMediaCoverage`, `listMediaSentences`, `listParseOverrides`, `listGrammarPoints`.

**`local_or_proxy`:** `searchDictionary`, `getDictEntry`, `listEntryExamples`, `listDictHeadwords`,
`getDictRelease`, `getKanji`, `listKanjiWords`.

**`proxy`:** `getHealth`, `getOwnDevice`, `updateOwnDevice`, `createPreset`, `updatePreset`,
`deletePreset`, `createNoteType`, `updateNoteType`, `deleteNoteType`, `createCard`, `updateCard`,
`deleteCard`, `updateMedia`, `deleteMedia`, `listJpdbVocab`,
`getSentence`, `setConfusable`,
`createGrammarPoint`, `getGrammarPoint`, `updateGrammarPoint`,
`deleteGrammarPoint`, `testGrammarRules`, `listGrammarExamples`, `putGrammarSpan`,
`removeGrammarSpan`, `parseText`, `putParseOverride`,
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
exist, uses another class, gives a class other than `hidden` to an operation a device token
cannot call, gives `local` / `local_or_proxy` / `cached` to an operation that needs a scope a
device token lacks, or gives `cached` to an operation that is not a `GET` (a cached write would
be a lost write). A new operation in `openapi.yaml` therefore cannot be merged without classifying it.

### 3.3 Headers

| Header (lower-case in `res.headers`) | On | Value |
|---|---|---|
| `content-type` | every `res` with a body | `application/json`, or `application/problem+json` for a problem; proxied: the server's. |
| `kotobaya-local` | every `res` the shell produced itself: `local` answers, `local_or_proxy` answers, `cached` answers (hit and miss; C-31), shell operations, and every problem the shell generates (`401` unpaired, `403` hidden, `404` no route, `415`, `503 shell_*`) | `1`. Never on a response forwarded from the server. |
| `x-request-id` | every `res` | The `x-request-id` of the `req` when it matches `^[A-Za-z0-9._-]{1,64}$`, else a fresh UUIDv7. Proxied: the server's response header. Equals `Problem.request_id`. |
| `kotobaya-seq` | proxied `2xx` only | The server's `Kotobaya-Seq`, unchanged. **Never** on a local answer (a local write has no `change.seq` yet). |
| `kotobaya-shell-stale` | proxied `2xx` whose effect is not yet in the replica (rule P-3, C-31: decided at once, never by waiting) | `1` |
| `kotobaya-cached-at` (C-31) | every `cached` hit (rule K-2); never on a miss | RFC 3339 UTC with milliseconds: the device clock when the stored body was fetched or last confirmed unchanged. Example: `2026-11-20T10:00:05.120Z`. Informational (an "updated …" label); the SPA MUST NOT schedule anything from it and shows a value in the future as "just now" (10.17). |
| `kotobaya-shell-partial` (C-31) | a `listDictHeadwords` `200` from which at least one requested `seq` was omitted because the device does not have it **yet** (rule L-7: a miss that is not authoritative) | `1`. Absent when every omitted `seq` is a known miss. |
| `retry-after` | `503 shell_rebuilding` (`2`); proxied: the server's. **Never** on `503 shell_uncached` or `503 shell_dict_missing`: there is nothing to retry on a timer, the SPA waits for the event (`cache.updated`, `shell.dict`) | seconds |
| `content-location` | as the server (answer for a merge survivor, `openapi.yaml` section 3) | path under `/api/v1` |

No other header is promised. Request headers native reads: `content-type` (2.4), `x-request-id`.
Request headers native MUST ignore locally and MUST NOT forward: `authorization`, `cookie`,
`x-kotobaya-csrf`.

## 4. Semantics

### 4.1 Local operations — rules L-1 … L-9

**L-1 Always local.** A `local` operation is answered from the replica whether the device is
online or not. There is one source of truth on the phone: a write is visible to the next read at
once (read-your-writes), and the phone may differ from the server until the next sync, exactly as
the native app did. C-31: one operation is local only in part, because one of its requests needs
an op only the server may mint: `createDeck` with `type: list` (4.3; the single exception of
rule L-9). `bulkCards` is local for every action.

**L-2 Writes are device ops.** A writing handler builds the op(s) its operation's `x-emits-ops`
names, with the payload the server would build for the same request, and mints them with
`OpMinter` **as the paired device** (its `device_id`, its HLC, device clock) — never as the server
device `00000000-0000-7000-8000-000000000001`. Mint, local apply (the same apply path as pulled
ops) and enqueue in `pending_op` happen in **one** `store.transaction`; after commit the handler
calls `SyncTriggers.localChange()` and a `sync.changed` event follows. C-31: `localChange()` only
**schedules** a cycle (debounced: 2 s after the last local change, at most 10 s after the first
one not yet followed by a cycle; SYNC.md 6.1 "Device scheduling"); the handler never waits for
it and its `res` is posted before any cycle starts. Ids the server would
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
3. a **miss**. C-31: the lookup itself never asks the server. A miss that is not a known miss
   (below) **enqueues the reference for gap fill** (`GapFillQueue.request(kind, key, urgent)`:
   `urgent = true` when the miss came from a request of the SPA, so it is fetched before the
   proactive references of SHELL-L9) and is answered at once. Gap fill runs in the background
   while online, stores a hit in the sidecar cache, records a `404` as a negative result, and a
   `shell.dict` event follows (`ShellDictInstall.gap_fill` changed), on which the SPA requests
   again what it shows. Enqueueing the same reference twice is one fetch.

No step touches the network on the request's path (rule L-9). (Before C-31 step 3 was "when
online: ask the server and wait up to 12 s"; that wait is gone for dictionary reads **and** for
`createNote` / `updateNote`.) Everything that renders or schedules — `QueueItem.entry`,
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

**Negative results.** When the server itself answers `404` for an entry or kanji (a
gap-fill fetch), the shell records that as a negative result **for that server release**. While it
stands, the reference is a known miss: `null` in rendering, `404` / `422 not_found` without a new
request. Every negative result is dropped when the last known server release changes (the word may
exist in the new release) and gap fill asks again; it is never permanent.

Per `local_or_proxy` operation:

| Operation | Answer |
|---|---|
| `getDictEntry`, `getKanji` | Step 1 or 2 hit → local `200` (L-1/L-3). Miss: `404 not_found` when the installed package is authoritative or a negative result stands; else `503 shell_dict_missing` **at once**, online or not, and the reference is enqueued for gap fill (`urgent`). Never forwarded. Example: scope `top60k`, `GET /dictionary/entries/2859204` (not in the package, not cached), online → `503 shell_dict_missing` within the handler's own time; about a second later `shell.dict` (`gap_fill.cached` + 1); the SPA's second request → `200`. |
| `listDictHeadwords` | Each requested `seq` by steps 1–2. Always a local `200` holding the ones found (as the server omits unknown seqs). A missing `seq` that is not a known miss is enqueued for gap fill and the answer carries `kotobaya-shell-partial: 1` (3.3); when every missing one is a known miss the header is absent. Never `503`, never forwarded: a list of words does not fail for one entry. |
| `searchDictionary`, `listKanjiWords` | **Always local**, over the installed package plus the sidecar cache's entries, with each item's per-user overlay (`status`, `note_id` and the like) from the replica. No package installed **and** an empty sidecar cache → `503 shell_dict_missing` (nothing to search; the SPA offers `startDictInstall`, and rule "Auto-install" below installs one). Never forwarded and never merged with a server page. Consequence (documented difference): with scope `top60k` a word outside the package and outside the sidecar cache is **not found** on the phone, online or not, until scope `all` is installed (auto-installed on an unmetered network) or gap fill caches it because a note or deck item references it. Every cursor the shell issues starts with `l.`; any other cursor is `422 validation_failed` (pointer `/cursor`). |
| `listEntryExamples` | The entry by the lookup as for `getDictEntry` (same `404` / `503` outcomes, same enqueue); when it is found locally the answer holds sentences of media installed on the device only (may be empty). |
| `getDictRelease` | A package is installed → describes it. None → `503 shell_dict_missing` at once (never forwarded). |

Cursors of paged dictionary lists are valid only with the party that issued them: a cursor that
cannot be honoured — a server cursor (an SPA that held one across an app update), a local cursor
of a package that has since been replaced — is `422 validation_failed` (pointer `/cursor`) and the
SPA restarts the list.

**Auto-install (ADR-053, provisional).** So that a dictionary read does not depend on gap fill,
the shell starts the install of scope `all` by itself (exactly what `startDictInstall {scope:
"all"}` does, resumable, reported by `shell.dict`) when all of these hold: paired;
`ShellSyncStatus.first_sync.done`; no install is running; the installed package's scope is not
`all`; the user has not chosen `top60k` explicitly (a `startDictInstall` call with `scope:
"top60k"` is remembered as that choice; the default of a new install is "not chosen"); and the
active network is **unmetered** (`NET_CAPABILITY_NOT_METERED`). It is re-evaluated when one of the
conditions changes and at most once per hour after a failure. On a metered network nothing is
downloaded automatically: `top60k` plus gap fill, and the SPA offers the install. Nothing on the
UI path waits for it.
How `local` operations use the lookup: 4.3 (`getReviewQueue`, `createNote`) and 10.7.

**L-8 Rebuild.** While `sync_state.rebuild_pending = "1"` (SYNC.md 10.3, 10.5), every `local`
operation, every `local_or_proxy` operation answered locally and every shell operation except
`getShellInfo`, `pairShell`, `unpairShell`, `getSyncStatus` and `syncNow` answers `503 shell_rebuilding` with
`Retry-After: 2`. Nothing is read from the half-built replica and nothing is minted. `proxy`
operations are forwarded as usual and `cached` operations are answered from the response cache as
usual (neither reads the replica). The SPA retries once after
`Retry-After` and otherwise shows the rebuilding state from `shell.sync`.

**L-9 Never the network (C-31, ADR-050).** Handling a `local`, `local_or_proxy` or `cached`
operation, or a shell operation other than `pairShell`, MUST NOT start network I/O **and wait for
it**, MUST NOT join or wait for a running sync cycle, pull page, push, gap-fill fetch, dictionary
install or cache revalidation, and MUST NOT depend on `NetworkMonitor`, on
`ShellSyncStatus.phase`, on `consecutive_failures` or on whether a cycle is running — neither for
its answer nor for whether it materializes (4.3 `getReviewQueue`). What it may do: schedule
background work (`SyncTriggers.localChange()`, `GapFillQueue.request`, the native speech resolver,
a cache revalidation) and return. The time to its `res` is bounded by local CPU and SQLite only;
a local write waits for SQLite's write connection at most **5 s** (`busy_timeout`; SYNC.md 6.1
"Device scheduling" keeps pull transactions short), after which it answers `503 shell_rebuilding`
with `Retry-After: 2` and has changed nothing.

One exception, a write that cannot be made on the device: `createDeck` with `type: list` (4.3) is
forwarded by rules P-1 … P-3. Nothing else in these classes is.

Test (LF-A2, normative): with an `Upstream` double that fails the test when it is called and a
`SyncCoordinator` double whose cycle never finishes, every `local` and `local_or_proxy` operation
answers as with no network at all, every `cached` operation answers its hit or `503
shell_uncached` (the background fetch is started on a scheduler the test does not run), and the
answers equal the ones given with the device online.

### 4.2 Proxied and hidden operations — rules P-1 … P-4

**P-1 Offline.** `NetworkMonitor` says offline → `503 shell_offline`, **no network attempt**.
Connect failure, TLS failure, or no complete response within **12 s** → `503 shell_offline`. A
timed-out write may have been applied by the server; the API's own idempotency (client ids)
covers the retry, and the effect arrives by pull either way. There is no cached fallback for
`proxy` operations (the reads that have one are the class `cached`, 4.4). One attempt, no retry
by the shell.

**P-2 Forwarding (C-31: no pre-push).** The request is forwarded **at once**. The shell does not
push pending ops first, does not start a cycle first and does not wait for one that is running:
the server answers from the data it has, and the device's pending ops merge later by the normal
rules (10.14). (Before C-31 a push of up to 5 s ran before every forwarded call; it put a sync
cycle in front of every proxied read.) The upstream URL is `ShellInfo.server_url` + the `path` without its `/api/v1` prefix
+ `?` + `query` (when not empty). `method`, `body`, `content-type`, `accept` and `x-request-id`
are forwarded; `cookie`, `authorization` and `x-kotobaya-csrf` from the SPA are dropped;
`Authorization: Bearer <device token>` is added. Redirects are followed by the shell without the
`Authorization` header. The server's status, body, `content-type`, `x-request-id`, `retry-after`,
`content-location` and `kotobaya-seq` are passed back unchanged, **including `401` and `403`**
(10.2). The token is never logged and never returned.

**P-3 Read-your-writes through the proxy (C-31: no hold).** The `res` of a proxied call is sent
**as soon as the server's answer is complete**. When it is a `2xx` carrying `Kotobaya-Seq: n` and
`n` is greater than the device's pull cursor at that moment, the `res` carries
`kotobaya-shell-stale: 1` and the shell **schedules** a pull (`SyncTriggers.remoteChange()`, not
debounced, not awaited); the SPA then knows that local reads do not show the change yet, keeps
what it shows, and re-reads on the `sync.changed` event that follows when the pull has applied
it. `n` ≤ the pull cursor, or no `Kotobaya-Seq`: no header, no pull. This is how tables that are
only written through the proxy (section 11) reach the replica. Budget: the 12 s of P-1 and
nothing else. (Before C-31 the answer was held up to 10 s for the pull.)
Example: `updatePreset` answers `200` with `Kotobaya-Seq: 48214` while the pull cursor is 48213 →
`res` at once with `kotobaya-seq: 48214`, `kotobaya-shell-stale: 1`; ~300 ms later `sync.changed
{high_water_seq: 48214}`; the SPA's `listPresets` (local) now shows the change.

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
| `getReviewQueue` | ADR-037: the handler is the core `ReviewLoop.evaluate` over the replica plus the views, exactly as the server: `keepGoing` = the request's `keep_going`, `keepGoingNewBatch` = the request's `limit`, learn-ahead 0, no session counter (`newPerSession` / `newShownThisSession` are passed as 0 and ignored). The response carries `loop` (from `LoopState`) and the deprecated `counts`, `more_available`, `next_learning_due_at` (from `LoopState.legacy`), like the server's. Only `LoopResult.queue.toMaterialize` (the served build) is materialized, never the other build's. The SPA follows the client rules of `getReviewQueue` in `openapi.yaml` (C1-C7; C8 is for browsers without a replica and does not apply in the shell, where an answer is applied locally before its `res`); keep going (C2, ADR-039) lasts one review visit and lives in the SPA's memory only: nothing is stored in the WebView (`localStorage["kotobaya.keepGoingDay"]` is no longer used) and native code keeps no keep-going state. `loop.until_stop` (ADR-038) is `LoopState.untilStop`; `loop.keep_going_available` and `loop.until_stop_parts` (ADR-049, C-30) are `LoopState.keepGoingAvailable` and `LoopState.untilStopParts`, copied member by member (`learning`, `later`, `review`, `new`), never computed in the handler; the phase order is the core's (ADR-049: `waiting` before the stop screens), so the handler needs no change beyond the two members. (C-30 did not bump `LOCAL_API_VERSION`; C-31 does, 7.2.) **Keep-going target (C-31, ADR-052):** `keepGoingNewTarget` = the request's `keep_going_new_target` when `keep_going=true`, else `null`; native code never stores a target and native count-only callers pass `null` with `keepGoing = false`. **Presenter (C-31, ADR-051):** the SPA renders the review header, the bar and the screen from core `LoopPresenter.present` only (openapi rule C7); the handler is not involved. The SPA follows rule C6 (one refetch at `loop.later_today.next_due_at` in every phase) on Home and on the review screen. Native surfaces that show a state (the due notification, a widget line) treat `WAITING` as "next card at `nextDueAt`", never as done, and re-evaluate at `nextDueAt`. Native surfaces (widget, due notification, nudge threshold) show `readyLearning + readyReviews` of `ReviewLoop.evaluate` and never count cards themselves; that is the SPA's headline number too (`loop.ready.total`, rule C3, the same with and without keep going), so they need no keep-going state. C-27 materialization is performed by the device (ops minted by rule L-2, at most 3 rounds), since a device token holds `review` and `write`; `to_materialize` is then 0. **When (C-31, normative):** the handler materializes iff `sync_state.last_sync_at` is set (the first complete pull of this pairing has finished; `ShellSyncStatus.first_sync.done`) **and** `sync_state.rebuild_pending` is not set. Nothing else is consulted: not whether a cycle is running, not `consecutive_failures`, not `online`, not `phase` (rule L-9). (The implementation before C-31 also required "no cycle running and no failed cycle", so after every answer the queue came back empty with `to_materialize > 0` for the seconds a cycle took.) Before the first complete pull the handler mints nothing (every deck entry would otherwise be minted here and again by the replica that already has it) and reports `to_materialize` as the core gives it; the SPA shows the first-sync screen then (10.18). ADR-040 / ADR-042: the loader passes `requiredKanji`, `missingKanji` and (for `deck_id`) companions exactly as contracts/core README "C-27" says, and materialization runs step A (kanji notes of `kanjiToMaterialize`, `origin` `auto_kanji`) before step B, as the server does; `kanji_parts` come from core `KanjiParts.build` and `front_furigana` from core `KanjiRequirement` (setting `cards.kanji_exemptions` from the replica), both over the local dictionary. Dictionary data by L-7 steps 1–2 only (installed package, then sidecar cache; never the network): `QueueItem.entry` / `kanji` are `null` on a miss and the card is still served; a deck entry `e:<seq>` is passed to the builder as a pending entry only when steps 1–2 find it, so an entry the device does not have is neither materialized nor counted until gap fill has cached it (10.7). |
| `answerReview`, `undoReview` | Stateless (no session object). Card state is recomputed by the replay path, never by handler arithmetic. After commit the due alarm and widgets are refreshed. |
| `bulkCards` | C-31: **every** action is local, for both selectors (`card_ids`, `query`) and for `dry_run`; all-or-nothing as the server, one `store.transaction`, ops minted by the device in card-id order exactly as the table of `openapi.yaml` `bulkCards` says: `suspend` / `unsuspend` / `bury` / `unbury` / `flag` / `set_preset` / `delete` → one card op per matched card; `add_tags` / `remove_tags` → one `note.updated` (register `tags`) per distinct note; `move` → `deck.item_put` (and `deck.item_removed` with `from_deck_id`) per distinct note. All of these ops are client-emittable (`contracts/sync/ops/index.json`). `set_preset` checks `preset_id` against the replica's `fsrs_params`; `move` checks both decks against the replica (`409 deck_read_only`). Nothing is forwarded. (Before C-31 `set_preset`, the tag actions, `move` and `delete` were forwarded.) |
| `createNote` | Duplicate check against the replica (L-4); kinds by core `CardGenerator` / `CardKinds.isRetired` (ADR-035); `created_by_device_id` is this device. `entry_seq` is resolved by the L-7 lookup (steps 1–2; C-31: no fetch on the request). Found → the note is created locally (minted by the device, never forwarded). Known miss (the installed package is authoritative, or a negative result stands): `422 validation_failed`, `errors[0] = {pointer: "/entry_seq", code: "not_found"}`, as the server. Any other miss — `top60k`, no package, a package older than the server's release: `503 shell_dict_missing` **at once**, nothing minted, and the entry is enqueued for gap fill (`urgent`); the SPA repeats the request with the same `id` on the `shell.dict` event (L-4 makes that safe). This is rare: the SPA takes `entry_seq` from a local search or a local entry page, which only show entries the device has. **`createNote` creates no kanji notes**, whatever a deck's `add_kanji` setting says (also with `deck_id`): like the server's handler it mints exactly `note.created` (the requested note), one `card.created` per generated kind and, with `deck_id`, one `deck.item_put` — nothing else. The `auto_kanji` notes that `add_kanji` asks for are minted only by C-27 materialization in `getReviewQueue` (row above); kanji data is looked up there, by L-7 steps 1–2 only (a miss means "the literal alone", as on the server when `ref.kanji` lacks it). |
| `updateNote` | Setting `entry_seq` to an entry another live note already holds (**re-match collision**): the server merges and answers with the survivor; a device never merges (SYNC.md 8: the server decides). Locally the handler mints the one `note.updated`, and the answer is **the note itself** with its new `entry_seq`, `200`, no `content-location`; two live notes hold the entry on the phone until the server's `note.merged` (and `card.merged`) arrive by pull, after which the id answers with the survivor (L-6). A documented difference from the server's response, not parity-tested. The new `entry_seq` is checked like `createNote`'s. |
| `listDecks`, `getDeck`, `createDeck`, `updateDeck` (answers) | ADR-041: every `Deck` carries `progress`, computed by core `DeckProgress.count` over the replica (deck items, notes, cards, `word_status`, setting `cards.reading_enabled`) exactly as the server computes it; `null` for smart and tombstoned decks. It needs no dictionary data, so it is always available offline. |
| `getDeckCoverage` | C-31: class **`cached`** (was `proxy`; ADR-041 keeps the data on the server: `segments`, `curve`, `difficulty`, per-token counts and the `hide` classes come from media tables and analysis that are not replicated). The deck page's "N of M words learned" line is `Deck.progress` (local) and always works; the coverage block shows the cached answer with its `kotobaya-cached-at`, and on `503 shell_uncached` the SPA shows the progress line and hides the block until `cache.updated`. Neither `503 shell_uncached` nor `503 shell_offline` from this operation is an error state. |
| `createDeck` | `type: user` and `type: smart`: local (one `deck.created` minted by the device). `type: list`: `deck.created` for a list deck is server-only (OPS.md `deck.created`: a pushed one is rejected `server_only`) and needs the server's `deck-build` job, so the handler forwards the request by rules P-1 … P-3 (the one exception of rule L-9) — `503 shell_offline` at once when offline; the answer comes back with `kotobaya-shell-stale: 1` and the deck arrives by pull (`sync.changed`). `type: media` is `422` locally, as on the server. The request is validated locally first in every case. |
| `updateSettings` | One `settings.set` per present key. Applying `cards.reading_enabled` re-derives vocab word statuses through the same code as the pull path. `cards.kanji_exemptions` (ADR-042) is validated as `bool` and needs no re-derivation: the next `getReviewQueue` reads it. C-29: `review.autoplay_audio` is validated as `bool`, `tts.voice` as `string (voice)`, `audio.prefetch_days` as `int` 0–365 (SCHEMA.md 6.1), all as the server; none re-derives anything (`tts.voice` and `audio.prefetch_days` are read by the next queue build and prefetch pass). It never changes a kana word (ADR-046: the displayed form is the package's `dict_entry.headword`, which the shell reads as stored and does not recompute from `kanji_forms`). |
| `createDrillSession` | No op (as the server). Items carry `word_audio` as `getReviewQueue`'s (next row). |
| `getReviewQueue`, `createDrillSession`: `word_audio` (ADR-047, C-29) | Every item's `word_audio` is built as the server builds it (SCHEMA.md 6.6 "Server" rule 1): core `WordAudioResolver.resolve` over the note's fields and the dictionary data of the L-7 lookup; `voice` = `effectiveVoice` of the replica's `tts.voice`; `blob_hash` = the device table `tts_audio` row of the pair when its bytes are cached (`blob_cache.state = 'cached'`) or the device is online, else `null` (SCHEMA.md 6.6 "Device" rule 6; a `note` hash is reported as is). The handler enqueues nothing, calls no server and never blocks on audio. With an empty `tts_audio` table the answer equals the answer of a server that has generated nothing, which is the parity setup (no worker runs there): `blob_hash: null` for every `tts` item on both sides, the note's hash for every `note` item. Filling the table is the native prefetch pass (SCHEMA.md 6.6 "Device"), which calls `resolveSpeech` itself with the device token, outside the bridge. |
| `resolveSpeech` | C-31: **`local`** (was `proxy`). Validation exactly as the server (`422` pointers, the 200-item and 20-urgent limits). Per item, never touching the network: the pair is decided as the server decides it (`text` + `voice` after defaulting; `note_id` through core `WordAudioResolver` over the replica and the L-7 lookup). `source: note` → `ready` with the note's hash (the device cannot know whether the server has the blob; the intercept answers `404` if not). A `tts` pair with a `tts_audio` row → `ready` with that row's `blob_hash` and `duration_ms`. A `tts` pair without a row → `pending`, `blob_hash: null`, `retry_after_ms` **2000** when the device is online and **60000** when offline, and the pair is handed to the native speech resolver (`SpeechResolveQueue.request(voice, text, urgent)`), which calls the server's `resolveSpeech` with the device token in the background, repeats while the server says `pending`, and upserts `tts_audio` (SCHEMA.md 6.6 "Device"); a pair the server reported `failed` less than 600 s ago → `failed` with the remaining time. A `note_id` that is unknown, deleted, merged away, not `vocab` or without speakable text → `none`. No backlog limit applies locally (the server applies its own to the resolver's calls). The SPA's client rules are unchanged (ask again after `retry_after_ms`, at most 5 calls per showing); a card never waits for audio, and the blob itself comes through the intercept (2.7). Offline it answers `pending`, never `503`. |
| `getStatsSummary` (C-31) | **`local`.** The same computation as the server's handler over the replica: `due`, `more_available`, `next_due_at` and the loop-derived members from core `ReviewLoop.evaluate` with the input `getReviewQueue` builds for no `deck_id`, `keep_going=false`, batch 20 and **without materializing**; `done_today`, `totals`, `streak_days` from `review_log`, `card` and `note` by the definitions of `StatsSummary` in `openapi.yaml`; the study day by the device clock (L-5). |
| `getStatsHeatmap`, `getStatsRetention` (C-31) | **`local`.** From `review_log` joined to `card`, `note` and `note_type` in the replica, by the definitions of the operations in `openapi.yaml` (undone and `manual` rows excluded; a review's study day from `reviewed_at`, the row's `utc_offset_min` (else `scheduler.timezone`) and `scheduler.day_rollover_hour`; `from` > `to` and a range over 3660 days → `422` as the server; the defaults of `from` / `to` by the device clock). Reviews minted on this device and not yet pushed are included: the phone may show more than the server until the next cycle. |
| `bulkScheduleCards` (C-31) | **`local`** (was `proxy`). A manual `review.logged` (`review_type: manual`, `manual_action` `set_due` / `reset`) is client-emittable (OPS.md `review.logged`: only `import_source` and `replay_hlc` are server-only) and a device token holds `review`. Same selector, limits, order, transaction and `dry_run` rules as local `bulkCards`; one `review.logged` per processed card with a device-minted UUIDv7 `id` and `reviewed_at` = device now; `reset` of a `new` card skipped; `reschedule` with a `new` card among the matches → `422`, nothing minted; card state recomputed by the replay path. Repeating the call adds rows, as on the server. |
| `listCards`, `listNotes`, `createDrillSession`, `bulkCards` / `bulkScheduleCards` with `query` (C-31) | Every search string core `SearchParser` accepts is evaluated **on the device**; no query is forwarded (before C-31 the handler forwarded queries with dictionary terms it did not trust the device to evaluate). Terms that need dictionary data (reading, gloss, JLPT, frequency of the note's entry) are evaluated over the L-7 lookup, steps 1–2: a note whose entry the device lacks does not match such a term (documented difference from the server until gap fill has cached the entry; parity runs with a full package). A selector matching more than 5000 cards is `422 bulk_too_large` as on the server. |
| `getBlob` | Section 2.7. |

### 4.4 Cached operations — rules K-1 … K-4 (C-31)

The 14 `cached` operations are reads of server data the replica does not hold (analysis over
media, vocabulary-list catalogue, forecasts, confusables, grammar points). The shell answers them
from the device table `response_cache` (`contracts/db/sqldelight/app/…/Local.sq`, SCHEMA.md 5.3;
device-local, **not learning data**, no op, never synced, safe to delete entirely) and keeps it
fresh in the background. All four rules are under rule L-9: the request never waits.

**K-1 Key and entry.** One entry per **cache key**:

```
key = operationId + "\n" + path + "\n" + canonical(query)
```

- `path`: `req.path` exactly as received (the generated client builds one spelling per request).
- `canonical(query)`: split `req.query` at `&`; drop empty pieces; split each at its first `=`
  (no `=`: empty value); percent-decode name and value (UTF-8; `+` is a literal plus, as in 2.2);
  sort the pairs by name, then value, comparing Unicode code points; encode each name and value
  with RFC 3986 unreserved characters literal and every other byte as upper-case `%XX`; join as
  `name=value` with `&`. A repeated name keeps all its pairs. `""` for no query.
- Example: `GET /api/v1/media?limit=20&cursor=&q=%E7%8C%AB` → key
  `listMedia\n/api/v1/media\ncursor=&limit=20&q=%E7%8C%AB`; `?q=%e7%8c%ab&limit=20&cursor=` is the
  same key; `?limit=50` is another.

An entry holds: `status` (always `200`…`299`), `body` (the exact text the server sent),
`content_type`, `etag` (the server's `ETag`, or null), `fetched_at` (device clock, ms), `stale`
(K-4), `last_used_at`. Only a `2xx` answer with media type `application/json` and a body of at
most **4 MiB** (`4194304` bytes of UTF-8) is ever stored; nothing else creates or replaces an
entry. Entries belong to the pairing: `unpairShell` and a `pairShell` that changes `server_url`
delete all of them; a rebuild (SYNC.md 10.3) and a log reset (SYNC.md 6.2) mark all of them
`stale` and delete none.

**K-2 Hit.** An entry exists for the key → the `res` is its `status`, its `body`, `content-type`
as stored, `kotobaya-local: 1`, `kotobaya-cached-at: <fetched_at as RFC 3339 UTC ms>` and
`x-request-id`; `last_used_at` = now. Sent at once, online or not, whatever the entry's age.
Then, **after** the `res` and without the request waiting, one **background revalidation** of the
key starts iff all of: the device is online; no fetch for this key is in flight; no fetch for
this key finished in the last 5 s; and the entry is `stale` **or** its age `now − fetched_at` is
≥ **60 s** **or** negative (the device clock moved back: 10.17). A revalidation is the request
itself, forwarded as rule P-2 forwards one (device token, 12 s limit of P-1, plus
`If-None-Match: <etag>` when one is stored). Its outcome:

| Outcome | Entry | Event |
|---|---|---|
| `2xx`, storable, body bytes **differ** from the stored body | replaced; `fetched_at` = now; `stale` = 0 unless `pending_op` held a `pending` row when the fetch started | `cache.updated {operation_id, path, query, status}` |
| `2xx` with the same bytes, or `304` | `fetched_at` = now; `stale` as above; body untouched | none |
| `2xx` that is not storable (over 4 MiB, not JSON) | unchanged | none |
| `404` or `410` | **deleted** (the resource is gone) | `cache.updated` with that `status` |
| `401` | unchanged; handled as 9.2 (`auth_required`) | none |
| any other status, timeout, connect failure, offline meanwhile | unchanged (the stored answer keeps being served) | none |

**K-3 Miss.** No entry for the key → **immediately** `503 shell_uncached` (section 5),
`kotobaya-local: 1`, **no** `retry-after`. Then, without the request waiting: when the device is
online and no fetch for this key is in flight, one background fetch (as K-2's, without
`If-None-Match`). Storable `2xx` → stored, `cache.updated`. A final refusal — `400`, `403`,
`404`, `410`, `422` — stores nothing and sends `cache.updated` with that `status`, so that the
SPA can show "not found" instead of waiting forever. Anything else (offline, timeout, `5xx`,
`429`, `401`) stores nothing and sends nothing; the next request of the key, or the next warm
pass, tries again. The shell does not validate the request of a `cached` operation: an invalid
query is simply a key that never gets an entry (`cache.updated` with `422` tells the SPA).

SPA (normative): on `503 shell_uncached` show the surface's "not downloaded yet" placeholder
(when `online` is false: its "needs connection" text), keep everything else on the page usable,
and request the operation again **once per `cache.updated`** whose `operation_id`, `path` and
canonical query match, and once when `shell.connectivity` turns online. No timer, no polling, no
spinner that blocks the page. `shell_uncached` is not reported to `reportClientErrors`. On a hit
the SPA renders the body as it would the server's and re-requests on a matching `cache.updated`
(the answer is then the new hit); data on screen is replaced in place, never by a skeleton.

**K-4 Warm, invalidate, evict.**

- **Warm pass.** After every sync cycle that ended successfully (and so after the first sync),
  while online, in the background, at most 4 fetches at a time and at most 64 per pass:
  (1) the **seed requests**, each with the empty query — `listDeckLists`, `listMedia`,
  `listGrammarPoints`, `listConfusables`, `listParseOverrides`, `getStatsForecast`,
  `getStatsKnownWords`, `getStatsCalibration`, and `getDeckCoverage` for every live deck of type
  `media` or `list` in the replica (most recently updated first) — when they have no entry or
  their entry is `stale` or older than 60 s; (2) every other entry that is `stale`, most recently
  used first. Outcomes as K-2. The SPA SHOULD call the seed operations with an empty query
  wherever the defaults do (their first page), so that the first visit of a route is a hit.
  A pass that is interrupted (offline, app killed) is simply not finished; the next cycle starts
  another.
- **Invalidation.** When learning data changes in the replica — the moment a `sync.changed` event
  is due: a committed local write, a pull sub-page that applied an op, a finished rebuild — every
  entry of an operation whose tables (below) include a table the change wrote is marked
  `stale = 1`. A stale entry is still a hit (K-2); it is revalidated on its next hit or by the
  next warm pass. An entry fetched while this device had unpushed ops stays `stale` (the server
  could not have seen them), which is the `pending_op` clause of K-2.

  | Operation | Stale when one of these tables changed |
  |---|---|
  | `listDeckLists` | `deck` |
  | `getDeckCoverage` | `deck`, `deck_item`, `note`, `card`, `review_log`, `word_status`, `media`, `settings` |
  | `getStatsForecast` | `card`, `review_log`, `fsrs_params`, `settings` |
  | `getStatsKnownWords` | `word_status`, `note`, `card`, `review_log`, `settings` |
  | `getStatsCalibration` | `review_log`, `card`, `fsrs_params` |
  | `listConfusables`, `listWordConfusables` | `confusable`, `note`, `word_status` |
  | `listSentences`, `listMediaSentences` | `media`, `parse_override`, `note`, `word_status`, `sentence_grammar_span` |
  | `listMedia`, `getMedia` | `media`, `deck` |
  | `getMediaCoverage` | `media`, `note`, `card`, `review_log`, `word_status`, `settings` |
  | `listParseOverrides` | `parse_override` |
  | `listGrammarPoints` | `grammar_point`, `sentence_grammar_span`, `note` |

  A table missing from a row only delays freshness to the 60 s rule; it never loses data.
- **Size.** The sum of the stored bodies' UTF-8 sizes is at most **16 MiB** (`16777216` bytes).
  After an insert or replacement that exceeds it, entries are deleted in ascending
  `last_used_at` (least recently used first; ties by key) until it fits; the entry just written is
  deleted last. Eviction sends no event; the evicted key is a miss next time.

Worked example (deck page, first visit, online): `getDeckCoverage` for deck `…d01` has no entry →
`503 shell_uncached` in a few ms, the page shows the progress line; the background fetch answers
`200` (41 kB) after 180 ms → stored, `cache.updated {operation_id: "getDeckCoverage", path:
"/api/v1/decks/01931f40-2000-7000-8000-000000000d01/coverage", query: "", status: 200}`; the SPA
requests again → hit, `kotobaya-cached-at: 2026-11-20T10:00:05.300Z`; the block appears. Ten
minutes and 30 answers later the entry is `stale` (`review_log` changed): the next visit is a hit
with the old numbers at once, the revalidation finds other bytes → `cache.updated` → the block
updates in place. The same visit offline: the hit, no fetch, no event.

## 5. Problems

Every non-2xx `res` has `content-type: application/problem+json` and a body with the `Problem`
schema of `openapi.yaml` (`type`, `title`, `status`, `code`, `request_id` required; `type` =
`urn:kotobaya:problem:<code>`; `instance` = the `req.path`). The codes of `openapi.yaml` section 5
keep their meaning. The shell adds:

| Status | `code` | `title` | When | Client action |
|---|---|---|---|---|
| 503 | `shell_offline` | Offline | A `proxy` operation (or `createDeck` with `type: list`, `pairShell`, `syncNow`, `startDictInstall`) while offline or unreachable (rule P-1). Never a `local`, `local_or_proxy` or `cached` operation (C-31). | One calm message; retry when `shell.connectivity` says online. Not reported to `reportClientErrors`. |
| 503 | `shell_uncached` (C-31) | Not downloaded yet | Rule K-3: a `cached` operation whose key has no entry in the response cache. Answered at once, online or not, **without** `Retry-After`. Example body: `{"type":"urn:kotobaya:problem:shell_uncached","title":"Not downloaded yet","status":503,"code":"shell_uncached","instance":"/api/v1/stats/forecast","request_id":"0193a1b2-7000-7000-8000-00000000e021"}`. | Show the surface's placeholder; request again on the matching `cache.updated` (and when `shell.connectivity` turns online). No timer. Not reported to `reportClientErrors`. |
| 503 | `shell_dict_missing` | Dictionary data not on this device | The L-7 lookup ended in a miss that is not a known miss: a keyed dictionary read, or `createNote` / `updateNote` with an `entry_seq`, for an entry that is in neither the installed package nor the sidecar cache (C-31: **online or not**; the reference is enqueued for gap fill and never fetched on the request); a search with nothing to search (no package, empty sidecar cache); `getDictRelease` with no package. Never for `getReviewQueue` or any other rendering (a miss is `null` there), never for `listDictHeadwords`. No `Retry-After`. | Request again on the next `shell.dict` event; offer `startDictInstall` when no package is installed; offline: "connect to look this word up". |
| 503 | `shell_rebuilding` | Rebuilding local data | Rule L-8, and the 20 s readiness limit (2.5). Always with `Retry-After: 2`. | Retry once after `Retry-After`; then show the rebuilding state. |
| 415 | `shell_unsupported` | Not supported in the app | Section 2.4: request body over 4 MiB or not JSON; proxied response over 8 MiB or not text; `getBlob` through the bridge. | None (a feature the SPA should have hidden). |
| 409 | `shell_pending_ops` | Unsynced changes | `unpairShell` without `force` while ops are pending. | Ask, then repeat with `force: true`, or sync first. |

`shell_pending_ops` is the one code beyond the four of ADR-036; it exists so that the refusal of
`unpairShell` cannot be confused with a server conflict. `shell_uncached` (C-31) is separate from
`shell_offline` because it is also the answer while online, and its remedy is an event, not
connectivity (alternative rejected: an empty `200` body, which the SPA could not tell from "the
server says there is nothing").

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
| `min_local_api` | integer ≥ 1 | Lowest `LOCAL_API_VERSION` this bundle works with. `3` for every bundle built from the C-31 SPA (it sends `keep_going_new_target` and expects the classes and rules of 7.2 row 3); `2` for the ADR-037 … C-30 SPA. | `3` |
| `files` | object, path → lower-case hex SHA-256 | Every file of the bundle except the manifest itself. | `{"index.html":"9f86d0…"}` |

Over-the-air bundles (capability `ota`, SHELL-Z2) use the same manifest: downloaded to
`files/web/<web_version>/`, every hash verified before activation, activated on the next cold
start, and reverted to the bundled copy when `ready` is not received within 10 s on two
consecutive starts.

### 7.2 `LOCAL_API_VERSION`

An integer constant of the shell, **`3`** for this contract, reported as
`ShellInfo.local_api_version`.

| Value | Since | Why |
|---|---|---|
| `1` | SHELL-C1 (ADR-036) | First version. |
| `2` | LOOP-C (ADR-037) | `getReviewQueue` answers with `ReviewQueue.loop`, which the ADR-037 SPA cannot render without; that SPA's bundle has `min_local_api: 2`. Capability `display` appears with it. |
| `3` | C-31 (ADR-050, ADR-051, ADR-052) | A rule changed incompatibly for an older shell: the C-31 SPA sends `getReviewQueue?keep_going_new_target=…`, which a version-2 shell refuses as an unknown query parameter (`422`, rule L-3); it relies on `getStatsSummary`, `getStatsHeatmap`, `getStatsRetention`, `resolveSpeech`, `bulkScheduleCards` and every `bulkCards` action being local, on the class `cached` with `503 shell_uncached` / `cache.updated`, on `ShellSyncStatus.first_sync`, and on P-2 / P-3 without pre-push and hold. Capability `response_cache` appears with it. An SPA with `min_local_api: 2` keeps working on a version-3 shell: it sends no target (endless keep going as before), treats `503 shell_uncached` like any `503` of a proxied read, and ignores `cache.updated`. |

- **Bump it** only when a change could break an SPA built against the previous value: an
  operation leaves class `local` or `local_or_proxy` (it would stop working offline); a bridge
  message, a field or a shell operation is removed or changes meaning; a rule of this file changes
  incompatibly; **or a field a new SPA requires is added** to a local operation's answer (the
  bump is what lets that SPA's `min_local_api` demand it; an older SPA on the newer shell keeps
  working, here through the deprecated legacy fields).
- C-29 (ADR-047) did **not** bump it: `QueueItem.word_audio` is optional for the SPA (absent =
  null = no speaker button). (`resolveSpeech` was proxied then; it is local since version 3.)
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
`proxy`, `cached` and `/shell/v1` operations are not parity-tested (a `cached` answer *is* the
server's bytes; rules K-1 … K-4 are tested by LF-A2 with a fake upstream).
C-31 setup additions, both sides: the shell harness has `sync_state.last_sync_at` set (so it
materializes, 4.3) and an **empty** `tts_audio` table and no speech resolver running; the server
harness runs no worker. The shell harness reports itself **offline** to `resolveSpeech`'s
`retry_after_ms` rule, and scenarios `ignore` `/items/*/retry_after_ms` there.

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
| `queue.loop_waiting`, `queue.loop_empty` | `queue-loop-waiting-empty` | ADR-037 B6: nothing ready, no new card anywhere, learning later → `waiting` (`keep_going_available` false); nothing later → `empty`; with and without `keep_going`. |
| `queue.loop_waits_before_stop` | `queue-loop-wait-before-stop` | ADR-049 (C-30): reviews unlimited, goal reached, one learning card ready, two due later today, new cards remain → `reviewing`; then `waiting` with `keep_going_available` true (never `goal_reached` while a learning card is due today; nothing served early); `keep_going=true` at the same instant serves a new card; `goal_reached` only after the last learning card graduated; `until_stop_parts` sums to `until_stop` in every step. |
| `queue.kinds_counts` | `queue-kinds-filter-counts` | The `kinds` filter leaves `counts` unchanged. |
| `queue.reviews_unlimited` | `queue-reviews-unlimited` | `scheduler.reviews_unlimited` (ADR-034), toggled by a local `updateSettings`. |
| `cards.reading_retired`, `cards.reading_reenabled`, `notes.create_no_reading_card` | `reading-retired-reenabled` | Reading cards retired, re-enabled; `createNote` makes no reading card while retired (ADR-035). |
| `queue.materialize_count` | `queue-materialize-count` | C-27: how many deck entries one fetch materializes; a second fetch creates nothing. |
| `queue.loop_learning_past_day_end` | `queue-loop-day-end-learning` | ADR-049 (C-30): goal reached and the only learning card falls due exactly at `day_ends_at` → `goal_reached`, `later_today.count` 0, `until_stop` 0 (no waiting for the next study day); a learning card due 1 ms before `day_ends_at` → `waiting`; after the rollover both are ready learning. |
| `queue.until_stop` | `queue-until-stop` | ADR-038: `loop.until_stop` counts ready and later learning, ready reviews and the new cards left to the goal; Again keeps it, Good lowers it; ADR-049: with the goal reached under the review cap and two learning cards due later the phase is `waiting` (not `done_for_today`) and `until_stop` is the later-today count; with `keep_going` it is the backlog plus the batch. |
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
| `queue.kanji_exemptions_on`, `queue.kanji_exemptions_off` | `queue-kanji-exemptions` | ADR-042, ADR-045: with `cards.kanji_exemptions` on, an ateji word with learnable kanji gets its kanji cards **and** `front_furigana`, a word without learnable kanji gets no kanji cards and `front_furigana`, an untagged word with learnable kanji gets them and no furigana, a mixed word only its learnable one; switched off by a local `updateSettings`, every kanji is required and no front has furigana. |
| `queue.kanji_kana_word`, `queue.kanji_tagged_joyo_only` | `queue-kanji-rare-forms` | ADR-046 (C-28f): a word whose kanji forms are all `rK` / `sK` (イタリア 伊太利, おにぎり お握り; stand-ins for シンガポール 新嘉坡 and かるた 歌留多) is served with its kana `headword` as front, no kanji card and `front_furigana` false, with `cards.kanji_exemptions` on and off; a tagged word (金糸雀) gets cards for its jōyō kanji only and `front_furigana`; a tagged word without jōyō kanji (鯛) none; an untagged word (炒る) still gets its frequency-only kanji. |
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
| `stats.summary_local` | `stats-summary-local` | C-31: `getStatsSummary` from the replica: its `due` and loop-derived members equal `getReviewQueue`'s `loop` at the same instant, before and after a local answer; `done_today` counts the answer. |
| `stats.heatmap_local`, `stats.retention_local` | `stats-heatmap-retention-local` | C-31: reviews on two study days, one undone, one `manual`: the heatmap counts per day without the undone and manual rows; retention over `scheduled` rows only, grouped by `card_kind` and by `day`; `from` after `to` is `422`. |
| `cards.bulk_all_actions_local` | `bulk-cards-all-actions-local` | C-31: `bulkCards` `add_tags`, `remove_tags`, `set_preset`, `move`, `delete` answered locally with the server's counts and effects (`getNote` tags, `getCard` preset, `listDeckItems`, `410` after delete); `add_tags` that changes nothing is counted in `skipped`. |
| `cards.bulk_schedule_local` | `bulk-schedule-cards-local` | C-31: `bulkScheduleCards` `reschedule` (new `due_at` on a review card; `422` when a matched card is `new`) and `reset` (card back to `new`; a `new` card skipped), `dry_run` changes nothing; `listCardReviews` shows the manual rows. |
| `speech.resolve_local` | `resolve-speech-local` | C-31: `resolveSpeech` with nothing generated: a `text` item and a vocab `note_id` → `pending`; an unknown `note_id` → `none`; `text` together with `note_id` → `422` at `/items/0`; more than 20 urgent items → `422` at `/urgent`. |
| `queue.keep_going_target`, `queue.keep_going_batch_not_owed` | `queue-keep-going-target` | ADR-052 (C-31): goal 2 reached, 5 new cards left → `goal_reached`; `keep_going=true&keep_going_new_target=4` serves exactly 2 new cards (`until_stop_parts.new` 2, not bounded by `limit`); after one answer, a request **without** `keep_going` at that instant is `goal_reached` with `until_stop_parts.new` 0 and no item (the unfinished batch is not owed); a fresh press (`keep_going_new_target=5` = 3 + 2) serves a full batch of 2 again; with the target reached the same request is `goal_reached` with `keep_going` true and `keep_going_available` true (never a further card); a target below `new.today` serves no new card; the parameter without `keep_going=true` is ignored; `keep_going=true` without it is the endless stream; a malformed or negative target is `422` at `/keep_going_new_target`; with no new card left anywhere the phase is `empty` without Keep going, in both modes. |

C-28 (ADR-040): the scenarios written before it that fetch the queue with new or already studied vocab cards now
use kana-only entries (うっかり, えっと, …) where they used 明白, あの人, …, so that they keep showing
what they were written for and mint no kanji notes; their `expect`s are unchanged apart from
`until_stop` inside whole-`/loop` expectations. Kanji behaviour is covered by the `queue.kanji_*`
scenarios only. The ADR-042 scenario uses the fixture's 寿司, 昨日 and 鱈 and a note 鱈子 without an
entry, because 海老, 林檎, 薔薇 and 今日 are not in `fixtures/dict/JMdict_subset.xml`.
The ADR-046 scenario (`queue-kanji-rare-forms`) likewise uses イタリア (1021360, 伊太利; 伊 is grade
9 with a frequency rank, like 嘉 of 新嘉坡) and おにぎり (1001620) for シンガポール (1063410) and かるた
(1590710), which are not in the fixture. **Harness requirement (ADR-046 (a)):** the dictionary both
harnesses load MUST carry `headword` by SCHEMA.md 4.5 "Headword" (the reading for the 137 fixture
entries whose kanji forms are all `rK` / `sK`), on the server side in `ref.dict_entry` and in the
shell's fixture package alike; a fixture loader that still writes the first kanji form fails this
scenario at step 0. No scenario older than C-28f uses one of those entries, so their goldens do
not change.

`queue.session_cap_more_available` (scenario `queue-session-cap-more-available`) was retired by
ADR-037: there is no session cap. Every golden whose steps call `getReviewQueue` is re-recorded by
LOOP-SRV (the body gains `loop`; the order follows the proportional mix).

Further scenarios (one per remaining `local` operation) are added by the L tickets through a
`CONTRACT_CHANGE_REQUEST`; they need no schema change.

C-31: the six scenarios of the last six rows are new and hand-written; their goldens are
recorded by the server ticket of the local-first round (`RECORD=1`), and the shell tickets LF-A3
(stats), LF-A5 (bulk, search) and LF-A4 (speech) make the shell reproduce them. Their `expect`s
pin only what the contract text fixes (statuses, counts, phases); everything else is the golden's.

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
  `cached` operations keep answering their hits, and their background fetches change nothing
  while the server answers `401` (rule K-2);
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
  `online` is false, instead of failing request by request. C-31: no `proxy` operation is called
  while rendering Home, Review, Decks, a deck page, Search, Cards, Notes, the note editor,
  Dictionary, Struggle, Stats or Settings; on those routes a `proxy` operation is only ever the
  result of an explicit user action (a write such as `setConfusable`, `updatePreset`,
  `createDeck` with `type: list`), which shows its own inline "needs connection" message on
  `503 shell_offline`. A surface backed by a `cached` operation follows rule K-3 instead.
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
| 10.7 | **Dictionary data missing on the device** | Three situations, all by the one lookup of rule L-7. C-31: nothing here is forwarded on a request any more; the server is reached by gap fill and by the package install only, in the background. (a) **No package installed**: `getDictRelease` is `503 shell_dict_missing`; searches run over the sidecar cache alone (`503 shell_dict_missing` when it is empty too); everything in (b) applies to every entry; the package is auto-installed on an unmetered network (L-7 "Auto-install"). (b) **Package installed but an entry is not in it** (scope `top60k` and a word outside it, e.g. from a list deck): the sidecar cache is asked next; gap fill (SHELL-L9) fills it after each sync for every entry and kanji that local notes, deck items and queue-relevant cards reference. Until an entry is cached: its card is **still served** by `getReviewQueue` with `entry: null` (never dropped, never an error, never a network wait); a deck entry `e:<seq>` without a note is not passed to the queue builder (contracts/core README C-27: "a device without it skips it until it has it"), so it is neither materialized nor counted in `counts.new` yet; `getDictEntry` / `getKanji` / `createNote` answer `503 shell_dict_missing` at once, online or not, and enqueue the reference for gap fill (`urgent`); the `shell.dict` event tells the SPA when to ask again. A package of scope `all` that is **older than the last known server release** (or whose server release is not known yet) is treated exactly like `top60k` here: a word new in the server's release is fetched by gap fill, served with `entry: null` and not materialized only until then, and is `503 shell_dict_missing` — never `404` / `422` — until gap fill has cached it. `counts.new` can therefore lag the server's by the deck entries not yet cached; the lag ends with the next gap-fill run or package update. (c) **Known miss**: the installed package is authoritative (L-7), or the server answered `404` for this reference under its current release (negative result): `null` in rendering, `404 not_found` for the keyed reads, `422` `/entry_seq` `not_found` for `createNote` / `updateNote`, without asking again. A negative result is **not permanent**: all of them are dropped when the last known server release changes, and an authoritative package stops being authoritative the moment a newer server release is seen; the references are then looked up again as in (b). Notes without `entry_seq` are never affected. `ShellDictInstall.gap_fill` reports how many references are cached and how many are still missing. Parity: `dict-entry-missing` (case c). **Documented difference (ADR-042, ADR-043):** core `KanjiRequirement` needs the entry's form tags and per-sense `uk` and the kanji's grade and frequency rank. While the device lacks the entry it sees no tags, and while it lacks a kanji row it sees no facts: such a word is not exempt, such a kanji is not learnable. So, until gap fill has cached them, the phone may require other kanji for that word than the server (none where the server requires some, or a jōyō kanji of a `uk` word the server exempts), may mint a kanji note the server would not, and shows `front_furigana` where the server does not or the reverse. Nothing is lost: a kanji note minted either way is an ordinary note that syncs (one live note per literal, SYNC.md 8.1), and after gap fill both sides evaluate the same rule on the same data. Not parity-tested (parity runs with a full package). |
| 10.8 | **Snapshot / replica rebuild** | Rule L-8: `503 shell_rebuilding` + `Retry-After: 2` for local operations while `rebuild_pending`; nothing minted, nothing materialized (4.3); proxy operations still forwarded; cached operations still answered from the cache (every entry is marked `stale`, K-1). Installing a media or deck snapshot (SYNC.md 7.6) is one transaction and blocks nothing: readers see the old rows or the new rows. The startup replay is the readiness gate (2.5), not `shell_rebuilding`, unless it exceeds 20 s. |
| 10.9 | **Empty replica** | Paired, nothing pulled yet: every local read answers `200` with empty lists / zero counts and the built-in rows (note types, Default preset, default settings); `getReviewQueue` → `items: []`, counts 0, `more_available: false`; the three local stats operations → zero counts and empty `days` / `groups`. Never `500`, never `404` for a list. Parity: `empty-replica`. The SPA shows the first-sync screen from `ShellSyncStatus.first_sync` (10.18). |
| 10.10 | **Reload / renderer crash** | 2.5 "Reload". In-flight writes complete; their `res` is dropped; the SPA refetches on `hello`. |
| 10.11 | **Response lost, request retried** | A new bridge `id` with the same API-level id: L-4 makes it safe for creates, `answerReview` and `undoReview`; register writes (`updateNote`, `updateSettings`, suspend, …) are safe to repeat (a second op with a later HLC and the same value). |
| 10.12 | **Out-of-order answers** | The SPA sends answer 1 then answer 2; their `res` may arrive 2 then 1. Each request is one transaction, so the replica is consistent; the SPA awaits a write before the read that depends on it. |
| 10.13 | **Concurrent native writers** | AnkiProvider, on-device AnkiConnect, widgets and the sync engine write the same replica while the SPA runs. All go through the same op path and store transactions; the SPA learns of them by `sync.changed` / `note.created`. |
| 10.14 | **Proxied write while local ops are pending** | C-31: the proxied write is forwarded at once (P-2: no push first), so the server applies it on data that lacks the phone's pending ops; those are pushed by the next cycle and merge by the normal rules (registers: last writer by HLC, SYNC.md 7.1; a delete beats an edit, 7.3; reviews append and replay, 9). Nothing is lost on either side; the only visible effect is order, e.g. a preset edited through the proxy and a card moved to that preset locally converge whichever arrives first. |
| 10.15 | **Empty response cache** (C-31) | Fresh pairing, after `unpairShell`, or after the user cleared app storage for the cache: every `cached` operation answers `503 shell_uncached` (K-3) and, when online, fetches in the background; the warm pass after the first successful cycle fills the seed requests. Offline with an empty cache the surfaces show their "needs connection" placeholder and every `local` surface works. Nothing is lost: the cache holds no learning data. |
| 10.16 | **Duplicate, late or unmatched `cache.updated`** (C-31) | The same event twice, an event for a key the SPA no longer shows, or one that arrives after the SPA already re-requested: harmless. The SPA's only reaction is to request a shown operation again, which is a hit and idempotent. An event lost in a reload is covered by `hello` (refetch what is shown). The shell coalesces to one per key per second. |
| 10.17 | **Clock skew and `kotobaya-cached-at`** (C-31) | `fetched_at` is the **device** clock at the fetch; the server's `Date` is not used. Device clock set **back** after a fetch: `now − fetched_at` is negative → treated as old enough to revalidate (K-2), the header still reports the stored instant (a future time; the SPA shows "just now"). Device clock set **forward**: entries look older and are revalidated sooner; harmless. Nothing is ever evicted or refused because of its age, and no ordering decision uses `fetched_at` (LRU uses `last_used_at`, which follows the same clock and only affects which entry is evicted first). |
| 10.18 | **First sync, and first sync interrupted** (C-31) | From `pairShell` until the first cycle has pulled to `has_more = false` (`sync_state.last_sync_at` set), `ShellSyncStatus.first_sync.done` is false. The SPA shows a full-screen "Setting up" state with `first_sync.applied` / `first_sync.total` (indeterminate while `total` is null) — the one place a user of the shell waits for the network. During it: local reads answer from the partial replica (never an error); local writes are allowed (native intake, AnkiConnect) and mint as always; `getReviewQueue` **does not materialize** (4.3) and the warm pass, dictionary auto-install and audio prefetch have not started. **Interrupted** (offline, app killed, server error): every committed pull sub-page stays (SYNC.md 6.1), `applied` keeps its value across the restart because it is the stored pull cursor, `phase` says why it stopped (`offline`, `unreachable`, `error`), the screen stays with a "waiting for connection" line and the pull resumes at `pull_seq` on the next trigger; nothing is pulled twice and nothing is minted for deck entries meanwhile. A rebuild or log reset later does **not** reset `done` (`last_sync_at` stays set); the rebuild gate (L-8) covers that time. `unpairShell` followed by `pairShell` keeps the replica and `last_sync_at`, so `done` stays true. |
| 10.19 | **Local write while a pull is applying** (C-31) | A pull sub-page holds SQLite's one write connection. Sub-pages are at most 50 ops and the engine yields between them (SYNC.md 6.1 "Device scheduling"), so a local answer waits for at most one sub-page (tens of ms) and at most the 5 s `busy_timeout` of rule L-9. Its ops are minted with an HLC above everything the sub-pages before it observed; the rest of the page applies after it by the normal rules. |
| 10.20 | **Many answers, one cycle** (C-31) | `localChange()` is debounced (2 s trailing, 10 s at most): 40 answers in 3 minutes cause at most 18 cycles and usually far fewer, not 40. A cycle that fails changes nothing for the SPA: `shell.sync` reports it, local operations and materialization continue (L-9), the ops stay in `pending_op` and the backoff of the engine applies. |

## 11. Cross-check: table ↔ sync op ↔ operation in the shell

Every learning-data table of `contracts/db/SCHEMA.md` 4.1 (plus `media`, `parse_override`), the
ops that write it, and how the SPA reads and writes it on the phone. "Local" operations mint as
the device (L-2); "proxied" ones are minted by the server and reach the replica by pull (P-3);
"cached" ones (C-31) are reads answered from the response cache (4.4) and never write. C-31 moved
operations between the last two columns and into the local one; no row, op or table was added or
removed.

| Table | Sync ops | Local operations | Proxied (`proxy`) and cached (`cached`) operations |
|---|---|---|---|
| `note_type` | `note_type.created`, `note_type.updated`, `note_type.deleted` | read: `listNoteTypes`, `getNoteType` | write: `createNoteType`, `updateNoteType`, `deleteNoteType` |
| `note` | `note.created`, `note.updated`, `note.deleted`, `note.merged` (server) | `createNote`, `updateNote`, `deleteNote`, `getReviewQueue` (C-27 materialization), `bulkCards` actions `add_tags`, `remove_tags`; read: `listNotes`, `getNote` | — |
| `card` | `card.created`, `card.suspended`, `card.unsuspended`, `card.buried`, `card.unburied`, `card.flag_set`, `card.preset_set`, `card.deleted`, `card.merged` (server) | `createNote`, `getReviewQueue` (C-27), `bulkCards` (suspend, unsuspend, bury, unbury, flag, `set_preset`, `delete`); read: `listCards`, `getCard`, `getStatsSummary` | proxied: `createCard`, `updateCard`, `deleteCard`; cached read: `getStatsForecast` |
| `review_log` | `review.logged`, `review.undone` | `answerReview`, `undoReview`, `bulkScheduleCards` (manual rows); read: `listCardReviews`, `listHotWords`, `listHotKanji`, `listLeeches`, `createDrillSession`, `getStatsSummary`, `getStatsHeatmap`, `getStatsRetention` | cached read: `getStatsCalibration`, `getStatsForecast` |
| `word_status` | `word.status_set`, `word.status_cleared` | `setWordStatus`, `clearWordStatus`; read: `getWordStatus` | cached read: `getStatsKnownWords` |
| `deck` | `deck.created`, `deck.updated`, `deck.deleted`, `deck.content_published` (server) | `createDeck` (`type` `user`, `smart`), `updateDeck`, `deleteDeck`; read: `listDecks`, `getDeck` | proxied: `createDeck` with `type: list` (forwarded: server-only op and `deck-build` job), `deleteMedia` (its media deck); cached read: `listDeckLists`, `getDeckCoverage` |
| `deck_item` | `deck.item_put`, `deck.item_removed` | `addDeckItems`, `putDeckItem`, `removeDeckItem`, `createNote` (with `deck_id`), `bulkCards` action `move`; read: `listDeckItems` | — |
| `confusable` | `confusable.suggested` (server), `confusable.confirmed`, `confusable.rejected` | — | proxied: `setConfusable`; cached read: `listWordConfusables`, `listConfusables` |
| `grammar_point` | `grammar.created`, `grammar.updated`, `grammar.deleted` | — | proxied: `createGrammarPoint`, `updateGrammarPoint`, `deleteGrammarPoint`, read `getGrammarPoint`; cached read: `listGrammarPoints` |
| `sentence_grammar_span` | `grammar.span_put`, `grammar.span_removed` | — | `putGrammarSpan`, `removeGrammarSpan`; read: `listGrammarExamples` |
| `settings` | `settings.set` | `updateSettings`; read: `listSettings` | — |
| `fsrs_params` | `fsrs_params.created`, `fsrs_params.updated`, `fsrs_params.deleted`, `fsrs_params.published` (server) | read: `listPresets`, `getPreset` | `createPreset`, `updatePreset`, `deletePreset` (`optimizePreset` is hidden) |
| `media` | `media.created` (server), `media.updated`, `media.deleted`, `media.content_published` (server) | — | proxied: `updateMedia`, `deleteMedia`, read `getSentence`; cached read: `listMedia`, `getMedia`, `getMediaCoverage`, `listMediaSentences`, `listSentences` |
| `parse_override` | `parse_override.put`, `parse_override.deleted` | — | proxied: `putParseOverride`, `deleteParseOverride`; cached read: `listParseOverrides` |

Tables with no local write (`note_type`, `confusable`, `grammar_point`, `sentence_grammar_span`,
`fsrs_params`, `media`, `parse_override`) are written through the proxy while online and are
unavailable for writing offline; this is the price of not re-implementing their handlers and is
visible to the user as the "needs connection" message of the action (9.4). Their list reads are
`cached` (or `local` for `note_type` and `fsrs_params`), so the screens that show them open
offline.

Not learning data, listed for completeness — `proxy`: `getHealth`, `getOwnDevice`,
`updateOwnDevice`, `parseText`, `testGrammarRules`, `listJobs`,
`getJob`, `synthesizeSpeech`, `queryBlobs`, `putBlob`, `listJpdbVocab`, `exportAll`, `exportAnki`;
`local` (C-31): `resolveSpeech` (device table `tts_audio`). Device tables behind C-31 that are not
learning data and have no op: `response_cache` (4.4), `tts_audio`.

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
