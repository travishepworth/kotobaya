# API contract — summary for the human

Files: `openapi.yaml` (the contract, OpenAPI 3.1, 123 operations), `redocly.yaml` (lint rules),
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
- **Client error reports are log lines, not data (C-23, ADR-024).** `POST /client-errors`
  takes 1-20 reports from web/Android (any valid credential, any scope), logs one escaped line
  each at WARN/ERROR and stores nothing, so it emits no op and needs no table. Fixed limits: 1 MiB
  body or >20 items `413` (client splits), field limits `422`, 12 requests / 120 items per
  credential per minute `429`. Client queue, dedupe (`count`), `dropped` and retry rules are in
  the operation.
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
| Meta | `getHealth`, `getMeta`, `reportClientErrors` (C-23) |
| Presets | `listPresets`, `createPreset`, `getPreset`, `updatePreset`, `deletePreset`, `optimizePreset` |
| Note types | `listNoteTypes`, `createNoteType`, `getNoteType`, `updateNoteType`, `deleteNoteType` |
| Dictionary | `listEntryExamples`, `getDictRelease`, `refreshDictionary`, `listKanjiWords`, `listDictHeadwords` |
| Notes and cards | `getNote`, `bulkScheduleCards`, `createCard`, `getCard`, `updateCard`, `deleteCard`, `listCardReviews` |
| Decks | `listDeckLists`, `getDeck`, `deleteDeck`, `addDeckItems`, `putDeckItem`, `removeDeckItem` |
| Media | `listMedia`, `getMedia`, `updateMedia`, `deleteMedia`, `reparseMedia`, `importJpdbHistory`, `listSentences`, `getSentence` |
| jpdb mapping review queue (C-11) | `listJpdbVocab`, `putJpdbVocabResolution`, `clearJpdbVocabResolution` |
| Words | `getWordStatus`, `clearWordStatus`, `listHotKanji`, `listConfusables`, `setConfusable` |
| Grammar | `getGrammarPoint`, `updateGrammarPoint`, `deleteGrammarPoint`, `putGrammarSpan`, `removeGrammarSpan` |
| Stats | `getStatsSummary`, `getStatsCalibration` |
| Parsing | `listParseOverrides`, `putParseOverride`, `deleteParseOverride` |
| Jobs | `cancelJob`, `synthesizeSpeech`, `resolveSpeech` |
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
| `note` | `note.created/updated/deleted`; `note.merged` (consequence) | `listNotes`, `getNote`, `createNote`, `updateNote`, `deleteNote`, `bulkCards`, `ankiConnect`, `importJpdbHistory`, `getReviewQueue` (deck materialization, C-27) |
| `card` | `card.created/suspended/unsuspended/buried/unburied/flag_set/preset_set/deleted`; `card.merged` (consequence) | `listCards`, `getCard`, `createCard`, `updateCard`, `deleteCard`, `bulkCards`, `createNote`, `getReviewQueue`, `listLeeches` |
| `review_log` | `review.logged`, `review.undone` | `answerReview`, `undoReview`, `bulkScheduleCards`, `listCardReviews`, `importJpdbHistory`, `getStatsHeatmap`, `getStatsRetention`, `getStatsCalibration` |
| `word_status` | `word.status_set`, `word.status_cleared` | `getWordStatus`, `setWordStatus`, `clearWordStatus`, `getStatsKnownWords`, `importJpdbHistory` |
| `deck` | `deck.created/updated/deleted`; `deck.content_published` (job) | `listDecks`, `getDeck`, `createDeck`, `updateDeck`, `deleteDeck`, `importMedia`, `importJpdbDeck`, `deleteMedia` |
| `deck_item` | `deck.item_put`, `deck.item_removed`; snapshot via `deck.content_published` (job) | `listDeckItems`, `addDeckItems`, `putDeckItem`, `removeDeckItem`, `createNote`, `bulkCards`, `getDeckCoverage`, `listKanjiWords`, `getReviewQueue`, `createDrillSession`, `getNote`, `listNotes`, `getDictEntry`, `searchDictionary` |
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

## C-18 changes (additive): batch headword lookup
- **`listDictHeadwords`** `GET /dict/headwords?seq=1358280,1358300` (tag `dictionary`, scope
  `read`, read-only, no ops, no overlay, no paging). `seq`: one comma-separated parameter
  (`style: form`, `explode: false`, like `kinds`/`hide`), 1 to 500 `EntrySeq` values; missing,
  empty, repeated parameter, bad element or more than 500 is `422`. Duplicates collapse.
  Responses `200`/`401`/`422`.
- **Schemas**: `DictHeadwordList {items}`; `DictHeadword {seq, headword, headword_reading,
  glosses}` - same names and values as `DictEntrySummary`. One item per distinct existing `seq`,
  by `seq` ascending; unknown `seq` omitted (never `404`); retired entries returned. Example
  `DictHeadwordList`.
- No DB change, no sync op, no learning-data table touched (reads `ref.dict_entry` and its
  satellites only), so the cross-check table is unchanged.
