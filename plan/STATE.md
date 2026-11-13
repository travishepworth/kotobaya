# STATE
Mode: single-session
Phase: P7 live bugs (0.1.6 triage) + P9 UI rewrite   Gate: B2 HUMAN GATE pending (after UI-DS merges); GATE-P2..P5 deferred
Updated: RUN17

## RESUME HERE (run 17: INBOX ticketed + archived to plan/handoffs/INBOX-0.1.6.md)
- Dispatch order: HOTFIX-0.1.7 (A0, ship as 0.1.7) → BUG-8 (high, reviewer+architect) → BUG-9 → BUG-10; UI-FOUND-WEB alongside Track A after A0; UI-FOUND-APP after BUG-8; then UI-DS. C-19 when a slot is free.
- HARD HUMAN GATE: after UI-DS merges do NOT dispatch UI-WEB/UI-APP; they wait for the human's approval message.
- Max 3 writers; Gradle builds via flock /tmp/kotobaya-gradle.lock; stop dispatching < 10 GB free on /home.
- Leave alone: untracked .claude/settings.json; worktree .../scratchpad/rel016 (another session).

## 0.1.7 hotfix status
- HOTFIX-0.1.7: MERGED at a5c4e3a — READY TO SHIP AS 0.1.7 (web image + chart JAVA_TOOL_OPTIONS default; no migrations). Deploy notes in HUMAN_CHECKS top.

## Running
| Ticket | Agent | Attempt | Branch | Started |
|---|---|---|---|---|
| BUG-8 | writer-senior | 1 | t/BUG-8-android-sync-perf | RUN17 |
| UI-FOUND-WEB | writer-senior | 1 | t/UI-FOUND-WEB-state-harness | RUN17 |

## Queued
(none ready) · UI-FOUND-APP (after BUG-8) · UI-DS

## Ready for review / In review

## Blocked
(none) · HUMAN-GATED: UI-WEB, UI-APP (B2 gate)

## Notes
- Gradle builds are serialized via flock /tmp/kotobaya-gradle.lock. Never run two Gradle make checks at once.
- Disk: / 19G free, /home 37G free (run 14). Check `df -h /` before Testcontainers-heavy batches.
- PUSH main BEFORE dispatching worktree writers (worktrees are cut from origin/main).
- MAX_PARALLEL_WRITERS=3; 1 Gradle-heavy writer at a time. Up to 3 concurrent reviewers.
- Handoffs are archived to plan/handoffs/<ID>.md after merge.
- Model tiers (ADR-004): `fable` tickets go to Opus in the architect role. No Fable agents.
- Review rounds: loop only on real blocker/major findings that have a failure scenario. Minors go to FOLLOWUPS.

## Pending human questions
- UI-1/FEAT-x ungated (run 17) -> Phase 9. UI-WEB/UI-APP gated on human approval of UI-DS. Others: see plan/HUMAN_CHECKS.md.

## Phase stats
Run 17: merged 5 · attempts 7 · escalations 0
P7 (run 16): merged 4 (BUG-7, BUG-7b, C-18, BUG-7c) · attempts 4 · escalations 0 · make test green
P8: merged 5 (C-17, FEAT-1, AND-DECKS, AND-SEARCH, AND-SETTINGS) · attempts 7 (2 rebase fixes) · escalations 0
P7: merged 6 (BUG-1..BUG-6; BUG-2+3 batched) · BUG-1 2 attempts, BUG-6 2 attempts · escalations 0 · make test green (run 14)
P6 DONE (T607 deferred) · P5/P4/P3/P2/P0 DONE (see LOG.md for detail)

