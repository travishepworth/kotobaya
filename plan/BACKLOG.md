# BACKLOG

Every ticket the build needs, in phase order. The orchestrator updates only `status` and adds
tickets (splits, fixes, contract changes). Spec section names refer to `docs/SPEC.md`.

Fields: **model** = who writes it · **risk** = who reviews it (ORCHESTRATOR.md §6) ·
**deps** = must be MERGED first · **paths** = the only paths the writer may edit ·
**accept** = definition of done (tests must prove it where possible).

Gate deps: in single-session mode only GATE-0 blocks, and only until `docs/SPEC.md` exists. GATE-CONTRACTS
is auto-closed by automated review (ORCHESTRATOR.md §8a); every other gate is deferred (§0).

Status values: TODO · RUNNING · IN_REVIEW · MERGED · BLOCKED · BLOCKED_CONTRACT · BLOCKED_HUMAN

Module key for `make check MODULE=`: `core`, `server`, `worker`, `web`, `android`, `deploy`, `contracts`.

---

## GATE-0 — Kickoff (human)

**Status: SATISFIED 2026-11-12** — `docs/SPEC.md` exported; unanswered DECISIONS take their defaults;
missing items below are tracked in `plan/HUMAN_CHECKS.md` and do not block (ORCHESTRATOR.md §0).

Originally, before any ticket ran, the human would:
1. Export the spec doc as Markdown to `docs/SPEC.md`.
2. Answer the spec's open questions in `docs/DECISIONS.md` (template provided).
3. Provide: container registry URL, cluster arch (amd64/arm64), domain for DNS-01, whether
   Postgres/S3 already exist on the cluster, jpdb API key (in `.env.local`, never committed).
4. Put a jpdb review-history export sample at `fixtures/private/jpdb-export.json` (git-ignored).

---

## Phase 0 — Bootstrap, contracts, spikes

### T001 — Monorepo scaffold
- model: sonnet · risk: med · deps: GATE-0 · status: MERGED
- paths: `/` (root files), `core/`, `server/`, `android/`, `web/`, `worker/`, `deploy/`, `assets/`, `Makefile`, `docker-compose.yml`, `.editorconfig`, `.gitignore`
- spec: Architecture, Infrastructure › Build and release
- do: Gradle (Kotlin DSL, version catalog) with modules `core` (KMP: jvm, android, js), `server` (Ktor), `android` (app). `worker/` Python 3.12 with uv + pyproject, ruff, mypy, pytest. `web/` SvelteKit static adapter, TypeScript strict, vitest, eslint. `core`'s js target (IR, ES modules, `generateTypeScriptDefinitions()`) is built as the local package `@kotobaya/core` and consumed by `web/` as a workspace/file dependency; `make check MODULE=web` builds it first. `assets/card/` holds the shared card stylesheet and fonts list, consumed by `web/` and copied into Android assets by Gradle. `docker-compose.yml` for postgres, minio, api, worker, web. Makefile targets: `check MODULE=x`, `test`, `fmt`, `dev-up`, `dev-down`. One smoke test per module. No lint gates.
- accept: `make test` green from a clean clone; `make dev-up` starts all services with health checks passing; a web smoke test imports a trivial `@JsExport` function from `@kotobaya/core`.

### T001b — Compose: SeaweedFS instead of MinIO (ADR-003)
- model: haiku · risk: low · deps: T001 · status: MERGED (batched with T002)
- paths: `docker-compose.yml`
- accept: `make dev-up` all healthy.

### T002 — Image build and release script
- model: haiku · risk: low · deps: T001 · status: MERGED
- paths: `deploy/build/`, `Makefile` (`images`, `push`, `apk` targets only)
- do: `make images` builds api, worker, web for the cluster arch in DECISIONS (buildx); `make push` pushes to the registry; `make apk` builds a release APK. One optional workflow file that runs `make images push` on tag — no per-PR CI.
- accept: `make images push` publishes 3 images.
### T003 — Contract: database schema
- model: fable · risk: high · deps: T001 · status: MERGED
- paths: `contracts/db/`
- spec: Data model, Sync and offline, Scheduling
- do: Postgres DDL as Flyway `V1__init.sql` covering every table in the spec, with constraints, indexes (incl. pg_trgm for search), and comments. Separate reference-data schema (`ref.*`) from learning data (`app.*`). Matching SQLDelight `.sq` files for the on-device subset. A `SCHEMA.md` explaining each table, which side(s) hold it, and which sync op mutates it.
- accept: DDL applies cleanly to Postgres 17; SQLDelight files compile; every learning-data table maps to at least one op in T005 (cross-check listed in SCHEMA.md).

### T004 — Contract: OpenAPI 3.1
- model: fable · risk: high · deps: T003 · status: MERGED
- paths: `contracts/api/`
- spec: Public API and scripting, Sync and offline
- do: Full `openapi.yaml` for `/api/v1`: every endpoint in the spec's Resources table plus auth, devices, tokens, settings, events (SSE documented), errors (RFC 9457 problem+json), pagination (cursor), scopes per operation. Examples for each request/response.
- accept: Every spec endpoint present; every operation declares a scope.

### T005 — Contract: sync operations and conflict rules
- model: fable · risk: high · deps: T003 · status: MERGED
- paths: `contracts/sync/`
- spec: Sync and offline, Data model
- do: JSON Schema per op type (`review.logged`, `note.created`, `note.updated`, `note.deleted`, `card.suspended`, `card.buried`, `card.rescheduled`, `word.status_set`, `deck.*`, `deck_item.*`, `settings.updated`, `confusable.*`, `grammar_point.*`, `parse_override.*`, `media.*` metadata). HLC format and comparison. Conflict rule per op. Replay semantics (what triggers FSRS re-replay). Idempotency rules. Push/pull batch formats. `SYNC.md` with sequence diagrams and worked examples for: two devices reviewing the same card offline; edit vs delete; same word added on two devices.
- accept: Schemas validate the examples; every conflict case in the spec has a worked example.

### T006 — Contract: core interfaces
- model: fable · risk: high · deps: T003, T005 · status: MERGED
- paths: `contracts/core/`, `core/src/commonMain/kotlin/kotobaya/contracts/`
- spec: Scheduling, Public API › Card search language
- do: Kotlin interfaces + data classes (no implementations) for: `Scheduler` (FSRS), `QueueBuilder`, `CardGenerator`, `ReplayEngine`, `HotScorer`, `SearchQueryParser` (with the AST, plus `complete(prefix)` for autocomplete), `CardRenderer` (note + card kind + render options such as furigana on/off and pitch on/off → sanitised HTML fragment using only classes from `assets/card/`), `Hlc`, `OpCodec`. A `CoreJs` facade listing exactly what is `@JsExport`ed to the web app (search parse/complete, card render, FSRS interval preview), with JS-friendly types (no Long/collections in signatures). KDoc with exact semantics, edge cases, and invariants. Grammar for the search language in EBNF.
- accept: Compiles for jvm, android, js; KDoc covers every public member; EBNF parses every example in the spec.

### T007 — (dropped in lean mode)
- status: MERGED · Phase "Done when" checks are done by the human at each gate.
### T008 — Golden fixtures
- model: sonnet · risk: med · deps: T001 · status: MERGED
- paths: `fixtures/`
- do: FSRS reference vectors (review sequences → expected stability/difficulty/interval) generated from the reference implementation pinned in DECISIONS; a 2k-entry JMdict subset + matching KANJIDIC2 subset; 20 sample sentences with expected segmentation; small SRT, EPUB, and Mokuro samples (public-domain or self-written text only); an anonymised jpdb-export shape derived from the private sample (structure only, no personal data).
- accept: Each fixture has a README stating origin and licence; FSRS vectors include ≥ 200 sequences incl. lapses and long gaps.

