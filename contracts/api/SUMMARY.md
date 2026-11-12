# API contract — summary for the human

Files: `openapi.yaml` (the contract, OpenAPI 3.1, 117 operations), `redocly.yaml` (lint rules),
`check_contract.py` + `check.sh` (verification, see the end).

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

- **Snapshot archive formats** (media content, deck items, dictionary builds). This spec only
  says they are blobs fetched by hash. Someone must define the file format before P1.
- **Pairing codes and login rate limits have no table.** The spec allows in-memory state
  (restart invalidates outstanding codes). Fine for one API replica; needs an additive migration
  for more.
- **Who produces `confusable.suggested`** and when. No job kind or endpoint triggers it yet.
- **The jpdb "unmapped vocabulary" review queue.** Unmapped items are returned in job results
  (nothing is dropped silently), but there is no endpoint to resolve them one by one; the
  database contract deferred the table.
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
| Dictionary | `listEntryExamples`, `getDictRelease`, `refreshDictionary` |
| Notes and cards | `getNote`, `bulkScheduleCards`, `createCard`, `getCard`, `updateCard`, `deleteCard`, `listCardReviews` |
| Decks | `listDeckLists`, `getDeck`, `deleteDeck`, `addDeckItems`, `putDeckItem`, `removeDeckItem` |
| Media | `listMedia`, `getMedia`, `updateMedia`, `deleteMedia`, `reparseMedia`, `importJpdbHistory`, `listSentences`, `getSentence` |
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
| `deck_item` | `deck.item_put`, `deck.item_removed`; snapshot via `deck.content_published` (job) | `listDeckItems`, `addDeckItems`, `putDeckItem`, `removeDeckItem`, `createNote`, `bulkCards`, `getDeckCoverage` |
| `confusable` | `confusable.confirmed/rejected`; `confusable.suggested` (server; trigger not defined) | `listWordConfusables`, `listConfusables`, `setConfusable` |
| `grammar_point` | `grammar.created/updated/deleted` | `listGrammarPoints`, `getGrammarPoint`, `createGrammarPoint`, `updateGrammarPoint`, `deleteGrammarPoint` |
| `sentence_grammar_span` | `grammar.span_put`, `grammar.span_removed` | `putGrammarSpan`, `removeGrammarSpan`, `listGrammarExamples`, `testGrammarRules` |
| `settings` | `settings.set` | `listSettings`, `updateSettings` |
| `fsrs_params` | `fsrs_params.created/updated/deleted`; `fsrs_params.published` (job) | `listPresets`, `getPreset`, `createPreset`, `updatePreset`, `deletePreset`, `optimizePreset` |
| `media` | `media.updated/deleted`; `media.created`, `media.content_published` (job) | `listMedia`, `getMedia`, `updateMedia`, `deleteMedia`, `importMedia`, `importJpdbDeck`, `reparseMedia` |
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
