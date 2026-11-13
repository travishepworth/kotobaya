# HUMAN CHECKS

Everything the build needs from you after the code is written. The orchestrator adds specifics
(APK paths, commands, questions from blocked tickets) as it goes. Work top to bottom: later
checks assume earlier ones pass. Report problems back to the orchestrator as new tickets.

## Ready now (run 16, 2026-11-13) — BUG-7 card/notes viewing (no migrations, no new env/config)
- Deploy new server + web images (server adds GET /dict/headwords; Netty maxInitialLineLength 16384).
- /cards: click a jpdb vocab row (or Enter) -> preview shows dictionary front/back; table label shows the word; kind switcher works. Checkbox only toggles bulk.
- /notes: jpdb notes list as word/reading/meaning; selecting one shows the rendered card first; empty fields show "From dictionary: ..."; kanji note writing card front = keyword, back = kanji + on/kun; keyword card the reverse.
- FEAT-x (WYSIWYG per-field override) filed HUMAN-GATED with UI-1.

## Ready now (run 15, 2026-11-13) — Phase 8 (no migrations, no new env/config)
- Deploy: new server + web images (FEAT-1 adds GET /kanji/{literal}/words; no DB migration, no new env). New Android APK (Search, Decks, Settings tabs).
- Android on device: Settings → Devices → "Scan QR" opens the ML Kit scanner and pairs (tests only render the section); an unpaired first launch shows the Home "Pair this device" prompt.
- Android: Settings → Study changes (new/reviews per day, rollover hour) sync to web settings; Decks → Study this deck reviews only that deck; Search → My cards query (e.g. `deck:x is:due`) works offline.
- Web: dictionary entry → click a kanji → panel with Most frequent / In my decks, deck filter, load more.

## Ready now (run 7, 2026-11-13) — 0.1.3 (DEPLOYMENT CHANGES)
- W-5 changes deploy: worker now exposes a parse Service on :8082; api gets `PARSER_URL` (absent → parse endpoints 503); new NetworkPolicy api→worker:8082; new jpdb-cache PVC + `JPDB_CACHE_DIR`; optional `JPDB_API_KEY` Secret (`worker.jpdbApiKey.enabled`). Worker image now installs ffmpeg and takes `SUDACHI_DICT` build arg (default full) — image is bigger; rebuild both images and update values in ~/repos/kubernetes/kotobaya.
- Media/parse/sentences/coverage API live (no more 501s for those ops); media-publish runner starts only when a blob store is configured.
- New `kotobaya` CLI in `cli/` (`cd cli && uv run kotobaya --help`; needs `KOTOBAYA_URL`, `KOTOBAYA_TOKEN` PAT). `media import` takes `--kind` (default other), `--title` (default file stem), `--no-deck`/`--new-per-day`.

## Ready now (run 6, 2026-11-13) — 0.1.2
- [ ] **Build and push 0.1.2** from main: F-14 (SeaweedFS blob PUT: temp-file spool with known length; copy via get+put since SeaweedFS 3.80 CopyObject also 500s), F-15 (MediaApiModule registered → GET /import/jpdb/vocab no longer 501; WiringAuditTest pins the remaining 501 allowlist), F-16+F-19 (/health reports the build version: `make images VERSION=0.1.2` passes `--build-arg KOTOBAYA_VERSION`; if you build the api image by hand, add `--build-arg KOTOBAYA_VERSION=0.1.2`, else health says "dev"), F-18 (web dictionary page keeps the entry when examples fail).
- [ ] After deploy: retry the live jpdb import; check `/health` version; upload a blob through the UI to confirm the SeaweedFS fix live (tested only against chrislusf/seaweedfs:3.80 in Testcontainers).
- [ ] F-17 also in 0.1.2: dictionary examples endpoint works. Tatoeba examples stay empty until you add a `tatoeba` source to DICT_SOURCES_JSON: `"tatoeba": {"url": "<JA-EN pair TSV export from downloads.tatoeba.org/exports/>", "sha256": "<sha256sum of the file>"}`, then run dict-refresh. Format expected: see worker/src/kotobaya_worker/dict/extra/tatoeba.py (writer could not verify the exact export file offline). Writer says the worker requires sha256 for this entry (may conflict with ADR-020 optional pins; see FOLLOWUPS).
- Still 501 until W-5: coverage, sentences, parse, stats, grammar, confusables, leeches, hot words/kanji, TTS, export.