### T009 — Helm chart skeleton and dev cluster
- model: sonnet · risk: med · deps: T001 · status: MERGED
- paths: `deploy/helm/`, `deploy/k3d/`
- spec: Infrastructure
- do: Chart with values for each workload (enabled flags for postgres/minio so existing services can be used), ingress, secrets references, migrate hook placeholder, NetworkPolicies, non-root securityContexts. `deploy/k3d/up.sh` creates a local cluster and installs the chart.
- accept: `helm lint` and `helm template` clean; `k3d/up.sh` brings up a cluster where `/healthz` answers.

### S01 — Spike: Takoboto → AnkiDroid stand-in
- model: opus · risk: med · deps: T001 · status: MERGED · parallel-ok
- paths: `spikes/ankidroid-standin/`
- spec: Clients › Takoboto integration
- do: Minimal Android app that declares AnkiDroid's ContentProvider authority and permission, implements decks / models / model fields / notes insert / duplicate query against an in-memory store, and logs every call with arguments to a screen and logcat. Build variant with `applicationId com.ichi2.anki`.
- accept: APK builds; README lists the exact manual test. → **GATE-S01**

### GATE-S01 — Takoboto spike test (human)
Install the spike APK (AnkiDroid not installed), add a word from Takoboto, send the call log.
Record in DECISIONS: works with own applicationId, or needs the `com.ichi2.anki` variant.

### S02 — Spike: jpdb deck fetch and JMdict mapping
- model: sonnet · risk: low · deps: T008 · status: MERGED · parallel-ok
- paths: `spikes/jpdb-map/`
- spec: Content and media import › jpdb import
- do: Script that, using the API key from `.env.local`, fetches one user-chosen deck's vocabulary with occurrence counts (API first, page fallback with polite rate limiting and caching), maps to JMdict sequence numbers, and reports hit rate and unmapped samples. Also parses the jpdb export sample and reports its structure.
- accept: Report in `spikes/jpdb-map/REPORT.md`. → **GATE-S02** if hit rate < 95%.

### T008b — Fix jpdb fixture shape from S02 report
- model: haiku · risk: low · deps: S02 · status: MERGED
- paths: `fixtures/jpdb/`
- do: Rewrite `fixtures/jpdb/reviews-sample.json` (invented values) to the real export structure in `spikes/jpdb-map/REPORT.md`.
- accept: fixtures/tools pytest green; README cites REPORT.md.

### T009b — Wire `make check MODULE=deploy` to the Helm check
- model: haiku · risk: low · deps: T002, T009 · status: MERGED
- paths: `Makefile` (check-deploy target only)
- accept: `make check MODULE=deploy` runs `deploy/helm/check.sh` and is green; fails if the chart breaks.

### C-1 — Contract alignment from T006 reviews (db + sync, non-breaking)
- model: opus · risk: high · deps: T005 · status: MERGED
- paths: `contracts/db/`, `contracts/sync/`
- see plan/tickets/C-1.md

### C-2 — Contract freeze fixes (freeze review A + B)
- model: opus · risk: high · deps: C-1 · status: MERGED
- see plan/tickets/C-2.md

### GATE-CONTRACTS — Freeze contracts (human)
**Status: AUTO-CLOSED 2026-11-12** after freeze review A + B (2 rounds, C-1/C-2). Human review deferred to plan/HUMAN_CHECKS.md.
Single-session: auto-closed after the automated cross-contract review (ORCHESTRATOR.md §8a); the human
reviews `contracts/SUMMARY.md` later from `plan/HUMAN_CHECKS.md`. Gated: human reviews `contracts/`
(T003–T006) with the architect's summary and approves or requests changes.
Either way, contracts are then frozen (ORCHESTRATOR.md §10).

---

## Phase 1 — Foundations

### T101 — Dictionary pipeline: JMdict, KANJIDIC2, KRADFILE
- model: sonnet · risk: med · deps: GATE-CONTRACTS, T008 · status: MERGED
- paths: `worker/kotobaya_worker/dict/`, `worker/tests/dict/`
- do: Download (with checksums and caching), parse, normalise into `ref.*` tables for a release id.
- accept: Fixture subset loads exactly; idempotent re-run.

### T102 — Dictionary pipeline: pitch, frequency, Tatoeba
- model: sonnet · risk: low · deps: T101 · status: MERGED
- paths: `worker/kotobaya_worker/dict/extra/`, `worker/tests/dict/extra/`
- do: Kanjium pitch; frequency lists in Yomitan format (list configurable in DECISIONS); Tatoeba JP-EN pairs linked to entries.
- accept: Coverage report (entries with pitch / with freq rank / with examples) printed and asserted above floors set in the test.

### T103 — Dictionary release packaging for devices
- model: sonnet · risk: med · deps: T102 · status: MERGED
- paths: `worker/kotobaya_worker/dict/package/`, `worker/tests/dict/package/`
- do: Build the compact SQLite (SQLDelight schema) for a scope (referenced + top N, or full); zstd-compress; content-addressed upload to object storage; manifest with version and diff from previous release.
- accept: Package for top-60k < budget in DECISIONS; diff between two fixture releases applies correctly.

### T104 — Server skeleton
- model: sonnet · risk: med · deps: GATE-CONTRACTS · status: MERGED
- paths: `server/`
- do: Ktor app: config, Hikari + Exposed or jOOQ (per DECISIONS), Flyway from `contracts/db`, problem+json errors, request ids, structured logs, `/healthz` `/readyz` `/metrics`, OpenAPI conformance test (every implemented route matches `contracts/api`), stub routes return 501.
- accept: Conformance test green.

### T105 — Auth, devices, tokens, audit
- model: opus · risk: high · deps: T104 · status: SPLIT
- paths: `server/src/main/kotlin/kotobaya/auth/`, `server/src/test/kotlin/kotobaya/auth/`
- do: Single-user password (argon2id) + optional TOTP; session cookie (HttpOnly, SameSite=Strict); device pairing via short-lived QR code → device token; PATs with scopes and expiry, stored hashed; scope enforcement middleware driven by the OpenAPI scopes; structured audit log lines plus `change.token_id` (openapi §2: no audit table in v1).
- accept: Tests for every scope boundary; brute-force rate limit; token revocation takes effect immediately.

### T105a — Auth: password, TOTP, session, login rate limit
- model: opus · risk: high · deps: T104 · status: MERGED
- see plan/tickets/T105a.md (split from T105)

### T105b — Auth: devices, pairing, PATs, scope middleware
- model: opus · risk: high · deps: T105a · status: MERGED
- see plan/tickets/T105b.md (split from T105)

### T106 — Dictionary search API
- model: sonnet · risk: med · deps: T101, T104 · status: MERGED
- paths: `server/src/main/kotlin/kotobaya/dict/`, tests alongside
- do: `/dict/search` (kanji, kana, romaji via wanakana-equivalent conversion, deinflected input), `/dict/entries/{seq}`, `/kanji/{literal}`; ranking by match type then frequency.
- accept: The 50 fixture queries return the expected top result.

