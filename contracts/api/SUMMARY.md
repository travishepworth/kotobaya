# API contract — summary for the human

Files: `openapi.yaml` (the contract, OpenAPI 3.1, 114 operations), `redocly.yaml` (lint rules),
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
  returns the existing row and does nothing.
- **Duplicates are never created on purpose over REST.** Creating a note for a word that already
  has one returns an error naming the existing note (or the existing note itself, on request).
  The server-side merge remains for duplicates created offline.
- **Merged notes and cards answer with their survivor**; deleted rows answer `410` unless asked for.
- **Errors** are RFC 9457 problem documents with a stable `code`; the full table is at the top of
  the spec. **Lists** use opaque cursors (`limit` ≤ 500).
- **Sync endpoints define only the HTTP wrapper.** Push sends `{"batch": …}` and gets one result
  per op (`applied`, `duplicate`, `rejected`); pull returns `{"batch": …, "seqs": […],
  "next_since", "has_more"}`. The batch itself is whatever `contracts/sync/batch.schema.json`
  defines.
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
   vs. web" except as "phone vs. everything else", and "undo my last review" on the web means the
   last review made through the API by anyone (it never touches phone reviews). If per-browser
   attribution matters, the change is: login registers a web device and the server keeps a
   separate clock per such device.
2. **The split with the sync contract is assumed, not yet seen.** T005 was written in parallel.
   This spec assumes `batch.schema.json` describes just the ordered batch of ops, and that the
   push result list, cursor fields and limits belong here. If T005 also defined a response
   envelope, or a batch that cannot be embedded under a `batch` property, the two need
   reconciling before any client or server work starts. The two sync examples here carry a
   placeholder batch (`{"ops": []}`) that will fail the lint once the real schema lands if its
   shape differs; `check.sh` currently substitutes a permissive stand-in when the file is absent.
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
- `GET /devices/{id}` and `PATCH /devices/{id}` let a device token act on its own device without
  `admin`. That exception is in the description only; the machine-readable scope says `admin`.

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
| `POST /cards/bulk` | `bulkCards` |
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
| Devices | `listDevices`, `getDevice`, `updateDevice`, `revokeDevice`, `createPairing`, `pairDevice` |
| Tokens | `listTokens`, `createToken`, `getToken`, `revokeToken` |
| Settings | `listSettings`, `updateSettings` |

Everything else (additions):

| Area | operationIds |
|---|---|
| Meta | `getHealth`, `getMeta` |
| Presets | `listPresets`, `createPreset`, `getPreset`, `updatePreset`, `deletePreset`, `optimizePreset` |
| Note types | `listNoteTypes`, `createNoteType`, `getNoteType`, `updateNoteType`, `deleteNoteType` |
| Dictionary | `listEntryExamples`, `getDictRelease`, `refreshDictionary` |
| Notes and cards | `getNote`, `createCard`, `getCard`, `updateCard`, `deleteCard`, `listCardReviews` |
| Decks | `listDeckLists`, `getDeck`, `deleteDeck`, `addDeckItems`, `putDeckItem`, `removeDeckItem` |
| Media | `listMedia`, `getMedia`, `updateMedia`, `deleteMedia`, `reparseMedia`, `importJpdbHistory`, `listSentences`, `getSentence` |
| Words | `getWordStatus`, `clearWordStatus`, `listHotKanji`, `listConfusables`, `setConfusable` |
| Grammar | `getGrammarPoint`, `updateGrammarPoint`, `deleteGrammarPoint`, `putGrammarSpan`, `removeGrammarSpan` |
| Stats | `getStatsSummary`, `getStatsCalibration` |
| Parsing | `listParseOverrides`, `putParseOverride`, `deleteParseOverride` |
| Jobs | `cancelJob`, `synthesizeSpeech` |
| Blobs | `queryBlobs`, `getBlob`, `putBlob` |

## Cross-check: table ↔ ops ↔ API operations

Op names are those of `contracts/db/SCHEMA.md` §4. "(job)" = emitted by a server job the
operation starts; "(consequence)" = emitted by the op-apply path. Every op also flows through
`syncPush` / `syncPull`.

| Table | Ops | API operations |
|---|---|---|
| `note_type` | `note_type.created/updated/deleted` | `listNoteTypes`, `getNoteType`, `createNoteType`, `updateNoteType`, `deleteNoteType` |
| `note` | `note.created/updated/deleted`; `note.merged` (consequence) | `listNotes`, `getNote`, `createNote`, `updateNote`, `deleteNote`, `bulkCards`, `ankiConnect`, `importJpdbHistory` |
| `card` | `card.created/suspended/unsuspended/buried/unburied/flag_set/preset_set/deleted`; `card.merged` (consequence) | `listCards`, `getCard`, `createCard`, `updateCard`, `deleteCard`, `bulkCards`, `createNote`, `getReviewQueue`, `listLeeches` |
| `review_log` | `review.logged`, `review.undone` | `answerReview`, `undoReview`, `bulkCards`, `listCardReviews`, `importJpdbHistory`, `getStatsHeatmap`, `getStatsRetention`, `getStatsCalibration` |
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
response body and every parameter has an example, and the tables above cover every operationId
and every learning-data table. Needs node and python3; network on first run.