## Ready now (run 4, 2026-11-13)
- [ ] **Build and push 0.1.1**: W-5a is merged (main). putBlob/getBlob/queryBlobs are wired with S3BlobStore (S3_* env already in the Helm Deployment), and getDictRelease/refreshDictionary are implemented (dedupe key `dict-refresh`). Then retry the jpdb history import from the web app.
- [ ] Also in 0.1.1: Helm `httpRoute.enabled` + `httpRoute.parentRefs/hostnames` (D-1), so the hand-written gateway.yaml can go. Worker memory defaults to 1Gi request / 3Gi limit. A resumed dict refresh now rebuilds the device package (F-12). EDRDG files are no longer checksum-pinned (F-13/ADR-020); if your values set EDRDG `sha256` in DICT_SOURCES_JSON, drop them.
- [ ] New web pages: Dictionary (/dictionary) and Notes (/notes) (T412+T413). Notes search is the contract's plain substring `q`, not the card query language.
- [ ] Docker hygiene: tests now use tmpfs for Postgres (F-9); acceptance follows in F-10.

## Fill in when you can (placeholders used until then)
- [ ] `docs/DECISIONS.md` › Cluster: arch, registry URL, domain, DNS provider, existing Postgres/S3, restic target.
- [ ] `.env.local` with `JPDB_API_KEY`, and `fixtures/private/jpdb-export.json` (both git-ignored).
      The orchestrator adds the exact commands for the live jpdb runs (S02, T207) here.

## Contracts (deferred GATE-CONTRACTS)
- [ ] Read `contracts/SUMMARY.md`, especially "3 choices most worth a second look". Changes you want
      become `C-*` tickets (breaking ones with a migration).

- AnkiConnect (T311): default allowed origins include moz-extension://* and chrome-extension://* (loopback + API key). Confirm acceptable; server must be re-toggled after app restart.

## Provisional decisions
- ADR-021 (BUG-6, run 13): jpdb imported passes graduate learning cards in replay (fsrs6-2). Check: after deploy, imported Hard/Good jpdb cards show as Review, not stuck in learning.
  Deploy (run 14): no DB migration. First start of the new server image re-replays every live card (core_scheduler_version fsrs6-1 → fsrs6-2, one transaction, lock 7033001); `/readyz` fails until it commits, so allow a longer rollout (probe/progressDeadline). Android re-replays on first start after update. Expect ~1,100 stuck jpdb cards to move to Review.
