# API contract — summary for the human

Files: `openapi.yaml` (the contract, OpenAPI 3.1, 117 operations), `redocly.yaml` (lint rules),
`check_contract.py` + `check.sh` (verification, see the end). Parser (C-12): `PARSER.md`,
`token.schema.json`, `worker-parse.yaml` (cluster-internal), `examples/`, `check_parser.py`.

## What was decided

- **One rule for writes.** Every REST call that changes learning data is a wrapper that makes the
  server build sync ops and apply them through the same path as `POST /sync/push`. Each mutating
  operation lists the ops it emits in a machine-readable `x-emits-ops` field, and the check
  script fails if one is missing or names an op the database contract does not know.
- **Ops built for REST calls originate from the server device**, with the server's clock, and
  record the calling token for the audit trail. Web reviews are therefore logged as made by
  "server", not by a per-browser device.
- **Scopes are independent** (`read`, `write`, `review`, `import`, `admin`); none implies another.
  Every non-public operation declares the scope it needs. Web sessions hold all five; paired phones get `read`,
  `write`, `review`.
- **Three credentials**: session cookie (password + optional TOTP), device token (pairing code
  shown as QR, exchanged once), personal access token. All are rows of `api_token`; revocation
  is immediate. Cookie-authenticated writes need a fixed custom header as CSRF protection.
- **Password and TOTP secret come from the server's environment** (Kubernetes Secret), as the
  database contract assumed. There are no change-password or TOTP-enrolment endpoints.
- **Client-generated ids make creates idempotent**: re-sending a create with the same `id`
  returns the existing row and does nothing. For the two calls where a duplicate silently
  corrupts data the id is mandatory or dedicated: `POST /review/answer` **requires** `id` (a
  retried answer would otherwise log a second review and move FSRS twice), and
  `POST /media/import` takes `import_id` (job id and new media id; a retry returns the job).
- **Undo names its target.** `POST /review/undo` requires `review_id`; there is no implicit
  "undo my last review", which was not retry-safe (a retry would undo the review before it).
- **Duplicates are never created on purpose over REST.** Creating a note for a word that already
  has one returns an error naming the existing note (or the existing note itself, on request).
  The server-side merge remains for duplicates created offline.
- **Merged notes and cards answer with their survivor**; deleted rows answer `410` unless asked for.
- **Errors** are RFC 9457 problem documents with a stable `code`; the full table is at the top of
  the spec. **Lists** use opaque cursors (`limit` ≤ 500).
- **Sync bodies belong to the sync contract.** Push request/response and pull response are
  exactly `contracts/sync/batch.schema.json#/$defs/PushRequest`, `PushResponse`, `PullResponse`,
  with no API-owned fields. The API adds only framing: the server validates a push body against
  `PushRequestLenient` and answers `400` only when that fails; everything else (including a
  foreign `device_id`, clock rejections, and a `review.*` op without the `review` scope, code
  `insufficient_scope`) is a per-op rejection. Pull follows SYNC.md §6 exactly: own ops
  included, `next_since` = seq of the last op returned, `limit` ≤ 1000, and a `since` beyond
  the head is an empty `200` page whose `log_epoch` / `high_water_seq` trigger the client's log
  reset (no `cursor_ahead` error). REST elsewhere keeps RFC 3339 strings; only the sync bodies
  use integer `instant_ms`. "Head" is called `high_water_seq` everywhere (meta, events).
- **Size limits are per op, not per push.** `GET /meta` publishes `limits.max_op_bytes`
  (default 1 MiB, at most `max_push_bytes − 4096`). An op over it is a per-op `op_too_large`
  rejection; `413` is only for a whole push body, and the client answers it by halving the batch
  down to one op (SYNC.md §5.4), so one large op can never block the outbox.
- **Creates are checked and emitted under the sync lock.** The "id exists → 200, emit nothing"
  check and the emit run in one transaction holding advisory lock `7033001`, so a racing retry of
  `createNote` cannot mint a second set of cards.
- **REST reviews get the clock bounds pushes get from the device guards.** `POST /review/answer`
  refuses `reviewed_at` more than 5 min ahead, before 2020-01-01, or more than 24 h before
  `min(card.created_at, now)` — the bound SYNC.md §10.4 Re-mint uses. Pushed reviews still have
  no card-age check (SYNC.md §3.4 known issue).