### T107 — FSRS implementation
- model: opus · risk: high · deps: T006, T008 · status: SPLIT
- paths: `core/src/commonMain/kotlin/kotobaya/fsrs/`, `core/src/commonTest/kotlin/kotobaya/fsrs/`
- do: Implement `Scheduler` per contract: FSRS version pinned in DECISIONS, fuzz (seeded), learning/relearning steps, desired retention, easy days, load balancing hook.
- accept: Matches every golden vector within tolerance on jvm and js targets.

### T107a — FSRS: PortableMath, nodejs() target, :core:jsTest in make check core
- model: opus · risk: high · deps: T006, T008 · status: MERGED
- see plan/tickets/T107a.md (split from T107)

### T107b — FSRS: Scheduler + golden vectors + jvm/js bit-identity digest
- model: opus · risk: high · deps: T107a · status: MERGED
- see plan/tickets/T107b.md (split from T107)

### T108 — Search query language
- model: sonnet · risk: med · deps: T006 · status: SPLIT
- paths: `core/src/commonMain/kotlin/kotobaya/search/`, tests
- do: Parser per EBNF → AST; SQL compiler for Postgres and SQLite dialects.
- accept: Every spec example parses; property tests: random ASTs → print → parse round-trips.

### T108a — Search: parser, format, complete
- model: sonnet · risk: med · deps: T006 · status: MERGED
- see plan/tickets/T108a.md (split from T108)

### T108b — Search: SQL compiler
- model: sonnet · risk: med · deps: T108a · status: MERGED
- see plan/tickets/T108b.md (split from T108)

### T109 — Job queue and events
- model: sonnet · risk: med · deps: T104 · status: SPLIT
- paths: `server/src/main/kotlin/kotobaya/jobs/`, `worker/kotobaya_worker/runner/`, tests
- do: `app.job` table with `SKIP LOCKED` claiming, retries with backoff, progress, cancellation; worker runner loop; `/jobs` endpoints; `/events` SSE fan-out via Postgres LISTEN/NOTIFY.
- accept: 1000 jobs across 3 workers complete exactly once; SSE delivers progress events.

### T109a — Jobs API, retention, SSE
- model: sonnet · risk: med · deps: T104 · status: MERGED
- see plan/tickets/T109a.md (split from T109)

### T109b — Worker runner + 1000-jobs test
- model: sonnet · risk: med · deps: T109a · status: MERGED
- see plan/tickets/T109b.md (split from T109)

### T110 — Generated API clients
- model: haiku · risk: low · deps: T004 · status: MERGED
- paths: `clients/`, `Makefile` (`check MODULE=contracts` target)
- do: Generate Kotlin (for android), TypeScript (for web), Python (for CLI and worker) clients from `contracts/api`; `make check MODULE=contracts` regenerates and fails on diff.
- accept: Clients compile; `make check MODULE=contracts` green.

### T111 — Helm: real workloads
- model: sonnet · risk: med · deps: T009, T104, T109a, T109b · status: MERGED
- paths: `deploy/helm/`
- do: api, worker, web, optional postgres (CNPG or StatefulSet) and minio; migrate pre-upgrade hook; ingress with TLS from DECISIONS; resource requests; PodDisruptionBudgets off (single replica).
- accept: `deploy/k3d/up.sh` → working API with dictionary search through ingress.

### T112 — Backups and restore verification (early, before real data)
- model: sonnet · risk: high · deps: T111 · status: MERGED
- paths: `deploy/helm/templates/backup*`, `deploy/backup/`
- do: Nightly `pg_dump` + object-storage mirror, restic-encrypted, to the off-cluster target in DECISIONS; weekly restore into scratch DB with row-count checks; failure → ntfy/alert.
- accept: Restore drill script documented and passing in k3d.

→ `make test`, then **GATE-P1**: human runs `helm install` on the real cluster and searches the dictionary.

---

### C-3 — Contract: dictionary package + diff format (additive)
- model: opus · risk: high · deps: T103 · status: MERGED
- see plan/tickets/C-3.md

### T103b — Worker: align dictionary packaging with C-3
- model: sonnet · risk: med · deps: T103, C-3 · status: MERGED
- see plan/tickets/T103b.md

### T111b — Deploy fixes from T112 (SeaweedFS preallocation, BACKUPS_DISABLED, backup image)
- model: sonnet · risk: low · deps: T111, T112 · status: MERGED
- see plan/tickets/T111b.md

### W-1 — Wire server modules and worker jobs
- model: sonnet · risk: med · deps: T105a, T106, T109a, T109b · status: MERGED
- see plan/tickets/W-1.md

## Phase 2 — Review loop (web)

### T201 — Notes, note types, card generation
- model: sonnet · risk: med · deps: GATE-P1 · status: SPLIT (all parts MERGED)
- paths: `core/src/commonMain/kotlin/kotobaya/cards/`, `server/src/main/kotlin/kotobaya/notes/`, tests
- do: Built-in note types (vocab, kanji, phrase, grammar, sentence, custom) with templates; `CardGenerator`; notes CRUD writing ops; template field substitution (sanitised HTML). Full card rendering is T213.
- accept: Creating each note type yields the spec's default cards; optional kinds toggle correctly.

### T202 — Op log, sync push/pull (server)
- model: fable · risk: high · deps: GATE-P1 · status: SPLIT (all parts MERGED)
- paths: `server/src/main/kotlin/kotobaya/sync/`, `core/src/commonMain/kotlin/kotobaya/sync/`, tests
- do: HLC implementation; op validation against `contracts/sync`; idempotent apply; server sequence; push/pull endpoints; tombstones; same-entry merge.
- accept: Every worked example in `contracts/sync/SYNC.md` is a passing test; fuzz test: random op interleavings from 3 simulated devices converge to identical state.

### T203 — Replay engine
- model: fable · risk: high · deps: T107, T202 · status: MERGED
- paths: `core/src/commonMain/kotlin/kotobaya/replay/`, tests
- do: Rebuild card state from `review_log` (ordered by HLC), incremental replay for out-of-order inserts, drill/cram exclusion, parameter changes trigger full recompute.
- accept: Incremental and full replay always agree (property test).

### T204 — Decks and deck items
- model: sonnet · risk: med · deps: T201 · status: MERGED
- paths: `server/src/main/kotlin/kotobaya/decks/`, tests
- do: Deck types (user, media, list, smart), priorities, limits, ordering, deck_item CRUD via ops.
- accept: Smart deck query uses T108; reorder ops converge under concurrency.

### T205 — Queue builder
- model: opus · risk: high · deps: T203, T204 · status: MERGED
- paths: `core/src/commonMain/kotlin/kotobaya/queue/`, tests
- do: Daily limits (global, per deck), deck priority for new cards, sibling burying, interleaving, new-per-session cap, kanji-before-vocab option, day boundary in user's time zone.
- accept: Deterministic for a given seed and clock; table-driven tests for every rule in Scheduling › Queue building.

### T206 — Review endpoints
- model: sonnet · risk: med · deps: T205 · status: MERGED
- paths: `server/src/main/kotlin/kotobaya/review/`, tests
- do: `/review/queue`, `/review/answer` (4- and 5-button modes), `/review/undo`, word-status updates, never-forget / blacklist.
- accept: Undo restores exact prior state; 5-button mapping logged as specified.

