# app.kotobaya.client - Kotlin client library for Kotobaya API

Versioned REST/JSON interface at `/api/v1`. The web app uses nothing else; the Android app uses
`sync`, `blobs`, `dict/release`, `devices/pair`, `devices/self` and whatever conveniences it likes.

Implementers: this file is the contract. If something you need is missing or wrong, do not work
around it; return `CONTRACT_CHANGE_REQUEST`. Additive changes stay in v1; breaking changes go
to `/api/v2`.

## 1. Conventions

- **JSON.** Request and response bodies are `application/json; charset=utf-8` unless stated.
  Property names are `snake_case` and equal the column names of `contracts/db/V1__init.sql`
  wherever a property mirrors a column; the column `COMMENT` is then the definition.
- **Unknown properties.** Servers reject unknown request properties and unknown query
  parameters with `422 validation_failed`. Clients MUST ignore unknown response properties and
  MUST treat unknown enum values in responses as \"other\" (v1 may add both).
- **Absent vs null.** In `PATCH` bodies an absent property means \"leave unchanged\"; `null` means
  \"set to NULL\" where the property is nullable and is a validation error otherwise.
  Responses always include every documented property; nullable ones are `null`, never absent,
  unless the property is documented as \"omitted when ...\".
- **Ids.** Row ids are UUIDv7 in canonical lowercase `8-4-4-4-12` text. Uppercase ids in
  requests are rejected with `422`. \"Entry\" always means a JMdict sequence number (`seq`,
  JSON integer).
- **Time.** Instants are RFC 3339 UTC strings with millisecond precision and a `Z` suffix,
  e.g. `2026-11-12T09:30:00.000Z`. Servers accept any RFC 3339 offset on input and normalise
  to UTC. A \"study day\" is a local date `YYYY-MM-DD` computed from the settings
  `scheduler.timezone` and `scheduler.day_rollover_hour`: study day D covers local time
  `D rollover:00` (inclusive) to `D+1 rollover:00` (exclusive).
- **Text offsets** into sentence text are UTF-16 code units, half-open `[start, end)`.
- **Blobs** (audio, images, uploads, snapshots) are addressed by the lowercase hex SHA-256 of
  their bytes and transferred through `/blobs/{hash}`, never inline in JSON.

## 2. Authentication and scopes

Three credential kinds, all rows of `app.api_token` (stored hashed):

| Kind | How obtained | Sent as | Scopes |
|---|---|---|---|
| `session` | `POST /auth/login` | cookie `kotobaya_session` (HTTP-only) | all five |
| `device` | `POST /devices/pair` | `Authorization: Bearer kbd_...` | `read`, `write`, `review` |
| `personal` | `POST /tokens` | `Authorization: Bearer kbp_...` | chosen at creation |

Token strings are `<3-letter kind prefix>_<40 characters of [A-Za-z0-9]>` (`kbs_`, `kbd_`,
`kbp_`), generated from a CSPRNG. `token_hash` = lowercase hex SHA-256 of the whole string;
`token_prefix` = its first 8 characters. A token is valid iff `revoked_at IS NULL` and
(`expires_at IS NULL` or `expires_at > now()`) and, when it has a `device_id`, that device is
not revoked. Validity is checked on every request (no caching longer than 5 seconds), so
revocation takes effect at once.

Scopes are independent (no scope implies another):

