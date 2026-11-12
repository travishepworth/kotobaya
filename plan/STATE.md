# STATE
Mode: single-session
Phase: P3 (Android and offline)   Gate: GATE-P2 deferred (HUMAN_CHECKS); contracts frozen
Updated: 2026-11-12T19:23:09Z


## RESUME HERE (updated 2026-11-12T14:26:49Z)
- P1, P2 done (make test green; GATE-P1/P2 deferred). P3 tickets in plan/tickets/ (22; splits in BACKLOG "Phase 3 splits"). Ready order: T301a + T303a → T301b/T304a/T305a/T303b → ...
- Next after running ones: T213a (after T201a), T202b (after T202a), T207a (after C-4), T203 (after T202a). High-risk (T202*, T203, T205, T207*) → reviewer (opus) + architect 2nd pass for sync/replay.
- Web tickets: tell writers SvelteKit 3 = relative imports (no $lib), client via web/src/lib/api/generated.ts (see FOLLOWUPS T208).
- Conventions: named agent types now registered; architect-role tickets run with model=opus (never Fable). Archive HANDOFF.md → plan/handoffs/<ID>.md at merge. MAX_PARALLEL_WRITERS=6, ≤3 Gradle-heavy. Review loop only on real majors with failure scenarios.

## Running
| Ticket | Agent | Attempt | Branch | Started |
|---|---|---|---|---|
| T312 | writer-light (haiku) | 1 | t/T312-android-release | 2026-11-12T19:23:22Z |
| T307+T309 | writer (sonnet) | 1 | t/T307+T309-widget-ntfy | 2026-11-12T19:19:41Z |
| T303a | writer-senior (opus) | 2 | t/T303a-sync-client-protocol | 2026-11-12T19:18:06Z |
| C-10 | architect (opus) | 1 | t/C-10-log-reset-pull-seq | 2026-11-12T19:18:06Z |

## Queued (ready; waiting for a Gradle slot, ≤3 heavy)
- none

## Ready for review / In review
| Ticket | Reviewer | Started |
|---|---|---|

## Blocked
| Ticket | Reason | Needs |
|---|---|---|

## Notes
- Project agent types ARE registered as of the 2026-11-12 restart; dispatch with the named agent types.
- Stale worktrees (all merged tickets + the 4 stopped runs; old branch names held, hence `-r` branches) still present: removal denied by permission classifier → in HUMAN_CHECKS.
- MAX_PARALLEL_WRITERS=6; ≤3 Gradle-heavy at once. Human (go hard): up to 3 concurrent reviewers allowed.
- Handoffs archived to plan/handoffs/<ID>.md after merge (keeps HANDOFF.md off main → no rebase conflicts).
- Model tiers (ADR-004): `fable` tickets → Opus with architect role; escalation tops out at Opus. No Fable agents at all (human: burns usage). Resumes of old Fable agents are forbidden — restart on Opus in the same worktree. Agents must report tool refusals as BLOCKED, not work around them.
- Review rounds (human, 2026-11-12): loop while findings are REAL — blocker/major with a concrete failure scenario (inputs → wrong result/data loss/divergence). Minors and style never block (→ FOLLOWUPS). Re-reviews verify prior findings + only genuinely new majors. Safety cap: §9 (5 attempts → BLOCKED_HUMAN).

## Pending human questions
- none blocking. See plan/HUMAN_CHECKS.md.

## Phase stats
P2 DONE: merged 35/36 · attempts 53 · escalations 0 (make test green)
P3: merged 6/23 · attempts 13 · escalations 0
P0 done: 15 merged (T001, T001b, T002, T003–T006, T008, T008b, T009, T009b, S01, S02, C-1, C-2).
Merged 14/15 (freeze review next) (T009b added) (S02, T008b added) (T007 dropped, T001b added) · attempts 13 · escalations 0

## Recently merged (last 10)
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
