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
