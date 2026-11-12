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

- MAX_PARALLEL_WRITERS: 6 (human, 2026-11-12: more than 4 allowed; capped by this machine — 8 cores / 30 GB; at most 3 concurrent Gradle-heavy writers)

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
- LLM assist: [off] | local model on cluster | API provider (which)
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