- **Re-pairing checks `hlc_last`.** `pairDevice` takes an optional `hlc_last`; below the
  greatest HLC logged for that device id it is `422 hlc_regressed` (an Auto Backup restore) and
  the code stays valid so the client pairs again with a new device id.
- **Review history is listed in replay order, reversed**: `(reviewed_at, hlc, id)` descending
  (SYNC.md §9.1). Notes of a deleted note type stay usable (SYNC.md §7.3); `POST /notes` refuses
  the deleted type.
- **Writing review history needs `review`** on every path: answer/undo, `review.*` ops in push,
  and bulk reschedule/reset, which moved to their own operation `POST /cards/bulk/schedule`.
- **Auth exceptions are declared, not prose.** A device manages its own row through
  `GET/PATCH /devices/self` (`read`/`write`, bearer only, `403 device_required` otherwise);
  `/devices/{id}` is admin only. `POST /ankiconnect` has `security: [{}, …]` plus
  `x-auth: body-key` and `x-body-key-scope: write`; the check script allows `{}` nowhere else.
- **Device ids are never reused after local data loss** (SYNC.md §3.3): a reinstall or cleared
  data mints a new id; only an intact install re-pairs with its old id (checked through
  `hlc_last` where the server can). Re-minted ops (SYNC.md §10.4) are ordinary pushes with new
  op ids and need nothing from the API.
- **Cursors are keyset without snapshot**; the skip/repeat behaviour for mutable sort keys is
  specified, and clients de-duplicate by `id`.
- **Imports are jobs.** Files are uploaded as content-addressed blobs first, then referenced by
  hash from a JSON import request. The media row and its deck are created by the job.
- **Events** are a server-sent-events stream without replay; the first event carries the sync
  head so a reconnecting client knows whether to refetch.
- **AnkiConnect** keeps its own protocol (always HTTP 200, errors in the body) and accepts a
  personal access token in the body's `key`, which is the only thing Yomitan can send.

## Added beyond the spec's resource table

Asked for by the database contract (SCHEMA.md §7): note types, presets, settings, word-status
clear, deck delete, deck item put/remove, confusable confirm/reject, grammar edit/delete, manual
grammar spans, media edit/delete, parse overrides.

Added because something would otherwise be unreachable: single-card get/edit/delete and adding a
card kind to a note; a card's review history; blob upload/download; dictionary release (phone
download) and refresh; the catalogue of generated lists; re-parse; sentence search; jpdb history
import; TTS; hot kanji; a "today" stats summary and FSRS calibration; job cancel; server info.

## Alternatives rejected

- **Web app pushes raw ops instead of REST conveniences.** Faithful to "writes through the same
  operation endpoint", but it would put an HLC clock and op construction into every script. The
  conveniences end up in the same log, which is what the spec sentence is protecting.
- **A device row per browser session or script by default**, so REST-built ops carry a real
  origin. Two parties (browser and server) would then mint clock values for one device id. Kept
  optional: a personal token can be created with its own device if a script wants to push ops.
- **Multipart upload on `POST /media/import`.** One fewer step for the CLI, but two body formats
  to implement and no resumability. Upload-then-reference also de-duplicates files.
- **`If-Match`/ETag optimistic locking on PATCH.** The data model is last-writer-wins per field by
  design; a second conflict mechanism on top would contradict it.
- **An `Idempotency-Key` header.** Needs a new table. Client-supplied ids cover creates and
  reviews; the remaining writes are register sets, which are safe to repeat.
- **Storing drill sessions.** A drill is a list of cards plus answers logged as `drill`; nothing
  needs to persist.
- **Event replay with `Last-Event-ID`.** Would need an event log. The sync log already gives
  clients a durable cursor.
- **Scope hierarchy (`admin` ⊃ `write` ⊃ `read`).** Independent scopes are simpler to check and to
  reason about for narrow script tokens.

## Three choices most worth a second look

1. **Reviews made in the web app are attributed to the server, not to a browser.** A consequence
   of "REST-built ops originate from the server device". You cannot later split stats by "phone
   vs. web" except as "phone vs. everything else". If per-browser
   attribution matters, the change is: login registers a web device and the server keeps a
   separate clock per such device.
2. **`insufficient_scope` as a per-op push rejection.** SYNC.md's code set is open, so this is
   legal, but a rejection is permanent: a script token with `write` but not `review` that pushes
   a review loses it to Discard/Retry. The alternative (a whole-request `403`) would block the
   outbox, which T005 forbids. Device tokens always hold `review`, so phones never meet it.
