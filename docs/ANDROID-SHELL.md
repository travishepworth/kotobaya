# Android web shell — design (ADR-036)

Human decision 2026-11-16: the phone UI becomes the SvelteKit SPA in a WebView; all Compose screens go.
Kept natively: SQLDelight replica + `SqlDelightDeviceStore`, sync, queue, notes, dict, media, search, AnkiProvider,
AnkiConnect, Glance widgets + tile, alarms, UnifiedPush, intake parsing, pairing/TokenStore.

Verified against the repo: android/ = 28.3k lines, 9.6k in files importing `androidx.compose`; web/ is online-only
(generated `typescript-fetch` client, `Configuration{basePath:'/api/v1'}`, one `EventSource`, one
`new Audio('/api/v1/blobs/<hash>')`); `web/build` is 15 MB; device tokens carry `read, write, review` only
(no `import`, no `admin`); REST review ops are stateless (`getReviewQueue` / `answerReview{id}` / `undoReview{review_id}`),
so a local implementation needs no session object. Glance needs the Compose *runtime* + compiler plugin: those stay.

```
ADR-036 (human, 2026-11-16): Android app is a WebView shell around the web SPA — Compose screens deleted; the SPA is bundled in the APK and served same-origin from `https://appassets.androidplatform.net`; a transport-independent Kotlin "local API" answers the OpenAPI operations listed in `contracts/api/shell/ops.json` from the SQLDelight replica and mints ops as the device (OpMinter), everything else is proxied to the server with the device token or fails `503 shell_offline`; SPA↔native transport is an origin-restricted `WebMessageListener` bridge plugged into the generated client's `fetchApi`, plus `shouldInterceptRequest` for GET blobs — one UI to maintain, offline guarantees and all native integrations unchanged, no sync/db/openapi change. — Rejected: loopback HTTP server (port open to every app on the device, secret must live in JS and in `<audio src>` URLs, CORS preflights or an unstable origin); offline in the SPA itself (service worker + wasm SQLite: second replica/sync implementation); intercept-only transport (POST bodies unreadable); keeping Compose for review only (two UIs again). — Reverse: `LocalApi.handle()` is transport-free, so a loopback or remote transport is one adapter; the legacy Compose launcher stays until SHELL-Z1; after that, revert Z1.
```

## 1. Transport

**Decision: same-origin bridge.** The SPA is loaded from `https://appassets.androidplatform.net/` (WebViewAssetLoader),
so `API_BASE` stays `/api/v1`. Two entry points feed ONE dispatcher `LocalApi.handle(ShellRequest): ShellResponse`:

| Path | Mechanism | Used for |
|---|---|---|
| Bridge | `WebViewCompat.addWebMessageListener(webView, "KotobayaShell", setOf("https://appassets.androidplatform.net"))`; SPA passes `fetchApi: shellFetch` to both generated `Configuration`s | every generated-client call (all methods, JSON bodies), events, navigation, back, haptics |
| Intercept | `shouldInterceptRequest` for `GET /api/v1/blobs/{hash}` only | `<audio src>`, `<img src>` (no body, no headers needed) |

Bridge messages are JSON strings; every message has `v:1` and `t`. Unknown `t` or unknown properties are ignored (additive).

| Dir | `t` | Fields | Example |
|---|---|---|---|
| web→native | `req` | `id` (UUID, unique per page life), `method`, `path` (starts `/api/v1/` or `/shell/v1/`), `query` (raw, no `?`, may be `""`), `headers` (lower-case names), `body` (string or null) | `{"v":1,"t":"req","id":"…","method":"POST","path":"/api/v1/review/answer","query":"","headers":{"content-type":"application/json"},"body":"{…}"}` |
| native→web | `res` | `id`, `status`, `headers`, `body` (string or null) | `{"v":1,"t":"res","id":"…","status":200,"headers":{"content-type":"application/json","kotobaya-local":"1"},"body":"{…}"}` |
| web→native | `ready` | `web_version`, `min_local_api` | sent once after hydration |
| native→web | `event` | `event` = the `Event` envelope of `GET /events` (`type`, `at`, `data`) | `hello` on `ready`; `sync.changed`, `note.created` after local apply/pull |
| native→web | `navigate` | `path` (must match `^/[A-Za-z0-9\-._~/?&=%+]*$`, no `//`) | `{"v":1,"t":"navigate","path":"/review"}` |
| native→web / web→native | `back` / `back.result` | `id`; `handled: bool` | section 4 |
| web→native | `back.state` | `can_handle: bool` | on every route/overlay change |
| web→native | `haptic`, `theme` | `kind: tick\|confirm\|reject`; `dark: bool, surface: "#rrggbb"` | |

