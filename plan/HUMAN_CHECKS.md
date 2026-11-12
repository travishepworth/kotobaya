# HUMAN CHECKS

Everything the build needs from you after the code is written. The orchestrator adds specifics
(APK paths, commands, questions from blocked tickets) as it goes. Work top to bottom: later
checks assume earlier ones pass. Report problems back to the orchestrator as new tickets.

## Fill in when you can (placeholders used until then)
- [ ] `docs/DECISIONS.md` › Cluster: arch, registry URL, domain, DNS provider, existing Postgres/S3, restic target.
- [ ] `.env.local` with `JPDB_API_KEY`, and `fixtures/private/jpdb-export.json` (both git-ignored).
      The orchestrator adds the exact commands for the live jpdb runs (S02, T207) here.

## Contracts (deferred GATE-CONTRACTS)
- [ ] Read `contracts/SUMMARY.md`, especially "3 choices most worth a second look". Changes you want
      become `C-*` tickets (breaking ones with a migration).

## Provisional decisions
- ADR-011: turning on an optional card kind does not add cards to existing notes; add per note (T210a createCard).
- C-6 (breaking contract change, no user data yet): sync schemas forbid U+0000, cap int4 fields at 2147483647, cap instant_ms at year 9999 — values Postgres cannot store. Reviewer + architect approval required.
- ADR-010: sync JSON nesting limits op 500 / document 512 (T202a, C-5).
- C-4 (jpdb import): an imported `known` never overwrites a word status you already set by hand, and cards that already exist keep their suspended state on re-import. Kanji `writing` cards and known-only kanji are created suspended.
- P2: bulk card operations (tag/move/suspend/reschedule) run synchronously, up to 5000 cards per call, as the frozen API contract says. The backlog said "one job with progress". Fine for a personal collection; a C-ticket could add a job variant later.
<!-- orchestrator appends: ADR id, one line, how to reverse. Confirm or reverse each. -->

## Deploy
- [ ] **P1 — Cluster:** `helm install` on the real cluster (command filled in by orchestrator); open the web app; search the dictionary.
- [ ] **Backups:** confirm the first nightly backup landed off-cluster; run the restore drill once.

## Takoboto (spike + production)
- [ ] **S01/T305:** install the default APK (`com.ichi2.anki` flavor). In Takoboto, add 5 words. Confirm they appear as notes with the right fields.
- [ ] If the own-app-ID flavor is preferred, try it too and record which works in `docs/DECISIONS.md`.

## jpdb import
- [ ] **S02:** read `spikes/jpdb-map/REPORT.md`. Hit rate ≥ 95%? If not, work through the mapping review queue in the web app.
- [ ] **P2 — History import:** import your jpdb review history; spot-check 20 cards' due dates against jpdb.

## Daily use
- [ ] **P1 (GATE-P1, deferred; `make test` green 2026-11-12):** `helm install` the chart (k3d: `deploy/k3d/` scripts; set the AUTH_PASSWORD_HASH Secret first), log in, run the dict-refresh job (W-1 item above), then search the dictionary through the API (e.g. `GET /api/v1/dict/search?q=食べる`) and confirm sensible results. Note: login over plain http in k3d dev doesn't resend the Secure cookie — use HTTPS or see FOLLOWUPS.
- [ ] **P2:** review in the web app for 3 days. Note anything off.
- [ ] **P3:** pair the phone; review offline (airplane mode) for a week; sync; confirm review counts match on web and phone.
- [ ] **P3:** widget sizes, due-count notification, Quick Settings tile, share-to-app, Yomitan via on-device AnkiConnect.
- [ ] **P4:** import two shows you have on jpdb; compare coverage numbers (expect within ~3 points).
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
- Housekeeping: stale merged worktrees (.claude/worktrees/agent-ae408d50204f320d0, agent-af77a050ea16184ff, agent-af5ce7b42729bb029) and branches t/T009-helm, t/T111-helm-workloads, t/T112-backups — removal was denied by the auto-mode classifier. Run `git worktree remove -f -f <path>` and `git branch -D <branch>` when convenient.