3. **Scope boundaries for a paired phone.** Phones get `read`, `write`, `review` and not
   `import` or `admin`: the phone cannot start imports, optimizer runs or dictionary refreshes,
   and cannot manage devices or tokens. That matches the spec's "management stays in the web
   app", but it also means "import this file" from the Android share sheet would need the
   `import` scope added to device tokens.

## Known gaps (not defined here)

- ~~Snapshot archive formats~~ — closed: dictionary builds by C-3 (`contracts/db/DICT_PACKAGE.md`),
  media content and deck items by C-11 (`contracts/db/SNAPSHOT.md`).
- **Pairing codes and login rate limits have no table.** The spec allows in-memory state
  (restart invalidates outstanding codes). Fine for one API replica; needs an additive migration
  for more.
- **Who produces `confusable.suggested`** and when. No job kind or endpoint triggers it yet.
- ~~The jpdb "unmapped vocabulary" review queue~~ — closed by C-11: `listJpdbVocab`,
  `putJpdbVocabResolution`, `clearJpdbVocabResolution` (`contracts/db/MEDIA_PIPELINE.md` 5).
- **Backup status** for the settings screen: backups run outside the API, which has no knowledge
  of them.
- **LLM assist** endpoints (off by default in the spec).
- **Bulk reschedule/reset is not retry-safe**: a repeated call logs a second manual row per card
  (same schedule effect, extra history). Accepted; each row can be undone by id.
- **Job retention** is not specified; `import_id` idempotency relies on the job row (or, for new
  media, the media row) still existing when the retry arrives.
- **Pull `limit` maximum (1000) and push `maxItems` (500)** are fixed by `batch.schema.json`;
  `GET /meta` reports them as constants.

## Checklist: spec resource → operationId

| Spec endpoint | operationId |
|---|---|
| `GET /dict/search?q=` | `searchDictionary` |
| `GET /dict/entries/{seq}` | `getDictEntry` |
| `GET /kanji/{literal}` | `getKanji` |
| `GET /notes` | `listNotes` |
| `POST /notes` | `createNote` |
| `PATCH /notes/{id}` | `updateNote` |
| `DELETE /notes/{id}` | `deleteNote` |
| `GET /cards?query=` | `listCards` |
| `POST /cards/bulk` | `bulkCards` (reschedule/reset: `bulkScheduleCards`) |
| `GET /review/queue` | `getReviewQueue` |
| `POST /review/answer` | `answerReview` |
| `POST /review/undo` | `undoReview` |
| `POST /drill/session` | `createDrillSession` |
| `GET /decks` | `listDecks` |
| `POST /decks` | `createDeck` |
| `PATCH /decks/{id}` | `updateDeck` |
| `GET /decks/{id}/items` | `listDeckItems` |
| `GET /decks/{id}/coverage` | `getDeckCoverage` |
| `POST /media/import` | `importMedia` |
| `POST /media/import/jpdb` | `importJpdbDeck` |
| `GET /media/{id}/coverage` | `getMediaCoverage` |
| `GET /media/{id}/sentences?filter=i+1` | `listMediaSentences` |
| `PUT /words/{seq}/status` | `setWordStatus` |
| `GET /words/hot` | `listHotWords` |
| `GET /words/leeches` | `listLeeches` |
| `GET /words/{seq}/confusables` | `listWordConfusables` |
| `GET /grammar` | `listGrammarPoints` |
| `POST /grammar` | `createGrammarPoint` |
| `POST /grammar/{id}/rules/test` | `testGrammarRules` |
| `GET /grammar/{id}/examples` | `listGrammarExamples` |
| `GET /stats/heatmap` | `getStatsHeatmap` |
| `GET /stats/retention` | `getStatsRetention` |
| `GET /stats/forecast` | `getStatsForecast` |
| `GET /stats/known-words` | `getStatsKnownWords` |
| `POST /parse` | `parseText` |
| `GET /jobs` | `listJobs` |
| `GET /jobs/{id}` | `getJob` |
| `POST /sync/push` | `syncPush` |
| `GET /sync/pull` | `syncPull` |
| `GET /export` | `exportAll` |
| `GET /export/anki` | `exportAnki` |
| `GET /events` | `streamEvents` |
| `POST /ankiconnect` | `ankiConnect` |

