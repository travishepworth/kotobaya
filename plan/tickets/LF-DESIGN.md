# Local-first shell + fixed review loop (ADR-050, ADR-051) — design

Status: design only; no contract or code edited. Tickets: `local-first-tickets/`.

## 1. What is true today (corrects the scout notes)

The shell does **not** forward everything. `LocalApi.handle` (android `shell/api/LocalApi.kt:86-131`) dispatches by
`contracts/api/shell/ops.json`: 38 `local` ops (review queue/answer/undo, notes, decks, deck items, settings, word
status, hot words, leeches, card lists) are answered from the SQLDelight replica **online and offline**, minting device
ops through `LocalWrites` (L-2). The network is on the UI path only in these places:

| # | Where | Effect |
|---|---|---|
| N1 | 54 `proxy` ops via `ServerUpstream.forward` (`proxy/ServerUpstream.kt:66-90`): P-2 pre-push ≤ 5 s (a full push+pull cycle, `ProxySync.kt:37`), call ≤ 12 s, P-3 hold ≤ 10 s | Home (`getStatsSummary/Heatmap/Retention`), deck page (`getDeckCoverage`), new deck (`listDeckLists`), Stats, Media, Reader, Struggle confusables, presets/note-type writes |
| N2 | `Fallthrough` to N1: `bulkCards` actions `set_preset/add_tags/remove_tags/move/delete` (`BulkRoute.kt:261`), `listCards`/drill/bulk queries the device guard does not trust (`ListCardsRoute.kt:98`), `createDeck type=list` | Search, browse, note editing |
| N3 | `local_or_proxy` dictionary misses (rule L-7) and `createNote` entry fetch | Dictionary, add word |
| N4 | `resolveSpeech` (proxy) per card shown | Review audio (not blocking, but runs N1's pre-push cycle) |
| N5 | **`materializeAllowed`** (`queue/LocalQueueLoader.kt:500`, used by `QueueRoute.kt:99`): deck entries become new cards only when `!syncRunning && failures == 0 && lastSyncAt != null` | Review. Contradicts SHELL.md 4.3 ("materialization is performed by the device … `to_materialize` is then 0") |

## 2. Root causes

**RC-1 (5–10 s "grade does nothing", shell).** Every local answer calls `SyncTriggers.localChange()` → a sync cycle →
`coordinator.running = true`; on a bad link the probe/push takes seconds and ends as a failure (`failures > 0` until
the next good cycle). During all of that N5 refuses to materialize, so `getReviewQueue` answers `phase: reviewing`,
`items: []`, `to_materialize > 0`. The SPA has just taken the last local card off (`review.svelte.ts:996-998`), shows it
as the inert `held` card (looks ungraded; taps are ignored at `:958`), retries 3 times, then waits 2/4/8 s
(`PENDING_BASE_MS`) or stops at the "Refresh" screen. **RC-2 (same symptom, both clients).** With the local queue
drained, the next card needs `outbox.tail` (answer round trip) **then** a guarded full fetch, sequentially
(`:689-707`); prefetches are thrown away when a learning card came due or a full fetch landed meanwhile (`:510,:525`),
and only start at ≤ 3 cards left. On desktop a retryable failure also puts the card back and locks grading
(`:230-241`). **RC-3 (pull vs tap).** A pull sub-page holds SQLite's single write connection (`SyncEngine.kt:183-190`);
a local answer queues behind it.

**RC-4 (header says cards left, then an end screen).** Two sources of truth. The header is `view.loop` of the last
*applied full* fetch (`+page.svelte:161-165`); answers given since do not change it (prefetch deliberately leaves the
numbers alone, refresh is skipped while the outbox is non-empty, guarded fetches are dropped when the user answered
meanwhile). A user answering steadily sees frozen counts (and a bar total that grows by one per answer) until the queue
drains and the fresh terminal phase lands. On the phone RC-1 adds a second form: counts include drawn-but-unmaterialized
entries (`counts.new`, `until_stop_parts.new`) while no card can be served. Core itself is consistent per evaluation
(`DefaultReviewLoop.kt:30-70`, property tests in `ReviewLoopWaitTest`); the hole is the client mixing a stale
evaluation with local items, and N5.

**RC-5 (end screen without Keep going).** (a) The `pending`/`pendingStopped` screen (`+page.svelte:401-424`) has only
Refresh — on the phone it is what RC-1 produces. (b) In keep-going mode `keepGoingAvailable` is always false, so a
`waiting` reached there never offers it. (c) `empty` is correct only if nothing is left anywhere; on the phone, deck
entries missing from the `top60k` package are "neither materialized nor counted" (SHELL.md 10.7), so `empty` can show
while new words exist.

## 3. Target: route by route (shell)

Classes after C-31: **local** (replica, never network), **cached** (new: answered at once from a device response
cache, revalidated in the background, `cache.updated` event; a miss is an immediate `503 shell_uncached`, never a
wait), **remote** (explicit "needs connection" screens, single 12 s call, no pre-push, no hold).

| SPA route | Local (replica + core) | Cached (SWR) | Remote / background |
|---|---|---|---|
| `/` Home | queue+loop, decks+progress, hot words; **new:** `getStatsSummary`, `getStatsHeatmap`, `getStatsRetention` | — | — |
| `/review` | queue, answer, undo, drill, bulk bury/suspend, word status, note edit; **new:** `resolveSpeech` (answers from `tts_audio`, queues the native resolver) | — | audio bytes: prefetch + intercept download (never blocks a card) |
| `/decks`, `/decks/[id]`, `/decks/new` | lists, items, CRUD, progress | `listDeckLists`, `getDeckCoverage` | `createDeck type=list` (server-only op) |
| `/search`, `/cards`, `/notes` | **new:** every `listCards`/`listNotes` query (no `Forward`), all `bulkCards` actions, `bulkScheduleCards` | — | — |
| `/dictionary` | search, entry, kanji, examples from package + sidecar; **new:** a miss answers locally at once and queues gap fill (`shell.dict` event) | — | package / gap-fill download |
| `/struggle` | hot words/kanji, leeches, drill | `listConfusables`, `listWordConfusables`, `listSentences` | `setConfusable` |
| `/stats` | summary, heatmap, retention | forecast, known words, calibration | — |
| `/media`, reader, `/import`, `/note-types`, presets, grammar capture | reads already local stay local | `listMedia`, `getMedia`, `getMediaCoverage`, `listMediaSentences`, `listParseOverrides`, `listGrammarPoints` | all writes and jobs |
| `/settings` | settings, shell ops, sync status | — | devices/tokens stay hidden |

Invariant test (LF-A2, LF-X1): with an `Upstream` that fails the test when called, every `local` op answers; every
`cached` op answers from cache or `503 shell_uncached`; nothing on Home/Review/Decks/Search/Dictionary/note editing
reaches the upstream.

## 4. Mutations, push, pull, conflicts

Unchanged model (SYNC.md 4): handler → `LocalWrites.write` → mint as device + apply + `pending_op` in one transaction
→ `sync.changed`. Changes: (1) `localChange()` is debounced (2 s trailing, 10 s max) so a review session is a few
cycles, not one per answer; app-open, connectivity, push message and periodic triggers stay. (2) No request ever joins
or waits for a cycle (P-2/P-3 deleted; remote writes return with `kotobaya-shell-stale: 1`, the effect arrives by
pull + `sync.changed`). (3) Pull applies in sub-pages of ≤ 50 ops (SYNC.md 6.1 already allows sub-pages) and yields
between them; local writes use a 5 s busy wait. (4) Materialization (N5) depends only on "first complete pull done"
and "no rebuild pending". Conflicts are exactly SYNC.md: reviews append-only + replay (9); registers LWW by HLC (7.1);
tombstone beats older edit (7.3); double materialization or the same word twice → server `note.merged`/`card.merged`,
every replica merges, reviews replay on the survivor (8.1–8.3, trade-offs 8.4 accepted); rejected ops (10) are counted
in Settings → Sync and never block. The SPA re-reads what is visible on `sync.changed` without a skeleton; the card on
screen is never replaced (C1), and an answer for a card deleted/suspended elsewhere is the existing `410/409` drop
with a toast. No op, table or wire change; cross-check tables (SYNC.md 14, SHELL.md 11) only move operations from
"proxied" to "local".

## 5. On-device data: what is missing

| Need | Today | Change |
|---|---|---|
| Stats (Home) | none; review_log, card, word_status are in the replica | local handlers, parity scenarios (LF-A3) |
| Search | `CardQuery` forwards large dictionary-term queries | evaluate all terms locally (LF-A5) |
| Dictionary | `top60k` default; misses forwarded | never forward on the UI path; auto-install scope `all` on an unmetered network after first sync; gap fill stays proactive over `deck_item` (LF-A6) |
| Deck contents | `deck_item` by ops + content snapshots | none; deck coverage curve stays server data (cached) |
| Media sentences, coverage, vocab-list catalogue | not replicated as queryable analysis | response cache warmed after each successful cycle (LF-A2) |
| Audio | prefetch 7 days + on-demand intercept | `resolveSpeech` local; prefetch unchanged |
| Response cache | none | device-local table `response_cache(op, key, body, etag, fetched_at)`; not learning data, no op |

## 6. Startup and first sync

App open: the SPA renders from the replica at once; the readiness gate (startup replay, local CPU only) keeps its 20 s
bound but `getShellInfo`/`getSyncStatus` answer immediately and a cycle starts in the background. **First sync** after
pairing is the one network wait that remains: `ShellSyncStatus.first_sync = {done, applied, total}`; until done the SPA
shows a full-screen "Setting up" progress, local reads return the partial replica, materialization is off (otherwise
every deck entry would be minted twice). Afterwards: warm the response cache, install the dictionary, start audio
prefetch — all background, all resumable.

## 7. Desktop web (no replica)

(a) Outbox persisted in IndexedDB (`kotobaya.outbox`: the `ReviewAnswerRequest` with its client `id`), replayed in
order on load; the server's per-`id` idempotency makes replays safe. (b) Retryable failures stay in the outbox with
backoff; the card is **not** put back and grading is never locked; only permanent refusals drop with a toast.
(c) Prefetch keeps ≥ 10 cards ahead (`limit` 30), merges instead of discarding, excludes cards with pending answers.
(d) Header from the core presenter (section 8). (e) Stores keep showing old data while refetching; Home loads stats in
parallel with the queue. Out of scope: offline browser.

## 8. Loop changes (ADR-051), all decided in core

1. **Keep-going batch = goal.** New `QueueLimits.keepGoingNewTarget: Int?` (API query `keep_going_new_target`,
   additive; null = today's endless stream). At Keep going the client sends `loop.new.today + max(1, new.goal)`. K
   draws at most `target − newToday` new cards; with keep going on, target reached, nothing ready and nothing later →
   `GOAL_REACHED` / `DONE_FOR_TODAY` again with `keepGoingAvailable = (K∞ has a new card or a backlog)`.
2. **`keepGoingAvailable`** = "pressing it would serve a card now": true in every non-`REVIEWING` phase when K (with
   the next target) is non-empty, in both modes; false in `EMPTY` by definition (`EMPTY` ⇔ K∞ empty ∧ later = 0).
3. **One presenter.** Core `LoopPresenter.present(state, pendingAnswers, hasLocalCard)` returns the header parts and
   the screen: a local card → `CARD` with parts projected for the pending answers (never all zero); no card but
   pending answers → `SETTLING`; otherwise `state.phase` with `state`'s own parts. Web and shell render only this.
   The `pending` screen is removed (a `REVIEWING` state with nothing served is `SETTLING` with auto-refetch; on the
   phone it cannot occur after N5 is fixed).
4. **Property/fuzz test** (`ReviewLoopInvariantFuzzTest`, jvm + js, seeded, ≥ 2 000 runs × ≤ 60 steps): random cards,
   pending entries, limits, toggle, target; steps = answer the served head with a random rating through the real
   scheduler, advance the clock (including across a learning due and the day end), toggle keep going, leave some
   answers "pending". After every step: I1 `ready > 0 ⇒ phase = REVIEWING ∧ served.items ≠ ∅`; I2 terminal ⇒
   `readyLearning = readyReviews = laterToday = untilStop = 0`; I3 parts sum to `untilStop`; I4 `EMPTY ⇒ K∞ empty ∧
   ¬keepGoingAvailable`, stop/waiting phases ⇒ `keepGoingAvailable = K-next non-empty`; I5 a learning card is never
   served before its due, a due card is the head; I6 presenter: terminal screen ⇒ no pending answers ∧ parts zero,
   `parts.ready > 0 ⇒ screen ∈ {CARD, SETTLING}`; I7 answering the head never increases `untilStop` by more than 1.

## 9. Decision for the user (default proposed)

Keep going after ADR-051: **default = one press serves one goal-sized batch, then the stop screen returns with Keep
going** (reverses ADR-039's "endless within a visit"). Alternative: endless as today, batch size only changes the
count — one line in the client (omit `keep_going_new_target`). Second, minor: auto-download of the full dictionary
(scope `all`) on unmetered networks — default yes.