### T207 — jpdb history import
- model: opus · risk: high · deps: T203, T206, S02 · status: SPLIT (all parts MERGED)
- paths: `worker/kotobaya_worker/importers/jpdb_history/`, `server/src/main/kotlin/kotobaya/imports/`, tests
- do: Parse jpdb export; map words (using S02 mapping); create notes/cards; convert reviews to `review.logged` ops; replay; carry over known / never-forget / blacklisted; report unmapped items.
- accept: On the private sample: ≥ 95% mapped; replay produces due dates; report lists every unmapped item.

### T208 — Web shell
- model: sonnet · risk: low · deps: T110, T105 · status: MERGED
- paths: `web/src/lib/`, `web/src/routes/+layout*`, `web/src/routes/login/`
- do: Auth flow, layout, nav, design tokens (light/dark), Japanese font stack, generated TS client wiring, error toasts, SSE hook.
- accept: Login/logout works against the dev stack (manual).

### T209 — Web review screen
- model: sonnet · risk: med · deps: T206, T208, T213 · status: MERGED
- paths: `web/src/routes/review/`, `web/src/lib/review/`
- do: Card display via `renderCard` from `web/src/lib/core/` (no TS re-implementation), furigana toggle, keyboard-first (1–4 / 1–5, space, u), undo, edit note inline, bury/suspend/never-forget.
- accept: A full session works keyboard-only (manual).

### T210 — Web card browser and bulk ops
- model: sonnet · risk: med · deps: T108, T208, T213 · status: SPLIT (all parts MERGED)
- paths: `web/src/routes/cards/`, `server/src/main/kotlin/kotobaya/cards/bulk/`
- do: Search box (query language; validation and autocomplete via `parseQuery`/`completeQuery` from core, client-side), virtualised table, bulk tag/move/suspend/reschedule.
- accept: Bulk op runs as one job with progress.

### T211 — Web decks, note types, templates
- model: sonnet · risk: low · deps: T204, T208, T213 · status: MERGED
- paths: `web/src/routes/decks/`, `web/src/routes/note-types/`
- do: Template editor live preview renders client-side with `renderCard`.
- accept: Preview and review use the same rendering component.

### T212 — Web settings, devices, tokens
- model: sonnet · risk: med · deps: T105, T208 · status: SPLIT (all parts MERGED)
- paths: `web/src/routes/settings/`
- do: Settings, device list + QR pairing, PAT create/revoke with scopes, backup status, attribution page.
- accept: Token shown once; revoke works.

### T213 — Shared card renderer and core JS facade
- model: opus · risk: med · deps: T201, T108, T107 · status: SPLIT (all parts MERGED)
- paths: `core/src/commonMain/kotlin/kotobaya/render/`, `core/src/jsMain/`, `core/src/commonTest/kotlin/kotobaya/render/`, `assets/card/`, `web/src/lib/core/`
- spec: Scheduling › Card kinds per note type, Clients (Review screens), Public API › Card search language
- do: Implement `CardRenderer`: templates → HTML fragment with `<ruby>` furigana, pitch-accent spans (CardRenderer contract strips svg) from `entry_pitch`, audio placeholders the host fills, front/back; `assets/card/card.css` (light/dark via CSS variables, Japanese font stack). Implement the `CoreJs` facade (`@JsExport`) and a thin typed wrapper in `web/src/lib/core/` (`renderCard`, `parseQuery`, `completeQuery`, `previewIntervals`). One renderer for web review, template preview, and Android (WebView).
- accept: Golden HTML snapshot per built-in note type × card kind, identical on jvm and js targets; output passes the sanitiser (no script, no inline handlers); wrapper smoke test in vitest.

→ `make test`, then **GATE-P2**: human imports jpdb history and reviews in the web app for 3 days; reports issues.

---

## Phase 3 — Android and offline

### T301 — Android shell and local DB
- model: sonnet · risk: med · deps: GATE-P2 · status: SPLIT (all parts MERGED)
- paths: `android/app/src/main/` (excluding feature packages below), `android/app/src/test/`
- do: Compose app, navigation, DI (per DECISIONS), SQLDelight DB from contracts, pairing via QR scan, settings store.
- accept: Pairs with the dev server.

### T302 — On-device dictionary package
- model: sonnet · risk: med · deps: T103, T301 · status: MERGED
- paths: `android/app/src/main/kotlin/kotobaya/android/dict/`
- accept: Downloads, verifies, applies diffs; search works offline; storage shown in settings.

### T303 — Sync client
- model: opus · risk: high · deps: T202, T203, T301 · status: SPLIT (all parts MERGED)
- paths: `android/app/src/main/kotlin/kotobaya/android/sync/`, `core/src/commonMain/kotlin/kotobaya/sync/client/`
- do: Op outbox, batching, push/pull, local replay, WorkManager background sync with backoff, connectivity + server-reachability detection, status UI data.
- accept: Shares the T202 convergence fuzz harness (client side); kill-app-mid-push test loses nothing.

### T304 — Android review UI
- model: sonnet · risk: med · deps: T205, T301, T213 · status: SPLIT (all parts MERGED)
- paths: `android/app/src/main/kotlin/kotobaya/android/review/`
- do: Card display = `CardRenderer` (JVM) output in a WebView loading `assets/card/` locally (no network; one reused WebView, JS off unless needed for audio); native Compose for the grading bar, toolbar, and gestures. Furigana toggle, pitch graph (from the renderer), audio, swipe/tap grading (4/5), undo, edit, bury/suspend/never-forget.
- accept: Grading and undo work (manual on device).

### T305 — AnkiDroid stand-in (production)
- model: opus · risk: high · deps: GATE-S01, T303 · status: SPLIT (all parts MERGED)
- paths: `android/app/src/main/kotlin/kotobaya/android/ankiapi/`, `android/app/src/main/AndroidManifest.xml` (provider entries only)
- do: Productionise S01 against the real DB: Takoboto Vocab note type, field mapping, JMdict matching, duplicate detection, offline insert → ops. Build both product flavors: own app ID and `com.ichi2.anki`. In single-session mode the `com.ichi2.anki` flavor is the default; in gated mode, use the GATE-S01 decision.
- accept: Instrumented test replays the S01 call log and produces the expected notes. → **GATE-T305** (human: add 5 words from Takoboto).

### T306 — Share sheet, PROCESS_TEXT, deep links
- model: haiku · risk: low · deps: T301 · status: MERGED
- paths: `android/app/src/main/kotlin/kotobaya/android/intake/`
- accept: Each intent works (manual).

### T307 — Home-screen widget and Quick Settings tile
- model: sonnet · risk: low · deps: T205, T301 · status: MERGED
- paths: `android/app/src/main/kotlin/kotobaya/android/widget/`
- accept: Three sizes render from local DB with no network; tile launches review.

### T308 — Notifications and due alarms
- model: sonnet · risk: med · deps: T205, T301 · status: MERGED
- paths: `android/app/src/main/kotlin/kotobaya/android/notify/`
- do: Channels per spec; next-due inexact alarm batching (≥ 15 min); boot/time/timezone receivers; widget refresh from the same trigger; permission onboarding.
- accept: Unit test for the next-alarm calculation given synthetic due times.

### T309 — ntfy / UnifiedPush
- model: sonnet · risk: low · deps: T109, T301 · status: MERGED
- paths: `server/src/main/kotlin/kotobaya/push/`, `android/app/src/main/kotlin/kotobaya/android/push/`, `deploy/helm/templates/ntfy*`
- accept: Job completion produces an Android notification in k3d + emulator.

