# DECISIONS

Unanswered items take the bracketed default (single-session mode doesn't wait). Items with no default
are decided provisionally by the orchestrator (ORCHESTRATOR.md §0a) and listed in `plan/HUMAN_CHECKS.md`.
Delete and replace a default to change it; the orchestrator appends ADRs below the line.

## Stack
- Shared core: **Kotlin Multiplatform everywhere** (settled, ADR-002)
- Web app: **SvelteKit + core's JS build + shared card renderer** (settled, ADR-002)
- Server DB access: [jOOQ] | Exposed
- Android DI: [Koin] | Hilt
- FSRS version and reference implementation for golden vectors: [latest FSRS; py-fsrs pinned to a specific version]
- CI host: [GitHub Actions] | Gitea/Forgejo Actions

- MAX_PARALLEL_WRITERS: 3 (human, 2026-11-13: lowered from 6 after a machine crash; 8 cores / 30 GB; at most 1 Gradle-heavy writer (server/core/android/acceptance) at a time, and never run two `make check` Gradle builds concurrently)

## Cluster
- Architecture: amd64 | arm64 | mixed
- Container registry URL:
- Domain for the app (DNS-01 via Let's Encrypt): 
- DNS provider for cert-manager DNS-01:
- Existing Postgres operator? [none → chart deploys CloudNativePG cluster]
- Existing S3-compatible storage? [none → chart deploys MinIO]
- Off-cluster backup target (restic repo URL):
- Max parallel writers for the orchestrator: [4]

## Product
- Grading: [4 buttons default, 5-button jpdb mode available]
- Phone dictionary scope: [referenced + top 60k] | full JMdict
- Phone dictionary size budget: [150 MB]
- Frequency lists to load (Yomitan format), in priority order:
- LLM assist: **off** (human, 2026-11-13: deferred; T607 paused, not implemented for now)
- App name: **Kotobaya (言葉屋)** — settled. Package `kotobaya`, CLI `kotobaya`, deep links `kotobaya://`.
- Day rollover hour: [4:00 local]

## jpdb
- API key placed in `.env.local` as `JPDB_API_KEY`: yes/no
- Review export sample at `fixtures/private/jpdb-export.json`: yes/no

---

## ADRs
<!-- ADR-<n>[ (PROVISIONAL)]: <title> — <date> — <decision> — <why>[ — <how to reverse>] -->
- ADR-001: Name — 2026-11-12 — Kotobaya (言葉屋, "word shop") — settled by the human; replaces the "Kotoba" placeholder.
- ADR-002: Web stack and code sharing — 2026-11-12 — Keep SvelteKit; web consumes the KMP core's JS build (`@kotobaya/core`) for card rendering, search parsing/autocomplete and FSRS previews; one `CardRenderer` (HTML + `assets/card/` CSS) is used by web review, template preview, and the Android review screen via WebView; app chrome stays native per platform (Svelte / Compose). — Maximises shared logic and the most-used UI (the card) while keeping real DOM text for Yomitan, selection, IME and `<ruby>` furigana. Rejected: Compose Multiplatform web (canvas text breaks Yomitan and the reader; large CJK font bundles); TypeScript FSRS port (two schedulers to keep in agreement).
- ADR-003 (PROVISIONAL): Bundled object store — 2026-11-12 — Bundled S3-compatible store is SeaweedFS (`chrislusf/seaweedfs:3.80`, `server -s3`) instead of MinIO; app code uses only the plain S3 API with configurable endpoint/bucket/credentials. — MinIO no longer publishes pullable images (Docker Hub removed, quay.io unauthorized, verified 2026-11-12). — Reverse: point the S3 endpoint at MinIO/any S3 store, or swap the image in compose and the Helm values (`objectStore.*`); no app change.
- Note (T008, 2026-11-12): FSRS golden vectors pinned to py-fsrs 6.3.2, FSRS-6 default parameters, no fuzz.
- ADR-004 (human, 2026-11-12): Model tiers — tickets with `model: fable` (architect role: contracts, T202 sync, T203 replay, architect second-pass reviews) run on Opus from now on, keeping the architect role prompt. Escalation chain tops out at Opus. — The Fable 5-hour usage limit stalled the unattended run; review loops (reviewer + architect pass) plus the mandatory sync/replay tests are the quality backstop. — Reverse: dispatch `fable` tickets on Fable again. T004/T005 stay on Fable since they were already in progress.
- ADR-005 (PROVISIONAL): No collection-wide load balancing — 2026-11-12 — FSRS keeps deterministic fuzz (seeded by review id) plus Easy Days; it does not spread reviews by looking at other cards' due dates. A future "smoothing" may only be a queue-time display shift, never stored in the card cache. — SPEC says "fuzz and load balancing", but SYNC.md §9.3 requires each card's cache to be a pure function of its own replay set; load balancing would make replicas diverge. — Reverse: add a queue-time shift in QueueBuilder (no contract change).
- ADR-006 (PROVISIONAL): Mined (deckless) new cards — 2026-11-12 — Cards with no deck membership at all (e.g. Yomitan mining) form a virtual new-card source with a configurable priority setting (default 0, same tier as ordinary decks, interleaved with them). Cards whose decks are all disabled are not introduced. — Avoids starving decks for heavy miners while still surfacing mined words; respects "disabled deck supplies no new cards". — Reverse: change the setting default.
- ADR-007 (PROVISIONAL): Server DB access — 2026-11-12 — jOOQ (generated from the V1 schema) for the Ktor server. — The schema is SQL-first (Flyway + hand-written DDL with partial indexes, pg_trgm, advisory locks); jOOQ keeps queries close to that SQL and type-checked. Rejected: Exposed (DSL drifts from the hand-written DDL). — Reverse: swap the persistence layer inside server/ (no contract impact).
- ADR-008: jpdb "hard" grade — 2026-11-12 — maps to FSRS Hard (rating 2, button `hard`), as SPEC fixes Hard = FSRS Hard; jpdb hard is a pass, and FSRS Hard is a pass. Not remapped to Good. — Reverse: change the import mapping before the first real import.
- ADR-009 (human, 2026-11-12): jpdb history import rules (T207) — (1) Japanese→English only: each imported vocab note gets only the recognition (`meaning`) card, which receives the `cards_vocabulary_jp_en` history; no reading/production cards are generated for imported vocab. `cards_kanji_char_keyword` → kanji `keyword` card with history; `cards_kanji_keyword_char` (keyword→kanji, production) → `writing` card with history, created **suspended** (preserved, never shown unless unsuspended). (2) Rows repeating a vid (≈260): merge onto one note/card by entry_seq (vid), events interleaved by timestamp, exact duplicate events (same timestamp + grade) dropped; review ids are deterministic and unique (hash of vid, timestamp, grade, occurrence index). (3) `known`: set word status KNOWN only when `known` is the card's only event (≈65 vocab); `known` followed by reviews → FSRS schedules from the reviews, no override; known-only vocab gets no card. Known-only kanji → kanji note with a suspended card. `unknown` = card creation only. (4) Kanji keywords: if the export carries jpdb keyword text, write it into the note's `keyword` field (user-editable); otherwise leave empty so it fills from KANJIDIC (also editable). — User only studies JP→EN, wants nothing lost, keywords editable. — Reverse: change T207 mapping before the first real import. Contract follow-up: openapi JobResultJpdbHistory example/buckets must match (cards_created ≈ notes, not 2×) — C-ticket before T207.

- ADR-010 (PROVISIONAL): JSON nesting limits — 2026-11-12 — ops ≤ 500 deep from the op root; whole sync bodies ≤ 512 — any accepted op must fit inside every sync envelope (T202a review: equal limits made a legal op break pull forever) — reverse by changing both constants in core sync and SYNC.md (C-5) together; widening the gap is safe, narrowing is breaking.
- ADR-011 (PROVISIONAL): toggling an optional card kind on a note type does not backfill cards — 2026-11-12 — `default` affects new notes only; createNote `card_kinds` picks kinds; per-note additions via createCard (T210a) — contract has no backfill op (NotePatch lacks kinds; updateNoteType declares only note_type.updated) — reverse with an additive C-ticket (backfill job emitting card.created) if the UI needs bulk add.
- ADR-012 (PROVISIONAL): Android uses a hand-written OkHttp ApiClient, not clients/kotlin — 2026-11-12 — the generated Kotlin client needs Kotlin 2.4/Ktor 3.5, the Android module is on Kotlin 2.1.10 — reverse by aligning Kotlin versions (or generator settings) and swapping net/ApiClient for the generated client; keep ApiError mapping.
- ADR-013 (PROVISIONAL): Takoboto Vocab note type uses a fixed name-derived UUIDv8 id — 2026-11-13 — one shared row on every device via the 7.2 creation rule instead of per-device UUIDv7 types — SCHEMA §2.1 says UUIDv7 unless stated; nothing checks the version — reverse: contract C-ticket listing reserved derived ids, or mint UUIDv7 and dedupe by name.
- ADR-014 (PROVISIONAL): server installs media rows from worker-published snapshots — 2026-11-13 — worker computes media/segments/occurrences and publishes a snapshot (C-11 `media-publish` internal job); the server is the only writer of media tables — keeps rule 8 (single writer, op path for learning data) — reverse: let the worker write media tables directly; C-11 may refine.
- ADR-015 (PROVISIONAL): jpdb mapping corrections are server-side import settings, not a sync op — 2026-11-13 — corrections affect import mapping only, not learning data — reverse: add an additive op type via a C-ticket.
- ADR-016 (PROVISIONAL): jpdb deck responses cached on disk (PVC in Helm) — 2026-11-13 — avoids refetch per spec; survives pod restarts — reverse: move to S3/blob store.
- ADR-017 (PROVISIONAL): coverage and i+1 computed per request, no cache table — 2026-11-13 — simplest; single-user scale — reverse: add a materialised cache recomputed on word-status change.
- ADR-018 (PROVISIONAL): Android reads coverage/sentence lists from the API; no media snapshot on device — 2026-11-13 — offline coverage not in P4 "Done when" — reverse: ship snapshots to devices later.
- ADR-019 (PROVISIONAL): Sudachi full-dictionary tests skip when the dict is absent (offline); core dict is the baseline for the ≥97% agreement test — 2026-11-13 — build must stay offline-capable — reverse: vendor/download full dict in CI; human runs the full-dict check (HUMAN_CHECKS).
- ADR-020 (PROVISIONAL): EDRDG source files (JMdict/KANJIDIC2/KRADFILE) are not pinned to an exact checksum — 2026-11-13 — they change daily, so pins break every refresh; the fetched SHA-256 + Last-Modified is recorded in the dict release provenance instead, and an optional per-file pin (env/config) still enforces an exact digest for reproducible builds — reverse: make the pin mandatory again.