- Rejected: `headword` on the `Note` returned by `listCards`/`listNotes` (couples note reads to
  the dictionary and to its refreshes); `{items: DictEntry[]}` (500 full entries is megabytes);
  a `POST` lookup body (not cacheable, needs `x-emits-ops: []` for a pure read); the ticket's
  `/dictionary/...`, `reading` and scalar `gloss` names (the spec says `/dict/...`,
  `headword_reading`, `glosses`; a client wanting one gloss takes `glosses[0]`).
- Worth a second look: (1) a full 500-value request line is about 5 kB, above Netty's 4096
  default, so the server (BUG-7c) must raise `maxInitialLineLength` to >= 8192; (2) `glosses` is
  an array, not the ticket's scalar `gloss`; (3) items come back by `seq`, not in request order,
  and omitted `seq` values are the only "not found" signal.

## C-21 changes (additive): JLPT list decks

- `GET /deck-lists` now offers every loaded vocabulary list (`jlpt-n5` … `jlpt-n1` once the JLPT
  source is loaded) and omits lists with 0 items; `item_count` equals what a build publishes.
- `JobResultDeckBuild` gains `list_key`, `changed`, `unmatched`; `JobResultDictRefresh` gains
  `vocab_lists` (per-list row accounting). No new operation; `deck-build` semantics and the
  internal `deck-publish` hand-off are in `contracts/db/VOCAB_LISTS.md`.

## ARCH-CARD-TEXT changes (additive): plain-text card render on `QueueItem`

- `QueueItem.text` (`CardText`: `front`, `back`, `reading`, `sentence`), filled by the server
  with the core's `DefaultCardTextRenderer` (contract: `core/.../contracts/render/CardTextRenderer.kt`)
  for every item of `getReviewQueue` and `createDrillSession` whose note type the server can
  render. Optional on the wire: omitted for an item with an unknown note type (the item is
  still returned, no error) and by older servers; `reading`/`sentence` are required-nullable
  inside `CardText`.
- Per-kind rules are a table (front lines / back lines per built-in base type and kind); custom
  templates and unknown kinds fall back to the text of the HTML render (only the fallback drops
  back lines that repeat the front, i.e. `{{FrontSide}}`). The fronts ask the same question as
  the HTML fronts (vocab `pitch`: expression and reading; the back is the pitch line only).
- Dictionary data on the server is built from the item's `entry` like the web review screen:
  headword, headword reading, every gloss of every sense in order; pitch only from the row for
  the headword reading (no row: no pitch). A note whose type row and built-in type are both
  unknown gets no `text` (the rest of the page is unaffected).
- No DB change, no sync op, no learning-data table touched; cross-check table unchanged.
- Rejected: client-side text rendering in Python (CLI-DAEMON forbids re-implementing rendering);
  stripping the HTML render for every kind (the back repeats the front, the pitch is spans, the
  cloze blank is markup); exposing render warnings on the wire (i18n keys for UIs only).
- Worth a second look: (1) `back` includes the reading line, so `reading` duplicates it — a
  layout showing both must pick one; (2) pitch notation `ꜜ` after the downstep mora, heiban
  unmarked; (3) all senses' glosses (web) rather than first-sense only (Android's offline fill),
  so long entries give a long `meaning` line.

## C-25 changes (additive): review back-side extras and deck membership reads (ADR-029)

- **`QueueItem.decks`** (`QueueItemDeck {id, name, priority}`, max 3) + **`deck_count`**: the
  decks holding the card's note, in draw order `(priority, id)` ascending; `deck_count` is the
  total, so `deck_count - decks.length` is the UI's "+N". On every item of `getReviewQueue` and
  `createDrillSession` (any note type; `[]` / `0` when in no deck).
- **`QueueItem.kanji_examples`** (`KanjiExample {seq, headword, reading, gloss, reading_type:
  on|kun|other}`, max 3): present (possibly empty) iff `card.note.kanji_literal` is non-null.
  Chosen by the core `KanjiExamplePicker` (contract `core/.../contracts/dict/KanjiExamples.kt`):
  the user's words first (status learning/known/never_forget, a live note, or in a deck), then
  `freq_rank`, then `seq`; the best on'yomi word and the best kun'yomi word are always included
  when one exists among the first 50; blacklisted/ignored words, retired entries and entries
  whose headword does not contain the kanji are never shown. `gloss` = first sense joined by `; `.
- **`deck_ids`** on `DictEntry`, `DictEntrySummary` (so also `KanjiWord`, `DeckItem.entry`,
  parse and struggle summaries) and `Note`: ids of the live decks holding it, same order. Closes
  the FOLLOWUPS gap "no 'decks holding entry/note' read" (web no longer has to scan
  `listDeckItems` of every deck).
- **Membership rules.** Entry: a live item `e:<seq>` in a live deck (the rule `KanjiWord.decks`
  already had). Note (`Note.deck_ids`, `QueueItem.decks`): a live item `n:<id>` naming the note
  or a merge loser of it, or `e:<entry_seq>`; a tombstoned note or a merge loser has none.
  Deleted decks and tombstoned items never count; disabled decks do; smart decks never appear.