### T310 — Audio prefetch and storage budget
- model: sonnet · risk: low · deps: T303 · status: MERGED
- paths: `android/app/src/main/kotlin/kotobaya/android/media/`
- accept: Prefetch respects budget and N-day window; LRU eviction test.

### T311 — On-device AnkiConnect server
- model: sonnet · risk: med · deps: T305 · status: MERGED
- paths: `android/app/src/main/kotlin/kotobaya/android/ankiconnect/`
- accept: Yomitan-compatible request fixtures pass; bound to localhost only.

### T312 — Android release pipeline
- model: haiku · risk: low · deps: T002, T301 · status: MERGED
- paths: `.github/workflows/android-release*`, `android/signing/README.md`
- accept: Tag → signed APK release consumable by Obtainium.

### T313 — Offline chaos suite
- model: opus · risk: high · deps: T303, T305 · status: MERGED
- paths: `acceptance/offline/`
- do: Simulated two-device offline week: reviews, adds, edits, deletes, clock skew ±10 min; then sync in random orders.
- accept: Converges with zero lost reviews across 100 randomised runs (JVM simulation, no devices).

→ `make test`, then **GATE-P3**: one real week in airplane mode on the phone, then sync.

### Phase 3 splits and additions (2026-11-13, planner)
T301→T301a(shell, DI, SQLDelight)/T301b(pairing, net); T303→T303a(core: store seam, mint, outbox, push/pull)/T303b(core: device apply, replay, rebuild, log reset, refused cache)/T303c(Android: SQLDelight store, WorkManager, connectivity)/T303d(core: rejected ops, Retry/Discard, Re-mint, discard list)/T303e(UI); T304→T304a(queue loader)/T304b(review screen)/T304c(toolbar actions); T305→T305a(flavors, provider)/T305b(note creation service, new)/T305c(Takoboto note type, real store). Real deps differ from the parent rows (e.g. T304b needs T303c, T305b needs T302+T303c, T307 needs T308, T313 needs T303a/b/d + T305b). Ticket files in plan/tickets/ are authoritative for these; parent rows above are superseded. Android paths use the real module layout `android/src/main/...` (not `android/app/`). T313 adds an `acceptance` Gradle module.
- T301a status: MERGED · T301b MERGED · T302 MERGED · T303a MERGED · T303b MERGED · T303c MERGED · T303d MERGED · T303e MERGED · T304a MERGED · T304b MERGED · T304c MERGED · T305a MERGED · T305b MERGED · T305c MERGED · T306 MERGED · T307 MERGED · T308 MERGED · T309 MERGED · T310 MERGED · T311 MERGED · T312 MERGED · T313 MERGED
- W-4 MERGED (sonnet, low) Android media wiring — deps T310, T304b, T303e (ticket plan/tickets/W-4.md)
- F-5 MERGED (sonnet, med) server auto_status derivation — deps T304b (plan/tickets/F-5.md)
- F-6 MERGED (sonnet, med) sync DB first-use-in-transaction schema bug (plan/tickets/F-6.md)

---

## Phase 4 — Media

### T401 — jpdb media deck import
- model: sonnet · risk: med · deps: GATE-P3, S02 · status: SPLIT
- paths: `worker/kotobaya_worker/importers/jpdb_media/`
- accept: Imports a deck with occurrences and global rank; cache prevents refetch; unmapped → review queue.

### T402 — Text extractors
- model: sonnet · risk: low · deps: GATE-P3 · status: SPLIT
- paths: `worker/kotobaya_worker/extract/`
- do: SRT/ASS/VTT (timestamps), EPUB/TXT (chapters, ruby handling), Mokuro JSON, URL/HTML (readability), text-hooker logs.
- accept: Each fixture extracts to expected segments/sentences.

### T403 — Parser
- model: opus · risk: high · deps: T101, T402 · status: SPLIT
- paths: `worker/kotobaya_worker/parse/`
- do: Normalisation, Sudachi (full dict) mode C → B fallback, deinflection, JMdict candidate ranking, `parse_override` application, `/parse` endpoint backing.
- accept: ≥ 97% token-level agreement on the 20-sentence fixture (expand fixture to 200 sentences first if needed); overrides survive re-parse.

### T404 — Occurrences and coverage engine
- model: opus · risk: med · deps: T401, T403 · status: SPLIT
- paths: `worker/kotobaya_worker/coverage/`, `server/src/main/kotlin/kotobaya/coverage/`
- do: Per media/segment unique and token coverage, unknown-by-in-deck-frequency, top-N curve, filters, difficulty estimate; incremental recompute on word-status change.
- accept: Within 3 percentage points of jpdb's numbers on two imported decks (fixture snapshot).

### T405 — Coverage dashboards (web)
- model: sonnet · risk: low · deps: T404, T208 · status: SPLIT
- paths: `web/src/routes/media/`
- accept: Unknown words sortable by in-deck count and global rank; curve chart; filters persist.

### T406 — Web reader and parse correction
- model: sonnet · risk: med · deps: T403, T405 · status: MERGED
- paths: `web/src/routes/media/[id]/read/`, `web/src/lib/reader/`
- accept: Click word → lookup popup → mine; fix a token in ≤ 2 clicks creating a `parse_override`.

### T407 — jpdb mapping review queue (web)
- model: haiku · risk: low · deps: T401, T208 · status: MERGED
- paths: `web/src/routes/imports/mapping/`

### T408 — i+1 sentences and sentence cards
- model: sonnet · risk: low · deps: T404 · status: SPLIT
- paths: `server/src/main/kotlin/kotobaya/sentences/`, `web/src/routes/media/[id]/sentences/`

### T409 — Audio clip cutting
- model: sonnet · risk: low · deps: T402 · status: MERGED
- paths: `worker/kotobaya_worker/audio/`
- accept: ffmpeg-cut clips aligned to subtitle timestamps, stored by hash.

### T410 — Android coverage and sentence lists
- model: sonnet · risk: low · deps: T404, T301 · status: MERGED
- paths: `android/app/src/main/kotlin/kotobaya/android/media/browse/`

### Phase 4 splits and additions (2026-11-13, planner)
+C-11/C-12(contracts)/F-7(fixture); T401→T401a(client)/T401b(job); T402→T402a(subs/text)/T402b(epub/mokuro/html); T403→T403a(segmentation)/T403b(matching+service)/T403c(server API); T404→T404a(publish kit)/T404b(pipeline)/T404c(server coverage); T405→T405a(library)/T405b(dashboards); T408→T408a(server)/T408b(web); +T411a(blobs)/T411b(media API)/T411c(publish runner); +W-5(wiring). Ticket files plan/tickets/ hold deps/paths. Parents T401–T405, T408 → SPLIT.
- F-7 MERGED · T401a MERGED · T402a MERGED · T405a MERGED · T411a MERGED · C-11/T403a/T404c/T402b/T409 BLOCKED (disk full; work done, parked) · +F-8 TODO (server Flyway include V2+, deps C-11) · rest TODO (C-12, T401b, T402b, T403a, T403b, T403c, T404a, T404b, T405a, T405b, T406, T407, T408a, T408b, T409, T410, T411b, T411c, W-5)

→ `make test`, then **GATE-P4**: human compares coverage on two shows against jpdb.

---

## Phase 5 — Struggle tools and grammar

### T501 — Hot words, hot kanji, leeches
- model: sonnet · risk: med · deps: GATE-P4 · status: MERGED
- paths: `core/src/commonMain/kotlin/kotobaya/struggle/`, `server/src/main/kotlin/kotobaya/struggle/`
- accept: Scoring matches the spec's definition in table-driven tests; words decay off the list.

