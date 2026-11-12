<!-- Exported from the Claude Docs spec (https://claude.ai/artifact/5hyEa872D3QYqeBPK1Xj8H, rev 12) on 2026-11-12.
     Renamed Kotoba → Kotobaya (言葉屋). The architecture diagram is not exported; see the Architecture section's text and table.
     Human-edited only (ORCHESTRATOR.md §1). -->

# Kotobaya SRS — Full Product & Stack Spec

Nov 12, 2026 · @Travis

## Overview

Kotobaya (言葉屋) is a self-hosted, local-first Japanese SRS that replaces jpdb for one user. It has three front doors onto one backend: an Android app for reviewing and mining, a separate web app for full management, and a public API for scripting. Everything runs as containers on your Kubernetes cluster, reached over your VPN.

**Goals**

- Card anything: vocab, kanji, phrases, grammar points, sentences, and custom notes.
- jpdb-grade media workflow: import pre-parsed jpdb decks or your own files, then see coverage with raw words and in-deck frequency.
- Review fully offline on Android, with sync that never loses a review.
- One-tap mining from Takoboto by impersonating AnkiDroid, plus Yomitan via an AnkiConnect-compatible endpoint.
- Struggle tools: hot words, leeches, confusable pairs, extra drills that don't pollute scheduling.
- Everything the web app can do, the API can do. The web app is built only on the public API.

**Non-goals (v1)**

- Multiple users, sharing, social features, public hosting.
- iOS, desktop-native clients.
- Languages other than Japanese. The schema keeps a `language` field so this stays possible.

**Principles**

1. **Local-first.** The phone owns a full working copy and schedules on-device. The server is for sync, heavy processing, and backup.
2. **The review log is the source of truth.** Card state is always recomputable by replaying the log.
3. **One scheduler implementation** shared by server, Android, and web, so every client agrees on what's due.
4. **Your data stays yours.** Full export at any time, nightly off-cluster backups, no third-party services in the core loop.
5. **Containerised from day 1.** Local development uses the same images as production.

## Architecture

One Kotlin API, one Python worker, and Postgres form the core, with object storage, TTS, and push alongside. The Android app holds a full local copy and only needs the cluster to sync.

&#91;embedded content: System architecture · 4 clients, 7 cluster workloads\]

The API enqueues heavy work (parsing, imports, optimizer runs) in Postgres; the worker picks it up and writes results back.

**Tech choices**

| Layer | Choice | Why |
| --- | --- | --- |
| Shared core | Kotlin Multiplatform (JVM + Android + JS) | One FSRS, queue builder, and search parser for every client |
| API | Ktor | Same language as the core; small and fast to start |
| Worker | Python | Sudachi, the FSRS optimizer, and text tooling live there |
| Database | Postgres server-side, SQLite (SQLDelight) on the phone | Same logical schema in both |
| Android | Jetpack Compose, Glance widgets, WorkManager | Native: needed for the AnkiDroid stand-in, widget, and alarms |
| Web | SvelteKit, static build | Light, fast, consumes the generated API client |
| Storage | MinIO (S3-compatible) | Audio and uploads, addressed by content hash |
| TTS | VOICEVOX engine | Self-hosted, good Japanese voices |
| Push | ntfy with UnifiedPush | Self-hosted; nothing passes through Google |

## Data model

Words are learned once, globally; decks only decide *what to learn next* and *what to measure coverage against*. This is jpdb's model, and it avoids duplicate cards when the same word appears in ten shows.

All IDs are UUIDv7, generated on the client, so notes and reviews can be created offline without coordinating with the server. Postgres on the server and SQLite on the phone share the same logical schema.

**Reference data** (read-only, versioned by dictionary release)

| Table | Holds | Source |
| --- | --- | --- |
| `dict_entry` | JMdict sequence, kanji forms, readings, senses, POS, misc tags | JMdict |
| `entry_freq` | Rank per frequency list (jpdb, BCCWJ, novels, netflix…) | Yomitan frequency dictionaries |
| `entry_pitch` | Pitch accent patterns per reading | Kanjium |
| `kanji` | Literal, on/kun, meanings, strokes, grade, JLPT, frequency, components | KANJIDIC2, KRADFILE |
| `example_sentence` | Japanese + English, linked entries | Tatoeba |
| `grammar_point` | Title, pattern, explanation (Markdown), JLPT, detection rules | Your own library (see Grammar) |

**Learning data** (yours, synced)

| Table | Holds | Notes |
| --- | --- | --- |
| `note` | Type, reference (entry, kanji, grammar point, or none), fields JSON, tags, mnemonic, source sentence | Types: vocab, kanji, phrase, grammar, sentence, custom |
| `note_type` | Field definitions and card templates | Built-ins plus your own; templates are HTML with field placeholders |
| `card` | Note, kind, FSRS stability and difficulty, due, reps, lapses, suspended, buried-until, flags | A cache: rebuilt from `review_log` |
| `review_log` | Card, device, timestamp, rating, duration, review type | Append-only. Types: scheduled, learning, drill, cram |
| `word_status` | Per entry: new, learning, known, never-forget, blacklisted, ignored | Drives coverage; overrides are explicit rows |
| `deck` | Name, type, priority, new-card limit, settings | Types: user, media, list (JLPT, frequency), smart (saved query) |
| `deck_item` | Deck, entry or note, position, occurrence count | Ordering for new cards; counts for coverage |
| `confusable` | Pair of notes, reason (homophone, look-alike, meaning) | Auto-suggested, user-confirmed |

**Media**

| Table | Holds |
| --- | --- |
| `media` | Title, kind (anime, novel, VN, manga, web, other), jpdb ID, cover, parse status |
| `media_segment` | Episode, chapter, or volume, in order |
| `sentence` | Text, token JSON, segment, subtitle timestamps, audio clip reference |
| `occurrence` | Entry, media, segment, count, sentence references |
| `parse_override` | Your corrections to tokenisation, so a re-parse keeps them |
| `blob` | Audio and images in object storage, keyed by content hash |

**System:** `device`, `api_token` (hashed, scoped), `settings`, `fsrs_params` (per preset), and `change` (the sync log with a server sequence number).

## Scheduling

FSRS schedules every card, with parameters tuned weekly on your own review history. One Kotlin implementation runs on the server, the phone, and the web, so every client computes the same due dates.

**FSRS**

- Latest FSRS version, implemented in the shared Kotlin core. Desired retention defaults to 0.90 and is set per preset.
- A weekly worker job runs the FSRS optimizer on `review_log` and publishes new parameters. Clients pick them up on the next sync.
- Fuzz and load balancing spread reviews across days. "Easy days" lighten chosen weekdays.
- Only `scheduled` and `learning` reviews feed FSRS. `drill` and `cram` reviews are logged but never change a card's schedule.

**Grading**

- Default: four buttons (Again, Hard, Good, Easy).
- Optional jpdb-style five buttons: Nothing and Something both count as Again, Hard, Okay = Good, Easy. "Something" is kept in the log for analysis.
- Undo the last review, including on the widget's quick-review flow.

**Card kinds per note type**

| Note type | Default cards | Optional cards |
| --- | --- | --- |
| Vocab | Meaning (JP → EN), Reading (kanji → kana) | Listening (audio → word), Production (EN → JP), Pitch |
| Kanji | Keyword (kanji → keyword + readings) | Writing (keyword → kanji, self-graded), Components |
| Phrase | Meaning | Production, Listening |
| Grammar | Cloze (rotating sentences) | Production, Spot-the-grammar |
| Sentence | Comprehension (sentence → meaning) | Listening |

Sibling cards from one note are buried until the next day so one doesn't give away another.

**Queue building**

- Daily limits for new cards and reviews, globally and per deck.
- New cards come from decks in priority order. Inside a deck they follow the deck's order: frequency within the media by default, like jpdb.
- Optional: when a vocab card is added, add cards for kanji you haven't learned yet. Kanji cards can come before or alongside the word.
- Interleave new and review cards, with a cap on new cards per session.
- Word status updates automatically. Mark a word never-forget (known, no reviews) or blacklisted (ignored everywhere) at any time.

**Leeches and hot words**

- **Leech:** 8 or more lapses (configurable). Tagged and shown on a Leeches screen, with the option to suspend or rewrite.
- **Hot word:** recent struggle, not lifetime. The score uses failures over the last 14 days (older ones weigh less), FSRS difficulty, lapse rate, and relearning failures. Words drop off once you recover.
- **Hot kanji:** failures rolled up by kanji across all words containing it.
- **Confusables:** suggested from shared readings (homophones), look-alike kanji (待/持/特), and close meanings. Confirmed pairs can be drilled side by side.
- A hot word's page offers: an extra drill, sentences from your media, turning one into a sentence card, and a mnemonic shown on the card while it's hot.

**Stats**

A review heatmap, retention by card kind, and a due forecast. Also: time per review, FSRS calibration (predicted vs actual recall), known words over time, and coverage progress per media.

## Content and media import

Media arrives three ways: pre-parsed from jpdb, parsed by the worker from your own files, or mined one word at a time from Takoboto and Yomitan. All three end up as the same `media`, `occurrence`, and `deck_item` rows.

**Reference data pipeline**

- A `dict-refresh` job downloads JMdict, KANJIDIC2, KRADFILE, Kanjium, Tatoeba, and the chosen frequency lists. It builds a versioned release and loads it into Postgres.
- The phone downloads a compact SQLite build of the same release. Default scope: entries referenced by your cards and decks plus the top 60k by frequency. Option: everything.
- Licences: JMdict and KANJIDIC2 are CC BY-SA, Tatoeba is CC BY. Fine for personal use; keep an attribution page in Settings.

**jpdb import**

1. Review history: import jpdb's review export and replay it through FSRS, so cards start with real stability rather than from scratch.
2. Word status: known, never-forget, and blacklisted words carry over.
3. Media decks: pull a deck's vocabulary with per-media occurrence counts and global rank, via jpdb's API where available, otherwise its public vocabulary-list pages.
4. Map jpdb vocabulary to JMdict sequence numbers. Anything that doesn't map goes to a review queue in the web app.

The importer is rate-limited and caches every response. Re-running it only fetches what changed.

**Own-file import (worker)**

| Source | Formats | Extras |
| --- | --- | --- |
| Subtitles | SRT, ASS, VTT | Timestamps kept; optional audio clips cut from a video or audio file you upload |
| Books | EPUB, TXT | Chapters become segments; ruby (furigana) stripped or used as reading hints |
| Manga | Mokuro OCR JSON | Page and box positions kept |
| Web and VN | URL, HTML, text-hooker logs, paste | Readability extraction for URLs |

**Parsing**

1. Normalise text (width, iteration marks, odoriji).
2. Segment with Sudachi using the full dictionary. Mode C keeps compounds; fall back to mode B when a C token isn't in JMdict.
3. Deinflect to the dictionary form and match JMdict entries, ranking candidates by reading, POS, and frequency.
4. Apply `parse_override` rows, so your fixes survive re-parses.
5. Write sentences, occurrences, and per-segment counts. Tag grammar points (see Grammar).

The web app shows each sentence with its segmentation, and you can fix any token in two clicks.

**Coverage**

Shown per media and per segment, and recomputed whenever word status changes:

- Unique words: total, known, learning, unknown.
- Raw tokens: total, and % covered by known words. The occurrence-weighted number is the one that predicts comprehension.
- Unknown words sorted by in-deck frequency, with global rank beside them.
- A curve of "learn the top N unknown words to reach X% coverage".
- Filters: hide names, particles, auxiliary verbs, and ignored words.
- Difficulty estimate compared to other media you've imported.
- i+1 sentences: sentences with exactly one unknown word, ready to turn into sentence cards.

## Grammar review

Grammar points are cards whose examples come from your own media, so you review 〜わけにはいかない in sentences you actually read. The goal is the weird, low-frequency points, not an N5 course.

**Grammar library**

- Each point has: a title, a pattern (〜ざるを得ない), a meaning, register and nuance notes, JLPT level, related points, and reference links.
- You write the explanations, or draft them with the optional LLM step. Published grammar dictionaries can be linked to but not copied.
- Seed set: around 300 N3–N1 points as stubs (pattern and title only), filled in as you meet them.

**Detection**

- Each point carries one or more rules in a small token-pattern language over Sudachi output. A rule matches on surface form, lemma, POS, and conjugation, with optional gaps. Example: `[verb:未然形] ざる を 得 ない`.
- Matches are stored as tagged spans on `sentence`, so every grammar point has a list of where you've seen it.
- "I saw something weird": highlight a span in any sentence, on the phone or the web, and create or attach a grammar point. The span becomes its first example and a draft rule.

**Cards**

| Card | Front | Back |
| --- | --- | --- |
| Cloze | A sentence with the grammar span blanked, plus an English hint | The span, the explanation, the source |
| Recognition | A sentence with the span highlighted | Meaning and nuance |
| Spot-the-grammar | A sentence, no highlight | Which point is present and where |
| Production (optional) | English sentence | Japanese sentence; self-graded |

- The example sentence rotates on each review, drawn from your media and Tatoeba. This stops you memorising one sentence instead of the pattern.
- Prefer i+0 sentences: everything known except the grammar point.

**Optional LLM assist**

- Off by default; a pluggable provider (a local model on the cluster or an API).
- Drafts explanations, explains a specific sentence's nuance, and generates extra examples.
- Anything generated is labelled as generated and kept separate from mined sentences.

## Clients

There are two clients: a native Android app for reviewing and mining, and a separate web app for everything, management included. Both use the shared Kotlin core for scheduling, so they always agree.

### Android app

Kotlin and Jetpack Compose, with the shared Kotlin Multiplatform core and SQLDelight for the local SQLite database.

**Screens**

- Home: due and new counts, the forecast, hot words, a sync status chip.
- Review: furigana toggle, pitch-accent graph, audio with autoplay, swipe or tap grading, undo, edit the note mid-review, flag, bury, suspend, never-forget.
- Word, kanji, and grammar pages: entries, example sentences from your media, every card's history, confusables.
- Decks: priority order, limits, coverage summary, new-card queue preview.
- Hot words and leeches, with drill sessions.
- Search across dictionary, notes, and sentences (kana, kanji, or romaji).
- Settings, devices, offline storage.

Reading mode, a full media reader, stays in the web app for v1. The phone gets coverage numbers and sentence lists.

**Takoboto integration (AnkiDroid stand-in)**

- The app registers AnkiDroid's add-card endpoint, `com.ichi2.anki.flashcards`, and declares AnkiDroid's permission, `com.ichi2.anki.permission.READ_WRITE_DATABASE`. Both are free because AnkiDroid isn't installed.
- It answers the calls Takoboto makes: list decks, list note types and their fields, add a note, and check for duplicates.
- It exposes a "Takoboto Vocab" note type whose fields map to Takoboto's export. An added note is matched to a JMdict entry locally, saved offline, and synced later.
- Fallback if Takoboto checks for AnkiDroid's app ID: build a sideload variant with `applicationId com.ichi2.anki`.
- Spike first: a test app that only logs what Takoboto sends (Phase 0).

**Other ways in**

- The Android share sheet and the text-selection menu ("Add to Kotobaya") from any app.
- Deep links: `kotobaya://add?word=…&sentence=…` and `kotobaya://word/<id>`.
- An on-device AnkiConnect-compatible server on localhost, for Yomitan in mobile browsers.

**Home-screen widget (Jetpack Glance)**

| Size | Shows |
| --- | --- |
| 2×1 | Due and new counts; tap to review |
| 4×2 | Adds a 24-hour due forecast and today's progress |
| 4×3 | Adds a rotating hot word (kanji, reading, meaning); tap for its page |

The widget reads the local database directly, so it works offline. A Quick Settings tile starts a review.

**Notifications**

| Channel | Importance | Behaviour |
| --- | --- | --- |
| Due count | Minimum | Permanent and silent, no status-bar icon; "142 due · 38 new" |
| Nudges | Default | One alert past a due threshold, or an evening reminder if you haven't reviewed; off by default |
| Server events | Low | Import finished, sync stale for 2 days, backup failed |

- No polling: after each review or sync, the app sets one inexact alarm for the next due time, batched to at most every 15 minutes. The same alarm refreshes the widget.
- Recompute on boot, time or time-zone change, and app open.
- Server events arrive through self-hosted ntfy (UnifiedPush), so nothing passes through Google.
- Ask for the notification permission (Android 13+) during onboarding.

### Web app

A separate single-page app (SvelteKit, built static, served by nginx) that talks only to the public API. It is online-first: the server does the scheduling for web reviews using the same core.

**Everything the phone does, plus:**

- A card browser with a search language, bulk edit, tag, move, suspend, and reschedule.
- A media library: import wizard, parse progress, coverage dashboards, a reader with click-to-look-up and click-to-mine.
- A parse-correction tool and the jpdb mapping review queue.
- Grammar library editor with a rule tester run against your sentences.
- Note type and template editor with live preview.
- Full stats, FSRS optimizer results, calibration charts.
- Settings, devices, API tokens, export and backup status.
- Keyboard-first reviewing: 1–4 to grade, space to flip, `u` to undo.

## Sync and offline

Clients sync by exchanging an operation log, not database rows. Reviews are append-only events, so two devices reviewing offline can never overwrite each other. Card state is simply recomputed from the merged log.

**Protocol**

1. Every local change is written as an operation: `review.logged`, `note.created`, `note.updated`, `card.suspended`, `word.status_set`, and so on. Each carries a UUIDv7 and a hybrid logical clock timestamp.
2. `POST /api/v1/sync/push` sends pending operations in batches. Operations are idempotent, so retrying after a dropped connection is safe.
3. The server applies them, assigns a sequence number to each, and returns the high-water mark.
4. `GET /api/v1/sync/pull?since=<seq>` returns everything newer, including changes from the web app and scripts.
5. The client applies pulled operations and replays FSRS for any card that received out-of-order reviews.

**Conflict rules**

| Change | Rule |
| --- | --- |
| Reviews | Never conflict; all kept, replayed in timestamp order |
| Note fields, tags, settings | Last writer wins per field, by clock |
| Suspend, bury, word status | Last writer wins |
| Deletes | Tombstones; a delete beats an older edit |
| Same word added twice offline | Merged on the server by entry ID; cards and reviews combined |

**Large content** (dictionary releases, media decks, audio) doesn't go through the operation log. Clients download versioned, compressed snapshots and diffs when a new version appears.

**Offline guarantees**

- Reviewing, adding notes from Takoboto, searching the dictionary, the widget, and notifications all work with no network.
- Audio for every card due in the next 7 days is prefetched (configurable), plus all audio for new cards queued for today.
- Storage budget setting, with a breakdown of dictionary, audio, and media sentences.
- Sync runs on app open, after a review session, and in the background whenever the server is reachable, with exponential backoff.
- Sync status is always visible: last sync time, pending operation count, and a warning after 48 hours unsynced.

**The web app** writes through the same operation endpoint, so every change from any client ends up in one ordered log.

## Public API and scripting

The API is a versioned REST/JSON interface at `/api/v1`, described by an OpenAPI 3.1 spec generated from the server code. The web app uses nothing else, so anything you can click, you can script.

**Auth**

- Web app: password login (plus optional TOTP), stored in an HTTP-only session cookie.
- Devices: a long-lived device token issued at pairing (scan a QR code in the web app). Revocable per device.
- Scripts: personal access tokens with scopes — `read`, `write`, `review`, `import`, `admin` — and optional expiry. Stored hashed.
- Every request is audited with its token name.

**Resources**

| Area | Endpoints (abridged) |
| --- | --- |
| Dictionary | `GET /dict/search?q=`, `GET /dict/entries/{seq}`, `GET /kanji/{literal}` |
| Notes and cards | `GET/POST /notes`, `PATCH/DELETE /notes/{id}`, `GET /cards?query=`, `POST /cards/bulk` (suspend, tag, move, reschedule) |
| Reviewing | `GET /review/queue`, `POST /review/answer`, `POST /review/undo`, `POST /drill/session` |
| Decks | `GET/POST /decks`, `PATCH /decks/{id}`, `GET /decks/{id}/items`, `GET /decks/{id}/coverage` |
| Media | `POST /media/import` (file or URL), `POST /media/import/jpdb`, `GET /media/{id}/coverage`, `GET /media/{id}/sentences?filter=i+1` |
| Words | `PUT /words/{seq}/status`, `GET /words/hot`, `GET /words/leeches`, `GET /words/{seq}/confusables` |
| Grammar | `GET/POST /grammar`, `POST /grammar/{id}/rules/test`, `GET /grammar/{id}/examples` |
| Stats | `GET /stats/heatmap`, `GET /stats/retention`, `GET /stats/forecast`, `GET /stats/known-words` |
| Parsing | `POST /parse` (text in, tokens + entries + known status out) |
| Jobs | `GET /jobs`, `GET /jobs/{id}` — imports, parses, optimizer runs |
| Sync | `POST /sync/push`, `GET /sync/pull` |
| Export | `GET /export` (full JSON dump), `GET /export/anki` (.apkg) |

**Card search language** (shared by API, web, and phone): `deck:"Frieren" is:due tag:leech lapses>3 kanji:績 rated:7:1 kind:reading`. The parser lives in the shared core.

**Events:** a server-sent events stream at `GET /events` (job progress, sync, new notes) for scripts and the web app. The same events can be forwarded to ntfy.

**Compatibility endpoints**

- `POST /ankiconnect`: an AnkiConnect-compatible subset (`version`, `deckNames`, `modelNames`, `modelFieldNames`, `addNote`, `canAddNotes`, `findNotes`, `storeMediaFile`). Desktop Yomitan and other mining tools work unchanged.

**Tooling**

- A Python client and a `kotobaya` CLI generated from the OpenAPI spec. Example: `kotobaya media import ep01.srt --deck Frieren`, `kotobaya cards find 'is:leech' --json`.
- Interactive docs (Scalar or Swagger UI) at `/api/docs`.
- Breaking changes go to `/api/v2`; v1 stays until you remove it.

## Infrastructure

The whole stack ships as one Helm chart in one namespace. Local development runs the same images through Docker Compose or a local k3d cluster.

**Workloads**

| Workload | Image | Kind | Notes |
| --- | --- | --- | --- |
| `api` | Ktor on a distroless JVM | Deployment (1 replica) | Stateless; health, readiness, Prometheus metrics |
| `worker` | Python 3.12, Sudachi, fsrs optimizer | Deployment + job queue | Pulls jobs from Postgres (`SKIP LOCKED`), no extra broker |
| `web` | nginx serving the static SPA | Deployment | Same host as API, `/api` proxied |
| `postgres` | Postgres 17 | CloudNativePG cluster or a StatefulSet | PVC; WAL archiving if using CNPG |
| `minio` | MinIO | StatefulSet | Audio, images, import uploads. Skip if you already run S3-compatible storage |
| `voicevox` | VOICEVOX engine (CPU) | Deployment | TTS for words and sentences; results cached in object storage |
| `ntfy` | ntfy | Deployment | UnifiedPush for server events to Android |

**Jobs**

| Job | Schedule | Does |
| --- | --- | --- |
| `migrate` | Helm pre-upgrade hook | Flyway schema migrations |
| `dict-refresh` | Monthly | Builds a new dictionary release; the phone downloads the diff |
| `fsrs-optimize` | Weekly | Tunes FSRS parameters on your review log |
| `backup` | Nightly | `pg_dump` plus object-storage mirror, encrypted with restic, sent off-cluster |
| `backup-verify` | Weekly | Restores the latest backup into a scratch database and runs checks |

**Networking and TLS**

- Ingress only on the VPN-facing interface or an internal load balancer; nothing public.
- Use a real domain with Let's Encrypt via DNS-01, resolved only inside the VPN. Android trusts these certificates out of the box; a private CA needs installing on the phone and some apps ignore user CAs.
- One host: `kotobaya.<domain>` with `/` for web, `/api` for the API, `/ntfy` for push.

**Security**

- Auth is required even inside the VPN. Tokens are hashed and scoped; the session cookie is HTTP-only and SameSite strict.
- Secrets come from Kubernetes Secrets, or Sealed Secrets / External Secrets if you already use them.
- Containers run as non-root with read-only root filesystems; network policies allow only the needed paths.

**Observability**

- Prometheus metrics: request latency, sync lag, job durations, queue depth, reviews per day.
- JSON logs to stdout, picked up by whatever log stack the cluster runs.
- Alerts: backup failed, job stuck, disk above 80%.

**Build and release**

- Monorepo: `core/` (Kotlin Multiplatform), `server/`, `android/`, `web/`, `worker/`, `deploy/helm/`.
- CI builds multi-arch images (amd64 and arm64), runs tests, and pushes to your registry.
- Android: CI builds a signed APK on each tag and publishes a release. Obtainium on the phone installs updates from it.

**Rough footprint:** about 2 CPU and 4 GB RAM at idle, mostly the JVM, Postgres, and VOICEVOX. Storage is under 10 GB before audio.

## Phases and open questions

The build runs in seven phases. Each ends with something you use daily, and the riskiest integration (Takoboto) is tested first.

1. **Phase 0 — Spikes.** A test Android app that impersonates AnkiDroid and logs what Takoboto sends. A jpdb deck fetched and mapped to JMdict. *Done when:* Takoboto adds a note to the test app, and one jpdb deck maps with over 95% hits.
2. **Phase 1 — Foundations.** Monorepo, Helm chart, Postgres, API skeleton with auth and tokens, dictionary pipeline, shared core with FSRS. *Done when:* `helm install` brings up a working API, and dictionary search works through it.
3. **Phase 2 — Review loop.** Notes, cards, decks, queue building, web reviewing, jpdb history import and replay. *Done when:* you review your jpdb cards in the web app and stop using jpdb's scheduler.
4. **Phase 3 — Android and offline.** Local database, sync protocol, review UI, Takoboto stand-in, widget, notifications, audio prefetch. *Done when:* a full week of reviews in airplane mode syncs without loss.
5. **Phase 4 — Media.** jpdb media deck import, worker parsing for your own files, coverage dashboards, i+1 sentences, parse corrections, reader. *Done when:* a show's coverage numbers match jpdb's within a few percent.
6. **Phase 5 — Struggle tools and grammar.** Hot words, leeches, confusables, drills, grammar library, detection rules, cloze cards. *Done when:* grammar cards appear from your own mined sentences.
7. **Phase 6 — Polish.** VOICEVOX audio, pitch-accent cards, AnkiConnect endpoint, CLI, stats, the optional LLM step, backup verification.

**Open questions**

- [ ] Shared Kotlin core (one scheduler everywhere) vs. a TypeScript web app with its own FSRS port. Recommendation: Kotlin.
- [ ] Does your cluster already run Postgres (CloudNativePG?), S3-compatible storage, and a domain with DNS-01 certificates? These change the chart.
- [ ] Cluster architecture: amd64, arm64, or mixed? This decides the image builds.
- [ ] Which jpdb data can you export today? Review history, vocabulary lists, both? A sample file settles the importer design.
- [ ] Grading: four buttons, or jpdb's five?
- [ ] Phone storage budget for the dictionary: referenced words plus the top 60k, or all of JMdict?
- [ ] LLM assist: skip, local model on the cluster, or an API provider?
- [x] App name: settled as **Kotobaya (言葉屋)**.