- All new members are optional on the wire (older servers omit them); clients treat absent
  `deck_ids` / `decks` as "unknown", absent `kanji_examples` as empty. No DB change, no sync op,
  no mutation: the cross-check table only gains readers on the `deck_item` row.
- Server cost: three statements per queue page for all of it (note decks, entry deck ids,
  example candidates pre-limited to the picker's window in SQL), none per item.
- Rejected: a `GET /notes/{id}/decks` + `GET /dict/entries/{seq}/decks` pair (N+1 from every
  list); full `Deck` objects in `QueueItem.decks` (settings and counts nobody shows, and
  `item_count` is a per-deck subquery); letting each client call `listKanjiWords` and pick in
  TypeScript (CLAUDE.md: domain logic comes from the core); a `kanji_examples` field on `Kanji`
  (it would run the picker on every kanji read, and the overlay makes `Kanji` uncacheable).
- Worth a second look: (1) **smart decks are never listed**, although the queue may have drawn
  the card through one: membership there is a query evaluation per deck, and "is in deck" for a
  smart deck changes with every review; (2) **`DictEntry.deck_ids` counts `e:<seq>` items only**,
  so a note-keyed item (`n:<id>`) of a note that later gained the entry by a merge shows on the
  `Note` and the `QueueItem` but not on the entry; (3) **blacklisted/ignored words are dropped
  from the examples** even when they are the kanji's most frequent word, and a kanji whose only
  on/kun word ranks below the first 50 candidates shows none of that type.

## C-26 changes (additive): Keep going and the "New" badge (ADR-031)

- **`getReviewQueue?keep_going=true`** (boolean, default false; only `true`/`false`, else `422`
  `/keep_going` `invalid_value`): the core builder runs with `QueueLimits.keepGoing = true`,
  `keepGoingNewBatch = limit` (core `QueueBuilder` rule 6). Daily/session/per-deck limits are
  lifted, reviews due today come first, then up to about `limit` new cards in normal deck order;
  nothing not due before `day_ends_at` is returned. Stateless: not a setting, no op, nothing
  stored; the client repeats the flag on every fetch while the user keeps going. `counts` then
  describe the keep-going queue.
- **`ReviewQueue.more_available`** (boolean): true only when the request was a normal one, `items`
  is empty and the same request with `keep_going=true` would return items. Distinguishes "done for
  today, Keep going available" from "nothing left". Costs a second build only in the empty case.
- **`QueueItem.is_new`** (boolean): `card.state == 'new'` at build time (also on drill items);
  absent on older servers is read as `card.state == 'new'`.
- Cross-check: no new table, op or mutation; answers still go through `answerReview` →
  `review.logged`, so keep-going cards count against today's limits like any other.
- Rejected: a `scheduler.keep_going` setting or a per-day "extra new" op (state that must sync,
  expire at rollover and be undone; the user just wants "more until I stop"); a separate
  `/review/queue/more` endpoint (duplicates every filter); an integer `more_count` (needs the
  full uncapped build every time; a boolean needs it only when empty); study-ahead of tomorrow's
  reviews (changes FSRS intervals; out of scope).
- Worth a second look: (1) **reviews before new under keep going** instead of the rule-5
  interleave (the ticket's "due reviews first"; the normal mode is unchanged); (2) **per-deck
  review caps are lifted too**, not only `reviews_per_day` (otherwise "out" could still hide due
  reviews); (3) **the batch is `limit`** on the server and 10 on devices, and an add_kanji group
  may overshoot it so a large group never stalls keep going.
- Attempt 2 (architect pass): **scope is normative** — keep going is UI state for one visit to the
  review screen, cleared on leaving it, on a `study_day` change and on app restart; the normal
  Review entry and Today counts always use `keep_going=false` (so an SPA left open overnight never
  serves new cards past tomorrow's limit). `StatsSummary.more_available` (same core signal, no
  extra queue call for the Today page). `more_available` may mean only the session cap is reached
  ("done for now"); in keep-going mode `counts.new` is the batch.

## C-27 changes: deck entries become new cards in `getReviewQueue` (ADR-032)

- **`getReviewQueue` materializes.** The server passes deck entries without notes (core
  `PendingEntry`) to the builder; when the build draws some (`toMaterialize`, within the normal
  new-card budget or the keep-going batch) and the caller holds `review` or `write`, it mints, as
  the server device in one op transaction under the sync lock, `note.created` (`origin` `deck`,
  built-in vocab type, `fields` `{}`) + `card.created` per default kind (or the drawing deck's
  `card_kinds`), preceded by `auto_kanji` kanji notes if the deck's present `add_kanji` asks;
  then rebuilds (≤ 3 rounds) and answers. `x-emits-ops: [note.created, card.created]` — the only
  GET that emits (check_contract.py `GET_EMITS`). Idempotent per `entry_seq`: an entry a live note
  holds is skipped; a fetch that draws nothing emits nothing.
- **`ReviewQueue.to_materialize`** (integer, additive): drawn entries still without notes (0 for a
  caller that may review; the count for a `read`-only caller). `counts.new` and
  `StatsSummary.due.new` include them.
- **Pending entries** (contracts/core README "C-27"): `e:<seq>` of live enabled non-smart decks,
  entry in `ref.dict_entry`, no live note, **no user-deleted note** for the entry (deleting a
  deck word's note drops it for good), override not known/never_forget/blacklisted/ignored.
- **Devices** converge by SYNC.md 8.1 (the first holder of `entry_seq` survives; the other's cards
  and reviews merge into it); server test `DeckMaterializeTest`.
- Cross-check: `note` and `card` rows gain `getReviewQueue` (it was already listed for `card`).
- Rejected: a separate `POST` "fill"/materialize endpoint the client calls (a UI-driven workaround;
  every client would have to remember it, and a read of the queue would still show nothing);
  client-side minting on the web/CLI (re-implements the device path, needs dictionary data);
  materializing every pending entry up front (35k notes for decks the user may never reach).
- Worth a second look: (1) **a GET that emits ops** (scope `read` is enough to call it; only
  `review`/`write` callers materialize); (2) **user-deleted notes block re-materialization**
  (otherwise a deleted deck word comes back on the next fetch); (3) **kanji notes are created
  for a deck with a present `add_kanji`** (SCHEMA.md 6.2 says adding a vocab card creates them).

- **C-27 review (attempt 2).** `PendingEntry.expressionKanji` / `newKanjiCards` (core, additive):
  the builder draws an entry as the add_kanji group its card will be, so an entry that would not
  fit is never created (no unshown notes from polling). `card_kinds` `[]` or invalid falls back to
  the default kinds; kinds with an empty front are skipped; an entry left with no kind is never
  passed; a user-deleted kanji note is never re-created; the server repeats a materialize round
  only if the last round's notes reached `items`. The pending-entry query (35k deck items, five
  decks) loads in ~35 ms warm using `deck_item_order_idx` and `note_live_entry_uq`: no new index.

## SHELL-C1 (additive): Android web shell contract (ADR-036)

New directory `contracts/api/shell/`; nothing in `openapi.yaml`, `contracts/sync`, `contracts/db` or
`contracts/core` changed, and none of the three existing generated clients changed. Normative text:
`shell/SHELL.md`.

**What was decided**

- **Bridge** (`shell/bridge.schema.json`, 28 valid and 43 invalid examples): ten message types over an
  origin-restricted `WebMessageListener`; one `res` per `req`, duplicate ids dropped, replies in
  completion order, a reload starts a new page life, invalid messages are dropped without a reply.
  Request bodies: JSON, at most 4 MiB; proxied responses: text, at most 8 MiB; otherwise
  `415 shell_unsupported`.
- **Classification** (`shell/ops.json`): every operationId of `openapi.yaml` exactly once — 38 `local`,
  7 `local_or_proxy`, 53 `proxy`, 25 `hidden`. `check.sh` fails on a missing or invented id, on another
  class, and when an operation a device token cannot call is not `hidden`.
- **Local semantics**: rules L-1 … L-8 (local operations always answer from the replica; writes are ops
  minted by the paired device in one transaction; same statuses, codes and pointers as the server;
  idempotency by client id; device clock; deleted parents; dictionary fallback; rebuild) and P-1 … P-4
  (offline, forwarding with the device token, holding a proxied write until it is pulled, hidden).
- **Shell-only operations** (`shell/shell.yaml`, generated into `clients/typescript-shell`): 16 operations
  (18 since LOOP-C: display preferences)
  under `/shell/v1` for pairing, sync status, dictionary install, storage, notifications, push and the
  on-device AnkiConnect server. `Problem` is referenced from `openapi.yaml`, not copied.
- **Problem codes**: the four of ADR-036 (`shell_offline`, `shell_dict_missing`, `shell_rebuilding`,
  `shell_unsupported`) plus `shell_pending_ops` (409, unpair refused while changes are unsynced).
- **Events**: `hello`, `sync.changed`, `note.created` reuse the `Event` envelope with device values;
  `shell.connectivity`, `shell.sync` and `shell.dict` are new. No job or device events in shell mode.
- **Parity** (`shell/parity.schema.json`, 17 scenarios): inputs only. A seed log pushed by a second
  device, steps at fixed instants, optional `apply` steps (ops of the other device arriving mid-scenario),
  per-step `expect` and cross-step `invariants`, and a normalisation that renames minted ids, minted
  clocks and the minting device. 24 mandatory coverage tags are enforced by `check.sh`.
- **Partly local operations**: bulk card actions other than suspend, bury and flag, and creating a
  `list` deck (a server-only op plus a build job), are forwarded by the local handler; offline they
  answer `503 shell_offline`.
- **Token revoked mid-session**: local operations keep working and pending ops are kept; `ShellInfo`
  has `auth_required`, and pairing again replaces the token for the same device.

**Alternatives rejected**

- An `x-shell` extension on each operation of `openapi.yaml` instead of `ops.json`: a diff in the frozen
  file and a regeneration of three clients for a fact only the phone and the SPA read.
- Answering an invalid `req` with `400`: needs a trustworthy `id` from a message already known to be
  malformed; a correct SPA never sends one, so it is dropped and its timeout reports the bug.
- Base64 bodies on the bridge for binary uploads and exports: a device token cannot import, and exports
  belong to the web app; `415 shell_unsupported` keeps the bridge text-only.
- An unpair that wipes the replica: today's native unpair keeps data so the same install can pair again;
  a wipe is a separate, destructive action and is not offered.
- Harness-applied `settings` in parity scenarios: two ways to seed one table; the seed log carries the
  `settings.set` ops and `settings` is a checked index.
- One seed device that is also the device under test: seeded reviews and locally minted reviews would
  carry the same `device_id`, so the minting device could not be normalised.
- A parity scenario per `local` operation in this ticket: only the scenarios ADR-036 makes mandatory
  (plus empty replica and deleted parent) are here; the L tickets add theirs by change request.

**Three choices most worth a second look**

1. **A wrong device clock is reported as `422 validation_failed` at `/reviewed_at` for every write**
   (`before_floor`, `before_card` or `in_future`; SHELL.md 4.1 rule L-3), also for a request that has no
   `reviewed_at`. ADR-036 asks for the documented `422` codes; the cost is a pointer to a property the
   request did not send. The alternative is a sixth shell code such as `409 shell_clock_wrong`, which is
   clearer for the SPA but is not a server answer.
2. **Dictionary data is one per-entry lookup: installed package, then the gap-fill sidecar cache,
   then (dictionary reads and note creation only, online only) the server** (SHELL.md rule L-7, 10.7).
   Reviews never use the network: a card whose entry the phone lacks is served with `entry: null`, and
   a deck entry it lacks is not materialized or counted until gap fill has cached it, so phone and web
   counts can differ for a while on a `top60k` install. Creating a note by `entry_seq` fetches the entry
   and still mints locally; it is `503 shell_dict_missing` only when cache and server both fail. An
   installed package is authoritative (a miss is `404` / `422`) only when its scope is `all` and it is
   not older than the last dictionary release the server reported; a server `404` is remembered only
   until the server's release changes. Online, a search that the package cannot answer with authority
   is forwarded and its per-user fields recomputed locally, so list words outside `top60k` can be mined.
3. **The parity `expect` values were written from the contract text, not from a running server.** They
   are deliberately narrow (status, problem code, a few counts and lengths), but SHELL-P1 may find one
   that the server does not produce — most likely in `queue-materialize-count`, `queue-learning-wait`
   or the `reviewed_at` check order. That is then a `CONTRACT_CHANGE_REQUEST` against the scenario or a
   server bug, never something to edit in the golden.

Also worth knowing: a request that waits more than 20 s for the startup gate is answered
`503 shell_rebuilding` rather than left to the SPA's 30 s timeout (SHELL.md 2.5); pairing an install
that still holds data with a *different* server is not prevented (SHELL.md 13, same gap as today).

**Verifying**: `contracts/api/check.sh` lints `shell/shell.yaml` and runs `shell/check_shell.py` (named
checks listed at the top of that file, including negative self-tests).

## LOOP-C changes (additive): the review loop on the wire and shell display prefs (ADR-037)

**What was decided**

- **`ReviewQueue.loop`** (schema `ReviewLoop`, all members required): `phase` (`reviewing`,
  `goal_reached`, `done_for_today`, `waiting`, `empty`; open for readers), `keep_going`, `ready`
  {learning, review, total}, `later_today` {count, next_due_at}, `backlog` {reviews, beyond_limit},
  `reviews` {today, limit|null}, `new` {today, goal, available}. It is the wire form of the core's
  `LoopState`. Not in `required` only because "absent" means "server older than ADR-037". Computed
  for the request's `deck_id`; unaffected by `kinds`, `limit` and (except `phase`, `keep_going`) by
  `keep_going`.
- **Deprecated, still sent, meaning frozen**: `ReviewQueue.counts` (`new` = the day's remaining new
  cards under the goal and deck limits, not bounded by `limit`; `learning` = ready + later, the old over-count; `review` =
  `loop.ready.review`), `more_available`, `next_learning_due_at`, `StatsSummary.due`,
  `StatsSummary.more_available`. No new `StatsSummary` member: Home calls `getReviewQueue?limit=1`.
- **Order**: ready learning, then ready reviews and new cards in the proportional mix (core rule 5),
  in both modes; keep going: ready learning, all due reviews, then new cards.
- **Client rules C1-C5** in the `getReviewQueue` description: refetch when a learning card may have
  come due; keep going persisted per client for the study day (`localStorage["kotobaya.keepGoingDay"]`
  on web and in the shell SPA); what to display (the headline is always `loop.ready.total`, the
  backlog only in its own line, in both modes); empty `items` rendered from `phase` only; C-27
  retries.
- **`scheduler.new_per_session`** is ignored (SCHEMA.md 6); `updateSettings` still accepts it.
- **Shell**: `LOCAL_API_VERSION` 2 (SHELL.md 7.2: a field a new SPA requires was added);
  `getDisplayPrefs` / `updateDisplayPrefs` (`GET`/`PATCH /shell/v1/display`, `ShellDisplay`,
  `ShellDisplayPatch`, capability `display`): device-local "lock to portrait", usable unpaired. Parity: tag
  `queue.session_cap_more_available` and its scenario retired; four new scenarios with the five
  `queue.loop_*` tags, each pinning `loop.phase` and the numbers (`check_shell.py` enforces both);
  three existing scenarios gained `loop` expectations. Goldens are re-recorded by LOOP-SRV.
- Cross-check: no table, no op, no `SCHEDULER_VERSION` change. The queue is a pure view; the only ops
  `getReviewQueue` emits are still C-27's `note.created` / `card.created`, and only for the served
  build's entries.

**Alternatives rejected**

- A closed `phase` enum (a sixth phase would break generated clients' exhaustive switches at
  runtime): readers treat unknown values by rule C4.
- `loop` on `StatsSummary` as well: two sources for Home; one extra cheap queue call instead.
- Removing the deprecated members or changing their meaning to the new numbers: the old CLI daemon
  and an older web bundle read them.
- Timing rule C1/C4 from wall-clock equality: the client's clock may differ; one timer at
  `next_due_at` + 1 s with a 3 s minimum gap, and the next response reschedules.
- Reviews strictly before new cards in the normal session (the design's first reading; the user
  called it a regression on 2026-11-17).

**Three choices most worth a second look**

1. **The proportional mix** (core rule 5): a stride on the remaining ready reviews and the new
   cards still introducible today; never two new cards in a row while a review is left. With more
   new cards than reviews it alternates and the surplus new cards come last.
2. **Keep going is per client and persisted for the study day**: pressing it on the phone does not
   switch web or the widget; all numbers are the same everywhere regardless.
3. **`done_for_today` vs `goal_reached` follows the `reviews_unlimited` setting**, not which limit
   was hit: with the cap on, reaching only the new goal with no backlog is still `done_for_today`.

Known follow-ups outside this ticket's paths: `fixtures/shell-parity/golden/queue-session-cap-more-available.json`
is orphaned (LOOP-SRV deletes it when re-recording); `web` reads `counts` / `more_available` until LOOP-WEB.

## C-28 changes (ADR-038..042; all additive)

What was decided:
- `ReviewQueue.loop.until_stop` (integer): answers left until the stop screen; the review progress
  bar is `done / (done + until_stop)`. Not in `required` only so that old servers stay valid;
  clients fall back to `ready.total` when it is absent.
- Keep going lasts one review visit (client rule C2 rewritten): held in memory, never stored;
  a new visit, a restart, a day change or 10 idle minutes end it.
- Kanji first: `getReviewQueue` now also creates the missing kanji notes of the words it draws
  (same op types as before, `note.created` with origin `auto_kanji` and `card.created`), for every
  deck and for words in no deck. `to_materialize` counts them too. `DeckSettings.add_kanji` is
  deprecated: stored and returned, not read.
- `QueueItem.front_furigana` (boolean) and `QueueItem.kanji_parts[]` (`KanjiPart`: literal,
  keyword, meanings, on, kun, `used_reading` = `{type, reading, kana}` or null).
- `Deck.progress` = `{learned, total, percent}` or null (smart and deleted decks).
- `getDeckCoverage` **stays proxied** in the Android shell. The "N of M learned" line comes from
  `Deck.progress` and works offline; the raw coverage of media decks needs the server.
- No new operation, table or op type; the table, op and operation cross-check is unchanged.

Alternatives rejected:
- `used_reading` as a plain string. An object also carries the kana the word really uses (がっ for
  ガク), which the tile shows, and says whether it is an on or a kun reading.
- Making `getDeckCoverage` local. It needs per-segment data, the difficulty curve and the token
  classes, none of which is on the phone; it would be a second coverage implementation.
- A new endpoint for progress. It belongs to the deck and is needed wherever a deck is listed.
- Bumping the shell's `LOCAL_API_VERSION`. Every new field has a fallback, so an older shell with
  a newer web bundle still works (no bar change, no tiles, no progress line).

Three choices most worth a second look:
1. `until_stop` includes learning cards that are due later today, so at the "done for today"
   screen it can be above zero (those cards will come back). It follows ADR-038's formula.
2. With keep going, `until_stop` depends on the request's `limit` (the batch). It is the only
   loop number that does.
3. The older parity scenarios were moved to kana-only words so that kanji-first does not change
   what they test. Kanji behaviour is tested only by the six new scenarios. The ADR-042 scenario
   uses 寿司, 昨日, 鱈 and 鱈子 because 海老, 林檎, 薔薇 and 今日 are not in the fixture dictionary.

## C-28f changes (ADR-046): descriptions only
- No operation, schema, field or status code changes. `headword` descriptions (`DictEntry`,
  `DictEntrySummary`, `DictHeadword`, `listDictHeadwords`) now say: the first reading also when
  every kanji form is tagged `rK` / `sK` (`シンガポール`, not `新嘉坡`); `getReviewQueue` and
  `QueueItem.front_furigana` restate the amended rule (a tagged word requires its jōyō kanji only;
  a kana word never has a furigana front). Generated clients change in doc comments only.
- New parity scenario `queue-kanji-rare-forms` (tags `queue.kanji_kana_word`,
  `queue.kanji_tagged_joyo_only`, both mandatory). Its golden is recorded by CORE-28d once the
  fixture dictionary loaders apply SCHEMA.md 4.5 "Headword"; no existing golden changes.

## C-29 changes (ADR-047): automatic word audio

The whole design in one place; the normative text is SCHEMA.md 6.6 (rules for server, worker,
device), `openapi.yaml` (`resolveSpeech`, `QueueItem.word_audio`), the core contract
(`render/WordAudio.kt`, "Word audio" on `CardRenderer`) and `docs/REVIEW-LOOP.md` section 14
(what the review screen does).

What was decided, in plain words:
- **Audio is looked up, never written into notes.** A vocab card's sound is the note's own audio
  if it has one, else speech generated from the word's reading. Nothing edits a note, so there is
  no sync op, nothing to merge and nothing that can be lost: delete every generated file and the
  system makes them again.
- **One rule decides which sound a card has**, in the shared core (`WordAudioResolver`), used by
  the server, the phone's local API and the phone's download pass.
- **Generated audio is identified by the pair (voice, text)**, e.g. (`3`, `たべる`). The server
  keeps a small index table `tts_audio` (pair to audio file); the phone keeps a copy of the pairs
  it asked about. Two words with the same reading share one file.
- **`QueueItem.word_audio`** (new, optional): `{source: note|tts, blob_hash, text, voice}` or null.
  `blob_hash` null = not generated yet.
- **`POST /tts/resolve` (`resolveSpeech`)** (new): ask for up to 200 pairs or notes; the answer
  says ready / pending / failed / none, and missing ones are queued. The review screen asks for
  the card on screen ("urgent": jumps the job queue); the phone asks for its prefetch window.
  It needs the `write` scope, like `POST /tts`, because it makes the server generate and store
  audio for a text the caller chose. At most 340 speech jobs can ever be waiting (300 ordinary
  plus 40 reserved for urgent ones), whoever asks.
  `POST /tts` and the `tts` job are reused unchanged in shape; the worker additionally records
  each result in the index table.
- **The server generates ahead**: for the cards it serves, and every 10 minutes for cards due
  within `audio.prefetch_days` (default 7), at most 300 jobs waiting at a time.
- **Offline**: the phone works out which sounds it needs (same window plus today's new cards),
  resolves them while online, downloads the files into its existing blob cache, and its local
  queue then hands out the same `word_audio` the server would.
- **Card layout**: the speaker button sits inside the expression line on the answer side; on
  listening cards it is the front. The renderer tells the app which sound to autoplay.
- **Settings**: `review.autoplay_audio` (bool, default true), `tts.voice` (digits, default `"3"`;
  it existed only as a mention before), `audio.prefetch_days` now 0-365.
- Cross-check: `tts_audio` is a system table, not learning data, so it has no sync op by design;
  its operations are `resolveSpeech`, `synthesizeSpeech` (through the job) and `getReviewQueue` /
  `createDrillSession` (read). `settings` keeps `settings.set` / `listSettings`, `updateSettings`.
  No sync op, op schema or learning-data table changed.

Alternatives rejected:
- **Writing `[sound:…]` into the note's `audio` field** (by the server or a client). It would be a
  hidden note edit for every card: thousands of ops, a merge rule for "generated versus the
  user's own audio", and a voice change would mean rewriting every note.
- **A hash as the identity** (sha of engine, voice and text, as the ticket suggested). The pair
  itself is short, readable and needs no SHA-256 in the shared core (it has none today); the
  worker's job dedupe key keeps its existing hash. An engine other than VOICEVOX would later get
  voices that are not plain digits, so the pair still tells them apart.
- **A blocking "GET the audio, generate if missing" endpoint.** It would hold a request open on
  the worker queue and VOICEVOX; with ask-again the server stays stateless and reviewing never
  waits.
- **A separate manifest endpoint for the phone.** The phone already knows its own due cards
  (also ones it created offline that the server has not seen), so it asks for exactly those pairs
  with the same `resolveSpeech`.
- **The button carrying a text key instead of a file hash.** Every host already plays
  `data-kb-audio=<hash>` and the phone already intercepts blob URLs; one mechanism stays.

Three choices most worth a second look:
1. **VOICEVOX hears kana only** (`たべる`, as ADR-047 says: "the word's reading"). The reading is
   always right, but the pitch accent is VOICEVOX's guess for that kana, and 橋 and 箸 sound the
   same. Sending the kanji instead risks wrong readings. A later step can send the accent from
   the dictionary's pitch data; it only changes the text of the pair, nothing else here.
2. **Only vocab notes get generated audio.** Phrase and sentence notes keep whatever audio they
   have. Also unchanged: a `listening` card is still only created for a note that has its own
   audio. Saying "yes, generate for those too" is a small follow-up.
3. **Autoplay does not repeat on a listening card's answer.** The sound plays with the question;
   on reveal it is silent unless the user taps or presses P. Everything else plays on reveal.

Also changed in passing: `audio.prefetch_days` above 365 is now rejected by `updateSettings`
(stored larger values fall back to 7), and a non-blank `audio` field without any `[sound:]`
(left-over `<br>`) is no longer shown on built-in vocab cards.

Implementation tickets (each reads SCHEMA.md 6.6 first):

| Ticket | Module | Does | Needs |
|---|---|---|---|
| CORE-29 | core | `DefaultWordAudioResolver` (jvm + js tests from the table in `WordAudio.kt`); renderer: block W, `{{audio:word}}`, `RenderedCard.wordAudio` / `autoplayAudio`, `JsRenderInput.wordAudio` and the two `JsRenderedCard` members mapped; built-in vocab templates; update render goldens | C-29 |
| WRK-29 | worker | `tts` job: cache lookup and upsert on `app.tts_audio` (replaces the job-history lookup), voice / length validation, tests for run-twice idempotency | C-29 (V4 migration) |
| SRV-29 | server | V4 migration; `word_audio` on queue and drill items plus urgent enqueue; `resolveSpeech`; the sweep; settings validation of the three keys; `synthesizeSpeech` default voice via core; re-record parity goldens that call `getReviewQueue` / `createDrillSession`; scope test for `resolveSpeech` covering both request shapes (a read-only token gets `403 insufficient_scope` for `text` items and for `note_id`-only requests, and no job row appears; a `write` token succeeds) and a test that 340 queued jobs stop urgent enqueues | C-29, CORE-29 |
| WEB-29 | web + `assets/card/` | pass `word_audio.blob_hash` to the renderer; REVIEW-LOOP 14 rules (autoplay, P, tap, resolve-and-rerender); settings toggle (i18n); CSS for `.kb-expression .kb-audio`; layout test + screenshot | C-29, CORE-29 (SRV-29 to see it work) |
| AND-29 | android | device table `tts_audio` (schema migration); local `word_audio` (no dead button offline: rule 6); the prefetch pass (today's new cards first); local settings validation; parity goldens of SRV-29 | C-29, CORE-29, SRV-29 |

## C-30 changes (ADR-049; additive)

What was decided:
- `loop.phase` order: `waiting` is now decided before `goal_reached` / `done_for_today`. Whenever
  nothing can be served and a learning card falls due before `day_ends_at`, the phase is `waiting`,
  with and without `keep_going`. The stop screens (`goal_reached`, `done_for_today`, `empty`)
  appear only with `ready.total` 0, `later_today.count` 0 and `until_stop` 0. No enum value was
  added, so older clients render the new behaviour with their existing waiting screen.
- `ReviewQueue.loop.keep_going_available` (boolean): show a Keep going button now. True at
  `goal_reached` / `done_for_today` and during `waiting` when new cards or a backlog remain.
  Absent (older server): clients use `phase` in (`goal_reached`, `done_for_today`).
- `ReviewQueue.loop.until_stop_parts` = `{learning, later, review, new}`: `until_stop` split into
  the numbers the review header and Home show; the sum is always `until_stop`. Absent (older
  server): clients show `ready.learning`, `later_today.count`, `ready.review`.
- Client rules: C3 (header and Home show the four parts; single-number surfaces keep
  `ready.total`), C4 (the waiting screen resumes by itself and may offer Keep going), new C6 (every
  surface that stays on screen refetches once at `later_today.next_due_at` in every phase, not only
  in `waiting`; at a stop screen that member is null).
- Deprecated members keep their frozen meaning. `more_available` can now be true in phase
  `waiting`; that was already its definition ("nothing served and keep going has more").
- No new operation, table, op type or setting; the cross-check tables are unchanged. No
  `LOCAL_API_VERSION` bump: both members have a fallback.
- Parity: new scenario `queue-loop-wait-before-stop` (tag `queue.loop_waits_before_stop`, the
  reported case scaled down); `queue-until-stop` step 8 is `waiting` instead of `done_for_today`;
  every whole-`/loop` expectation gained the two members (19 expectations in 5 scenarios). All
  hand-evaluated; SRV-30 re-records the goldens.

Alternatives rejected:
- A new phase value for "goal reached but waiting". Older readers map an unknown phase with empty
  `items` to `empty`, the dead end this ticket removes.
- Putting the new-cards-left number into `loop.new`. That object is the same with and without
  keep going (asserted by the parity invariants); the parts are not.
- A `StatsSummary` field for Home. Home already calls `getReviewQueue?limit=1`.

Three choices most worth a second look:
1. `until_stop_parts.review` is `backlog.reviews` when `keep_going=true` and `ready.review`
   otherwise, so that the parts always sum to `until_stop`.
2. Rule C6 makes Home and an open review screen fetch once per learning step that ends (at most
   one fetch per 3 s). It is what keeps Home from showing a stale number.
3. An older web bundle on a new server shows its existing waiting screen without a Keep going
   button during a wait (the button appears at the stop screen as before). Accepted for the
   minutes until the bundle is updated; server and web deploy from one pin.