Invariants: one `res` per `req.id`, a duplicate `id` is dropped; requests run concurrently on `Dispatchers.IO` and
replies are posted on the main thread in completion order (not request order); request body > 4 MiB or non-JSON
content type → `415 shell_unsupported`; a `req` before the replay readiness gate (`awaitReady`) waits, it is not
rejected; SPA-side timeout 30 s → `FetchError`; page reload invalidates outstanding ids (late `res` ignored).
`EventSource` is not used in shell mode: `openEventStream` subscribes to `event` messages (no SSE through intercept).

Security: no socket. The listener only fires for the allowed origin and the handler also requires `isMainFrame`.
No `addJavascriptInterface`, `allowFileAccess=false`, `allowContentAccess=false`, mixed content never allowed,
`shouldOverrideUrlLoading` sends every non-app origin to the system browser, the device token never enters JS.
`WebViewFeature.WEB_MESSAGE_LISTENER` missing → native "Update Android System WebView" screen (strings via i18n).
New dependency: `androidx.webkit:webkit` (asset loader + message listener).

## 2. Shipping the SPA

- **Phase 1 (SHELL-A1):** Gradle task `bundleWeb` runs `pnpm install --frozen-lockfile && pnpm build` in `web/`
  (inputs `web/src`, `assets/card`, `clients/typescript`, lockfile; output copied to
  `android/build/generated/shellAssets/web/`, registered as an assets srcDir; `-PskipWebBundle` reuses the last output).
  It is the *same* build nginx serves: shell mode is detected at runtime, never a build flag.
  The asset path handler serves files, and answers any path without a file extension with `index.html` (SPA fallback).
- The web build writes `shell-manifest.json`: `{web_version, build_sha, min_local_api, files:{path:sha256}}`.
  Shell constant `LOCAL_API_VERSION` (int, starts at 1; bump only when an op moves out of `local` or a bridge
  message changes incompatibly). Shell refuses a bundle with `min_local_api > LOCAL_API_VERSION` (bundled one can never fail this).
- **Later (SHELL-Z2, not needed for the first phone test):** OTA. The server's static site already exposes the
  manifest; the app downloads a newer bundle to `files/web/<web_version>/`, verifies every sha256, activates on the
  next cold start, and reverts to the bundled copy when `ready` is not received within 10 s on two consecutive starts.

## 3. Auth, pairing, shell mode

- The device token stays in `TokenStore` (Keystore). In shell mode `getPrincipal` is `local`: it returns
  `{kind:"device", scopes:["read","write","review"], device_id, name, …}` from the stored pairing, so the SPA is
  "logged in" offline with no cookie and no CSRF. Unpaired → `401 unauthenticated` for every op except
  `/shell/v1/info` and `/shell/v1/pair`; the SPA's `/login` route renders the Pair screen in shell mode
  (server URL + code, or a pasted `kotobaya://pair?server=…&code=…`; `PairingUri` + `PairingRepository` reused).
- A proxied `401` (token revoked) is passed through; the SPA shows Pair again. Local data and pending ops are kept.
- **Detection:** `window.KotobayaShell` exists before any page script (injected by the listener). Then
  `GET /shell/v1/info` → `{shell_version, local_api_version, paired, server_url, online, capabilities:[…]}` with
  capabilities `sync, pairing, dict_install, offline_storage, notifications, push, ankiconnect, takoboto, haptics, ota`.
  `event{type:"shell.connectivity", data:{online}}` keeps `online` fresh. UI gates on a capability, never on "is Android".
- **Hiding:** three independent rules in the SPA: (a) scope — anything needing `admin`/`import` is hidden when the
  principal lacks it (Import, Tokens, other devices, optimizer; also correct for personal tokens on the web);
  (b) mode — routes whose operations are all `proxy` show a "needs connection" state while `online=false`;
  (c) `logout` is replaced by "Unpair" in device settings.

## 4. Native ↔ web navigation

`MainActivity` keeps its name (widget/tile/notification PendingIntents unchanged) and becomes the WebView host.