Named by the ticket (auth, devices, tokens, settings):

| Area | operationIds |
|---|---|
| Auth | `login`, `logout`, `getPrincipal` |
| Devices | `listDevices`, `getDevice`, `updateDevice`, `revokeDevice`, `createPairing`, `pairDevice`, `getOwnDevice`, `updateOwnDevice` |
| Tokens | `listTokens`, `createToken`, `getToken`, `revokeToken` |
| Settings | `listSettings`, `updateSettings` |

Everything else (additions):

| Area | operationIds |
|---|---|
| Meta | `getHealth`, `getMeta` |
| Presets | `listPresets`, `createPreset`, `getPreset`, `updatePreset`, `deletePreset`, `optimizePreset` |
| Note types | `listNoteTypes`, `createNoteType`, `getNoteType`, `updateNoteType`, `deleteNoteType` |
| Dictionary | `listEntryExamples`, `getDictRelease`, `refreshDictionary`, `listKanjiWords` |
| Notes and cards | `getNote`, `bulkScheduleCards`, `createCard`, `getCard`, `updateCard`, `deleteCard`, `listCardReviews` |
| Decks | `listDeckLists`, `getDeck`, `deleteDeck`, `addDeckItems`, `putDeckItem`, `removeDeckItem` |
| Media | `listMedia`, `getMedia`, `updateMedia`, `deleteMedia`, `reparseMedia`, `importJpdbHistory`, `listSentences`, `getSentence` |
| jpdb mapping review queue (C-11) | `listJpdbVocab`, `putJpdbVocabResolution`, `clearJpdbVocabResolution` |
| Words | `getWordStatus`, `clearWordStatus`, `listHotKanji`, `listConfusables`, `setConfusable` |
| Grammar | `getGrammarPoint`, `updateGrammarPoint`, `deleteGrammarPoint`, `putGrammarSpan`, `removeGrammarSpan` |
| Stats | `getStatsSummary`, `getStatsCalibration` |
| Parsing | `listParseOverrides`, `putParseOverride`, `deleteParseOverride` |
| Jobs | `cancelJob`, `synthesizeSpeech` |
| Blobs | `queryBlobs`, `getBlob`, `putBlob` |

## Cross-check: table ↔ ops ↔ API operations

This table is a superset of SYNC.md §14: every `METHOD /path` there exists here and (if
mutating) emits an op of its row's table or is a job — `check_contract.py` rule 7 enforces it.
Rows here also list readers (stats, coverage) and two emitters §14 does not name
(`importJpdbHistory` creates notes and cards; `importMedia` creates the media deck).

Op names are those of `contracts/db/SCHEMA.md` §4. "(job)" = emitted by a server job the
operation starts; "(consequence)" = emitted by the op-apply path. Every op also flows through
`syncPush` / `syncPull`.