## Recently merged (last 10)
- BUG-10 (run 17, 4ce6632): HotWords.loadHistory via card pk/merged_into + review_log_card_idx (7.2s->110ms warm), settings loaded once; no migration (0.1.8)
- TEST-1 (run 17, b43dbca): jsdom@31 devDep (human-approved), CardPreview/NoteEditor bounded-effect vitests (fail on pre-fix code)
- C-19 (run 17, 578985c): RenderOptions.fieldRegions (data-field/data-field-source spans, RenderedCard.fields), goldens unchanged when off; for UI-WEB FEAT-x
- BUG-9 (run 17, ac0765f): stats chunked replay (500 cards), retention+calibration share one pass, Semaphore(1); /stats/* 7-607 ms on 89,775 reviews; Dockerfile JAVA_TOOL_OPTIONS default (0.1.8)
- HOTFIX-0.1.7 (run 17): preview $effect loop fixed (local busy), chart api.env JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=70 default; vitest deferred (jsdom denied)
- BUG-7c (run 16): listDictHeadwords server (one query, 422 rules), Netty initial line 16384, /cards + /notes tables batch-filled (chunks of 500); 501 allowlist empty
- C-18 (run 16): listDictHeadwords GET /dict/headwords?seq= (<=500; headword, headword_reading, glosses[]) + clients regen; 501-allowlisted until BUG-7c
- BUG-7b (run 16): /notes rowLabel + Reading/Meaning cols, Preview|Edit (default Preview), kind switcher (notes + cards), Kanji fetch->toKanji, 'From dictionary' field values
- BUG-7 (run 16): /cards row click + Enter opens preview, rowLabel fallback (fields→headword→kanji→id), preview fetches DictEntry/Kanji (toDictionary/toKanji)
- AND-SETTINGS (run 15): Settings tab renders all registered settingsSections (QR pairing reachable), Study limits/rollover via settings.set ops, About; unpaired Home pairing prompt
- AND-SEARCH (run 15): Android Search tab (offline dict -> API; my cards via core parser+SQLite compiler -> API), word/note detail in-screen
- FEAT-1 (run 15): listKanjiWords server (keyset, mine/deck_id, decks[]) + web clickable kanji panel (Most frequent / In my decks)
- AND-DECKS (run 15): Android Decks tab (replica counts = LocalQueueLoader, deck study via ReviewScreen deckId); read-only, gaps -> FOLLOWUPS
- C-17 (run 15): listKanjiWords GET /kanji/{literal}/words (deck_id, mine, cursor bound to filters) + clients regen
- BUG-6 (run 14): jpdb imported passes graduate (re)learning cards in replay; core SCHEDULER_VERSION fsrs6-2 (startup re-replay); server reads import_source; reviewer + architect APPROVE
- BUG-1 (run 13): stats summary 153ms (was 19.7s), review queue 48ms, independent stats panels; reviewer APPROVE
- BUG-5 (run 12): listHotKanji (hot-note scores rolled up per kanji); 501 allowlist now EMPTY; SSE client-disconnect write errors logged at debug (no test)
- BUG-2+BUG-3 (run 12): jpdb deck title from vocabulary-list page (id fallback), bare numeric id rejected (UI + worker); optimizer datetimes normalised to aware UTC
- BUG-4 (run 12): voicevox runs run.py directly (no gosu), per-component readOnlyRootFilesystem wins (set over deepCopy), ~/.local/share emptyDir only; check.sh asserts; smoke OK
- SEC-1 (run 11): getBlob nosniff + CSP sandbox on all responses; inline only audio/* + raster images, else attachment; review APPROVE
- SEC-2 (run 11): worker URL import SSRF guard (per-hop resolve, non-public refused, pinned IP, manual redirects cap 5)
- T610 (run 11): docs/RUNBOOK.md (install, checks, backup/restore per SYNC 6.2-6.3, drill table, incidents)
- T609 (run 11): security review — docs/SECURITY.md (S-01..S-14), chart hardening, egress NetworkPolicies, HTTPRoute headers, ntfy image fix, watchdog/restore fixes; review APPROVE
- F-28 (run 11): worker torch CPU-only index (uv.lock no nvidia/triton; Dockerfile pins torch==2.15.0 CPU; image 2.04 GB)
- T606 (run 10): FSRS optimizer (worker py-fsrs training + holdout gate, server FsrsParamsPublishRunner, helm cron default off); review APPROVE
- T608 (run 10): export (JSON dump snapshot stream, Anki .apkg via sqlite-jdbc); review APPROVE, minors in FOLLOWUPS
- T605 (run 10): stats (6 server handlers via core replay/queue builder, web /stats page)
- T603 (run 10): server AnkiConnect actions (7 actions, anki_id, findNotes, media rewrite; ops via OpApply); review APPROVE, minors in FOLLOWUPS
- T601 (run 10): VOICEVOX TTS (server synthesizeSpeech enqueue+dedupe, worker tts job Opus/S3 cache, helm voicevox default off)
- C-16 (run 10): fsrs-params-publish contract (contracts/db/FSRS_PUBLISH.md, optimizer schema+examples, JobResultFsrsOptimize fields); unblocks T606
- T602 (run 10): pitch card kind (front expr+kana, back pitch graph), pattern labels, odaka particle slot; marks only when options.pitch
- T507 (run 8): grammar card sentence picker (core) + server source (not exposed → F-25); seed trigger env; Main.kt start wait=false+join
- D-3 (run 8): worker pg_server fixture postgres:17 → tmpfs; volumes stable (216) across worker+server+core checks
- T506c (run 8): worker rule-test endpoint; server testGrammarRules + 422/503 rule validation (needs PARSER_URL in deploy)
- D-2 (run 8): Docker volume leak fixed (tmpfs for postgres:17 core test + seaweedfs); prune cmd in HUMAN_CHECKS
- C-14 (run 8): worker-parse 1.1.0 rule-test endpoint (+validate-only); minors → F-24
- T504a (run 8): web struggle screens; extra drill = all hot words (F-21)
- T506a grammar DSL/matcher/test_rules + grammar-detect job (61 rules, real Sudachi); detection in parse/import publishes — reviewer APPROVE x2 (run 7)
- W-6 grammar-detect enqueue on rule edits, publish keeps parse_status, examples + manual span API (testGrammarRules still 501 → C-14/T506c) (run 7)
- C-13 `grammar-detect` JobKind (additive; fan-out lock 7033001 race fix) — reviewer APPROVE after 2 attempts (run 7)
- T502 confusables: server-side suggester (homophone/look-alike/meaning, every 6h via ops) + list/set API (run 7)
- T503 drill selection (hot/leech/confusable pairs, seeded) + DrillApi sources; drill replay never changes schedule (run 7)
- T505b grammar CRUD via ops + GrammarSeeder (not yet invoked → F-20) (run 7)
- T501 hot words/kanji scorer (core) + listHotWords/listLeeches (server, leech threshold from settings) (run 7)
- T505a grammar seed: 280 N3–N1 stubs (title/pattern/jlpt, CC0) + validate.py (run 7)
- W-5 parse/coverage/sentences wired, media-publish runner, worker parse service :8082 + Helm/compose (run 7)
- T604 `kotobaya` CLI (cli/, health/cards find/media import/jobs; make check MODULE=cli) (run 7)
- T410 Android media browse: coverage, segments, curve, i+1 sentences (6th bottom tab) (run 6)
- T406+T408b web reader with parse correction + media sentence lists and sentence cards (run 6)
- T404b worker media-import and media-parse jobs + coverage aggregation (554 worker tests) (run 6)
- T403c+T408a parse API (parseText, overrides via ops) + sentences API (i+1, i+0); unwired until W-5 (run 6)
- F-17 listEntryExamples implemented (Tatoeba + media sentences; empty when no Tatoeba loaded) (run 6)
- F-19 API image build passes KOTOBAYA_VERSION build-arg (health shows real version) (run 6)
- F-15+F-16 MediaApiModule wired (jpdb vocab 501 fixed), WiringAuditTest 501 allowlist, health version from build (run 6)
- T403b parser matching (ranked JMdict, overrides, parse service; agreement 0.978) — reviewer APPROVE (run 6)
- F-14 SeaweedFS blob PUT: spool to temp file, known length; copy via get+put (CopyObject 500) (run 6)
- F-18 web dict page keeps entry when examples fail/empty (run 6)
- F-7 parse fixture 200 sentences + JMdict_parse_extra (hand-authored segmentation)
- T405a web media library + import wizard (UUIDv7 import_id retry)
- T411a blobs API (BlobsModule PUT/GET/query, S3BlobStore + in-memory; not wired until W-5)
- T401a jpdb deck client (Pages + API source, disk cache, rate limit; API layout assumed → HUMAN_CHECKS)
- T402a extractors SRT/ASS/VTT/TXT/text-hooker + sentence splitter
- T313 offline chaos suite (acceptance/: real core client devices vs real server on Postgres, 100 seeds 0 lost reviews, §12 examples + 16 scenario files) — reviewer + architect APPROVE, 1 attempt
- T306+T311 share sheet/PROCESS_TEXT/deep-link intake + loopback AnkiConnect v6 server over TakobotoAnkiStore (API key, origin allowlist)
- F-6 sync DB schema forced open outside any tx (first-use-in-transaction bug) + synchronous=FULL re-asserted per outer tx
- T305c Takoboto note type (fixed id, ADR-013) + op-backed AnkiStore (AnkiIdMap ankiapi.db, NoteCreator/DuplicateCheck, eager AnkiStoreHolder), S01 replay test — reviewer APPROVE
- F-5 server auto_status via core DefaultWordStatusRules (CoreAutoStatusHook; OpApplier re-derives after review/preset/merge/fsrs_params) — server = device test
- T304c+W-4 review actions (suspend/bury/flag/never-forget/blacklist/edit, undo) + media wiring (CardAudioNeeds, MediaSettings, prefetch after sync)
- T305b on-device NoteCreator (EntryMatcher, DuplicateCheck, card_kinds/add_kanji, one-tx mint, request-id retry, two-device merge test) — reviewer + architect APPROVE
- T304b Android review screen (ReviewSession mints via OpMinter, JS-off WebView, undo, durability test, readers gated on startup replay, device auto_status, BlobStore audio) — 2 attempts (stale base)
- T303e Android sync UI (chip, Sync screen, Rejected list grouped, Re-mint flow hidden on clock wrong, unreachable vs server error) — 1 attempt
- T310 audio prefetch (planner, Range-resume downloader w/ SHA-256, BlobStore on durable driver, WorkManager) — wiring gaps → W-4
- T303d core rejected ops (Retry/Discard/clock repair) + Re-mint §10.4 (1a/1b/1c, E order, 6a re-key, atomic rollback), 4 scenario files + 110-seed fuzz — reviewer + architect APPROVE, 1 attempt
- T303c Android sync: SQLDelight DeviceStore/LearningTables, DurableDriver (WAL+FULL), HttpSyncTransport, SyncCoordinator, WorkManager, startup-replay gate — reviewer + architect APPROVE, 1 attempt
- T303b core device apply (all op types, append-only review_log, refused cache, startup replay, merge readers, rebuild 0–3, auto-retry; 112-seed fuzz) — 2 attempts (1 cut off); reviewer + architect APPROVE
- T307+T309 widget/QS tile + ntfy/UnifiedPush (server NtfySink wired in Main.kt, SSRF-guarded, off unless NTFY_BASE_URL)
- T312 Android release: env signing, tag→versionCode, GitHub release workflow publishing both flavors
- T303a core sync client (outbox, mint incl. mintAt, push/pull/purge, clock_blocked hysteresis, resetLog) — 2 attempts; reviewer + architect APPROVE
- C-10 SYNC 6.2: log reset sets pull_seq=0 (fixes restore livelock); model.py guard + 12.5.1
- T305a AnkiDroid-API provider + flavors (ankidroid default com.ichi2.anki, own); no silent fallback (AnkiStoreHolder); both APKs build — 2 attempts, reviewer APPROVE
- T308 notifications/due alarms (inexact alarms, boot/TZ reschedule, DueTrigger seam, stale-sync notice)
- T302 on-device dictionary (DICT_PACKAGE installer, atomic swap, offline search = server on 50 T106 queries; core Romaji)
- T304a Android queue loader (mirrors server loader rules; DST StudyDay; counts/forecast) — smart decks + headword seams empty
- T301b Android pairing (QR/manual), OkHttp ApiClient with typed errors, Keystore AES-GCM TokenStore, MetaLimitsStore
- T301a Android shell: Compose nav, Koin FeatureRegistry, SQLDelight from contracts/db, settings store, i18n
- F-3 server: refused cache written + skipped by QueueLoader, durable_seq persisted while backups off (startup + every change tx), remint-scenarios + SimDevice port + fuzz re-mint windows — reviewer APPROVE
- F-2 core: decide step 1b, IncrementalReplay refusal (never throws, heals on undo/params), queue rule 4 (home-deck charging, atomic groups) — reviewer APPROVE
- F-4 backup L tag + restore data-loss preflight (refuses without --accept-data-loss) — k3d drill not run
- C-8 sync gaps: refused cache (9.4), durable_seq while backups off, re-mint E/D rules (mining kept, double removal), add_kanji attribution — 4 attempts; reviewer + architect APPROVE
- T207c jpdb import end to end (worker apply_plan/fetch_blob, server ImportsModule live, internal jobs hidden, heartbeat/requeue cap, no resurrection) — live run in HUMAN_CHECKS
- C-9 internal job kinds (hidden from API/SSE), jpdb-history-apply 4-step protocol, reviews_id_collisions — reviewer APPROVE
- T214 web jpdb import screen (upload → import → SSE progress → counters + unmapped tables) — usable once T207c wires the server
- T207b jpdb history import server side (plan → ops via OpApply, batched/resumable, idempotent re-run, ADR-009) — reviewer APPROVE; not wired until T207c
- F-1 decks spot-check fixes (no !!, limit/422 tests, refused item ops, deck.updated 2-device convergence, deck: on smart)
- W-3 web nav: Cards + Note types links (Review/Decks/Settings already present)
- T210b web card browser (windowed table, core search/preview, bulk with dry-run count) — untested in a live browser
- T210a cards API (list/get/create/update/delete/reviews, search via T108b, bulk ≤5000 + bulk/schedule via OpApply) — live in Main.kt
- T209 web review screen (grading 4/5 buttons, core previewIntervals, keyboard shortcuts, undo/bury/suspend, drill) + shared CardView — untested in a live browser
- T206 review API (queue via core builder, answer/undo via ReviewOps, drill/cram, word status, DST StudyDay, deleted-deck fallback) — live in Main.kt
- W-2 wiring: sync/notes/decks/settings live in Main.kt (shared OpApply), startup replay, placeholder scheduler version removed; e2e SyncWiringTest (REST→push→cache→pull, 2-device merge)
- T205 core queue builder (rules 1–5, round-robin, deckless/add_kanji, determinism property tests) + word auto status — reviewer APPROVE, 1 attempt
- T202d core NO_NUL codec check (iterative, jvm=js, per-op on push) — reviewer APPROVE; architect pass skipped (small, no conformance issue per reviewer)
- T212b settings + FSRS presets server (SCHEMA 6.1 validity test, unknown keys stored) — 2 attempts
- T202c §8.1 merge (reserveHlc), card cache via core ReplayEngine, restore/durable_seq, startup replay, 3-device convergence fuzz — reviewer + architect APPROVE, 1 attempt. SyncModule now safe to wire (W-2)
- C-7 settings keys kanji_before_vocab/mined_priority, int/bool validity test, add_kanji presence rule, QueueInput.decklessAddKanji — 2 attempts
- C-6 storable values (BREAKING, no data): no U+0000, int4 caps, instant_ms ≤ 9999, max interval 36500 — 2 attempts; reviewer + architect APPROVE
- T211 web decks + note-types screens (core parseQuery/completeQuery/renderCard; prod build bundles core verified)
- T204 decks API (10 ops, smart decks via SearchSqlCompiler, list decks enqueue deck-build; coverage stays 501) — not wired in Main.kt
- T201b notes + note-types API (10 ops via OpApply, CardGenerator cards, U+3000 tags → 422) — not wired in Main.kt
- T202b server sync: push/pull, OpApply (45 op types), hlc_last, reserveHlc merge seam, pg-safe instants — 2 attempts; reviewer + architect APPROVE. NOT wired in Main.kt until T202c (gate)
- T203 replay engine (order/replay/fold/decide, IncrementalReplay), 1000-case property test, jvm=js digest — reviewer + architect APPROVE, 1 attempt
- T202a sync core: HLC, remint, canonical JSON (jvm=js), op codec, PortableRegex (ECMA), push-body parser (per-op DuplicateKey/TooDeep), depth op 500/doc 512 — 4 attempts; reviewer + architect APPROVE
- C-5 SYNC 2.1: ECMA-262 pattern semantics (validate.py translator), depth op 500 / doc 512, 7 invalid/raw-push examples — reviewer APPROVE
- T213b web core wrapper web/src/lib/core/index.ts (renderCard/parseQuery/completeQuery/previewIntervals) + vitest vs T213a goldens
- T207a jpdb history import plan (worker): buckets property-tested, stable review ids, ADR-009 — reviewer APPROVE
- T213a card renderer + sanitiser (55 hostile payloads), 36 goldens jvm+js, card.css drift test
- T212 web settings/devices/PAT/QR pairing/attribution (web half; server half split → T212b, dep T202b)
- T208 web shell: login/session/nav/tokens/toasts/SSE; k3d dev HTTPS on :8443 (login verified)
- C-4 jpdb import contract aligned with ADR-009 (additive counters, bucket arithmetic for T207a)
- T201a note types + CardGenerator + template substitution (drift test vs V1 seeds)
- T111b deploy fixes: BACKUPS_DISABLED env, backup image, restore follow-ups (stamped pre-restore DB, DRILL-gated kill hook)
- T112 backups + restore drill — 3 attempts; reviewer APPROVE (watchdog, restore/backup exclusion, durable_seq under lock, crash-safe rename)
- T105b devices, pairing, PATs, scope enforcement — 2 attempts, reviewer APPROVE; revocation ends live streams (real wiring test)
- T111 Helm real workloads — k3d e2e: health, 401, login verified; dev needs HTTPS for Secure cookie
- W-1 wiring: auth + dict + jobs live in server (end-to-end WiringTest); worker dict-refresh job
- T105a auth: login, TOTP, sessions, proxy-aware rate limit — 2 attempts, reviewer APPROVE
- T109b worker job runner (1000 jobs × 3 runners exactly once, retries, crash reclaim, cancel)
- T109a jobs API, retention, SSE events (not yet wired — W-1)
- T106 dictionary search API (50 fixture queries) — handlers not yet wired in Main.kt (W-1 after T105a/T109a)
- T104 server skeleton (Ktor, jOOQ, Flyway, health/readiness, startup lock/restore/scheduler-version, 501 stubs, conformance test)
- T110 generated clients (TS, Kotlin, Python) + make check MODULE=contracts (4 checks + drift) — 2 attempts (haiku→sonnet)
- T103b worker matches DICT_PACKAGE.md — wrong-base/digest/missing-base rejections tested (60 worker tests)
- T108b search→SQL (Postgres + SQLite prefilter/residual) — 80 executed queries both dialects, DST day bounds
- C-3 dictionary package/diff contract (DICT_PACKAGE.md) — 2 attempts; content digests, published-base rule
- T107b FSRS scheduler — reviewer verified line-by-line vs py-fsrs 6.3.2; jvm+js identical
- T103 dictionary device package + diffs (zstd, S3 SigV4) — real size budget unmeasured
- C-2 freeze fixes — freeze A + B APPROVE; GATE-CONTRACTS auto-closed
- T107a PortableMath (fdlibm 5.3) — reviewer verified digests against StrictMath independently; jsTest on node wired
- T102 pitch/frequency/Tatoeba loaders — idempotent, coverage floors, CC0 test data
- T108a search parser/format/complete — examples, round trip, 3000 random trees
- T101 dictionary pipeline (JMdict/KANJIDIC2/KRADFILE → ref.*) — 13 tests incl. Postgres 17
- T006 core interfaces — 2 attempts; reviewer + architect APPROVE; PortableMath (fdlibm), search round-trip, queue rules
- C-1 db/sync contract alignment — 2 attempts; replay order, last_review_at, server_state, op versioning rule, FSRS bounds
- T004 OpenAPI contract — 3 attempts (1 Fable, 2 Opus); reviewer + architect APPROVE; 117 operations
- T005 sync contract — 4 attempts (1 Fable, 3 Opus); reviewer + architect APPROVE; restore detection, clock re-mint, op size cap
- T003 DB schema contract — 2 attempts; reviewer + architect APPROVE; 13 followups (T005 carries 8)
- T009b make check MODULE=deploy → helm check.sh (verified fails on broken chart)
- T001b+T002 SeaweedFS compose (dev-up all healthy), images/push/apk targets; apk fixed on attempt 2
- T008b jpdb fixture aligned to real export shape (invented values)
- T009 Helm chart + k3d — lint/template clean; k3d /healthz ok
- S02 jpdb map spike — 99.93% hit rate (GATE-S02 not triggered); jpdb vid == JMdict seq
- S01 AnkiDroid stand-in spike — both APK variants build; GATE-S01 deferred to HUMAN_CHECKS
- T008 golden fixtures — 240 FSRS vectors (py-fsrs 6.3.2), dict subsets, media, jpdb shape (unverified)
- T001 monorepo scaffold — make test green; compose minio unpullable → ADR-003/T001b