| Source | Native handling | SPA path |
|---|---|---|
| Launcher | load last path saved in `onSaveInstanceState`, else `/` | `/` |
| Widget, tile, due notification (`kotobaya://review`) | — | `/review` |
| Hot-word widget, `kotobaya://word/<seq>` | — | `/dictionary?seq=<seq>` |
| `kotobaya://struggle` | — | `/struggle` |
| Share sheet, PROCESS_TEXT, `kotobaya://add?word=&sentence=` | `IntakeActivity` becomes a no-UI trampoline: `IntakeParser` (reused) → `MainActivity` | `/search?q=<word or text>[&sentence=<s>]` |
| `kotobaya://pair?…` | — | `/login?pair=<encoded uri>` |
| Server-event notification | — | `/settings` (sync stale), `/media` (import finished) |

Delivery: SPA `ready` seen → `navigate` message; otherwise the path becomes the initial URL. A path failing the
regex is replaced by `/`. Intake loses the sheet-over-the-other-app look (second-look item).

**Back (ADR-030 preserved).** Native `OnBackPressedCallback`, enabled iff the last `back.state.can_handle` was true
(so predictive back-to-launcher still animates on Home). On back, native sends `back{id}`; the SPA runs, first match wins:
1. an open dialog/sheet closes; 2. on `/review` with an answer of this visit not yet undone and no undo by back in
the last 3 s → undo once (`undoReview` with the kept review id) + toast; 3. `history.length` allows → `history.back()`;
4. on a non-Home tab → `goto('/')`; 5. Home → `handled:false`. No `back.result` within 300 ms → native
`webView.canGoBack() ? goBack() : moveTaskToBack(true)`. The 3 s rule is a TS port of `ReviewBackPolicy` with its test cases.

Haptics: `haptic` → `View.performHapticFeedback` (no permission). Insets: native pads the WebView with system-bar and
IME insets (`100dvh` then shrinks with the keyboard; `env(safe-area-inset-*)` is 0) and colours the bars from `theme`.
`mediaPlaybackRequiresUserGesture=false` (audio autoplay), overscroll glow off, text zoom 100.

## 5. Contract impact

No change to `openapi.yaml`, `contracts/sync`, `contracts/db`, `contracts/core`. One additive ticket (SHELL-C1) adds
`contracts/api/shell/`: `SHELL.md` (sections 1–6 of this file, normative), `bridge.schema.json`, `shell.yaml`
(OpenAPI for `/shell/v1/*`, generated into `clients/typescript-shell`), `ops.json` (every operationId of
`openapi.yaml` exactly once → `local | local_or_proxy | proxy | hidden`; `check.sh` fails on a missing/unknown id),
`parity/` scenarios (section 6). A separate file rather than an `x-` extension on 122 operations: no client regen,
no diff in the frozen file, and the Android route-table test and the SPA both read the same JSON.

**Local semantics (normative in SHELL.md):**
- L-1 `local` ops are always answered locally, online or not (read-your-writes; one source of truth on the phone).
- L-2 Writes mint ops with `OpMinter` as the paired device (its id, its HLC), apply and enqueue them in one
  `store.transaction`, then call `SyncTriggers.localChange()`. Never the server device; no `Kotobaya-Seq`; header `Kotobaya-Local: 1`.
- L-3 Same statuses, problem `code`s and `errors[0].pointer/code` as the server, same unknown-property `422`.
  `MintRefusal` maps to the documented `422` codes (`in_future`, `before_floor`, `before_card`).
- L-4 Idempotency as the server: create with an existing `id` → `200`, nothing minted; `answerReview.id` required;
  undo of an undone review → `200`, nothing minted. Check + mint share one transaction (replaces lock 7033001).
- L-5 `reviewed_at` defaults to the device clock; a device clock ahead is caught by the same G1/G2 guards as today.
- L-6 Deleted parent: a tombstoned note/card answers `410 gone` locally; if the delete arrives later by pull, the
  already-minted edit loses by the existing rule (delete beats older edit). Nothing new.
- L-7 `local_or_proxy` (dictionary reads): local when the dictionary package is installed, else proxy, else `503 shell_dict_missing`.
- L-8 Snapshot rebuild in progress → `503 shell_rebuilding` + `Retry-After: 2`.
- P-1 `proxy`: offline or connect failure → `503 shell_offline` (problem JSON, no network attempt when `NetworkMonitor` says offline).
- P-2 Before forwarding, push pending ops (≤ 5 s; on failure forward anyway). Cookies and any client `Authorization`
  are dropped; `Authorization: Bearer <device token>` is added; `X-Request-Id` passes through.