| Table | Ops | API operations |
|---|---|---|
| `note_type` | `note_type.created/updated/deleted` | `listNoteTypes`, `getNoteType`, `createNoteType`, `updateNoteType`, `deleteNoteType` |
| `note` | `note.created/updated/deleted`; `note.merged` (consequence) | `listNotes`, `getNote`, `createNote`, `updateNote`, `deleteNote`, `bulkCards`, `ankiConnect`, `importJpdbHistory` |
| `card` | `card.created/suspended/unsuspended/buried/unburied/flag_set/preset_set/deleted`; `card.merged` (consequence) | `listCards`, `getCard`, `createCard`, `updateCard`, `deleteCard`, `bulkCards`, `createNote`, `getReviewQueue`, `listLeeches` |
| `review_log` | `review.logged`, `review.undone` | `answerReview`, `undoReview`, `bulkScheduleCards`, `listCardReviews`, `importJpdbHistory`, `getStatsHeatmap`, `getStatsRetention`, `getStatsCalibration` |
| `word_status` | `word.status_set`, `word.status_cleared` | `getWordStatus`, `setWordStatus`, `clearWordStatus`, `getStatsKnownWords`, `importJpdbHistory` |
| `deck` | `deck.created/updated/deleted`; `deck.content_published` (job) | `listDecks`, `getDeck`, `createDeck`, `updateDeck`, `deleteDeck`, `importMedia`, `importJpdbDeck`, `deleteMedia` |
| `deck_item` | `deck.item_put`, `deck.item_removed`; snapshot via `deck.content_published` (job) | `listDeckItems`, `addDeckItems`, `putDeckItem`, `removeDeckItem`, `createNote`, `bulkCards`, `getDeckCoverage`, `listKanjiWords` |
| `confusable` | `confusable.confirmed/rejected`; `confusable.suggested` (server; trigger not defined) | `listWordConfusables`, `listConfusables`, `setConfusable` |
| `grammar_point` | `grammar.created/updated/deleted` | `listGrammarPoints`, `getGrammarPoint`, `createGrammarPoint`, `updateGrammarPoint`, `deleteGrammarPoint` |
| `sentence_grammar_span` | `grammar.span_put`, `grammar.span_removed` | `putGrammarSpan`, `removeGrammarSpan`, `listGrammarExamples`, `testGrammarRules` |
| `settings` | `settings.set` | `listSettings`, `updateSettings` |
| `fsrs_params` | `fsrs_params.created/updated/deleted`; `fsrs_params.published` (job) | `listPresets`, `getPreset`, `createPreset`, `updatePreset`, `deletePreset`, `optimizePreset` |
| `media` | `media.updated/deleted`; `media.created`, `media.content_published` (job); `media.content_published` `parsing` (request, C-11) | `listMedia`, `getMedia`, `updateMedia`, `deleteMedia`, `importMedia`, `importJpdbDeck`, `reparseMedia`, `putJpdbVocabResolution`, `clearJpdbVocabResolution` |
| `parse_override` | `parse_override.put`, `parse_override.deleted` | `listParseOverrides`, `putParseOverride`, `deleteParseOverride`, `parseText` |

## Verifying

```
contracts/api/check.sh
```

Lints the spec with Redocly (pinned version, via `npx`), which also validates every example
against its schema; then runs `check_contract.py`: every operation declares a scope, every
mutating operation declares `x-emits-ops` with op names known to SCHEMA.md, every request and
response body and every parameter has an example, the tables above cover every operationId
and every learning-data table, the three sync bodies are exactly the `batch.schema.json` `$defs`,
every example marked `x-example-source` equals its file under `contracts/sync/examples/`, and
every endpoint in SYNC.md §14 exists with a matching emitted op.
`check.sh` fails if `contracts/sync/batch.schema.json` is missing (the permissive stand-in of
attempt 1 is gone). Needs node and python3; network on first run.

## C-2 changes
- **jpdb history import covers kanji.** The two kanji arrays become kanji notes (built-in Kanji
  type, only the literal filled in) with a `keyword` card (kanji → keyword) or a `writing` card
  (keyword → kanji). Every grade has a fixed mapping: `hard` is FSRS Hard (ADR-008), `pass`/`fail`
  become Good/Again with no button, `unknown` only creates the card, and `known` sets a word status
  (vocab only). The job result gains kanji counts and status-event counts. Three sums prove that no
  record was dropped.
- `QueueItem.kanji` (optional) carries the `ref.kanji` row, so kanji notes with empty fields render
  the same on every host.
- Push: duplicate-key rules (400 vs per-op `schema_invalid`). `insufficient_scope` is checked
  right after `device_mismatch`.
- Dictionary search: the romaji conversion is the server's (wanakana-equivalent), and the search
  does not deinflect.
- Adding tags beyond the 200-tag limit gives `422 validation_failed` / `out_of_range`. A vocab note
  type needs `expression`, `reading` and `meaning`.

## C-4 changes
- **jpdb history import aligned with ADR-009** (additive): one `meaning` card per vocab word,
  `en_jp` rows ignored but counted, rows repeating a seq merged with exact duplicates dropped
  (`reviews_duplicate_dropped`), `known` sets a status only for known-only words (never over an
  existing override), `writing` and known-only kanji cards created suspended
  (`kanji_cards_suspended`), optional kanji `keyword`, and a fixed deterministic review-id formula.

## C-9 changes (additive)
- **Server-internal jobs.** An `app.job` row whose kind is not a `JobKind` value is internal.
  `listJobs`, `getJob`, `cancelJob` (404) and `streamEvents` never expose it; it shows only
  through its parent job. First use: `jpdb-history-apply`, which carries the worker's import
  plan to the server's OpApply. `importJpdbHistory` "Execution" gives the four-step protocol and
  how the parent's status, progress and result follow the child.