- C-13 (run 7): `grammar-detect` is a public JobKind; contracts/SUMMARY.md NOT updated (agent's write to it was refused by the tool) — add a line pointing to SCHEMA.md "grammar-detect" section.
- C-12 (contract tightening, no merged consumer): SentenceToken all 10 fields required; worker parse port 8082 unauthenticated (NetworkPolicy only); cform stored verbatim; pos_class ties → `content`; overrides must align to token boundaries. Reverse via a C-ticket.
- ADR-014..019 (P4 planning): server installs media from worker snapshots; jpdb mapping corrections = server import settings; jpdb cache on PVC; coverage/i+1 per request; Android coverage via API; Sudachi full-dict tests skip offline.
- ADR-020: EDRDG source files no longer exact-checksum pinned (they change daily); fetched SHA-256 recorded in release provenance; optional pin for reproducible builds (F-13).
- 501 audit (2026-11-13): putBlob/getBlob/queryBlobs implemented but unwired, and refreshDictionary/getDictRelease never implemented → W-5a (urgent). Other 501s belong to open P4 tickets or later phases (grammar, export, TTS). No ticket found for listHot*; confirm whether it is in scope.
- Blob endpoints were 501 through P2/P3 → the web jpdb history upload (T214) could not work; fixed by T411a (P4). Use `PUT /blobs` path only after T411a merges.
- ADR-013: Takoboto note type id is a name-derived UUIDv8 (SCHEMA says UUIDv7 unless stated). Confirm or ask for a contract note.
- ADR-012: Android calls the API through a hand-written client (generated Kotlin client needs a newer Kotlin than the app).
- jpdb re-import (T207c): notes you deleted after an import are NOT recreated on re-run; they are skipped and counted.
- C-8: a word status set offline during a clock-skew window is re-minted at its estimated time and can beat another device's earlier status for the same word.
- C-8: after a clock-skew Re-mint, offline-mined words keep their deck membership and statuses (re-minted); other edits of rows the server already had that depend on window rows are discarded, and the device MUST show the discard list before you confirm.
- C-8: kanji drawn via add_kanji count against their own deck (may exceed its newPerDay; a newPerDay=0 kanji deck still feeds via vocab), not the drawing deck; vocab+kanji drawn atomically.
- ADR-011: turning on an optional card kind does not add cards to existing notes; add per note (T210a createCard).
- C-6 (breaking contract change, no user data yet): sync schemas forbid U+0000, cap int4 fields at 2147483647, cap instant_ms at year 9999 — values Postgres cannot store. Reviewer + architect approval required.
- ADR-010: sync JSON nesting limits op 500 / document 512 (T202a, C-5).
- C-4 (jpdb import): an imported `known` never overwrites a word status you already set by hand, and cards that already exist keep their suspended state on re-import. Kanji `writing` cards and known-only kanji are created suspended.
- P2: bulk card operations (tag/move/suspend/reschedule) run synchronously, up to 5000 cards per call, as the frozen API contract says. The backlog said "one job with progress". Fine for a personal collection; a C-ticket could add a job variant later.
<!-- orchestrator appends: ADR id, one line, how to reverse. Confirm or reverse each. -->

## Disk space (2026-11-13) — BLOCKS THE BUILD
- [ ] **Run 8 (D-2 fixed the leak source):** 192 anonymous volumes came from postgres:17 (core SearchSqlExecutionTest) and seaweedfs (server S3BlobStoreSeaweedFsTest), both now on tmpfs; count stayed at 195 through full core+server checks; D-3 fixed the last source (worker pg_server fixture); now 216, stable. Free ~21 GB once with the dev stack running: `docker volume ls -qf dangling=true | grep -E '^[0-9a-f]{64}$' | xargs docker volume rm` (keeps the 3 named compose volumes).
- **Run 7 end: 8.7 GB free on / (below the 10 GB floor) — run stopped dispatching for this reason.** Please free space (Docker anon volumes / build caches, `docker system df`) before the next run.
- [ ] **URGENT:** root fs hit 100% again during P4 (179 anonymous Docker volumes, ~35 GB, from Testcontainers). Run `docker volume prune -f` (and optionally `docker builder prune -f`, 8 GB), then resume the orchestrator. Consider allowing `docker volume prune` for the orchestrator, or configuring Testcontainers/ryuk to remove volumes.
- [ ] Root fs at 95% (5.3 GB free); `make test` hit Postgres DiskFull in worker dict tests. 131 unused anonymous Docker volumes (~22 GB, Testcontainers leftovers) — run `docker volume prune -f` (anonymous only; keeps named volumes). Orchestrator's prune was denied by the permission classifier. Merged worktrees were removed 2026-11-13 (10 GB free after). Still recommended: prune before P4 (Testcontainers volumes accumulate per run).

## Deploy
- [ ] Run 8 server changes: grammar create/update with detection rules now calls the worker (PARSER_URL) and returns 503 if it is unset/down; new opt-in env `GRAMMAR_SEED_ON_START=true` loads the ~280 grammar stubs once; Main.kt now starts the server non-blocking then joins (check pod startup/shutdown on the next image).
- [ ] F-4: run the extended restore drill on k3d (`deploy/backup/restore-drill.sh`): confirm the data-loss preflight refuses without `--accept-data-loss` and leaves api/CronJobs untouched.
- [ ] **P1 — Cluster:** `helm install` on the real cluster (command filled in by orchestrator); open the web app; search the dictionary.
- [ ] **Backups:** confirm the first nightly backup landed off-cluster; run the restore drill once.

## Android
- [ ] Release signing (T312): create a keystore (see android/signing/README.md), add GitHub secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`; push a tag `vX.Y.Z` (Y,Z < 100); install `kotobaya-<ver>.apk` via Obtainium and confirm it detects the next tag. Back up the keystore offline — a lost key means reinstalling.
- [ ] Install the offline dictionary on a real phone (Settings > Offline storage); confirm download resumes after toggling airplane mode mid-download, and offline search works.
- [ ] Pair a phone: QR pairing uses Google Play services (ML Kit code scanner); on a phone without Play services use manual code entry.

## Takoboto (spike + production)
- [ ] Capture Takoboto's real AnkiDroid-API call log with the **debug** APK (release builds hide field values): logcat tag `KotobayaAnkiApi`; send it so the provider fixtures can be replaced. Uninstall AnkiDroid first (only one app can own `com.ichi2.anki`).
- [ ] **S01/T305:** install the default APK (`com.ichi2.anki` flavor). In Takoboto, add 5 words. Confirm they appear as notes with the right fields.
- [ ] If the own-app-ID flavor is preferred, try it too and record which works in `docs/DECISIONS.md`.

## jpdb import
- Live jpdb history import (T207a/b/c; needs server + worker with S3_* env, dev stack up):
  1. Web: /import → pick your jpdb export → start (or `PUT /blobs/{sha256}` then `POST /import/jpdb/history`), watch `GET /jobs/<id>` until `succeeded`.
  2. Check (vocabulary_carded + vocabulary_status_only) / (vocabulary_total − vocabulary_en_jp_ignored) ≥ 0.95; skim `unmapped` / `kanji_unmapped`; `reviews_id_collisions` should be 0.
  3. Spot-check ~20 cards' due dates against jpdb.
  4. Re-run the same import: 0 new ops. Delete one imported note, re-run: it stays deleted (provisional rule).
- [ ] **T401a jpdb deck fetch:** (1) fetch one real public deck via the Pages source and compare row counts with the site; (2) with `JPDB_API_KEY`, run the API path once and report real field names (response layout ASSUMED in `map_api_response`); verify numeric-id deck URL form `deck/<id>`.
- [ ] **S02:** read `spikes/jpdb-map/REPORT.md`. Hit rate ≥ 95%? If not, work through the mapping review queue in the web app.
- [ ] **P2 — History import:** import your jpdb review history; spot-check 20 cards' due dates against jpdb.

## Daily use
- [ ] **GATE-P2 (deferred) — "you review your jpdb cards in the web app and stop using jpdb's scheduler":**
  1. `make dev-up` (or k3d via `deploy/k3d/up.sh`), log in at the web app.
  2. Run the live jpdb history import (steps under "jpdb import" below); skim /import results.
  3. /review: study a session keyboard-only (1–4 or 1–5 per settings, u undo, b bury, s suspend, e edit); check interval previews look sane.
  4. /cards: search (e.g. `deck:Mining is:due`), bulk dry-run then apply a tag; /decks: create a smart deck; /note-types: preview a template; /settings: change limits, create a PAT (shown once), pair a device QR.
  5. Push/pull from a second client (or two browsers) and confirm a duplicate word added on both merges (no 503).
  Web screens were never exercised in a real browser by the build — expect small UI bugs; list them for fix tickets.
- [ ] **GATE-P3 (deferred; all 25 P3 tickets merged 2026-11-13) — "a real week offline on the phone, then sync, loses nothing":**
  1. Build/install the APK (`make apk`; default flavor `com.ichi2.anki`, own-ID flavor also built). Pair with the server (QR or manual code), install the offline dictionary.
  2. Takoboto: add 5 words → notes with correct fields appear (see Takoboto section); share a word from a browser and use PROCESS_TEXT on selected text.
  3. Review normally for a week in airplane mode (use undo, bury, suspend, edit at least once). Sync chip should show offline/pending, never an error.
  4. Re-enable network → sync. Review counts on phone and web must match; Sync screen shows no rejected ops (if any appear, note their codes before Retry/Discard).
  5. Yomitan → on-device AnkiConnect (enable in settings; set API key + origin allowlist), add a card from a browser on the phone.
  6. Widget sizes, due-count notification, Quick Settings tile, ntfy (only if `NTFY_BASE_URL` set on the server).
  Automated proxy already green: `make check MODULE=acceptance` (T313: 100 randomised offline-week runs, 0 lost reviews).
- [ ] **GATE-P4 (deferred; all P4 tickets merged, `make test` green 2026-11-13 run 7) — "a show's coverage numbers match jpdb's within a few percent":**
  1. Deploy 0.1.3 (see "Ready now (run 7)": parse Service, PARSER_URL, jpdb-cache PVC, optional JPDB_API_KEY).
  2. Web → Media → import two shows you have on jpdb (jpdb deck import), and one own subtitle file (SRT/ASS) with optional audio for clip cutting.
  3. Compare coverage % per show with jpdb (expect within ~3 points). Open the reader: click-to-look-up, fix one token's parse, mine an i+1 sentence card.
  4. Android → Media tab: coverage/segments/curve and i+1 sentences match web.
- [ ] **CLAUDE.md:** add `acceptance/` and `cli/` (Python `kotobaya` CLI, `make check MODULE=cli`) to the module map (human-owned file).
- [ ] **P1 (GATE-P1, deferred; `make test` green 2026-11-12):** `helm install` the chart (k3d: `deploy/k3d/` scripts; set the AUTH_PASSWORD_HASH Secret first), log in, run the dict-refresh job (W-1 item above), then search the dictionary through the API (e.g. `GET /api/v1/dict/search?q=食べる`) and confirm sensible results. Note: login over plain http in k3d dev doesn't resend the Secure cookie — use HTTPS or see FOLLOWUPS.
- [ ] **P2:** review in the web app for 3 days. Note anything off.
- [ ] **P3:** pair the phone; review offline (airplane mode) for a week; sync; confirm review counts match on web and phone.
- [ ] **P3:** widget sizes, due-count notification, Quick Settings tile, share-to-app, Yomitan via on-device AnkiConnect.
- [ ] **P4:** import two shows you have on jpdb; compare coverage numbers (expect within ~3 points).
- [ ] **F-7:** spot-check a few of the 180 new hand-authored sentences in fixtures/parse/sentences.json (segmentation/readings; Sudachi was not run).
- [ ] **P4 (Sudachi full dict):** run the parser agreement test with the full Sudachi dictionary installed (ADR-019).
- [ ] **P4:** import one of your own files (subtitles or EPUB); fix a mis-parsed word in the reader.
- [ ] **P5:** hot words and leeches look sensible after a week of data; grammar cards appear from your own sentences.
- [ ] **P6:** TTS audio, pitch display, CLI (`kotobaya cards find 'is:due'`), export.

## Blocked tickets and open questions
<!-- orchestrator appends: ticket, question, options, what's skipped because of it -->
- ADR-003: SeaweedFS replaces MinIO as the bundled S3 store (MinIO images no longer pullable). Confirm or point at your own S3.
- T001: `make dev-up` api/web healthchecks were not verified (disk was full at the time). Run `make dev-up` and confirm all services are healthy.
- T008: the Mokuro sample shape was written from memory (the jpdb fixture was since corrected from your real export by S02/T008b).
- GATE-S01 specifics: APKs at `spikes/ankidroid-standin/build/apk/` (git-ignored; rebuild with `cd spikes/ankidroid-standin && ./gradlew assembleStandinDebug assembleAnkidroidDebug`). Follow `spikes/ankidroid-standin/README.md` § Manual test. Try `standin` first; on Android 11+ package visibility may force the `ankidroid` (`com.ichi2.anki`) variant. The provider contract was written from memory of FlashCardsContract; unknown URIs are logged, so send the log either way. T305 builds both variants regardless.
- S02 live API run (needs JPDB_API_KEY in .env.local): see `spikes/jpdb-map/REPORT.md` for the exact command; API field names were unverified.
- ADR-005: no collection-wide load balancing (only deterministic fuzz + Easy Days), because cards' schedules must be identical on every device. Daily counts may be somewhat lumpier than Anki.
- ADR-006: mined (deckless) new cards share priority 0 with your decks by default; configurable. Disabled decks introduce nothing.
- T006 queue default to confirm: a word counts as auto-"known" when every started card has an interval of 21 days or more.
- ADR-006 detail: decks of equal priority take turns one card each (round-robin), not a fixed order. Confirm this matches what you want from "priority order".
- T101: run the dictionary pipeline once for real (downloads JMdict/KANJIDIC2/KRADFILE from EDRDG) and confirm counts; URLs and KRAD parsing were only tested offline.
- T102: choose the frequency lists (Yomitan-format zips) and the Tatoeba export URL to pin; the pipeline has no defaults (config ExtraSources).
- GATE-CONTRACTS (auto-closed 2026-11-12): review contracts/db/SUMMARY.md, contracts/sync/SUMMARY.md, contracts/api/SUMMARY.md, contracts/core/SUMMARY.md — especially each file's "choices worth a second look" and the C-1/C-2 change sections. Contracts are frozen; changes now go through C-tickets.
- T103: the top-60k dictionary package size budget (<150 MB) was only estimated on synthetic data; measure on a real JMdict build.
- Auth setup at deploy: generate AUTH_PASSWORD_HASH with `java -cp server-all.jar kotobaya.auth.HashPasswordKt`, store it (and optional AUTH_TOTP_SECRET) in the Kubernetes Secret; set AUTH_TRUSTED_PROXIES to the ingress pod CIDR. First login after a restart may wait ≤30 s for the next TOTP code.
- W-1: first real dictionary refresh: set DICT_SOURCES_JSON (pinned EDRDG/frequency/Tatoeba URLs + checksums), run the dict-refresh job, confirm a release with artifacts appears at /api/v1/dict/release.
- T111 deploy values: Secret key AUTH_PASSWORD_HASH; `api.trustedProxies` (ingress pod CIDR); external Postgres needs `CREATE DATABASE ... TEMPLATE template0 LC_COLLATE 'C.UTF-8' LC_CTYPE 'C.UTF-8'`; existing compose volumes need `make dev-down` + volume wipe for the new locale.
- T105b: set `api.publicUrl` (PUBLIC_URL) in the real install, e.g. https://kotobaya.<your-domain>; without it, device pairing returns 500 unless trusted-proxy forwarded headers are set.
- T112 backups: choose the restic repository target (DECISIONS has none; the drill used an in-cluster bucket), backup frequency, and set backup.enabled=true with the repo Secret. Run deploy/backup/restore-drill.sh once against the real install.
- Disk: ~14.6 GB of anonymous dangling Docker volumes (origin unknown, may include other projects). `docker volume prune` if safe.

## GATE-P5 (Struggle + grammar) — deferred, make test green 2026-11-13 (run 9)
- Web: Struggle screens (hot words/kanji, leeches, confusables confirm/reject, extra drill); grammar review shows a rotating sentence with span highlight (reviews A,B,C all appear; undo/refetch same sentence); "Saw something weird" button in reader, media sentences, dictionary examples → new/attach point, rule test.
- Android: Struggle bottom tab + 4x3 hot-word widget (tap → kotobaya://struggle/word/<seq>); grammar capture from media sentences and hot-word sentences; offline grammar card sentence (needs media snapshots on device, else notice).
- Known limits: Android struggle/grammar-capture writes are online-only (F-27); widget hot word uses local lapse stand-in. Deploy: grammar rule create/update needs PARSER_URL (503 otherwise); GRAMMAR_SEED_ON_START seeds grammar points.

- (run 11) Worker image torch is CPU-only now (F-28); image ~2.04 GB (sudachidict-full + torch). Build with `--build-arg SUDACHI_DICT=core` for a smaller image if wanted.

## Run 11 (T609 security review)
- kubectl current-context was switched by T609's k3d test and is now `homepage`; restore your intended context (`kubectl config use-context <ctx>`).
- Backups are off in the live deploy (values in ~/repos/kubernetes/kotobaya); enable them and narrow `networkPolicy.egress.backupTo`.
- Live gateway.yaml (outside this repo) needs the security response-header filters the chart's HTTPRoute now sets.
- NEW chart value: external Postgres/S3 now require `networkPolicy.egress.externalServices` (render fails otherwise). Bundled live setup unaffected.
- SEC-3: app uses the Postgres superuser; split roles (app owner non-superuser, backup CREATEDB+read). Needs a one-off live DB migration.
- SEC-9: host ntfy on its own name (`ntfy.baseUrl`) and test a push to a phone.