### T502 — Confusables
- model: sonnet · risk: low · deps: T501 · status: MERGED
- paths: `worker/kotobaya_worker/confusables/`, `server/src/main/kotlin/kotobaya/confusables/`
- do: Homophones, look-alike kanji (component/shape similarity from KRADFILE), close meanings.

### T503 — Drill sessions
- model: sonnet · risk: med · deps: T501 · status: MERGED
- paths: `core/src/commonMain/kotlin/kotobaya/drill/`, `server/src/main/kotlin/kotobaya/drill/`
- accept: Drill reviews logged as `drill`; replay proves they never change schedules.

### T504 — Struggle UIs (web, Android, widget hot word)
- model: sonnet · risk: low · deps: T502, T503, T307 · status: MERGED
- paths: `web/src/routes/struggle/`, `android/app/src/main/kotlin/kotobaya/android/struggle/`, `android/app/src/main/kotlin/kotobaya/android/widget/hotword/`

### T505 — Grammar library and seed stubs
- model: sonnet · risk: low · deps: GATE-P4 · status: MERGED
- paths: `server/src/main/kotlin/kotobaya/grammar/`, `fixtures/grammar-seed/`
- do: CRUD; ~300 N3–N1 stubs (title + pattern only, no copied explanations).

### T506 — Grammar rule DSL and matcher
- model: opus · risk: high · deps: T403, T505 · status: MERGED
- paths: `worker/kotobaya_worker/grammar/`, `contracts/grammar-dsl/` (via C-ticket if contract change)
- do: DSL grammar, parser, matcher over Sudachi tokens, rule tester endpoint, batch tagging of sentences.
- accept: 50 hand-written rules match their positive examples and none of their negative ones.

### T507 — Grammar cards
- model: sonnet · risk: med · deps: T506, T201 · status: MERGED
- paths: `core/src/commonMain/kotlin/kotobaya/cards/grammar/`, `server/src/main/kotlin/kotobaya/grammar/cards/`
- accept: Sentence rotates per review; prefers i+0 sentences.

### T508 — "Saw something weird" flow
- model: sonnet · risk: low · deps: T507, T406 · status: MERGED
- paths: `web/src/lib/grammar-capture/`, `android/app/src/main/kotlin/kotobaya/android/grammar/`

→ `make test`, then **GATE-P5**.

---

## Phase 6 — Polish

### T601 — VOICEVOX TTS
- model: sonnet · risk: low · deps: GATE-P5 · status: MERGED
- paths: `worker/kotobaya_worker/tts/`, `deploy/helm/templates/voicevox*`

### T602 — Pitch accent display and cards
- model: sonnet · risk: low · deps: GATE-P5 · status: MERGED
- paths: `core/src/commonMain/kotlin/kotobaya/cards/pitch/`, `core/src/commonMain/kotlin/kotobaya/render/pitch/`, `assets/card/`
- do: Pitch card kind and any pitch display beyond T213, inside the shared renderer only; no per-client pitch code.

### T603 — Server AnkiConnect endpoint
- model: sonnet · risk: med · deps: GATE-P5 · status: MERGED
- paths: `server/src/main/kotlin/kotobaya/ankiconnect/`

### T604 — CLI
- model: haiku · risk: low · deps: T110 · status: MERGED (run 7)
- paths: `cli/`

### T605 — Stats
- model: sonnet · risk: low · deps: GATE-P5 · status: MERGED
- paths: `server/src/main/kotlin/kotobaya/stats/`, `web/src/routes/stats/`

### T606 — FSRS optimizer job
- model: sonnet · risk: med · deps: GATE-P5 · status: MERGED
- paths: `worker/kotobaya_worker/optimizer/`, `deploy/helm/templates/cron-optimize*`
- accept: Parameters published only if they improve log-loss on a held-out split.

### T607 — Optional LLM assist
- model: sonnet · risk: med · deps: GATE-P5 · status: DEFERRED (human, run 8: LLM assist deferred — DECISIONS.md; do not dispatch; P6 complete without it)
- paths: `server/src/main/kotlin/kotobaya/llm/`
- do: Pluggable provider per DECISIONS; outputs labelled generated; off by default.

### T608 — Export (JSON, .apkg)
- model: sonnet · risk: med · deps: GATE-P5 · status: MERGED
- paths: `server/src/main/kotlin/kotobaya/export/`

### T609 — Security hardening review
- model: opus · risk: high · deps: T601–T608 · status: MERGED
- paths: `deploy/`, `docs/SECURITY.md` (findings become new tickets)

### T610 — Runbook and restore drill docs
- model: haiku · risk: low · deps: T609 · status: MERGED
- paths: `docs/RUNBOOK.md`

### F-28 — Worker image: CPU-only torch
- model: sonnet · risk: low · deps: T606 · status: MERGED
- paths: `worker/pyproject.toml`, `worker/uv.lock`, `worker/Dockerfile` (ticket: plan/tickets/F-28.md; from T606 HUMAN_CHECK)

### SEC-1 — Blob responses: nosniff/CSP/attachment (T609 S-01, HIGH)
- model: opus · risk: high · deps: T609 · status: MERGED
- paths: server blobs/, ankiconnect/AnkiMedia.kt + tests (ticket: plan/tickets/SEC-1.md)

### SEC-2 — Worker SSRF guard on URL import (T609 S-02)
- model: sonnet · risk: med · deps: T609 · status: MERGED
- paths: worker extract/html/, worker/tests/extract/ (ticket: plan/tickets/SEC-2.md)

→ **GATE-DONE**: human sign-off.

## Phase 7 — Live bugfixes (0.1.4, run 12; ahead of FOLLOWUPS)

### BUG-1 — Stats endpoints slow (LATERAL per review) + page fails as a whole
- model: opus · risk: high · deps: — · status: MERGED
- paths: server stats/ + tests, web/src/routes/stats/ (ticket: plan/tickets/BUG-1.md)

### BUG-2 — jpdb deck import title 404 + bare numeric ID mapping
- model: sonnet · risk: med · deps: — · status: MERGED (batched with BUG-3)
- paths: worker importers/jpdb_deck/ + tests, web media/new (ticket: plan/tickets/BUG-2.md)

### BUG-3 — FSRS optimizer naive datetimes
- model: sonnet · risk: med · deps: — · status: MERGED (batched with BUG-2)
- paths: worker optimizer/ + tests (ticket: plan/tickets/BUG-3.md)

### BUG-4 — VOICEVOX gosu crash-loop + readOnlyRootFilesystem override
- model: sonnet · risk: low · deps: — · status: MERGED
- paths: deploy/helm voicevox (ticket: plan/tickets/BUG-4.md)

### BUG-5 — /kanji/hot 501 + 501 audit + SSE ChannelWriteException
- model: sonnet · risk: med · deps: — · status: MERGED
- paths: server struggle/, jobs SSE, Main.kt wiring, tests (ticket: plan/tickets/BUG-5.md)

### BUG-6 — jpdb import replay: Hard never graduates (re)learning cards (~1,100 stuck)
- model: opus (architect) · risk: high (+architect second pass) · deps: — · status: MERGED
- paths: contracts/sync/, core replay/decide + scheduler version, tests (ticket: plan/tickets/BUG-6.md)