- **`JobResultJpdbHistory.reviews_id_collisions`** (required, ≥ 0): rating events whose review
  id is already held by a review of a different live card. It is a subset of
  `reviews_already_imported`, not a new bucket, so the three sums are unchanged.
- Worth a second look: (1) internal is decided by "kind not in `JobKind`" and not by a prefix.
  This matches the T207b code, but adding a kind to `JobKind` later exposes it. (2) Collisions
  count inside bucket 8 instead of getting their own bucket. (3) The parent reports no partial
  counts on cancel.

## C-11 changes (additive)
- **Media pipeline.** Media imports, re-parses and jpdb deck imports publish through a second
  server-internal job kind, `media-publish`: the worker builds two snapshot archives (media
  content, deck items; `contracts/db/SNAPSHOT.md`) and the server installs them and mints
  `media.created`, `deck.created`, `media.content_published`, `deck.content_published` in one
  transaction (`contracts/db/MEDIA_PIPELINE.md`). The worker no longer writes media rows.
- **`parse_status`.** `importMedia` (append), `importJpdbDeck` (known deck) and `reparseMedia` now
  emit `media.content_published` with `parsing` when they enqueue a job for an existing media;
  a server reconciler turns a stuck `parsing` into `failed` or back to `parsed`. Their
  `x-emits-ops` lists `media.content_published`.
- **jpdb mapping review queue.** `GET /import/jpdb/vocab` (`listJpdbVocab`), `PUT` / `DELETE
  /import/jpdb/vocab/{jpdb_vid}/resolution` (`putJpdbVocabResolution`,
  `clearJpdbVocabResolution`, scope `import`): map an unmapped jpdb word to a dictionary entry or
  ignore it. Stored server-side (not an op, not synced); each change re-publishes the affected
  jpdb media from the importer's cache (`jpdb-deck-import` jobs with the new server-set
  `JpdbDeckImportRequest.remap_only`).
- Worth a second look: (1) corrections are not synced and not in exports; (2) `parsing` is
  emitted by the request, not the job, so a crashed job relies on the reconciler; (3) the remap
  job clears its own dedupe key on start so later corrections can queue another remap.

## C-12 changes (additive)
- **Token format 1 frozen** (`PARSER.md` 1, `token.schema.json`): ten members, all always
  present: `s`, `e` (UTF-16 code units into the ORIGINAL sentence text, half-open, ascending,
  non-overlapping), `surface` (= that slice), `lemma`, `reading` (hiragana), `pos` (Sudachi levels
  1-4 without `*`), `cform` (Sudachi form verbatim, e.g. `連用形-一般`, or null), `seq` (null =
  unmapped), `class`, `override`. Punctuation and whitespace are gaps, never tokens. The same value
  is stored, snapshotted, served by the API and returned by the worker. `SentenceToken` now lists
  all ten as required: a **tightening** of a response-only schema (still open to unknown members),
  acceptable because no merged code consumes it yet; examples use hiragana readings and verbatim
  forms.
- **`class` table** (`PARSER.md` 2): Sudachi 助詞 → particle, 助動詞 → auxiliary, 固有名詞 →
  name; then JMdict POS refinements (entry only `n-pr` → name, only `prt` → particle, only
  auxiliary tags → auxiliary); 数詞 → other; ordinary word classes → content; the rest → other.
  `occurrence.pos_class` = most frequent token class, ties `content > name > particle > auxiliary
  > other`.
- **Override application** (`PARSER.md` 4): exact match on the original text; a match must not
  cut a parser token; candidates sorted by scope (sentence > media > global), longest
  `match_text`, newest `hlc`, `id`, leftmost; greedy non-overlapping acceptance. Missing
  `lemma`/`reading`/`pos`/`cform`/`class` come from a same-span parser token, else from analysing
  the surface alone; absent `seq` = null. `OverrideToken` gains optional `reading` (hiragana; the
  API rejects katakana) and `class`. Rows arriving via the `parse_override.put` sync op are
  sanitised when read: katakana readings folded, invalid `lemma`/`reading`/`class`/`seq` treated
  as absent, so no stored override can make a parse invalid. `/parse` applies global overrides
  only.