- P-3 A 2xx carrying `Kotobaya-Seq: n` is held until the pull cursor ≥ n (≤ 10 s); on timeout the reply carries `Kotobaya-Shell-Stale: 1`.
- P-4 `hidden` → `403 insufficient_scope` without touching the network.

**operationIds the local API implements** (R = reuse of existing Android logic, N = new code; all need a new JSON view):

| Group (ticket) | operationId | Today on Android |
|---|---|---|
| Identity (L1) | `getPrincipal`, `getMeta` | N (from `TokenStore`, `MetaLimits`, sync state) |
| | `listSettings`, `updateSettings` | R `settings/StudySettingsModel`, `QueueSettings`; mint `settings.set` |
| | `listPresets`, `getPreset`, `listNoteTypes`, `getNoteType` | R replica tables via `SqlLearningTables` |
| | `reportClientErrors` | R `report/ClientErrorQueue` (queued, flushed when online) |
| | `getBlob` (intercept) | R `media/BlobStore`, miss+online → `BlobDownloader` |
| Review (L2) | `getReviewQueue` | R `queue/LocalQueueLoader` (+`EntryMaterializer` = `NoteCreator.materialize`, `materializeAllowed`), `review/CardPayload`, `CardRows`, `BackExtras`, `InstalledRenderData`; N `kinds`/`limit`/`more_available`/`to_materialize` shaping, `QueueItem` view |
| Review (L3) | `answerReview`, `undoReview` | R mint requests of `review/ReviewSession` (read as reference, not edited), `ReviewTiming`; N stateless handler, validation, idempotency |
| Actions (L4) | `bulkCards` (suspend, unsuspend, bury, unbury, flag) | R `review/actions/ReviewActions`, `WordCardSuspension`; other bulk actions → proxy via `local` handler returning `503 shell_offline` when offline |
| | `setWordStatus`, `clearWordStatus`, `getWordStatus` | R `ReviewActions.toggleStatus`, `decks/DeckWordOps` |
| | `getNote`, `updateNote`, `deleteNote` | R `ReviewActions.saveEdit`, `notes/NoteChangeLog`, `word/WordDetail.loadNote`; N delete mint |
| | `getCard`, `listCardReviews` | N (replica selects) |
| Mining (L5) | `searchDictionary`, `getDictEntry`, `listDictHeadwords`, `getKanji`, `listKanjiWords`, `getDictRelease` | R `dict/InstalledDictionary`, `search/WordSearch`, `word/WordRepository`, `search/EntryLabelCache` |
| | `listEntryExamples` | R `grammar/GrammarSentenceSource`, `media/browse/MediaBrowseRepository` (installed media sentences only) |
| | `createNote` | R `notes/NoteCreator.create` (`DuplicateCheck`, `EntryMatcher`, ADR-035 via core `CardKinds`) |
| Browse (L6) | `listCards`, `listNotes` | R `search/LocalCardSearch`, `SearchSqlCompiler` (`dictForms`), `notes/NoteIndex`; N cursor paging |
| Decks (L7) | `listDecks`, `getDeck`, `listDeckItems` | R `decks/DeckRepository`, `DeckMembership` |
| | `addDeckItems`, `putDeckItem`, `removeDeckItem`, `createDeck`, `updateDeck`, `deleteDeck` | R `decks/DeckWordOps`, `search/SearchDeckOps`, mint calls now inside `DecksViewModel` (moved out) |
| Struggle (L8) | `listHotWords`, `listHotKanji`, `listLeeches`, `createDrillSession` | R `struggle/StruggleRepository`, core `DrillSelector`, `DefaultHotScorer` |

Everything else: `proxy` (stats, media, sentences, coverage, reader/parse, grammar, confusables, jobs, tts, note-type
and preset writes, `listDeckLists`, `bulkScheduleCards`, `getOwnDevice`, export) or `hidden` (auth login/logout,
devices admin, tokens, imports, `refreshDictionary`, `optimizePreset`, `reparseMedia`, sync push/pull, `ankiConnect`).
Shell-only ops in `shell.yaml`: `getShellInfo`, `pairShell`, `unpairShell`, `getSyncStatus`, `syncNow`,
`getDictInstall`, `startDictInstall`, `getStorage`, `updateStorage`, `getNotificationState`, `requestNotificationPermission`,
`updateNotificationPrefs`, `getPushState`, `updatePushState`, `getAnkiConnectState`, `updateAnkiConnectState`.