### BUG-7 — Card browser: preview unreachable/blank for dictionary-backed notes
- model: sonnet · risk: low · deps: — · module: web
- paths: web/src/lib/cards/ (CardTable.svelte, CardPreview.svelte, model.ts, messages.ts, api.ts), web/src/routes/cards/
- status: MERGED (ticket: plan/tickets/BUG-7.md)

Symptom (prod 0.1.5): on /cards, selecting a row only shows the bulk bar; the preview pane stays on "select a card to preview it". Affects ~6,591 of the user's notes (all jpdb-imported vocab).

Root cause (verified):
1. Preview is opened only by clicking the note label button in the Note column (CardTable.svelte ~L94). Its text is noteSummary(note.fields) (model.ts:39), which returns '' for referenced notes, whose fields are `{}` by design (openapi: "for referenced notes an empty or missing field means render from the dictionary"). The result is a zero-width button with nothing to click. The checkbox only toggles bulk selection.
2. Even when opened, CardPreview.svelte:31-32 passes `dictionary: null, kanji: null` to renderCard, so referenced notes render blank. The review screen does this correctly: routes/review/+page.svelte:87 uses toDictionary(item.entry) / toKanji(item.kanji).

Do:
- Row label: when noteSummary is empty, fall back to the headword: the dictionary entry's first kanji form/reading for note.entrySeq, note.kanjiLiteral for kanji notes, else the short note id. Fetch headwords for the visible page in one call if the existing DictionaryApi allows it; if that needs a new endpoint, return CONTRACT_CHANGE_REQUEST and ship the kanjiLiteral/id fallback only.
- Clicking anywhere on the row (except the checkbox) activates the preview; keep the checkbox for bulk selection only. Keyboard: Enter on a focused row activates it.
- CardPreview: for notes with entrySeq / kanjiLiteral, fetch the DictEntry / Kanji through the generated DictionaryApi (same calls as the dictionary page / KanjiPanel). Pass them through toDictionary / toKanji from lib/review/model.ts into renderCard. Show a loading state; on fetch failure, render with null and show a small error. Cache per entrySeq/literal for the page's life.
- All new strings via the cards i18n messages.

Acceptance:
- [ ] A jpdb-imported vocab card (fields `{}`, entrySeq set) shows a non-empty label in the table and renders front/back with the dictionary content in the preview.
- [ ] Kanji notes and custom notes with their own fields still preview as before.
- [ ] Checkbox still only toggles bulk selection; row click only changes the preview.
- [ ] One small vitest for the label fallback (noteSummary empty → headword → id), since that logic lives in model.ts. No other UI tests required.
- [ ] make check MODULE=web green.

Out of scope: server /cards response shape changes (no contract edits in this ticket), bulk bar, search language, Android.

User addendum to BUG-7: widen it to /notes as well. Add web/src/lib/notes/ and web/src/routes/notes/ to its paths.

User report: "there needs to be a way to actually view my cards/notes, i.e. what I will review. All it states is 'leave empty to render from the dictionary', but I want to see everything."

Verified on /notes:
- NoteTable.svelte:61 shows `noteSummary(fields) || n.id`, so the ~8,776 jpdb notes (fields `{}`) list as UUIDs. Use the same headword fallback as the card browser (shared helper in lib/cards/model.ts).
- NoteEditor: for referenced notes, each empty field should show the dictionary-supplied value as a read-only "from dictionary" display or the textarea placeholder, so the user sees the content and knows typing overrides it. Keep the refFieldHint.
- NoteEditor.svelte:72 passes `kanji: null`; fetch Kanji for kanjiLiteral notes (as in KanjiPanel) and pass toKanji(...). Same fix as CardPreview.
- Move the Preview above the edit form (or make it a tab: Preview | Edit, default Preview) so selecting a note shows the rendered card first. Preview should let the user switch between the note's live card kinds (Meaning / Reading / …), not just one.
- Table: add Reading and Meaning (first gloss) columns for referenced notes, filled from the same headword batch lookup, so the list is readable without opening each note. If a batch lookup needs a new endpoint, return CONTRACT_CHANGE_REQUEST (headword/reading/gloss per entry_seq list) and ship the per-row fallback meanwhile.

Extra acceptance:
- [ ] Selecting a jpdb vocab note on /notes shows the rendered card first, with dictionary content.
- [ ] Empty override fields visibly show the dictionary value.
- [ ] Kanji note preview shows keyword/readings.
- [ ] Notes table shows word/reading/meaning instead of UUIDs.

Note: the batch lookup is now needed by both pages (headword + reading + first gloss per entry_seq). If no existing endpoint serves a list of entry_seqs, the contract ticket for it is in scope this run: architect, additive. Then wire both tables to it.

(Orchestrator: BUG-7 had already merged when this arrived; the addendum ships as BUG-7b. Batch wiring for both tables is BUG-7c after C-18.)

### C-18 — Contract: batch dictionary headword/reading/gloss per entry_seq (additive; BUG-7 CCR + addendum)
- model: opus (architect) · risk: med · deps: BUG-7 · status: MERGED
- paths: contracts/api/openapi.yaml, clients/ (regen) (ticket: plan/tickets/C-18.md)

### BUG-7b — Notes page: readable list, preview-first, dictionary values (BUG-7 addendum)
- model: sonnet · risk: low · deps: BUG-7 · status: MERGED
- paths: web/src/lib/notes/, web/src/routes/notes/, web/src/lib/cards/ (model.ts, model.test.ts, messages.ts, CardPreview.svelte, api.ts), web/src/routes/cards/, web/src/routes/review/, web/src/lib/review/ (ticket: plan/tickets/BUG-7b.md; includes BUG-7 addendum 2, kanji preview)

### BUG-7c — Batch headword/reading/gloss in /cards and /notes tables
- model: sonnet · risk: low · deps: C-18, BUG-7b · status: MERGED
- paths: server dictionary handler + tests, web/src/lib/cards/, web/src/routes/cards/, web/src/lib/notes/, web/src/routes/notes/ (ticket: plan/tickets/BUG-7c.md)

### HOTFIX-0.1.7 — Preview $effect loop hangs browser (I-3) + chart JAVA_TOOL_OPTIONS default (I-1 stopgap)
- model: haiku (writer-light) · risk: low · deps: — · status: MERGED a5c4e3a (vitest deferred: jsdom dep denied → FOLLOWUPS) · ships as 0.1.7
- paths: web/src/lib/cards/, web/src/lib/notes/, deploy/helm/kotobaya/ (ticket: plan/tickets/HOTFIX-0.1.7.md)

### TEST-1 — Regression vitest for preview $effect loop (jsdom approved by human 2026-11-13)
- model: haiku (writer-light) · risk: low · deps: HOTFIX-0.1.7 · status: MERGED b43dbca (ticket: plan/tickets/TEST-1.md)