- **Worker parse service** (`worker-parse.yaml`, `PARSER.md` 5): `POST /internal/parse` and
  `GET /internal/health` (readiness = Sudachi loaded only; a missing dictionary release or an
  unreachable database is a per-request `503 not_ready` and shows as `dict_release: null`, so a
  fresh install's `helm --wait` does not hang) on worker port `PARSER_PORT` (8082), no auth,
  NetworkPolicy only, never host-exposed; ≤ `PARSER_MAX_CONCURRENCY` (4) in flight else `503 busy`. API env `PARSER_URL`,
  `PARSER_TIMEOUT_MS` (10 s). Everything but worker `200`/`413` becomes public `503 unavailable`.
- **Worker rule test (C-14, additive, `worker-parse.yaml` 1.1.0)**: `POST
  /internal/grammar/rules/test` `{rules:[str], sentences?:[str | {text, tokens}], max_matches?}`
  → `200 {matches:[{rule_index, sentence_index, start_offset, end_offset, text}],
  errors:[{rule_index, message, column}], truncated, parser_version}`. The worker is the only
  implementation of the token-pattern language, so the server asks it instead of re-implementing.
  - Validate-only = no `sentences`: needs neither dictionary nor database, never `busy` or
    `not_ready`. `createGrammarPoint`/`updateGrammarPoint` call it for a non-empty
    `detection_rules` before building the op; each error is a `422` item with pointer
    `/detection_rules/{i}/rule` and detail `column N: message`. Worker unreachable: `503
    unavailable`, nothing written.
  - Invalid rules are `200` + `errors` (at most one per rule; `column` 1-based in code points);
    valid rules are still matched. Offsets are UTF-16 into the sentence text; one rule's matches
    in a sentence never overlap; order (sentence, start, rule, end).
  - `testGrammarRules` sends stored sentences **with their stored tokens** (`{text, tokens}`,
    used as given, so media- and sentence-scoped overrides count); a plain string is tokenised
    as one sentence with global overrides only.
  - Limits: 20 rules × 1000 code points, 500 sentences × 20,000 code points, 8 MiB body, 10,000
    matches (`truncated`); over a limit is `413`.
  - Ops pushed by sync are never validated (rejecting would break convergence): stored rules
    may be invalid, detection skips them.
  - Rejected: a separate validate endpoint (same parser, one more path); strings only as the
    ticket sketched (re-tokenising 200,000 stored sentences per test, and results that disagree
    with stored spans because scoped overrides are lost); `422` from the worker for bad rules
    (a batch with one bad rule would lose the other rules' results); porting the language to
    the server (two implementations to keep in step).
  - Worth a second look: (1) grammar create/update with rules now fails `503` when the worker is
    down (fail closed) rather than storing unchecked rules; (2) the tokenised sentence form goes
    beyond the ticket's `sentences:[str]`; (3) PARSER.md 5.1 still says the service has only two
    paths (not owned by C-14; `worker-parse.yaml` states that it supersedes that sentence).
- **Versioning**: optional `parser_version` (opaque) in the worker response, `ParseResult` and
  `JobResultMedia`. Follow-up for the snapshot owner: an optional `parser_version` header member in
  the media content archive so the publish runner can fill `JobResultMedia.parser_version`.
- Rejected: matching overrides on normalised text (offsets and surfaces are original; the user
  picks original text); letting overrides cut tokens (leftover fragments would need re-parsing);
  passing overrides in the parse request (the worker already reads Postgres); a shared-secret
  header (read-only endpoint, one more secret to wire).
- Worth a second look: (1) no auth on the internal parse port, relying on NetworkPolicy; (2)
  `cform` stored verbatim (`連用形-一般`), so the Phase 5 grammar matcher must compare the part
  before `-`; (3) tie-break prefers `content`, so a word used equally as particle and content is
  never hidden by "hide particles". HUMAN_CHECKS candidate: the `SentenceToken` required-list
  tightening.

## C-15 changes (additive): grammar card sentence at review time
- **Shape**: optional `QueueItem.grammar_sentence` (`GrammarCardSentence | null`; absent = null)
  on `getReviewQueue` and `createDrillSession` items: `sentence_id`, `text`, the chosen span
  (`start_offset`/`end_offset`, UTF-16), `translation` (null for now), the pick's inputs
  (`unknown_count`, `rotation`, `last_shown_sentence_id`) and `source` (`ExampleSentenceSource`:
  media/segment id and title, `start_ms`, `location`). It maps 1:1 to the core renderer's
  `ClozeSentence`/`JsCloze`. `QueueItem.sentence` carries the full row (tokens, audio). No new
  operation, no new op, no table change; `ReviewAnswerRequest.sentence_id` (existing) is echoed back.
- **Rules**: set for grammar-note cards except `production`; the server MUST call core
  `GrammarCardSentences.pick(candidates, lastShown, rotation)`. Candidates = live spans
  (detected + manual) of the point in live `origin = media` sentences; `rotation = card.reps`;
  `lastShown` = `sentence_id` of the card's latest non-undone, non-manual review by `hlc`. Pure
  function of replica state, so a refetch or an undo shows the same sentence; an answer moves on.
- **Offline / Android**: the phone runs the same pick over its replica (installed media
  snapshots + synced manual spans + local word status and review log). No candidate → render
  with `cloze = null` and an i18n "no example sentence yet" notice (grammar notes have no fixed
  sentence field to fall back to). Phone and server may pick differently; display only.
- Rejected: a per-card `GET` (one extra round trip per grammar card, and the queue is refetched
  after every answer anyway); a stored "current sentence" register on the card (a new synced
  op and LWW conflicts for a display choice); random rotation (undo/redisplay would change it).
- Worth a second look: (1) `rotation = card.reps` ignores drills (they still rotate via
  `lastShown`); (2) only the latest review's `sentence_id` is consulted, so a review answered
  without `sentence_id` lets the previous sentence repeat once; (3) `translation` is always null
  until mined sentences get translations, so `{{cloze:translation}}`/hint stay empty.

## C-16 changes (additive): FSRS optimizer result and publication
- **`optimizePreset`** gains an "Execution" paragraph: the weights are published only when they
  beat the current ones on held-out reviews; the hand-off to the server is the new
  server-internal job kind `fsrs-params-publish` (hidden from `listJobs`, `getJob`, `cancelJob`
  and the event stream like the other two). Protocol: `contracts/db/FSRS_PUBLISH.md`.
- **`JobResultFsrsOptimize`** gains three optional members: `log_loss_before` (held-out log loss
  of the weights being replaced), `holdout_review_count`, and `reason` (`too_few_reviews`,
  `not_improved`, `out_of_bounds`, `preset_deleted`; null when published). Not `required`, so
  results already stored stay valid.
- **`Preset.optimizer_stats`** (description only): publications now also carry
  `log_loss_before` and `holdout_review_count`.
- No new operation, no new public `JobKind`, no new op. Cross-check row unchanged:
  `fsrs_params` ↔ `fsrs_params.published` ↔ `optimizePreset` (job, `x-emits-ops: []`).
- Worth a second look: see `contracts/db/SUMMARY.md` "C-16 changes".

## C-17 changes (additive): words containing a kanji
- **`listKanjiWords`** `GET /kanji/{literal}/words` (tag `dictionary`, scope `read`, read-only, no
  ops). Live entries with a `ref.entry_kanji` row for the literal. One code point else `422`; a
  literal missing from `ref.kanji` (or contained by no entry) is an empty page, not `404`.
  Filters: `deck_id` (live entry-keyed items of that live deck; deleted/unknown deck `404`),
  `mine` (live item of any live deck, or a live vocab note; word status alone does not count).
  Order: with `deck_id`, `occurrence_count` desc, then `freq_rank` asc NULLs last, then `seq`;
  else `freq_rank`, `seq`. `limit` 1..200 (default 50; own parameter, the shared `Limit` allows
  500), keyset `cursor` bound to `literal`/`deck_id`/`mine` (mismatch `422`).
- **Schemas**: `KanjiWord` = `allOf(DictEntrySummary, {decks})`; `KanjiWordDeck {deck_id, name,
  type, occurrence_count}`, decks ordered by `name` in code-point order (`COLLATE "C"`) then id;
  `KanjiWordPage {items, next_cursor}`. Example `KanjiWordPage`.
- No DB change (`entry_kanji_literal_idx`, `deck_item_entry_idx` suffice). Cross-check: added to
  the `deck_item` row as a reader.
- Rejected: a `kanji:` filter on `searchDictionary` (different ordering and no deck membership);
  embedding the word list in `Kanji` (unbounded, unpaged).
- Worth a second look: (1) `literal` is matched without NFKC, so a CJK compatibility ideograph
  finds nothing; (2) pages are not a snapshot - clients de-duplicate by `seq`; (3) `mine` ignores
  word status, so a word marked `known` but in no deck and with no note is excluded.