Cross-check (table ↔ op ↔ operation): review_log ↔ `review.logged`/`review.undone` ↔ answer/undo; note, card ↔
`note.*`/`card.*` ↔ createNote/updateNote/deleteNote/bulkCards/getReviewQueue (C-27); word_status ↔ `word.status_*` ↔
set/clearWordStatus; deck, deck_item ↔ `deck.*` ↔ L7; setting ↔ `settings.set` ↔ updateSettings. Tables with no
local write (preset, note_type, grammar, confusable, parse_override) are written through the proxy and arrive by pull (P-3).

## 6. Consistency: the server is the oracle

`contracts/api/shell/parity/<id>.json` (schema in C1): `{id, covers, clock_ms, settings, seed:{log:[ops…]}, steps:[{at_ms,
request:{method,path,query,body}, ignore:[json pointers]}]}`. Seeds reuse the op shapes of `contracts/sync/examples`.

- **SHELL-P1 (server):** a test applies `seed.log` by `syncPush` as a test device, runs the steps over HTTP with a
  device token and an injected clock, and with `RECORD=1` writes `fixtures/shell-parity/golden/<id>.json`
  (status, selected headers, body). Without `RECORD` it asserts the goldens still match (server drift check).
- **SHELL-P2 (android, JVM):** seeds a `SqlDelightDeviceStore` by applying the same log through the core pull-apply
  path, runs the same steps on `LocalApi.handle`, normalises, and requires equality with the golden.
- Normalisation (in SHELL.md): drop `generated_at`, `Kotobaya-Seq`, `Kotobaya-Local`, `X-Request-Id`; ids minted
  during the scenario are renamed `<id1>`, `<id2>`… in first-appearance order on both sides; step `ignore` pointers.
- Mandatory scenarios (each an acceptance item of L2/L3/L5): counts equal with and without `keep_going` (ADR-033);
  only not-yet-due learning left → empty `items` + `next_learning_due_at`; `more_available` when only
  `new_per_session` is reached; `kinds` filter leaves `counts` unchanged; `scheduler.reviews_unlimited` (ADR-034);
  reading retired and re-enabled, incl. `createNote` making no reading card (ADR-035); C-27 materialization count;
  day rollover with a non-UTC `scheduler.timezone`; answer retry with the same `id`; answer on suspended (409) and
  deleted (410); `reviewed_at` in_future/before_floor/before_card; `button` not matching `rating`; undo twice;
  unknown `review_id`; createNote duplicate and same-`id` retry.
- Op-level safety stays where CLAUDE.md wants it: each writing ticket also tests that the minted ops validate against
  `contracts/sync/op.schema.json`, that replaying the handler is idempotent, and that applying its ops out of order
  on a second in-memory store converges (existing fuzz harness in `core`).

## 7. Order, parallelism, first phone test

Gradle-heavy (one at a time): every `SHELL-A*`, `SHELL-L*`, `SHELL-P*`, `SHELL-Z*`. Not Gradle: `SHELL-C1`, `SHELL-W*`
— these run in parallel with the Gradle lane. Each `L*` ticket owns only its package
`android/src/main/kotlin/kotobaya/android/shell/api/<group>/` (A2 pre-creates an empty route list per group), so
the lane order is free once deps are met.

First phone test = offline review, Takoboto add (native, untouched), sync (existing `SyncBootstrap` + L-2 trigger):
`C1 → A1 → A2 → P1 → P2 → L1 → L2 → L3 → A3`, with `W1` in parallel. The legacy Compose UI stays installed behind a
second launcher entry ("Kotobaya (old)") until Z1, so pairing, settings and everything not yet local remain reachable.
Recommended next: A6 + W2 (sync chip, pair screen), A4 + W3 (widget/back), A5 (proxy).

## 8. Three choices worth a second look

1. **Bridge instead of loopback HTTP.** Safer and same-origin, but DevTools shows no network rows for API calls and
   binary request bodies are unsupported (only matters for import, which a device token cannot do anyway).
2. **`local` ops are local even when online.** Phone and web can differ until the next sync (as today's app). The
   alternative (server-first when online) gives two code paths per screen and loses read-your-writes.
3. **Intake opens the full app** at `/search?q=` instead of a sheet over the source app. Cheapest path to deleting
   Compose; a translucent second WebView activity can restore the sheet later without contract changes.

Rejected and recorded: hoisting server view builders into `core` so both sides share one JSON shaper (best
long-term answer to section 6, but a large server refactor; the parity goldens make it optional); giving device
tokens `import`/`admin` (security change, not needed for the phone).
