# STATE
Mode: single-session
Phase: P5 (Struggle + grammar)   Gate: GATE-P2, GATE-P3, GATE-P4 deferred (HUMAN_CHECKS); contracts frozen
Updated: 2026-11-13 (orchestrator run 9 in progress)


## RESUME HERE (updated 2026-11-13, end of orchestrator run 8)
- Run 8 merged (6): T504a (web struggle UI), C-14 (worker-parse 1.1.0 rule-test), D-2 + D-3 (Docker volume leaks fixed: core/server/worker postgres+seaweedfs → tmpfs; counts stable), T506c (rule tester + 422/503 rule validation), T507 (grammar cards picker/rotation/i+0 + F-20 seed trigger GRAMMAR_SEED_ON_START).
- Stopped for context budget, not disk (32 GB free; human pruned volumes).
- T607 DEFERRED by human (LLM assist; DECISIONS.md line 32): never dispatch; P6 is complete without it.
- Nothing in flight; no worktrees or t/* branches.
- QUEUE ORDER next run (P5 remaining; all deps merged):
  1. F-25 (server, sonnet med): expose GrammarCardSentenceSource to review (no API op yet → may need C-ticket) — needed for P5 "Done when".
  2. T504b (android struggle + 4×3 hot-word widget; ticket plan/tickets/T504.md SPLIT line; mirror web T504a). Gradle slot.
  3. T508 (ticket plan/tickets/T508.md; web+android; deps T507 ✓ T406 ✓). Consider split a=web (no Gradle) / b=android.
  4. F-22 (grammar detection gaps; check part 1 vs T506a att 2), F-24 (contract polish) if cheap.
  5. Then `make test` → GATE-P5 deferred → P6 (T604 merged; T607 deferred).
- Merge helper unchanged (rebase → make check → ff → git mv HANDOFF.md plan/handoffs/<ID>.md → worktree remove -f -f → branch -D incl. worktree-agent-* → LOG → push).
- RESOURCES: MAX_PARALLEL_WRITERS=3; 1 Gradle-heavy at a time (count re-checks/reviewers). Stale idle Gradle daemons (4–6 h, ~5 GB RSS) seen run 8 — `./gradlew --stop` when nothing builds.
- BACKLOG status column is stale for P4/P5 (STATE/LOG are authoritative).

## Running
| Ticket | Agent | Attempt | Branch | Started |
|---|---|---|---|---|
| F-25 | writer | 1 | t/F-25-grammar-sentence-review | run 9 (Gradle after T504b) |
| T508b | writer | 1 | t/T508b-grammar-capture-android | run 9 |

## Parked
(none)

## Ready for review / In review
| Ticket | Reviewer | Started |
|---|---|---|

## Blocked
| Ticket | Reason | Needs |
|---|---|---|

## Notes
- Disk: root fs filled during make test (Docker anon volumes ~22 GB; prune denied → HUMAN_CHECKS). Merged worktrees now removed after each merge (works). Check `df -h /` before Gradle/Testcontainers-heavy batches.
- PUSH main BEFORE dispatching worktree writers: worktrees are cut from origin/main (T304b/T305b got a stale base on 2026-11-13).
- Project agent types ARE registered as of the 2026-11-12 restart; dispatch with the named agent types.
- MAX_PARALLEL_WRITERS=3 (crash 2026-11-13); 1 Gradle-heavy writer at a time; never two Gradle make checks at once (count reviewers). Human (go hard): up to 3 concurrent reviewers allowed.
- Handoffs archived to plan/handoffs/<ID>.md after merge (keeps HANDOFF.md off main → no rebase conflicts).
- Model tiers (ADR-004): `fable` tickets → Opus with architect role; escalation tops out at Opus. No Fable agents at all (human: burns usage). Resumes of old Fable agents are forbidden — restart on Opus in the same worktree. Agents must report tool refusals as BLOCKED, not work around them.
- Review rounds (human, 2026-11-12): loop while findings are REAL — blocker/major with a concrete failure scenario (inputs → wrong result/data loss/divergence). Minors and style never block (→ FOLLOWUPS). Re-reviews verify prior findings + only genuinely new majors. Safety cap: §9 (5 attempts → BLOCKED_HUMAN).

## Pending human questions
- (answered run 8) LLM assist DEFERRED: T607 not dispatched; P6 is complete without it.
- none blocking. See plan/HUMAN_CHECKS.md.

## Phase stats
P5: merged 14 (T501, T502, T503, T505a, T505b, C-13, W-6, T506a; run 8: T504a, C-14, D-2, T506c, D-3, T507) · attempts 16 · escalations 0 · left: F-25, T504b, T508
P4 DONE: W-5 merged run 7; make test green
P4: merged 33+/37 (run 6: F-14, F-15, F-16, F-17, F-18, F-19, T403b, T403c, T408a, T404b, T406, T408b, T410; left: W-5) (run 3: T402a, T401a, T411a, T405a, F-7; run 4: T402b, T409, T403a, C-11, F-8, F-9, T407, T404a, D-1, F-11, W-5a, T404c, T412, T413, F-12, F-13, T405b, T411b, F-10) · open high-risk: C-12, T411c, T403b
P2 DONE: merged 35/36 · attempts 53 · escalations 0 (make test green)
P3 DONE: merged 25/25 (22 planned leaf tickets + W-4/F-5/F-6) · attempts 36 · escalations 0 (make test green per module 2026-11-13 after env DiskFull) · attempts 35 · escalations 0
P0 done: 15 merged (T001, T001b, T002, T003–T006, T008, T008b, T009, T009b, S01, S02, C-1, C-2).
Merged 14/15 (freeze review next) (T009b added) (S02, T008b added) (T007 dropped, T001b added) · attempts 13 · escalations 0

## Recently merged (last 10)
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