### BUG-8 — Android first sync slow + ANR: main-thread DB, single connection, per-op costs (I-4, I-7)
- model: opus · risk: high (reviewer + architect) · deps: HOTFIX-0.1.7 · status: MERGED dcc3216 (reviewer + architect APPROVE)
- paths: android/**, core sync client (ticket: plan/tickets/BUG-8.md)

### SYNC-T1 — Chunked pull failure-path tests + hardening (BUG-8 review minors)
- model: opus · risk: med · deps: BUG-8 · status: RUNNING (run 19, attempt 2, resumed from partial worktree) (ticket: plan/tickets/SYNC-T1.md)

### C-20 — SYNC.md 6.1: client MAY apply a page as consecutive sub-pages (each commits pull_seq = its last seq; 9.3 replays at end of that tx). Wording only, matches BUG-8.
- model: opus (architect) · risk: low · deps: BUG-8 · status: MERGED 6814485 (ticket: plan/tickets/C-20.md)

### BUG-9 — Stats OOM/slow: one bounded streaming pass for retention+calibration (I-1 real fix)
- model: sonnet · risk: med · deps: HOTFIX-0.1.7 · status: MERGED ac0765f
- paths: server stats/, server/Dockerfile heap default (ticket: plan/tickets/BUG-9.md)

### BUG-10 — /words/hot, /kanji/hot, /kanji/{literal}/words slow (I-2)
- model: sonnet · risk: med · deps: HOTFIX-0.1.7 · status: MERGED 4ce6632
- paths: server hot words/kanji queries (+ index migration if allowed) (ticket: plan/tickets/BUG-10.md)

Source for HOTFIX-0.1.7..BUG-10 and Phase 9: plan/handoffs/INBOX-0.1.6.md (0.1.6 live triage + run 17 brief).

## Phase 8 — Features (requested 2026-11-13)

### C-17 — Contract: GET /kanji/{literal}/words (by frequency, deck membership; additive)
- model: opus (architect) · risk: med · deps: — · status: MERGED
- paths: contracts/api/, clients/ (regen) (ticket: plan/tickets/C-17.md)

### FEAT-1 — Clickable kanji in dictionary: words by frequency + in my decks
- model: sonnet · risk: med · deps: C-17 · status: MERGED
- paths: server dict/ + tests, server routing, web dictionary (ticket: plan/tickets/FEAT-1.md)

### AND-SEARCH — Android Search tab (core parser, offline dict/replica, API fallback; word detail route)
- model: sonnet · risk: med · deps: — · status: MERGED
- paths: android search/, word/ (ticket: plan/tickets/AND-SEARCH.md)

### AND-DECKS — Android Decks tab (counts, deck detail, study this deck)
- model: sonnet · risk: med · deps: — · status: MERGED
- paths: android decks/, review route deck arg (ticket: plan/tickets/AND-DECKS.md)

### AND-SETTINGS — Android Settings tab (server/pairing, sync, limits via settings ops, notify, TTS, integrations, about)
- model: sonnet · risk: med · deps: — · status: MERGED
- paths: android settings/ui/ (ticket: plan/tickets/AND-SETTINGS.md)

### UI-1 — Full UI gamification and visual polish pass across web and Android
- status: SUPERSEDED (run 17) by Phase 9 UI-FOUND-WEB/UI-FOUND-APP/UI-DS/UI-WEB/UI-APP (human ungated UI-1 on 2026-11-13).

### FEAT-x — WYSIWYG note view: rendered card with per-field override
- status: SUPERSEDED (run 17): ungated; folded into UI-WEB, with core render contract C-19 first · from BUG-7 addendum 2 FOLLOWUP
On /notes (and the card browser preview), show the actual rendered card, front and back, with each field region (expression, reading, meaning, keyword, readings, notes, …) as a box filled from the dictionary/KANJIDIC. Each box has an edit button that turns it into an override input. Saving writes the note field (note.updated); "reset to dictionary" clears it. Overridden boxes are visibly marked. Needs a renderer hook from core so the web can map rendered regions to field keys (e.g. data-field="keyword" wrappers in RenderedCard). That is a core render-contract change, so architect first (C-xx), then web.

## Phase 9 — UI rewrite (UI-1 ungated by the human, run 17 brief)
Order (human direction 2026-11-13): UI-FOUND-WEB → UI-DS → HARD HUMAN GATE (DESIGN.md + /styleguide + web screenshots) → UI-WEB ∥ UI-APP. Android side in parallel: BUG-8 → UI-FOUND-APP → UI-DS-APP (after UI-DS; not gated).
UI-FOUND (B1) split into web/app halves: the Android half rewrites the same IO/ViewModel code BUG-8 changes.

### UI-FOUND-WEB — Web state modules, demo dataset, mock-server mode, Playwright screenshot matrix (B1 web)
- model: opus · risk: med · deps: HOTFIX-0.1.7 · status: MERGED 2056faa (ticket: plan/tickets/UI-FOUND-WEB.md)

### UI-FOUND-APP — Android ViewModels/UiState, demo switch, Roborazzi screenshot matrix (B1 app)
- model: opus · risk: med · deps: BUG-8, UI-FOUND-WEB · status: MERGED b0683c1 (ticket: plan/tickets/UI-FOUND-APP.md)

### UI-DS — Design system (tokens JSON → CSS + Compose theme files), DESIGN.md IA + visual plan (both platforms), web /styleguide + Playwright screenshots (B2)
- model: opus · risk: med · deps: UI-FOUND-WEB · status: MERGED 3007499 (ticket: plan/tickets/UI-DS.md) · android/: generated theme/token files only (BUG-8 owns the rest)
- HUMAN GATE fires when UI-DS merges: UI-WEB and UI-APP wait for human approval.

### UI-DS-APP — Android debug styleguide screen + Roborazzi screenshots (split from UI-DS; NOT part of the human gate)
- model: opus · risk: low · deps: UI-DS, UI-FOUND-APP · status: RUNNING (run 19, attempt 2) (ticket: plan/tickets/UI-DS-APP.md)

### C-19 — Core render contract: data-field regions in RenderedCard (FEAT-x prerequisite; additive)
- model: opus (architect) · risk: med · deps: — · status: MERGED 578985c (ticket: plan/tickets/C-19.md)

### UI-WEB — Rebuild every web screen on the design system (B3, folds in FEAT-x)
- model: opus · risk: high · deps: UI-DS, C-19 · status: RUNNING (run 19, attempt 1) (ticket: plan/tickets/UI-WEB.md)

### UI-APP — Rebuild every Android screen on the design system (B4; drops Media tab per IA, dark mode)
- model: opus · risk: high · deps: UI-DS, UI-DS-APP, BUG-8 · status: TODO (B2 approved 2026-11-14)

### Phase 2 splits and additions (2026-11-12, planner + orchestrator)
T201→T201a/T201b; T202→T202a/T202b/T202c; T207→T207a/T207b; T210→T210a(server)/T210b(web); T213→T213a/T213b. New: C-4 (jpdb import contract vs ADR-009, additive), T214 (web jpdb import screen). Ticket files in plan/tickets/ are authoritative for these; parent rows above are superseded.
- C-4 status: MERGED · C-10 MERGED · C-9 MERGED · T207c MERGED · F-1 MERGED · W-3 MERGED · W-2 MERGED · C-8 MERGED · F-2 MERGED · F-3 MERGED · F-4 MERGED · C-7 MERGED (settings keys + QueueInput) · T202d MERGED · C-6 MERGED (storable values: U+0000, int4 max, instant cap; BREAKING, no data yet) · C-5 MERGED (ECMA pattern semantics, depth limit; from T202a review) · T201a MERGED · T201b MERGED · T202a MERGED · T202b MERGED · T202c MERGED · T203 MERGED · T204 MERGED · T205 MERGED · T206 MERGED · T207a MERGED · T207b MERGED · T208 MERGED · T209 MERGED · T210a MERGED · T210b MERGED · T211 MERGED · T212 MERGED (web half) · T212b MERGED · T213a MERGED · T213b MERGED · T214 MERGED