| Scope | Grants |
|---|---|
| `read` | Every `GET` of learning, media and reference data; `POST /parse`; `GET /events`; `GET /sync/pull`; exports. |
| `write` | Create, change, delete notes, cards, decks, grammar, note types, presets, settings, word status, parse overrides, media metadata; upload blobs; `POST /sync/push` (non-review ops). |
| `review` | `POST /review/answer`, `POST /review/undo`, `POST /drill/session`, `POST /cards/bulk/schedule`; `review.*` ops inside `POST /sync/push` (per op; without it the op is rejected with `insufficient_scope`). |
| `import` | Start and cancel import, parse, dictionary and optimizer jobs; upload blobs. |
| `admin` | Devices, pairing, tokens (a device's own row: `/devices/self` with `read`/`write`). |

Each operation lists the scope it needs in its `security` requirement: the array after the
scheme name. When two alternatives are listed for one scheme the caller needs either. A request
with no or an invalid credential gets `401 unauthenticated`; a valid credential lacking the
scope gets `403 insufficient_scope`. Operations with `security: []` are public.

There are exactly two places where who may call is not just \"scheme + scope\", and both are
declared in the operation, not only in prose:
- `GET/PATCH /devices/self` (`read` / `write`, bearer only): the token must be bound to a
  device, else `403 device_required`. A device never needs `admin` to manage its own row;
  `/devices/{device_id}` is admin only.
- `POST /ankiconnect` (`x-auth: body-key`): the token may instead be sent in the JSON body
  property `key`; the empty `{}` alternative in its `security` only signals that no header is
  needed. The scope for `key` is `x-body-key-scope`.

**CSRF.** A request authenticated by the session cookie whose method is not `GET` or `HEAD`
MUST carry the header `X-Kotobaya-Csrf: 1`; otherwise `403 csrf_required`. The cookie is set
with `HttpOnly; Secure; SameSite=Strict; Path=/api`. The server sends no CORS headers (the web
app is served from the same origin). If both a cookie and an `Authorization` header are
present, the header wins and the cookie is ignored.

**Audit.** Every request is logged with `token.id` and `token.name` (structured server log;
there is no audit table or endpoint in v1). Ops record the token in `change.token_id`.

## 3. Writes are sync ops

Learning data is only ever changed by applying sync ops (`contracts/sync`). Every REST
operation that changes learning data is a convenience wrapper: the server builds the op(s)
named in the operation's `x-emits-ops`, applies them through the same op-apply path as
`POST /sync/push` in one transaction, and they then appear in `GET /sync/pull` for every
device. Op names are those of `contracts/db/SCHEMA.md` section 4; `contracts/sync` is
authoritative for names and payloads.

For an op the server builds on behalf of a REST call:

- `op_id` is a fresh UUIDv7; origin `device_id` is the **server device**
  `00000000-0000-7000-8000-000000000001` (also for session and device tokens: only one party
  ever generates HLCs for a given device id); `hlc` comes from the server's HLC clock;
  `change.token_id` is the calling token.
- A property absent from a `PATCH` body produces no register write. A property present with
  the value the row already has still produces a write (it bumps the register's clock).
- If a call changes nothing (documented per operation, e.g. deleting an already deleted
  note), no op is emitted and the response is still successful.
- **Never an invalid op (C-6).** If a request would make the server build an op that fails
  its schema or an op-level rule in `contracts/sync` - a tag containing ECMA-262 whitespace
  (e.g. U+3000 or U+00A0), U+0000 in any string (field values and open JSON objects
  included), an integer above its op maximum (`2147483647` for int4 columns) or an instant
  after `9999-12-31T23:59:59.999Z` - the call is `422 validation_failed` with a field error
  at that request property, code `invalid_value` (or `out_of_range` / `too_long` where the
  operation's own request bound fails first). Nothing is emitted; never a `500`.
  SYNC.md 2.1 \"Storable values\" and \"Input that would mint an invalid op\".
- Responses to mutating calls carry the header `Kotobaya-Seq`: the `change.seq` of the last op
  the call emitted. A client holding a local replica knows it has seen the effect once its
  pull cursor is at or past that value. The header is absent when no op was emitted.

**Idempotent creation.** Create bodies accept an optional client-generated `id` (UUIDv7).
Re-sending a create with an `id` that already exists returns the existing resource with
status `200` instead of `201` and emits nothing, whatever the rest of the body says. Clients
that may retry MUST send `id`. When `id` is omitted the server generates one. `POST /review/answer`
requires `id` (a duplicate review would corrupt scheduling); `POST /media/import` takes the same
optional idempotency id as `media_id`.

The existence check and the emit are **one transaction that takes advisory lock `7033001`
before the check** and holds it until commit (the lock `POST /sync/push` takes per op,
SYNC.md 5.2): check whether `id` exists; if not, mint, apply and log the op(s) - for
`createNote` the `note.created` and every `card.created` - then commit. Without the lock two
concurrent retries of the same create both pass the check; the second `note.created` is a
no-op on the row (SYNC.md 7.2) but its `card.created` ops carry fresh card ids, so the note
would get duplicate cards. The same holds for every other id-idempotent call
(`POST /review/answer`, `POST /review/undo`, `POST /media/import`), and a pushed op that
creates the same id races safely because it takes the same lock.

**Tombstones and merges.** Deleted learning rows are tombstones (`deleted_at` set). `GET` by
id of a deleted row returns `410 gone` unless `include_deleted=true`; lists omit deleted rows
unless `include_deleted=true`. A note or card that lost a merge (`merged_into_id` set) is
answered with its survivor: `GET`, `PATCH` and review calls addressed to the loser's id act on
the survivor and the response body is the survivor (its `id` differs from the requested id;
the response has `Content-Location` with the survivor's URL).

## 4. Pagination

List operations that take `limit` and `cursor` return `{\"items\": [...], \"next_cursor\": ...}`.
`next_cursor` is an opaque string, or `null` on the last page. Pass it back unchanged as
`cursor` together with the same other parameters; changing any other parameter while passing
a cursor is `422 validation_failed` (`pointer` `/cursor`). `limit` defaults to 50, maximum 500.
Order is stable and documented per operation; rows created while paging may or may not appear.

**Cursor semantics (keyset, no snapshot).** A cursor encodes the sort-key values and the `id`
of the last row returned; the next page is the rows strictly after that tuple in the current
order, evaluated against the data at the time of that request. The order is not snapshotted.
Consequently, while a client pages through a list sorted by a mutable key (`due_at`,
`updated_at`, `lapses`, ...):
- a row whose key changes so that it moves from after the cursor to before it is **skipped**;
- a row whose key changes so that it moves from before the cursor to after it is **returned
  again** (with its new values);
- a row deleted while paging disappears; one created may or may not appear.
Rows that do not change are returned exactly once. Clients MUST de-duplicate by `id` (last
occurrence wins). A client that needs an exact set (bulk actions, exports) uses an `id`-sorted
list where offered, `POST /cards/bulk` with `query`, or `GET /export`. Lists sorted by an
immutable key (`id`, `created_at`) have no skips or repeats.
An empty result is `{\"items\": [], \"next_cursor\": null}` with status `200`.

## 5. Errors

Every non-2xx response has the body `application/problem+json` (RFC 9457), schema `Problem`.
`code` is the stable machine-readable identifier; `type` is `urn:kotobaya:problem:<code>`.

| Status | `code` | When |
|---|---|---|
| 400 | `invalid_request` | Body is not valid JSON, wrong content type framing, malformed header; `POST /sync/push` body not valid against `PushRequestLenient`. |
| 401 | `unauthenticated` | No credential, or it is unknown, expired or revoked. |
| 401 | `invalid_credentials` | Wrong password or TOTP code (`POST /auth/login`). |
| 401 | `totp_required` | TOTP is enabled and `totp_code` was not sent. |
| 401 | `invalid_pairing_code` | Pairing code unknown, expired or already used. |
| 403 | `insufficient_scope` | Credential lacks the scope; `required_scope` names it. |
| 403 | `csrf_required` | Cookie-authenticated write without `X-Kotobaya-Csrf`. |
| 403 | `device_required` | `POST /sync/push` or `/devices/self` with a credential that has no `device_id`. |
| 404 | `not_found` | No such resource (also: id of another resource kind). |
| 409 | `already_exists` | Natural-key collision; `existing_id` names the holder. |
| 409 | `builtin_immutable` | Deleting a built-in note type or the Default preset, or removing a built-in field. |
| 409 | `in_use` | Deleting a note type that live notes use. |
| 409 | `deck_read_only` | Changing items or deleting a `media`/`list` deck through deck endpoints. |
| 409 | `not_applicable` | The resource kind does not support the operation (e.g. items of a smart deck). |
| 409 | `card_not_reviewable` | Card is suspended. |
| 409 | `job_not_cancellable` | Job already finished. |
| 410 | `gone` | Tombstoned row requested without `include_deleted=true`. |
| 413 | `payload_too_large` | Body over the limit in `GET /meta`. On `POST /sync/push` only for the whole batch (the client halves it); one op over `max_op_bytes` is a per-op `op_too_large` result instead. |
| 415 | `unsupported_media_type` | Wrong `Content-Type`. |
| 422 | `validation_failed` | Well-formed but invalid; `errors[]` lists each problem. |
| 422 | `invalid_query` | Card search string does not parse; `errors[0].position` is the UTF-16 offset. |
| 422 | `bulk_too_large` | Bulk selector matched more than 5000 cards. |
| 422 | `hash_mismatch` | Uploaded bytes do not hash to the `{hash}` in the path. |
| 429 | `rate_limited` | Too many login or pairing attempts; see `Retry-After`. |
| 500 | `internal` | Bug. Safe to retry idempotent calls. |
| 503 | `unavailable` | Database or object store unreachable. Retry with backoff. |

Check order: 401, then 403, then 404/410, then 415/400, then 422, then 409.

## 6. Events

`GET /events` is a server-sent events stream; see that operation. Event payload schemas are
`Event*` in components.


## Overview
This API client was generated by the [OpenAPI Generator](https://openapi-generator.tech) project.  By using the [openapi-spec](https://github.com/OAI/OpenAPI-Specification) from a remote server, you can easily generate an API client.

- API version: 1.0.0
- Package version: 
- Generator version: 7.27.0
- Build package: org.openapitools.codegen.languages.KotlinClientCodegen
For more information, please visit [https://kotobaya.invalid/api/docs](https://kotobaya.invalid/api/docs)

## Requires

* Kotlin 2.4.0
* Gradle 8.14

## Build

First, create the gradle wrapper script:

```
gradle wrapper
```

Then, run:

```
./gradlew check assemble
```

This runs all tests and packages the library.

## Features/Implementation Notes

* Supports JSON inputs/outputs, File inputs, and Form inputs.
* Supports collection formats for query parameters: csv, tsv, ssv, pipes.
* Some Kotlin and Java types are fully qualified to avoid conflicts with types defined in OpenAPI definitions.
* Implementation of ApiClient is intended to reduce method counts, specifically to benefit Android targets.

<a id="documentation-for-api-endpoints"></a>
## Documentation for API Endpoints

All URIs are relative to */api/v1*

| Class | Method | HTTP request | Description |
| ------------ | ------------- | ------------- | ------------- |
| *AuthApi* | [**getPrincipal**](docs/AuthApi.md#getprincipal) | **GET** /auth/session | Who am I - the credential making this request. |
| *AuthApi* | [**login**](docs/AuthApi.md#login) | **POST** /auth/login | Password login; sets the session cookie. |
| *AuthApi* | [**logout**](docs/AuthApi.md#logout) | **POST** /auth/logout | Revoke the current session and clear the cookie. |
| *BlobsApi* | [**getBlob**](docs/BlobsApi.md#getblob) | **GET** /blobs/{hash} | Download a blob. |
| *BlobsApi* | [**putBlob**](docs/BlobsApi.md#putblob) | **PUT** /blobs/{hash} | Upload a blob under its SHA-256. |
| *BlobsApi* | [**queryBlobs**](docs/BlobsApi.md#queryblobs) | **POST** /blobs/query | Ask which of a set of blobs the server already has. |
| *CardsApi* | [**bulkCards**](docs/CardsApi.md#bulkcards) | **POST** /cards/bulk | Apply one action to many cards (suspend, tag, move, delete, ...). |
| *CardsApi* | [**bulkScheduleCards**](docs/CardsApi.md#bulkschedulecards) | **POST** /cards/bulk/schedule | Reschedule or reset many cards (manual review-log rows). |
| *CardsApi* | [**createCard**](docs/CardsApi.md#createcard) | **POST** /cards | Add an optional card kind to an existing note. |
| *CardsApi* | [**deleteCard**](docs/CardsApi.md#deletecard) | **DELETE** /cards/{card_id} | Delete one card (permanent tombstone); the note and its other cards stay. |
| *CardsApi* | [**getCard**](docs/CardsApi.md#getcard) | **GET** /cards/{card_id} | One card with its note summary. |
| *CardsApi* | [**listCardReviews**](docs/CardsApi.md#listcardreviews) | **GET** /cards/{card_id}/reviews | Review history of a card (its whole replay set plus drills and crams). |
| *CardsApi* | [**listCards**](docs/CardsApi.md#listcards) | **GET** /cards | Find cards with the card search language. |
| *CardsApi* | [**updateCard**](docs/CardsApi.md#updatecard) | **PATCH** /cards/{card_id} | Suspend, bury, flag or re-preset one card. |
| *CompatApi* | [**ankiConnect**](docs/CompatApi.md#ankiconnect) | **POST** /ankiconnect | AnkiConnect-compatible subset for Yomitan and other mining tools. |
| *DecksApi* | [**addDeckItems**](docs/DecksApi.md#adddeckitems) | **POST** /decks/{deck_id}/items | Add several items to a user deck. |
| *DecksApi* | [**createDeck**](docs/DecksApi.md#createdeck) | **POST** /decks | Create a user or smart deck, or enable a generated list. |
| *DecksApi* | [**deleteDeck**](docs/DecksApi.md#deletedeck) | **DELETE** /decks/{deck_id} | Delete a user, smart or list deck. Notes and cards are untouched. |
| *DecksApi* | [**getDeck**](docs/DecksApi.md#getdeck) | **GET** /decks/{deck_id} | One deck. |
| *DecksApi* | [**getDeckCoverage**](docs/DecksApi.md#getdeckcoverage) | **GET** /decks/{deck_id}/coverage | Coverage of a deck's items by your word statuses. |
| *DecksApi* | [**listDeckItems**](docs/DecksApi.md#listdeckitems) | **GET** /decks/{deck_id}/items | Items of a deck with dictionary summary and word status. |
| *DecksApi* | [**listDeckLists**](docs/DecksApi.md#listdecklists) | **GET** /deck-lists | Generated lists (JLPT, frequency bands) that can be enabled as `list` decks. |
| *DecksApi* | [**listDecks**](docs/DecksApi.md#listdecks) | **GET** /decks | List decks. |
| *DecksApi* | [**putDeckItem**](docs/DecksApi.md#putdeckitem) | **PUT** /decks/{deck_id}/items/{item_key} | Add one item to a user deck, or move it. |
| *DecksApi* | [**removeDeckItem**](docs/DecksApi.md#removedeckitem) | **DELETE** /decks/{deck_id}/items/{item_key} | Remove one item from a user deck (reversible by putting it again). |
| *DecksApi* | [**updateDeck**](docs/DecksApi.md#updatedeck) | **PATCH** /decks/{deck_id} | Rename, reprioritise, enable/disable, set limits or settings. |
| *DevicesApi* | [**createPairing**](docs/DevicesApi.md#createpairing) | **POST** /devices/pairings | Create a one-time pairing code (shown as a QR code by the web app). |
| *DevicesApi* | [**getDevice**](docs/DevicesApi.md#getdevice) | **GET** /devices/{device_id} | One device. |
| *DevicesApi* | [**getOwnDevice**](docs/DevicesApi.md#getowndevice) | **GET** /devices/self | The device the calling token is bound to. |
| *DevicesApi* | [**listDevices**](docs/DevicesApi.md#listdevices) | **GET** /devices | List devices, including revoked ones and the built-in server device. |
| *DevicesApi* | [**pairDevice**](docs/DevicesApi.md#pairdevice) | **POST** /devices/pair | Exchange a pairing code for a long-lived device token. |
| *DevicesApi* | [**revokeDevice**](docs/DevicesApi.md#revokedevice) | **DELETE** /devices/{device_id} | Revoke a device and all its tokens. |
| *DevicesApi* | [**updateDevice**](docs/DevicesApi.md#updatedevice) | **PATCH** /devices/{device_id} | Rename a device or change its push topic. |
| *DevicesApi* | [**updateOwnDevice**](docs/DevicesApi.md#updateowndevice) | **PATCH** /devices/self | Rename the caller's device or change its push topic. |
| *DictionaryApi* | [**getDictEntry**](docs/DictionaryApi.md#getdictentry) | **GET** /dict/entries/{seq} | One JMdict entry with frequency, pitch, kanji and your status. |
| *DictionaryApi* | [**getDictRelease**](docs/DictionaryApi.md#getdictrelease) | **GET** /dict/release | The current dictionary release and its downloadable device builds. |
| *DictionaryApi* | [**getKanji**](docs/DictionaryApi.md#getkanji) | **GET** /kanji/{literal} | One kanji from KANJIDIC2 with components and your kanji note, if any. |
| *DictionaryApi* | [**listEntryExamples**](docs/DictionaryApi.md#listentryexamples) | **GET** /dict/entries/{seq}/examples | Example sentences for an entry, from your media and from Tatoeba. |
| *DictionaryApi* | [**refreshDictionary**](docs/DictionaryApi.md#refreshdictionary) | **POST** /dict/refresh | Queue a dictionary refresh (download sources, build and load a new release). |
| *DictionaryApi* | [**searchDictionary**](docs/DictionaryApi.md#searchdictionary) | **GET** /dict/search | Search JMdict entries by kanji, kana, romaji or English. |
| *EventsApi* | [**streamEvents**](docs/EventsApi.md#streamevents) | **GET** /events | Server-sent events - job progress, sync activity, new notes, device changes. |
| *ExportApi* | [**exportAll**](docs/ExportApi.md#exportall) | **GET** /export | Full JSON dump of your data. |
| *ExportApi* | [**exportAnki**](docs/ExportApi.md#exportanki) | **GET** /export/anki | Export notes and cards as an Anki package (.apkg). |
| *GrammarApi* | [**createGrammarPoint**](docs/GrammarApi.md#creategrammarpoint) | **POST** /grammar | Create a grammar point. |
| *GrammarApi* | [**deleteGrammarPoint**](docs/GrammarApi.md#deletegrammarpoint) | **DELETE** /grammar/{grammar_point_id} | Delete a grammar point. |
| *GrammarApi* | [**getGrammarPoint**](docs/GrammarApi.md#getgrammarpoint) | **GET** /grammar/{grammar_point_id} | One grammar point. |
| *GrammarApi* | [**listGrammarExamples**](docs/GrammarApi.md#listgrammarexamples) | **GET** /grammar/{grammar_point_id}/examples | Sentences where the grammar point was seen (detected or highlighted by hand). |
| *GrammarApi* | [**listGrammarPoints**](docs/GrammarApi.md#listgrammarpoints) | **GET** /grammar | List or search the grammar library. |
| *GrammarApi* | [**putGrammarSpan**](docs/GrammarApi.md#putgrammarspan) | **PUT** /grammar/{grammar_point_id}/spans/{sentence_id}/{start_offset}/{end_offset} | Highlight a span of a sentence as an example of this grammar point (\"I saw something weird\"). |
| *GrammarApi* | [**removeGrammarSpan**](docs/GrammarApi.md#removegrammarspan) | **DELETE** /grammar/{grammar_point_id}/spans/{sentence_id}/{start_offset}/{end_offset} | Remove a manual span (reversible by putting it again). |
| *GrammarApi* | [**testGrammarRules**](docs/GrammarApi.md#testgrammarrules) | **POST** /grammar/{grammar_point_id}/rules/test | Run detection rules against your sentences without saving anything. |
| *GrammarApi* | [**updateGrammarPoint**](docs/GrammarApi.md#updategrammarpoint) | **PATCH** /grammar/{grammar_point_id} | Edit a grammar point. |
| *JobsApi* | [**cancelJob**](docs/JobsApi.md#canceljob) | **POST** /jobs/{job_id}/cancel | Cancel a queued or running job. |
| *JobsApi* | [**getJob**](docs/JobsApi.md#getjob) | **GET** /jobs/{job_id} | One job with progress, result or error. |
| *JobsApi* | [**listJobs**](docs/JobsApi.md#listjobs) | **GET** /jobs | List background jobs (imports, parses, optimizer runs, ...). |
| *JobsApi* | [**synthesizeSpeech**](docs/JobsApi.md#synthesizespeech) | **POST** /tts | Synthesize Japanese speech (VOICEVOX) into an audio blob. |
| *MediaApi* | [**clearJpdbVocabResolution**](docs/MediaApi.md#clearjpdbvocabresolution) | **DELETE** /import/jpdb/vocab/{jpdb_vid}/resolution | Clear a correction - the vocabulary item goes back to the review queue. |
| *MediaApi* | [**deleteMedia**](docs/MediaApi.md#deletemedia) | **DELETE** /media/{media_id} | Delete a media, its deck and its parsed content. |
| *MediaApi* | [**getMedia**](docs/MediaApi.md#getmedia) | **GET** /media/{media_id} | One media with its segments. |
| *MediaApi* | [**getMediaCoverage**](docs/MediaApi.md#getmediacoverage) | **GET** /media/{media_id}/coverage | Coverage of a media (or one segment) by your word statuses. |
| *MediaApi* | [**getSentence**](docs/MediaApi.md#getsentence) | **GET** /sentences/{sentence_id} | One sentence with tokens and grammar spans. |
| *MediaApi* | [**importJpdbDeck**](docs/MediaApi.md#importjpdbdeck) | **POST** /media/import/jpdb | Import a pre-parsed jpdb media deck (vocabulary with occurrence counts). |
| *MediaApi* | [**importJpdbHistory**](docs/MediaApi.md#importjpdbhistory) | **POST** /import/jpdb/history | Import jpdb review history and word statuses from a jpdb export file. |
| *MediaApi* | [**importMedia**](docs/MediaApi.md#importmedia) | **POST** /media/import | Import your own file(s), a URL or pasted text as media (worker job). |
| *MediaApi* | [**listJpdbVocab**](docs/MediaApi.md#listjpdbvocab) | **GET** /import/jpdb/vocab | The jpdb mapping review queue - jpdb vocabulary that did not map to a JMdict entry. |
| *MediaApi* | [**listMedia**](docs/MediaApi.md#listmedia) | **GET** /media | List the media library. |
| *MediaApi* | [**listMediaSentences**](docs/MediaApi.md#listmediasentences) | **GET** /media/{media_id}/sentences | Sentences of a media, optionally only i+1 sentences. |
| *MediaApi* | [**listSentences**](docs/MediaApi.md#listsentences) | **GET** /sentences | Search sentences across all media. |
| *MediaApi* | [**putJpdbVocabResolution**](docs/MediaApi.md#putjpdbvocabresolution) | **PUT** /import/jpdb/vocab/{jpdb_vid}/resolution | Map a jpdb vocabulary item to a JMdict entry, or ignore it. |
| *MediaApi* | [**reparseMedia**](docs/MediaApi.md#reparsemedia) | **POST** /media/{media_id}/reparse | Re-parse a media (after parse overrides or a dictionary refresh). |
| *MediaApi* | [**updateMedia**](docs/MediaApi.md#updatemedia) | **PATCH** /media/{media_id} | Change a media's title, kind or cover. |
| *MetaApi* | [**getHealth**](docs/MetaApi.md#gethealth) | **GET** /health | Liveness and readiness probe. |
| *MetaApi* | [**getMeta**](docs/MetaApi.md#getmeta) | **GET** /meta | Server versions, limits and sync head. |
| *NoteTypesApi* | [**createNoteType**](docs/NoteTypesApi.md#createnotetype) | **POST** /note-types | Create a note type. |
| *NoteTypesApi* | [**deleteNoteType**](docs/NoteTypesApi.md#deletenotetype) | **DELETE** /note-types/{note_type_id} | Delete one of your own note types. |
| *NoteTypesApi* | [**getNoteType**](docs/NoteTypesApi.md#getnotetype) | **GET** /note-types/{note_type_id} | One note type. |
| *NoteTypesApi* | [**listNoteTypes**](docs/NoteTypesApi.md#listnotetypes) | **GET** /note-types | List note types (built-ins and your own). |
| *NoteTypesApi* | [**updateNoteType**](docs/NoteTypesApi.md#updatenotetype) | **PATCH** /note-types/{note_type_id} | Edit a note type's name, fields, templates or CSS. |
| *NotesApi* | [**createNote**](docs/NotesApi.md#createnote) | **POST** /notes | Create a note and its cards. |
| *NotesApi* | [**deleteNote**](docs/NotesApi.md#deletenote) | **DELETE** /notes/{note_id} | Delete a note (permanent tombstone; hides all its cards). |
| *NotesApi* | [**getNote**](docs/NotesApi.md#getnote) | **GET** /notes/{note_id} | One note with its live cards. |
| *NotesApi* | [**listNotes**](docs/NotesApi.md#listnotes) | **GET** /notes | List or search notes. |
| *NotesApi* | [**updateNote**](docs/NotesApi.md#updatenote) | **PATCH** /notes/{note_id} | Edit a note's fields, tags, mnemonic, source sentence or dictionary match. |
| *ParsingApi* | [**deleteParseOverride**](docs/ParsingApi.md#deleteparseoverride) | **DELETE** /parse-overrides/{override_id} | Delete a tokenisation correction (permanent). |
| *ParsingApi* | [**listParseOverrides**](docs/ParsingApi.md#listparseoverrides) | **GET** /parse-overrides | List your tokenisation corrections. |
| *ParsingApi* | [**parseText**](docs/ParsingApi.md#parsetext) | **POST** /parse | Parse text - tokens, matched entries and their known status. |
| *ParsingApi* | [**putParseOverride**](docs/ParsingApi.md#putparseoverride) | **PUT** /parse-overrides/{override_id} | Create or replace a tokenisation correction. |
| *PresetsApi* | [**createPreset**](docs/PresetsApi.md#createpreset) | **POST** /presets | Create a preset. |
| *PresetsApi* | [**deletePreset**](docs/PresetsApi.md#deletepreset) | **DELETE** /presets/{preset_id} | Delete a preset. |
| *PresetsApi* | [**getPreset**](docs/PresetsApi.md#getpreset) | **GET** /presets/{preset_id} | One preset. |
| *PresetsApi* | [**listPresets**](docs/PresetsApi.md#listpresets) | **GET** /presets | List scheduling presets. |
| *PresetsApi* | [**optimizePreset**](docs/PresetsApi.md#optimizepreset) | **POST** /presets/{preset_id}/optimize | Queue an FSRS optimizer run for this preset. |
| *PresetsApi* | [**updatePreset**](docs/PresetsApi.md#updatepreset) | **PATCH** /presets/{preset_id} | Change preset options. |
| *ReviewApi* | [**answerReview**](docs/ReviewApi.md#answerreview) | **POST** /review/answer | Grade a card. |
| *ReviewApi* | [**createDrillSession**](docs/ReviewApi.md#createdrillsession) | **POST** /drill/session | Build an extra-practice session that does not touch scheduling. |
| *ReviewApi* | [**getReviewQueue**](docs/ReviewApi.md#getreviewqueue) | **GET** /review/queue | The next cards to review, built by the shared core's queue builder. |
| *ReviewApi* | [**undoReview**](docs/ReviewApi.md#undoreview) | **POST** /review/undo | Undo a review. |
| *SettingsApi* | [**listSettings**](docs/SettingsApi.md#listsettings) | **GET** /settings | All stored settings. |
| *SettingsApi* | [**updateSettings**](docs/SettingsApi.md#updatesettings) | **PATCH** /settings | Set one or more settings. |
| *StatsApi* | [**getStatsCalibration**](docs/StatsApi.md#getstatscalibration) | **GET** /stats/calibration | FSRS calibration - predicted versus actual recall. |
| *StatsApi* | [**getStatsForecast**](docs/StatsApi.md#getstatsforecast) | **GET** /stats/forecast | Due forecast per study day, plus the next 24 hours by hour. |
| *StatsApi* | [**getStatsHeatmap**](docs/StatsApi.md#getstatsheatmap) | **GET** /stats/heatmap | Reviews per study day. |
| *StatsApi* | [**getStatsKnownWords**](docs/StatsApi.md#getstatsknownwords) | **GET** /stats/known-words | Known words over time. |
| *StatsApi* | [**getStatsRetention**](docs/StatsApi.md#getstatsretention) | **GET** /stats/retention | Retention (share of reviews not rated Again), grouped. |
| *StatsApi* | [**getStatsSummary**](docs/StatsApi.md#getstatssummary) | **GET** /stats/summary | Today's counts for home screens (due, new, done, time). |
| *SyncApi* | [**syncPull**](docs/SyncApi.md#syncpull) | **GET** /sync/pull | Pull logged operations after a sequence number. |
| *SyncApi* | [**syncPush**](docs/SyncApi.md#syncpush) | **POST** /sync/push | Push a batch of locally minted operations. |
| *TokensApi* | [**createToken**](docs/TokensApi.md#createtoken) | **POST** /tokens | Create a personal access token. |
| *TokensApi* | [**getToken**](docs/TokensApi.md#gettoken) | **GET** /tokens/{token_id} | One credential. |
| *TokensApi* | [**listTokens**](docs/TokensApi.md#listtokens) | **GET** /tokens | List credentials of every kind (never the token strings or hashes). |
| *TokensApi* | [**revokeToken**](docs/TokensApi.md#revoketoken) | **DELETE** /tokens/{token_id} | Revoke a credential of any kind. |
| *WordsApi* | [**clearWordStatus**](docs/WordsApi.md#clearwordstatus) | **DELETE** /words/{seq}/status | Clear the override; the status falls back to the derived one. |
| *WordsApi* | [**getWordStatus**](docs/WordsApi.md#getwordstatus) | **GET** /words/{seq}/status | Word status of one entry. |
| *WordsApi* | [**listConfusables**](docs/WordsApi.md#listconfusables) | **GET** /confusables | All confusable pairs (any note type). |
| *WordsApi* | [**listHotKanji**](docs/WordsApi.md#listhotkanji) | **GET** /kanji/hot | Hot kanji - recent failures rolled up by kanji. |
| *WordsApi* | [**listHotWords**](docs/WordsApi.md#listhotwords) | **GET** /words/hot | Hot words - recent struggle, not lifetime. |
| *WordsApi* | [**listLeeches**](docs/WordsApi.md#listleeches) | **GET** /words/leeches | Leech cards (lapses at or above `scheduler.leech_threshold`). |
| *WordsApi* | [**listWordConfusables**](docs/WordsApi.md#listwordconfusables) | **GET** /words/{seq}/confusables | Confusable partners of the word's note. |
| *WordsApi* | [**setConfusable**](docs/WordsApi.md#setconfusable) | **PUT** /confusables/{note_a_id}/{note_b_id}/{reason} | Confirm or reject a suggested pair, or record a pair yourself. |
| *WordsApi* | [**setWordStatus**](docs/WordsApi.md#setwordstatus) | **PUT** /words/{seq}/status | Set an explicit status override (known, never-forget, blacklisted, ignored). |


<a id="documentation-for-models"></a>
## Documentation for Models

 - [app.kotobaya.client.models.AnkiConnectRequest](docs/AnkiConnectRequest.md)
 - [app.kotobaya.client.models.AnkiConnectResponse](docs/AnkiConnectResponse.md)
 - [app.kotobaya.client.models.Blob](docs/Blob.md)
 - [app.kotobaya.client.models.BlobQuery](docs/BlobQuery.md)
 - [app.kotobaya.client.models.BlobQueryResult](docs/BlobQueryResult.md)
 - [app.kotobaya.client.models.Card](docs/Card.md)
 - [app.kotobaya.client.models.CardBulkAction](docs/CardBulkAction.md)
 - [app.kotobaya.client.models.CardBulkRequest](docs/CardBulkRequest.md)
 - [app.kotobaya.client.models.CardBulkResult](docs/CardBulkResult.md)
 - [app.kotobaya.client.models.CardBulkScheduleRequest](docs/CardBulkScheduleRequest.md)
 - [app.kotobaya.client.models.CardBulkScheduleRequestAction](docs/CardBulkScheduleRequestAction.md)
 - [app.kotobaya.client.models.CardCreate](docs/CardCreate.md)
 - [app.kotobaya.client.models.CardPage](docs/CardPage.md)
 - [app.kotobaya.client.models.CardPatch](docs/CardPatch.md)
 - [app.kotobaya.client.models.CardState](docs/CardState.md)
 - [app.kotobaya.client.models.CardTemplate](docs/CardTemplate.md)
 - [app.kotobaya.client.models.Cardburiedschema](docs/Cardburiedschema.md)
 - [app.kotobaya.client.models.Cardcreatedschema](docs/Cardcreatedschema.md)
 - [app.kotobaya.client.models.Carddeletedschema](docs/Carddeletedschema.md)
 - [app.kotobaya.client.models.CardflagSetschema](docs/CardflagSetschema.md)
 - [app.kotobaya.client.models.Cardmergedschema](docs/Cardmergedschema.md)
 - [app.kotobaya.client.models.CardpresetSetschema](docs/CardpresetSetschema.md)
 - [app.kotobaya.client.models.Cardsuspendedschema](docs/Cardsuspendedschema.md)
 - [app.kotobaya.client.models.Cardunburiedschema](docs/Cardunburiedschema.md)
 - [app.kotobaya.client.models.Cardunsuspendedschema](docs/Cardunsuspendedschema.md)
 - [app.kotobaya.client.models.Confusable](docs/Confusable.md)
 - [app.kotobaya.client.models.ConfusableList](docs/ConfusableList.md)
 - [app.kotobaya.client.models.ConfusablePage](docs/ConfusablePage.md)
 - [app.kotobaya.client.models.ConfusableReason](docs/ConfusableReason.md)
 - [app.kotobaya.client.models.ConfusableSet](docs/ConfusableSet.md)
 - [app.kotobaya.client.models.ConfusableStatus](docs/ConfusableStatus.md)
 - [app.kotobaya.client.models.Confusableconfirmedschema](docs/Confusableconfirmedschema.md)
 - [app.kotobaya.client.models.Confusablerejectedschema](docs/Confusablerejectedschema.md)
 - [app.kotobaya.client.models.Confusablesuggestedschema](docs/Confusablesuggestedschema.md)
 - [app.kotobaya.client.models.Coverage](docs/Coverage.md)
 - [app.kotobaya.client.models.CoverageCounts](docs/CoverageCounts.md)
 - [app.kotobaya.client.models.CoverageCurveInner](docs/CoverageCurveInner.md)
 - [app.kotobaya.client.models.CoverageDifficulty](docs/CoverageDifficulty.md)
 - [app.kotobaya.client.models.CoverageSegmentsInner](docs/CoverageSegmentsInner.md)
 - [app.kotobaya.client.models.CoverageTokens](docs/CoverageTokens.md)
 - [app.kotobaya.client.models.Deck](docs/Deck.md)
 - [app.kotobaya.client.models.DeckCreate](docs/DeckCreate.md)
 - [app.kotobaya.client.models.DeckItem](docs/DeckItem.md)
 - [app.kotobaya.client.models.DeckItemList](docs/DeckItemList.md)
 - [app.kotobaya.client.models.DeckItemPage](docs/DeckItemPage.md)
 - [app.kotobaya.client.models.DeckItemPut](docs/DeckItemPut.md)
 - [app.kotobaya.client.models.DeckItemsAdd](docs/DeckItemsAdd.md)
 - [app.kotobaya.client.models.DeckItemsAddItemsInner](docs/DeckItemsAddItemsInner.md)
 - [app.kotobaya.client.models.DeckList](docs/DeckList.md)
 - [app.kotobaya.client.models.DeckListCatalogue](docs/DeckListCatalogue.md)
 - [app.kotobaya.client.models.DeckListCatalogueEntry](docs/DeckListCatalogueEntry.md)
 - [app.kotobaya.client.models.DeckPatch](docs/DeckPatch.md)
 - [app.kotobaya.client.models.DeckSettings](docs/DeckSettings.md)
 - [app.kotobaya.client.models.DeckType](docs/DeckType.md)
 - [app.kotobaya.client.models.DeckUpdatedSchemaSet](docs/DeckUpdatedSchemaSet.md)
 - [app.kotobaya.client.models.DeckcontentPublishedschema](docs/DeckcontentPublishedschema.md)
 - [app.kotobaya.client.models.Deckcreatedschema](docs/Deckcreatedschema.md)
 - [app.kotobaya.client.models.Deckdeletedschema](docs/Deckdeletedschema.md)
 - [app.kotobaya.client.models.DeckitemPutschema](docs/DeckitemPutschema.md)
 - [app.kotobaya.client.models.DeckitemRemovedschema](docs/DeckitemRemovedschema.md)
 - [app.kotobaya.client.models.Deckupdatedschema](docs/Deckupdatedschema.md)
 - [app.kotobaya.client.models.DetectionRule](docs/DetectionRule.md)
 - [app.kotobaya.client.models.Device](docs/Device.md)
 - [app.kotobaya.client.models.DeviceList](docs/DeviceList.md)
 - [app.kotobaya.client.models.DevicePatch](docs/DevicePatch.md)
 - [app.kotobaya.client.models.DictEntry](docs/DictEntry.md)
 - [app.kotobaya.client.models.DictEntryFreqsInner](docs/DictEntryFreqsInner.md)
 - [app.kotobaya.client.models.DictEntryKanjiFormsInner](docs/DictEntryKanjiFormsInner.md)
 - [app.kotobaya.client.models.DictEntryPitchInner](docs/DictEntryPitchInner.md)
 - [app.kotobaya.client.models.DictEntryReadingsInner](docs/DictEntryReadingsInner.md)
 - [app.kotobaya.client.models.DictEntrySensesInner](docs/DictEntrySensesInner.md)
 - [app.kotobaya.client.models.DictEntrySensesInnerGlossInner](docs/DictEntrySensesInnerGlossInner.md)
 - [app.kotobaya.client.models.DictEntrySummary](docs/DictEntrySummary.md)
 - [app.kotobaya.client.models.DictEntrySummaryPage](docs/DictEntrySummaryPage.md)
 - [app.kotobaya.client.models.DictRelease](docs/DictRelease.md)
 - [app.kotobaya.client.models.DictReleaseArtifactsInner](docs/DictReleaseArtifactsInner.md)
 - [app.kotobaya.client.models.DictReleaseFreqListsInner](docs/DictReleaseFreqListsInner.md)
 - [app.kotobaya.client.models.DrillSession](docs/DrillSession.md)
 - [app.kotobaya.client.models.DrillSessionRequest](docs/DrillSessionRequest.md)
 - [app.kotobaya.client.models.EntryExamples](docs/EntryExamples.md)
 - [app.kotobaya.client.models.Event](docs/Event.md)
 - [app.kotobaya.client.models.EventDevice](docs/EventDevice.md)
 - [app.kotobaya.client.models.EventHello](docs/EventHello.md)
 - [app.kotobaya.client.models.EventNoteCreated](docs/EventNoteCreated.md)
 - [app.kotobaya.client.models.EventSyncChanged](docs/EventSyncChanged.md)
 - [app.kotobaya.client.models.EventType](docs/EventType.md)
 - [app.kotobaya.client.models.ExportDump](docs/ExportDump.md)
 - [app.kotobaya.client.models.FieldError](docs/FieldError.md)
 - [app.kotobaya.client.models.FsrsParamsPublishedSchemaOptimizerStats](docs/FsrsParamsPublishedSchemaOptimizerStats.md)
 - [app.kotobaya.client.models.FsrsParamsUpdatedSchemaSet](docs/FsrsParamsUpdatedSchemaSet.md)
 - [app.kotobaya.client.models.FsrsParamscreatedschema](docs/FsrsParamscreatedschema.md)
 - [app.kotobaya.client.models.FsrsParamsdeletedschema](docs/FsrsParamsdeletedschema.md)
 - [app.kotobaya.client.models.FsrsParamspublishedschema](docs/FsrsParamspublishedschema.md)
 - [app.kotobaya.client.models.FsrsParamsupdatedschema](docs/FsrsParamsupdatedschema.md)
 - [app.kotobaya.client.models.GrammarCreatedSchemaDetectionRulesInner](docs/GrammarCreatedSchemaDetectionRulesInner.md)
 - [app.kotobaya.client.models.GrammarCreatedSchemaLinksInner](docs/GrammarCreatedSchemaLinksInner.md)
 - [app.kotobaya.client.models.GrammarLink](docs/GrammarLink.md)
 - [app.kotobaya.client.models.GrammarPoint](docs/GrammarPoint.md)
 - [app.kotobaya.client.models.GrammarPointCreate](docs/GrammarPointCreate.md)
 - [app.kotobaya.client.models.GrammarPointPage](docs/GrammarPointPage.md)
 - [app.kotobaya.client.models.GrammarPointPatch](docs/GrammarPointPatch.md)
 - [app.kotobaya.client.models.GrammarRuleTestRequest](docs/GrammarRuleTestRequest.md)
 - [app.kotobaya.client.models.GrammarRuleTestResult](docs/GrammarRuleTestResult.md)
 - [app.kotobaya.client.models.GrammarRuleTestResultMatchesInner](docs/GrammarRuleTestResultMatchesInner.md)
 - [app.kotobaya.client.models.GrammarRuleTestResultMatchesInnerSpansInner](docs/GrammarRuleTestResultMatchesInnerSpansInner.md)
 - [app.kotobaya.client.models.GrammarSpan](docs/GrammarSpan.md)
 - [app.kotobaya.client.models.GrammarSpanPut](docs/GrammarSpanPut.md)
 - [app.kotobaya.client.models.GrammarSpanRef](docs/GrammarSpanRef.md)
 - [app.kotobaya.client.models.GrammarUpdatedSchemaSet](docs/GrammarUpdatedSchemaSet.md)
 - [app.kotobaya.client.models.Grammarcreatedschema](docs/Grammarcreatedschema.md)
 - [app.kotobaya.client.models.Grammardeletedschema](docs/Grammardeletedschema.md)
 - [app.kotobaya.client.models.GrammarspanPutschema](docs/GrammarspanPutschema.md)
 - [app.kotobaya.client.models.GrammarspanRemovedschema](docs/GrammarspanRemovedschema.md)
 - [app.kotobaya.client.models.Grammarupdatedschema](docs/Grammarupdatedschema.md)
 - [app.kotobaya.client.models.Health](docs/Health.md)
 - [app.kotobaya.client.models.HotKanji](docs/HotKanji.md)
 - [app.kotobaya.client.models.HotKanjiList](docs/HotKanjiList.md)
 - [app.kotobaya.client.models.HotWord](docs/HotWord.md)
 - [app.kotobaya.client.models.HotWordList](docs/HotWordList.md)
 - [app.kotobaya.client.models.Job](docs/Job.md)
 - [app.kotobaya.client.models.JobKind](docs/JobKind.md)
 - [app.kotobaya.client.models.JobPage](docs/JobPage.md)
 - [app.kotobaya.client.models.JobPayloadDeckBuild](docs/JobPayloadDeckBuild.md)
 - [app.kotobaya.client.models.JobPayloadDictRefresh](docs/JobPayloadDictRefresh.md)
 - [app.kotobaya.client.models.JobPayloadFsrsOptimize](docs/JobPayloadFsrsOptimize.md)
 - [app.kotobaya.client.models.JobPayloadGrammarDetect](docs/JobPayloadGrammarDetect.md)
 - [app.kotobaya.client.models.JobPayloadMediaParse](docs/JobPayloadMediaParse.md)
 - [app.kotobaya.client.models.JobResultDeckBuild](docs/JobResultDeckBuild.md)
 - [app.kotobaya.client.models.JobResultDictRefresh](docs/JobResultDictRefresh.md)
 - [app.kotobaya.client.models.JobResultFsrsOptimize](docs/JobResultFsrsOptimize.md)
 - [app.kotobaya.client.models.JobResultGrammarDetect](docs/JobResultGrammarDetect.md)
 - [app.kotobaya.client.models.JobResultGrammarDetectFanOut](docs/JobResultGrammarDetectFanOut.md)
 - [app.kotobaya.client.models.JobResultGrammarDetectMedia](docs/JobResultGrammarDetectMedia.md)
 - [app.kotobaya.client.models.JobResultJpdbHistory](docs/JobResultJpdbHistory.md)
 - [app.kotobaya.client.models.JobResultJpdbHistoryKanjiUnmappedInner](docs/JobResultJpdbHistoryKanjiUnmappedInner.md)
 - [app.kotobaya.client.models.JobResultJpdbHistoryUnmappedInner](docs/JobResultJpdbHistoryUnmappedInner.md)
 - [app.kotobaya.client.models.JobResultMedia](docs/JobResultMedia.md)
 - [app.kotobaya.client.models.JobResultMediaUnmappedInner](docs/JobResultMediaUnmappedInner.md)
 - [app.kotobaya.client.models.JobResultTts](docs/JobResultTts.md)
 - [app.kotobaya.client.models.JobStatus](docs/JobStatus.md)
 - [app.kotobaya.client.models.JpdbDeckImportRequest](docs/JpdbDeckImportRequest.md)
 - [app.kotobaya.client.models.JpdbHistoryImportRequest](docs/JpdbHistoryImportRequest.md)
 - [app.kotobaya.client.models.JpdbVocab](docs/JpdbVocab.md)
 - [app.kotobaya.client.models.JpdbVocabMedia](docs/JpdbVocabMedia.md)
 - [app.kotobaya.client.models.JpdbVocabPage](docs/JpdbVocabPage.md)
 - [app.kotobaya.client.models.JpdbVocabResolution](docs/JpdbVocabResolution.md)
 - [app.kotobaya.client.models.JpdbVocabResolutionPut](docs/JpdbVocabResolutionPut.md)
 - [app.kotobaya.client.models.JpdbVocabResolutionResult](docs/JpdbVocabResolutionResult.md)
 - [app.kotobaya.client.models.Kanji](docs/Kanji.md)
 - [app.kotobaya.client.models.LoginRequest](docs/LoginRequest.md)
 - [app.kotobaya.client.models.Media](docs/Media.md)
 - [app.kotobaya.client.models.MediaDeckOptions](docs/MediaDeckOptions.md)
 - [app.kotobaya.client.models.MediaImportFile](docs/MediaImportFile.md)
 - [app.kotobaya.client.models.MediaImportRequest](docs/MediaImportRequest.md)
 - [app.kotobaya.client.models.MediaImportRequestOptions](docs/MediaImportRequestOptions.md)
 - [app.kotobaya.client.models.MediaKind](docs/MediaKind.md)
 - [app.kotobaya.client.models.MediaPage](docs/MediaPage.md)
 - [app.kotobaya.client.models.MediaPatch](docs/MediaPatch.md)
 - [app.kotobaya.client.models.MediaSegment](docs/MediaSegment.md)
 - [app.kotobaya.client.models.MediaUpdatedSchemaSet](docs/MediaUpdatedSchemaSet.md)
 - [app.kotobaya.client.models.MediacontentPublishedschema](docs/MediacontentPublishedschema.md)
 - [app.kotobaya.client.models.Mediacreatedschema](docs/Mediacreatedschema.md)
 - [app.kotobaya.client.models.Mediadeletedschema](docs/Mediadeletedschema.md)
 - [app.kotobaya.client.models.Mediaupdatedschema](docs/Mediaupdatedschema.md)
 - [app.kotobaya.client.models.Meta](docs/Meta.md)
 - [app.kotobaya.client.models.MetaDictRelease](docs/MetaDictRelease.md)
 - [app.kotobaya.client.models.MetaLimits](docs/MetaLimits.md)
 - [app.kotobaya.client.models.Note](docs/Note.md)
 - [app.kotobaya.client.models.NoteBaseType](docs/NoteBaseType.md)
 - [app.kotobaya.client.models.NoteCreate](docs/NoteCreate.md)
 - [app.kotobaya.client.models.NoteField](docs/NoteField.md)
 - [app.kotobaya.client.models.NotePage](docs/NotePage.md)
 - [app.kotobaya.client.models.NotePatch](docs/NotePatch.md)
 - [app.kotobaya.client.models.NoteSummary](docs/NoteSummary.md)
 - [app.kotobaya.client.models.NoteType](docs/NoteType.md)
 - [app.kotobaya.client.models.NoteTypeCreate](docs/NoteTypeCreate.md)
 - [app.kotobaya.client.models.NoteTypeCreatedSchemaFieldsInner](docs/NoteTypeCreatedSchemaFieldsInner.md)
 - [app.kotobaya.client.models.NoteTypeList](docs/NoteTypeList.md)
 - [app.kotobaya.client.models.NoteTypePatch](docs/NoteTypePatch.md)
 - [app.kotobaya.client.models.NoteTypeUpdatedSchemaSet](docs/NoteTypeUpdatedSchemaSet.md)
 - [app.kotobaya.client.models.NoteTypecreatedschema](docs/NoteTypecreatedschema.md)
 - [app.kotobaya.client.models.NoteTypedeletedschema](docs/NoteTypedeletedschema.md)
 - [app.kotobaya.client.models.NoteTypeupdatedschema](docs/NoteTypeupdatedschema.md)
 - [app.kotobaya.client.models.NoteUpdatedSchemaSet](docs/NoteUpdatedSchemaSet.md)
 - [app.kotobaya.client.models.Notecreatedschema](docs/Notecreatedschema.md)
 - [app.kotobaya.client.models.Notedeletedschema](docs/Notedeletedschema.md)
 - [app.kotobaya.client.models.Notemergedschema](docs/Notemergedschema.md)
 - [app.kotobaya.client.models.Noteupdatedschema](docs/Noteupdatedschema.md)
 - [app.kotobaya.client.models.Opschema](docs/Opschema.md)
 - [app.kotobaya.client.models.OverrideToken](docs/OverrideToken.md)
 - [app.kotobaya.client.models.PairRequest](docs/PairRequest.md)
 - [app.kotobaya.client.models.PairResponse](docs/PairResponse.md)
 - [app.kotobaya.client.models.Pairing](docs/Pairing.md)
 - [app.kotobaya.client.models.ParseOverride](docs/ParseOverride.md)
 - [app.kotobaya.client.models.ParseOverridePage](docs/ParseOverridePage.md)
 - [app.kotobaya.client.models.ParseOverridePut](docs/ParseOverridePut.md)
 - [app.kotobaya.client.models.ParseOverridePutSchemaTokensInner](docs/ParseOverridePutSchemaTokensInner.md)
 - [app.kotobaya.client.models.ParseOverridedeletedschema](docs/ParseOverridedeletedschema.md)
 - [app.kotobaya.client.models.ParseOverrideputschema](docs/ParseOverrideputschema.md)
 - [app.kotobaya.client.models.ParseRequest](docs/ParseRequest.md)
 - [app.kotobaya.client.models.ParseResult](docs/ParseResult.md)
 - [app.kotobaya.client.models.ParsedSentence](docs/ParsedSentence.md)
 - [app.kotobaya.client.models.Preset](docs/Preset.md)
 - [app.kotobaya.client.models.PresetCreate](docs/PresetCreate.md)
 - [app.kotobaya.client.models.PresetList](docs/PresetList.md)
 - [app.kotobaya.client.models.PresetPatch](docs/PresetPatch.md)
 - [app.kotobaya.client.models.Principal](docs/Principal.md)
 - [app.kotobaya.client.models.Problem](docs/Problem.md)
 - [app.kotobaya.client.models.PullResponse](docs/PullResponse.md)
 - [app.kotobaya.client.models.PushRequest](docs/PushRequest.md)
 - [app.kotobaya.client.models.PushRequestOpsInner](docs/PushRequestOpsInner.md)
 - [app.kotobaya.client.models.PushResponse](docs/PushResponse.md)
 - [app.kotobaya.client.models.PushResult](docs/PushResult.md)
 - [app.kotobaya.client.models.PushResultError](docs/PushResultError.md)
 - [app.kotobaya.client.models.QueueItem](docs/QueueItem.md)
 - [app.kotobaya.client.models.RetentionGroup](docs/RetentionGroup.md)
 - [app.kotobaya.client.models.ReviewAnswerRequest](docs/ReviewAnswerRequest.md)
 - [app.kotobaya.client.models.ReviewButton](docs/ReviewButton.md)
 - [app.kotobaya.client.models.ReviewLog](docs/ReviewLog.md)
 - [app.kotobaya.client.models.ReviewPage](docs/ReviewPage.md)
 - [app.kotobaya.client.models.ReviewQueue](docs/ReviewQueue.md)
 - [app.kotobaya.client.models.ReviewQueueCounts](docs/ReviewQueueCounts.md)
 - [app.kotobaya.client.models.ReviewResult](docs/ReviewResult.md)
 - [app.kotobaya.client.models.ReviewType](docs/ReviewType.md)
 - [app.kotobaya.client.models.ReviewUndoRequest](docs/ReviewUndoRequest.md)
 - [app.kotobaya.client.models.Reviewloggedschema](docs/Reviewloggedschema.md)
 - [app.kotobaya.client.models.Reviewundoneschema](docs/Reviewundoneschema.md)
 - [app.kotobaya.client.models.SchedulePreview](docs/SchedulePreview.md)
 - [app.kotobaya.client.models.SchedulePreviewOption](docs/SchedulePreviewOption.md)
 - [app.kotobaya.client.models.Scope](docs/Scope.md)
 - [app.kotobaya.client.models.Sentence](docs/Sentence.md)
 - [app.kotobaya.client.models.SentencePage](docs/SentencePage.md)
 - [app.kotobaya.client.models.SentenceToken](docs/SentenceToken.md)
 - [app.kotobaya.client.models.Setting](docs/Setting.md)
 - [app.kotobaya.client.models.SettingList](docs/SettingList.md)
 - [app.kotobaya.client.models.SettingsPatch](docs/SettingsPatch.md)
 - [app.kotobaya.client.models.Settingssetschema](docs/Settingssetschema.md)
 - [app.kotobaya.client.models.SpanOrigin](docs/SpanOrigin.md)
 - [app.kotobaya.client.models.StatsCalibration](docs/StatsCalibration.md)
 - [app.kotobaya.client.models.StatsCalibrationBinsInner](docs/StatsCalibrationBinsInner.md)
 - [app.kotobaya.client.models.StatsForecast](docs/StatsForecast.md)
 - [app.kotobaya.client.models.StatsForecastDaysInner](docs/StatsForecastDaysInner.md)
 - [app.kotobaya.client.models.StatsForecastHoursInner](docs/StatsForecastHoursInner.md)
 - [app.kotobaya.client.models.StatsHeatmap](docs/StatsHeatmap.md)
 - [app.kotobaya.client.models.StatsHeatmapDaysInner](docs/StatsHeatmapDaysInner.md)
 - [app.kotobaya.client.models.StatsKnownWords](docs/StatsKnownWords.md)
 - [app.kotobaya.client.models.StatsKnownWordsCurrent](docs/StatsKnownWordsCurrent.md)
 - [app.kotobaya.client.models.StatsKnownWordsSeriesInner](docs/StatsKnownWordsSeriesInner.md)
 - [app.kotobaya.client.models.StatsRetention](docs/StatsRetention.md)
 - [app.kotobaya.client.models.StatsSummary](docs/StatsSummary.md)
 - [app.kotobaya.client.models.StatsSummaryDoneToday](docs/StatsSummaryDoneToday.md)
 - [app.kotobaya.client.models.StatsSummaryDue](docs/StatsSummaryDue.md)
 - [app.kotobaya.client.models.StatsSummaryTotals](docs/StatsSummaryTotals.md)
 - [app.kotobaya.client.models.TatoebaSentence](docs/TatoebaSentence.md)
 - [app.kotobaya.client.models.Template](docs/Template.md)
 - [app.kotobaya.client.models.Token](docs/Token.md)
 - [app.kotobaya.client.models.TokenCreate](docs/TokenCreate.md)
 - [app.kotobaya.client.models.TokenCreated](docs/TokenCreated.md)
 - [app.kotobaya.client.models.TokenKind](docs/TokenKind.md)
 - [app.kotobaya.client.models.TokenList](docs/TokenList.md)
 - [app.kotobaya.client.models.TtsRequest](docs/TtsRequest.md)
 - [app.kotobaya.client.models.WordStatus](docs/WordStatus.md)
 - [app.kotobaya.client.models.WordStatusSet](docs/WordStatusSet.md)
 - [app.kotobaya.client.models.WordStatusValue](docs/WordStatusValue.md)
 - [app.kotobaya.client.models.WordstatusClearedschema](docs/WordstatusClearedschema.md)
 - [app.kotobaya.client.models.WordstatusSetschema](docs/WordstatusSetschema.md)


<a id="documentation-for-authorization"></a>
## Documentation for Authorization


Authentication schemes defined for the API:
<a id="bearerAuth"></a>
### bearerAuth

- **Type**: HTTP Bearer Token authentication

<a id="sessionCookie"></a>
### sessionCookie

- **Type**: API key
- **API key parameter name**: kotobaya_session
- **Location**: 

