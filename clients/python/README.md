# kotobaya-client
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


This Python package is automatically generated by the [OpenAPI Generator](https://openapi-generator.tech) project:

- API version: 1.0.0
- Package version: 1.0.0
- Generator version: 7.27.0
- Build package: org.openapitools.codegen.languages.PythonClientCodegen
For more information, please visit [https://kotobaya.invalid/api/docs](https://kotobaya.invalid/api/docs)

## Requirements.

Python 3.11+

Packaging uses standard `[project]` metadata and `[dependency-groups]` (PEP 621/735).
Install the package with `python -m pip install .`; install development dependencies
with Poetry 2.2 or newer using `poetry install`, or pip 25.1 or newer using
`python -m pip install --group dev`. The latter installs the group, not the package.

## Installation & Usage
### pip install

If the python package is hosted on a repository, you can install directly using:

```sh
pip install git+https://github.com/GIT_USER_ID/GIT_REPO_ID.git
```
(you may need to run `pip` with root permission: `sudo pip install git+https://github.com/GIT_USER_ID/GIT_REPO_ID.git`)

Then import the package:
```python
import kotobaya_client
```

### Setuptools

Install via [Setuptools](http://pypi.python.org/pypi/setuptools).

```sh
python setup.py install --user
```
(or `sudo python setup.py install` to install the package for all users)

Then import the package:
```python
import kotobaya_client
```

### Tests

Execute `pytest` to run the tests.

## Getting Started

Please follow the [installation procedure](#installation--usage) and then run the following:

```python

import kotobaya_client
from kotobaya_client.rest import ApiException
from pprint import pprint

# Defining the host is optional and defaults to /api/v1
# See configuration.py for a list of all supported configuration parameters.
configuration = kotobaya_client.Configuration(
    host = "/api/v1"
)

# The client must configure the authentication and authorization parameters
# in accordance with the API server security policy.
# Examples for each auth method are provided below, use the example that
# satisfies your auth use case.

# Configure API key authorization: sessionCookie
configuration.api_key['sessionCookie'] = os.environ["API_KEY"]

# Uncomment below to setup prefix (e.g. Bearer) for API key, if needed
# configuration.api_key_prefix['sessionCookie'] = 'Bearer'

# Configure Bearer authorization: bearerAuth
configuration = kotobaya_client.Configuration(
    access_token = os.environ["BEARER_TOKEN"]
)


# Enter a context with an instance of the API client
with kotobaya_client.ApiClient(configuration) as api_client:
    # Create an instance of the API class
    api_instance = kotobaya_client.AuthApi(api_client)

    try:
        # Who am I - the credential making this request.
        api_response = api_instance.get_principal()
        print("The response of AuthApi->get_principal:\n")
        pprint(api_response)
    except ApiException as e:
        print("Exception when calling AuthApi->get_principal: %s\n" % e)

```

## Documentation for API Endpoints

All URIs are relative to */api/v1*

Class | Method | HTTP request | Description
------------ | ------------- | ------------- | -------------
*AuthApi* | [**get_principal**](docs/AuthApi.md#get_principal) | **GET** /auth/session | Who am I - the credential making this request.
*AuthApi* | [**login**](docs/AuthApi.md#login) | **POST** /auth/login | Password login; sets the session cookie.
*AuthApi* | [**logout**](docs/AuthApi.md#logout) | **POST** /auth/logout | Revoke the current session and clear the cookie.
*BlobsApi* | [**get_blob**](docs/BlobsApi.md#get_blob) | **GET** /blobs/{hash} | Download a blob.
*BlobsApi* | [**put_blob**](docs/BlobsApi.md#put_blob) | **PUT** /blobs/{hash} | Upload a blob under its SHA-256.
*BlobsApi* | [**query_blobs**](docs/BlobsApi.md#query_blobs) | **POST** /blobs/query | Ask which of a set of blobs the server already has.
*CardsApi* | [**bulk_cards**](docs/CardsApi.md#bulk_cards) | **POST** /cards/bulk | Apply one action to many cards (suspend, tag, move, delete, ...).
*CardsApi* | [**bulk_schedule_cards**](docs/CardsApi.md#bulk_schedule_cards) | **POST** /cards/bulk/schedule | Reschedule or reset many cards (manual review-log rows).
*CardsApi* | [**create_card**](docs/CardsApi.md#create_card) | **POST** /cards | Add an optional card kind to an existing note.
*CardsApi* | [**delete_card**](docs/CardsApi.md#delete_card) | **DELETE** /cards/{card_id} | Delete one card (permanent tombstone); the note and its other cards stay.
*CardsApi* | [**get_card**](docs/CardsApi.md#get_card) | **GET** /cards/{card_id} | One card with its note summary.
*CardsApi* | [**list_card_reviews**](docs/CardsApi.md#list_card_reviews) | **GET** /cards/{card_id}/reviews | Review history of a card (its whole replay set plus drills and crams).
*CardsApi* | [**list_cards**](docs/CardsApi.md#list_cards) | **GET** /cards | Find cards with the card search language.
*CardsApi* | [**update_card**](docs/CardsApi.md#update_card) | **PATCH** /cards/{card_id} | Suspend, bury, flag or re-preset one card.
*CompatApi* | [**anki_connect**](docs/CompatApi.md#anki_connect) | **POST** /ankiconnect | AnkiConnect-compatible subset for Yomitan and other mining tools.
*DecksApi* | [**add_deck_items**](docs/DecksApi.md#add_deck_items) | **POST** /decks/{deck_id}/items | Add several items to a user deck.
*DecksApi* | [**create_deck**](docs/DecksApi.md#create_deck) | **POST** /decks | Create a user or smart deck, or enable a generated list.
*DecksApi* | [**delete_deck**](docs/DecksApi.md#delete_deck) | **DELETE** /decks/{deck_id} | Delete a user, smart or list deck. Notes and cards are untouched.
*DecksApi* | [**get_deck**](docs/DecksApi.md#get_deck) | **GET** /decks/{deck_id} | One deck.
*DecksApi* | [**get_deck_coverage**](docs/DecksApi.md#get_deck_coverage) | **GET** /decks/{deck_id}/coverage | Coverage of a deck&#39;s items by your word statuses.
*DecksApi* | [**list_deck_items**](docs/DecksApi.md#list_deck_items) | **GET** /decks/{deck_id}/items | Items of a deck with dictionary summary and word status.
*DecksApi* | [**list_deck_lists**](docs/DecksApi.md#list_deck_lists) | **GET** /deck-lists | Generated lists (JLPT, frequency bands) that can be enabled as &#x60;list&#x60; decks.
*DecksApi* | [**list_decks**](docs/DecksApi.md#list_decks) | **GET** /decks | List decks.
*DecksApi* | [**put_deck_item**](docs/DecksApi.md#put_deck_item) | **PUT** /decks/{deck_id}/items/{item_key} | Add one item to a user deck, or move it.
*DecksApi* | [**remove_deck_item**](docs/DecksApi.md#remove_deck_item) | **DELETE** /decks/{deck_id}/items/{item_key} | Remove one item from a user deck (reversible by putting it again).
*DecksApi* | [**update_deck**](docs/DecksApi.md#update_deck) | **PATCH** /decks/{deck_id} | Rename, reprioritise, enable/disable, set limits or settings.
*DevicesApi* | [**create_pairing**](docs/DevicesApi.md#create_pairing) | **POST** /devices/pairings | Create a one-time pairing code (shown as a QR code by the web app).
*DevicesApi* | [**get_device**](docs/DevicesApi.md#get_device) | **GET** /devices/{device_id} | One device.
*DevicesApi* | [**get_own_device**](docs/DevicesApi.md#get_own_device) | **GET** /devices/self | The device the calling token is bound to.
*DevicesApi* | [**list_devices**](docs/DevicesApi.md#list_devices) | **GET** /devices | List devices, including revoked ones and the built-in server device.
*DevicesApi* | [**pair_device**](docs/DevicesApi.md#pair_device) | **POST** /devices/pair | Exchange a pairing code for a long-lived device token.
*DevicesApi* | [**revoke_device**](docs/DevicesApi.md#revoke_device) | **DELETE** /devices/{device_id} | Revoke a device and all its tokens.
*DevicesApi* | [**update_device**](docs/DevicesApi.md#update_device) | **PATCH** /devices/{device_id} | Rename a device or change its push topic.
*DevicesApi* | [**update_own_device**](docs/DevicesApi.md#update_own_device) | **PATCH** /devices/self | Rename the caller&#39;s device or change its push topic.
*DictionaryApi* | [**get_dict_entry**](docs/DictionaryApi.md#get_dict_entry) | **GET** /dict/entries/{seq} | One JMdict entry with frequency, pitch, kanji and your status.
*DictionaryApi* | [**get_dict_release**](docs/DictionaryApi.md#get_dict_release) | **GET** /dict/release | The current dictionary release and its downloadable device builds.
*DictionaryApi* | [**get_kanji**](docs/DictionaryApi.md#get_kanji) | **GET** /kanji/{literal} | One kanji from KANJIDIC2 with components and your kanji note, if any.
*DictionaryApi* | [**list_entry_examples**](docs/DictionaryApi.md#list_entry_examples) | **GET** /dict/entries/{seq}/examples | Example sentences for an entry, from your media and from Tatoeba.
*DictionaryApi* | [**refresh_dictionary**](docs/DictionaryApi.md#refresh_dictionary) | **POST** /dict/refresh | Queue a dictionary refresh (download sources, build and load a new release).
*DictionaryApi* | [**search_dictionary**](docs/DictionaryApi.md#search_dictionary) | **GET** /dict/search | Search JMdict entries by kanji, kana, romaji or English.
*EventsApi* | [**stream_events**](docs/EventsApi.md#stream_events) | **GET** /events | Server-sent events - job progress, sync activity, new notes, device changes.
*ExportApi* | [**export_all**](docs/ExportApi.md#export_all) | **GET** /export | Full JSON dump of your data.
*ExportApi* | [**export_anki**](docs/ExportApi.md#export_anki) | **GET** /export/anki | Export notes and cards as an Anki package (.apkg).
*GrammarApi* | [**create_grammar_point**](docs/GrammarApi.md#create_grammar_point) | **POST** /grammar | Create a grammar point.
*GrammarApi* | [**delete_grammar_point**](docs/GrammarApi.md#delete_grammar_point) | **DELETE** /grammar/{grammar_point_id} | Delete a grammar point.
*GrammarApi* | [**get_grammar_point**](docs/GrammarApi.md#get_grammar_point) | **GET** /grammar/{grammar_point_id} | One grammar point.
*GrammarApi* | [**list_grammar_examples**](docs/GrammarApi.md#list_grammar_examples) | **GET** /grammar/{grammar_point_id}/examples | Sentences where the grammar point was seen (detected or highlighted by hand).
*GrammarApi* | [**list_grammar_points**](docs/GrammarApi.md#list_grammar_points) | **GET** /grammar | List or search the grammar library.
*GrammarApi* | [**put_grammar_span**](docs/GrammarApi.md#put_grammar_span) | **PUT** /grammar/{grammar_point_id}/spans/{sentence_id}/{start_offset}/{end_offset} | Highlight a span of a sentence as an example of this grammar point (\&quot;I saw something weird\&quot;).
*GrammarApi* | [**remove_grammar_span**](docs/GrammarApi.md#remove_grammar_span) | **DELETE** /grammar/{grammar_point_id}/spans/{sentence_id}/{start_offset}/{end_offset} | Remove a manual span (reversible by putting it again).
*GrammarApi* | [**test_grammar_rules**](docs/GrammarApi.md#test_grammar_rules) | **POST** /grammar/{grammar_point_id}/rules/test | Run detection rules against your sentences without saving anything.
*GrammarApi* | [**update_grammar_point**](docs/GrammarApi.md#update_grammar_point) | **PATCH** /grammar/{grammar_point_id} | Edit a grammar point.
*JobsApi* | [**cancel_job**](docs/JobsApi.md#cancel_job) | **POST** /jobs/{job_id}/cancel | Cancel a queued or running job.
*JobsApi* | [**get_job**](docs/JobsApi.md#get_job) | **GET** /jobs/{job_id} | One job with progress, result or error.
*JobsApi* | [**list_jobs**](docs/JobsApi.md#list_jobs) | **GET** /jobs | List background jobs (imports, parses, optimizer runs, ...).
*JobsApi* | [**synthesize_speech**](docs/JobsApi.md#synthesize_speech) | **POST** /tts | Synthesize Japanese speech (VOICEVOX) into an audio blob.
*MediaApi* | [**clear_jpdb_vocab_resolution**](docs/MediaApi.md#clear_jpdb_vocab_resolution) | **DELETE** /import/jpdb/vocab/{jpdb_vid}/resolution | Clear a correction - the vocabulary item goes back to the review queue.
*MediaApi* | [**delete_media**](docs/MediaApi.md#delete_media) | **DELETE** /media/{media_id} | Delete a media, its deck and its parsed content.
*MediaApi* | [**get_media**](docs/MediaApi.md#get_media) | **GET** /media/{media_id} | One media with its segments.
*MediaApi* | [**get_media_coverage**](docs/MediaApi.md#get_media_coverage) | **GET** /media/{media_id}/coverage | Coverage of a media (or one segment) by your word statuses.
*MediaApi* | [**get_sentence**](docs/MediaApi.md#get_sentence) | **GET** /sentences/{sentence_id} | One sentence with tokens and grammar spans.
*MediaApi* | [**import_jpdb_deck**](docs/MediaApi.md#import_jpdb_deck) | **POST** /media/import/jpdb | Import a pre-parsed jpdb media deck (vocabulary with occurrence counts).
*MediaApi* | [**import_jpdb_history**](docs/MediaApi.md#import_jpdb_history) | **POST** /import/jpdb/history | Import jpdb review history and word statuses from a jpdb export file.
*MediaApi* | [**import_media**](docs/MediaApi.md#import_media) | **POST** /media/import | Import your own file(s), a URL or pasted text as media (worker job).
*MediaApi* | [**list_jpdb_vocab**](docs/MediaApi.md#list_jpdb_vocab) | **GET** /import/jpdb/vocab | The jpdb mapping review queue - jpdb vocabulary that did not map to a JMdict entry.
*MediaApi* | [**list_media**](docs/MediaApi.md#list_media) | **GET** /media | List the media library.
*MediaApi* | [**list_media_sentences**](docs/MediaApi.md#list_media_sentences) | **GET** /media/{media_id}/sentences | Sentences of a media, optionally only i+1 sentences.
*MediaApi* | [**list_sentences**](docs/MediaApi.md#list_sentences) | **GET** /sentences | Search sentences across all media.
*MediaApi* | [**put_jpdb_vocab_resolution**](docs/MediaApi.md#put_jpdb_vocab_resolution) | **PUT** /import/jpdb/vocab/{jpdb_vid}/resolution | Map a jpdb vocabulary item to a JMdict entry, or ignore it.
*MediaApi* | [**reparse_media**](docs/MediaApi.md#reparse_media) | **POST** /media/{media_id}/reparse | Re-parse a media (after parse overrides or a dictionary refresh).
*MediaApi* | [**update_media**](docs/MediaApi.md#update_media) | **PATCH** /media/{media_id} | Change a media&#39;s title, kind or cover.
*MetaApi* | [**get_health**](docs/MetaApi.md#get_health) | **GET** /health | Liveness and readiness probe.
*MetaApi* | [**get_meta**](docs/MetaApi.md#get_meta) | **GET** /meta | Server versions, limits and sync head.
*NoteTypesApi* | [**create_note_type**](docs/NoteTypesApi.md#create_note_type) | **POST** /note-types | Create a note type.
*NoteTypesApi* | [**delete_note_type**](docs/NoteTypesApi.md#delete_note_type) | **DELETE** /note-types/{note_type_id} | Delete one of your own note types.
*NoteTypesApi* | [**get_note_type**](docs/NoteTypesApi.md#get_note_type) | **GET** /note-types/{note_type_id} | One note type.
*NoteTypesApi* | [**list_note_types**](docs/NoteTypesApi.md#list_note_types) | **GET** /note-types | List note types (built-ins and your own).
*NoteTypesApi* | [**update_note_type**](docs/NoteTypesApi.md#update_note_type) | **PATCH** /note-types/{note_type_id} | Edit a note type&#39;s name, fields, templates or CSS.
*NotesApi* | [**create_note**](docs/NotesApi.md#create_note) | **POST** /notes | Create a note and its cards.
*NotesApi* | [**delete_note**](docs/NotesApi.md#delete_note) | **DELETE** /notes/{note_id} | Delete a note (permanent tombstone; hides all its cards).
*NotesApi* | [**get_note**](docs/NotesApi.md#get_note) | **GET** /notes/{note_id} | One note with its live cards.
*NotesApi* | [**list_notes**](docs/NotesApi.md#list_notes) | **GET** /notes | List or search notes.
*NotesApi* | [**update_note**](docs/NotesApi.md#update_note) | **PATCH** /notes/{note_id} | Edit a note&#39;s fields, tags, mnemonic, source sentence or dictionary match.
*ParsingApi* | [**delete_parse_override**](docs/ParsingApi.md#delete_parse_override) | **DELETE** /parse-overrides/{override_id} | Delete a tokenisation correction (permanent).
*ParsingApi* | [**list_parse_overrides**](docs/ParsingApi.md#list_parse_overrides) | **GET** /parse-overrides | List your tokenisation corrections.
*ParsingApi* | [**parse_text**](docs/ParsingApi.md#parse_text) | **POST** /parse | Parse text - tokens, matched entries and their known status.
*ParsingApi* | [**put_parse_override**](docs/ParsingApi.md#put_parse_override) | **PUT** /parse-overrides/{override_id} | Create or replace a tokenisation correction.
*PresetsApi* | [**create_preset**](docs/PresetsApi.md#create_preset) | **POST** /presets | Create a preset.
*PresetsApi* | [**delete_preset**](docs/PresetsApi.md#delete_preset) | **DELETE** /presets/{preset_id} | Delete a preset.
*PresetsApi* | [**get_preset**](docs/PresetsApi.md#get_preset) | **GET** /presets/{preset_id} | One preset.
*PresetsApi* | [**list_presets**](docs/PresetsApi.md#list_presets) | **GET** /presets | List scheduling presets.
*PresetsApi* | [**optimize_preset**](docs/PresetsApi.md#optimize_preset) | **POST** /presets/{preset_id}/optimize | Queue an FSRS optimizer run for this preset.
*PresetsApi* | [**update_preset**](docs/PresetsApi.md#update_preset) | **PATCH** /presets/{preset_id} | Change preset options.
*ReviewApi* | [**answer_review**](docs/ReviewApi.md#answer_review) | **POST** /review/answer | Grade a card.
*ReviewApi* | [**create_drill_session**](docs/ReviewApi.md#create_drill_session) | **POST** /drill/session | Build an extra-practice session that does not touch scheduling.
*ReviewApi* | [**get_review_queue**](docs/ReviewApi.md#get_review_queue) | **GET** /review/queue | The next cards to review, built by the shared core&#39;s queue builder.
*ReviewApi* | [**undo_review**](docs/ReviewApi.md#undo_review) | **POST** /review/undo | Undo a review.
*SettingsApi* | [**list_settings**](docs/SettingsApi.md#list_settings) | **GET** /settings | All stored settings.
*SettingsApi* | [**update_settings**](docs/SettingsApi.md#update_settings) | **PATCH** /settings | Set one or more settings.
*StatsApi* | [**get_stats_calibration**](docs/StatsApi.md#get_stats_calibration) | **GET** /stats/calibration | FSRS calibration - predicted versus actual recall.
*StatsApi* | [**get_stats_forecast**](docs/StatsApi.md#get_stats_forecast) | **GET** /stats/forecast | Due forecast per study day, plus the next 24 hours by hour.
*StatsApi* | [**get_stats_heatmap**](docs/StatsApi.md#get_stats_heatmap) | **GET** /stats/heatmap | Reviews per study day.
*StatsApi* | [**get_stats_known_words**](docs/StatsApi.md#get_stats_known_words) | **GET** /stats/known-words | Known words over time.
*StatsApi* | [**get_stats_retention**](docs/StatsApi.md#get_stats_retention) | **GET** /stats/retention | Retention (share of reviews not rated Again), grouped.
*StatsApi* | [**get_stats_summary**](docs/StatsApi.md#get_stats_summary) | **GET** /stats/summary | Today&#39;s counts for home screens (due, new, done, time).
*SyncApi* | [**sync_pull**](docs/SyncApi.md#sync_pull) | **GET** /sync/pull | Pull logged operations after a sequence number.
*SyncApi* | [**sync_push**](docs/SyncApi.md#sync_push) | **POST** /sync/push | Push a batch of locally minted operations.
*TokensApi* | [**create_token**](docs/TokensApi.md#create_token) | **POST** /tokens | Create a personal access token.
*TokensApi* | [**get_token**](docs/TokensApi.md#get_token) | **GET** /tokens/{token_id} | One credential.
*TokensApi* | [**list_tokens**](docs/TokensApi.md#list_tokens) | **GET** /tokens | List credentials of every kind (never the token strings or hashes).
*TokensApi* | [**revoke_token**](docs/TokensApi.md#revoke_token) | **DELETE** /tokens/{token_id} | Revoke a credential of any kind.
*WordsApi* | [**clear_word_status**](docs/WordsApi.md#clear_word_status) | **DELETE** /words/{seq}/status | Clear the override; the status falls back to the derived one.
*WordsApi* | [**get_word_status**](docs/WordsApi.md#get_word_status) | **GET** /words/{seq}/status | Word status of one entry.
*WordsApi* | [**list_confusables**](docs/WordsApi.md#list_confusables) | **GET** /confusables | All confusable pairs (any note type).
*WordsApi* | [**list_hot_kanji**](docs/WordsApi.md#list_hot_kanji) | **GET** /kanji/hot | Hot kanji - recent failures rolled up by kanji.
*WordsApi* | [**list_hot_words**](docs/WordsApi.md#list_hot_words) | **GET** /words/hot | Hot words - recent struggle, not lifetime.
*WordsApi* | [**list_leeches**](docs/WordsApi.md#list_leeches) | **GET** /words/leeches | Leech cards (lapses at or above &#x60;scheduler.leech_threshold&#x60;).
*WordsApi* | [**list_word_confusables**](docs/WordsApi.md#list_word_confusables) | **GET** /words/{seq}/confusables | Confusable partners of the word&#39;s note.
*WordsApi* | [**set_confusable**](docs/WordsApi.md#set_confusable) | **PUT** /confusables/{note_a_id}/{note_b_id}/{reason} | Confirm or reject a suggested pair, or record a pair yourself.
*WordsApi* | [**set_word_status**](docs/WordsApi.md#set_word_status) | **PUT** /words/{seq}/status | Set an explicit status override (known, never-forget, blacklisted, ignored).


## Documentation For Models

 - [AnkiConnectRequest](docs/AnkiConnectRequest.md)
 - [AnkiConnectResponse](docs/AnkiConnectResponse.md)
 - [Blob](docs/Blob.md)
 - [BlobQuery](docs/BlobQuery.md)
 - [BlobQueryResult](docs/BlobQueryResult.md)
 - [Card](docs/Card.md)
 - [CardBulkAction](docs/CardBulkAction.md)
 - [CardBulkRequest](docs/CardBulkRequest.md)
 - [CardBulkResult](docs/CardBulkResult.md)
 - [CardBulkScheduleRequest](docs/CardBulkScheduleRequest.md)
 - [CardBulkScheduleRequestAction](docs/CardBulkScheduleRequestAction.md)
 - [CardBuriedSchema](docs/CardBuriedSchema.md)
 - [CardCreate](docs/CardCreate.md)
 - [CardCreatedSchema](docs/CardCreatedSchema.md)
 - [CardDeletedSchema](docs/CardDeletedSchema.md)
 - [CardFlagSetSchema](docs/CardFlagSetSchema.md)
 - [CardMergedSchema](docs/CardMergedSchema.md)
 - [CardPage](docs/CardPage.md)
 - [CardPatch](docs/CardPatch.md)
 - [CardPresetSetSchema](docs/CardPresetSetSchema.md)
 - [CardState](docs/CardState.md)
 - [CardSuspendedSchema](docs/CardSuspendedSchema.md)
 - [CardTemplate](docs/CardTemplate.md)
 - [CardUnburiedSchema](docs/CardUnburiedSchema.md)
 - [CardUnsuspendedSchema](docs/CardUnsuspendedSchema.md)
 - [Confusable](docs/Confusable.md)
 - [ConfusableConfirmedSchema](docs/ConfusableConfirmedSchema.md)
 - [ConfusableList](docs/ConfusableList.md)
 - [ConfusablePage](docs/ConfusablePage.md)
 - [ConfusableReason](docs/ConfusableReason.md)
 - [ConfusableRejectedSchema](docs/ConfusableRejectedSchema.md)
 - [ConfusableSet](docs/ConfusableSet.md)
 - [ConfusableStatus](docs/ConfusableStatus.md)
 - [ConfusableSuggestedSchema](docs/ConfusableSuggestedSchema.md)
 - [Coverage](docs/Coverage.md)
 - [CoverageCounts](docs/CoverageCounts.md)
 - [CoverageCurveInner](docs/CoverageCurveInner.md)
 - [CoverageDifficulty](docs/CoverageDifficulty.md)
 - [CoverageSegmentsInner](docs/CoverageSegmentsInner.md)
 - [CoverageTokens](docs/CoverageTokens.md)
 - [Deck](docs/Deck.md)
 - [DeckContentPublishedSchema](docs/DeckContentPublishedSchema.md)
 - [DeckCreate](docs/DeckCreate.md)
 - [DeckCreatedSchema](docs/DeckCreatedSchema.md)
 - [DeckDeletedSchema](docs/DeckDeletedSchema.md)
 - [DeckItem](docs/DeckItem.md)
 - [DeckItemList](docs/DeckItemList.md)
 - [DeckItemPage](docs/DeckItemPage.md)
 - [DeckItemPut](docs/DeckItemPut.md)
 - [DeckItemPutSchema](docs/DeckItemPutSchema.md)
 - [DeckItemRemovedSchema](docs/DeckItemRemovedSchema.md)
 - [DeckItemsAdd](docs/DeckItemsAdd.md)
 - [DeckItemsAddItemsInner](docs/DeckItemsAddItemsInner.md)
 - [DeckList](docs/DeckList.md)
 - [DeckListCatalogue](docs/DeckListCatalogue.md)
 - [DeckListCatalogueEntry](docs/DeckListCatalogueEntry.md)
 - [DeckPatch](docs/DeckPatch.md)
 - [DeckSettings](docs/DeckSettings.md)
 - [DeckType](docs/DeckType.md)
 - [DeckUpdatedSchema](docs/DeckUpdatedSchema.md)
 - [DeckUpdatedSchemaSet](docs/DeckUpdatedSchemaSet.md)
 - [DetectionRule](docs/DetectionRule.md)
 - [Device](docs/Device.md)
 - [DeviceList](docs/DeviceList.md)
 - [DevicePatch](docs/DevicePatch.md)
 - [DictEntry](docs/DictEntry.md)
 - [DictEntryFreqsInner](docs/DictEntryFreqsInner.md)
 - [DictEntryKanjiFormsInner](docs/DictEntryKanjiFormsInner.md)
 - [DictEntryPitchInner](docs/DictEntryPitchInner.md)
 - [DictEntryReadingsInner](docs/DictEntryReadingsInner.md)
 - [DictEntrySensesInner](docs/DictEntrySensesInner.md)
 - [DictEntrySensesInnerGlossInner](docs/DictEntrySensesInnerGlossInner.md)
 - [DictEntrySummary](docs/DictEntrySummary.md)
 - [DictEntrySummaryPage](docs/DictEntrySummaryPage.md)
 - [DictRelease](docs/DictRelease.md)
 - [DictReleaseArtifactsInner](docs/DictReleaseArtifactsInner.md)
 - [DictReleaseFreqListsInner](docs/DictReleaseFreqListsInner.md)
 - [DrillSession](docs/DrillSession.md)
 - [DrillSessionRequest](docs/DrillSessionRequest.md)
 - [EntryExamples](docs/EntryExamples.md)
 - [Event](docs/Event.md)
 - [EventDevice](docs/EventDevice.md)
 - [EventHello](docs/EventHello.md)
 - [EventNoteCreated](docs/EventNoteCreated.md)
 - [EventSyncChanged](docs/EventSyncChanged.md)
 - [EventType](docs/EventType.md)
 - [ExportDump](docs/ExportDump.md)
 - [FieldError](docs/FieldError.md)
 - [FsrsParamsCreatedSchema](docs/FsrsParamsCreatedSchema.md)
 - [FsrsParamsDeletedSchema](docs/FsrsParamsDeletedSchema.md)
 - [FsrsParamsPublishedSchema](docs/FsrsParamsPublishedSchema.md)
 - [FsrsParamsPublishedSchemaOptimizerStats](docs/FsrsParamsPublishedSchemaOptimizerStats.md)
 - [FsrsParamsUpdatedSchema](docs/FsrsParamsUpdatedSchema.md)
 - [FsrsParamsUpdatedSchemaSet](docs/FsrsParamsUpdatedSchemaSet.md)
 - [GrammarCreatedSchema](docs/GrammarCreatedSchema.md)
 - [GrammarCreatedSchemaDetectionRulesInner](docs/GrammarCreatedSchemaDetectionRulesInner.md)
 - [GrammarCreatedSchemaLinksInner](docs/GrammarCreatedSchemaLinksInner.md)
 - [GrammarDeletedSchema](docs/GrammarDeletedSchema.md)
 - [GrammarLink](docs/GrammarLink.md)
 - [GrammarPoint](docs/GrammarPoint.md)
 - [GrammarPointCreate](docs/GrammarPointCreate.md)
 - [GrammarPointPage](docs/GrammarPointPage.md)
 - [GrammarPointPatch](docs/GrammarPointPatch.md)
 - [GrammarRuleTestRequest](docs/GrammarRuleTestRequest.md)
 - [GrammarRuleTestResult](docs/GrammarRuleTestResult.md)
 - [GrammarRuleTestResultMatchesInner](docs/GrammarRuleTestResultMatchesInner.md)
 - [GrammarRuleTestResultMatchesInnerSpansInner](docs/GrammarRuleTestResultMatchesInnerSpansInner.md)
 - [GrammarSpan](docs/GrammarSpan.md)
 - [GrammarSpanPut](docs/GrammarSpanPut.md)
 - [GrammarSpanPutSchema](docs/GrammarSpanPutSchema.md)
 - [GrammarSpanRef](docs/GrammarSpanRef.md)
 - [GrammarSpanRemovedSchema](docs/GrammarSpanRemovedSchema.md)
 - [GrammarUpdatedSchema](docs/GrammarUpdatedSchema.md)
 - [GrammarUpdatedSchemaSet](docs/GrammarUpdatedSchemaSet.md)
 - [Health](docs/Health.md)
 - [HotKanji](docs/HotKanji.md)
 - [HotKanjiList](docs/HotKanjiList.md)
 - [HotWord](docs/HotWord.md)
 - [HotWordList](docs/HotWordList.md)
 - [Job](docs/Job.md)
 - [JobKind](docs/JobKind.md)
 - [JobPage](docs/JobPage.md)
 - [JobPayloadDeckBuild](docs/JobPayloadDeckBuild.md)
 - [JobPayloadDictRefresh](docs/JobPayloadDictRefresh.md)
 - [JobPayloadFsrsOptimize](docs/JobPayloadFsrsOptimize.md)
 - [JobPayloadGrammarDetect](docs/JobPayloadGrammarDetect.md)
 - [JobPayloadMediaParse](docs/JobPayloadMediaParse.md)
 - [JobResultDeckBuild](docs/JobResultDeckBuild.md)
 - [JobResultDictRefresh](docs/JobResultDictRefresh.md)
 - [JobResultFsrsOptimize](docs/JobResultFsrsOptimize.md)
 - [JobResultGrammarDetect](docs/JobResultGrammarDetect.md)
 - [JobResultGrammarDetectFanOut](docs/JobResultGrammarDetectFanOut.md)
 - [JobResultGrammarDetectMedia](docs/JobResultGrammarDetectMedia.md)
 - [JobResultJpdbHistory](docs/JobResultJpdbHistory.md)
 - [JobResultJpdbHistoryKanjiUnmappedInner](docs/JobResultJpdbHistoryKanjiUnmappedInner.md)
 - [JobResultJpdbHistoryUnmappedInner](docs/JobResultJpdbHistoryUnmappedInner.md)
 - [JobResultMedia](docs/JobResultMedia.md)
 - [JobResultMediaUnmappedInner](docs/JobResultMediaUnmappedInner.md)
 - [JobResultTts](docs/JobResultTts.md)
 - [JobStatus](docs/JobStatus.md)
 - [JpdbDeckImportRequest](docs/JpdbDeckImportRequest.md)
 - [JpdbHistoryImportRequest](docs/JpdbHistoryImportRequest.md)
 - [JpdbVocab](docs/JpdbVocab.md)
 - [JpdbVocabMedia](docs/JpdbVocabMedia.md)
 - [JpdbVocabPage](docs/JpdbVocabPage.md)
 - [JpdbVocabResolution](docs/JpdbVocabResolution.md)
 - [JpdbVocabResolutionPut](docs/JpdbVocabResolutionPut.md)
 - [JpdbVocabResolutionResult](docs/JpdbVocabResolutionResult.md)
 - [Kanji](docs/Kanji.md)
 - [LoginRequest](docs/LoginRequest.md)
 - [Media](docs/Media.md)
 - [MediaContentPublishedSchema](docs/MediaContentPublishedSchema.md)
 - [MediaCreatedSchema](docs/MediaCreatedSchema.md)
 - [MediaDeckOptions](docs/MediaDeckOptions.md)
 - [MediaDeletedSchema](docs/MediaDeletedSchema.md)
 - [MediaImportFile](docs/MediaImportFile.md)
 - [MediaImportRequest](docs/MediaImportRequest.md)
 - [MediaImportRequestOptions](docs/MediaImportRequestOptions.md)
 - [MediaKind](docs/MediaKind.md)
 - [MediaPage](docs/MediaPage.md)
 - [MediaPatch](docs/MediaPatch.md)
 - [MediaSegment](docs/MediaSegment.md)
 - [MediaUpdatedSchema](docs/MediaUpdatedSchema.md)
 - [MediaUpdatedSchemaSet](docs/MediaUpdatedSchemaSet.md)
 - [Meta](docs/Meta.md)
 - [MetaDictRelease](docs/MetaDictRelease.md)
 - [MetaLimits](docs/MetaLimits.md)
 - [Note](docs/Note.md)
 - [NoteBaseType](docs/NoteBaseType.md)
 - [NoteCreate](docs/NoteCreate.md)
 - [NoteCreatedSchema](docs/NoteCreatedSchema.md)
 - [NoteDeletedSchema](docs/NoteDeletedSchema.md)
 - [NoteField](docs/NoteField.md)
 - [NoteMergedSchema](docs/NoteMergedSchema.md)
 - [NotePage](docs/NotePage.md)
 - [NotePatch](docs/NotePatch.md)
 - [NoteSummary](docs/NoteSummary.md)
 - [NoteType](docs/NoteType.md)
 - [NoteTypeCreate](docs/NoteTypeCreate.md)
 - [NoteTypeCreatedSchema](docs/NoteTypeCreatedSchema.md)
 - [NoteTypeCreatedSchemaFieldsInner](docs/NoteTypeCreatedSchemaFieldsInner.md)
 - [NoteTypeDeletedSchema](docs/NoteTypeDeletedSchema.md)
 - [NoteTypeList](docs/NoteTypeList.md)
 - [NoteTypePatch](docs/NoteTypePatch.md)
 - [NoteTypeUpdatedSchema](docs/NoteTypeUpdatedSchema.md)
 - [NoteTypeUpdatedSchemaSet](docs/NoteTypeUpdatedSchemaSet.md)
 - [NoteUpdatedSchema](docs/NoteUpdatedSchema.md)
 - [NoteUpdatedSchemaSet](docs/NoteUpdatedSchemaSet.md)
 - [OpSchema](docs/OpSchema.md)
 - [OverrideToken](docs/OverrideToken.md)
 - [PairRequest](docs/PairRequest.md)
 - [PairResponse](docs/PairResponse.md)
 - [Pairing](docs/Pairing.md)
 - [ParseOverride](docs/ParseOverride.md)
 - [ParseOverrideDeletedSchema](docs/ParseOverrideDeletedSchema.md)
 - [ParseOverridePage](docs/ParseOverridePage.md)
 - [ParseOverridePut](docs/ParseOverridePut.md)
 - [ParseOverridePutSchema](docs/ParseOverridePutSchema.md)
 - [ParseOverridePutSchemaTokensInner](docs/ParseOverridePutSchemaTokensInner.md)
 - [ParseRequest](docs/ParseRequest.md)
 - [ParseResult](docs/ParseResult.md)
 - [ParsedSentence](docs/ParsedSentence.md)
 - [Preset](docs/Preset.md)
 - [PresetCreate](docs/PresetCreate.md)
 - [PresetList](docs/PresetList.md)
 - [PresetPatch](docs/PresetPatch.md)
 - [Principal](docs/Principal.md)
 - [Problem](docs/Problem.md)
 - [PullResponse](docs/PullResponse.md)
 - [PushRequest](docs/PushRequest.md)
 - [PushRequestOpsInner](docs/PushRequestOpsInner.md)
 - [PushResponse](docs/PushResponse.md)
 - [PushResult](docs/PushResult.md)
 - [PushResultError](docs/PushResultError.md)
 - [QueueItem](docs/QueueItem.md)
 - [RetentionGroup](docs/RetentionGroup.md)
 - [ReviewAnswerRequest](docs/ReviewAnswerRequest.md)
 - [ReviewButton](docs/ReviewButton.md)
 - [ReviewLog](docs/ReviewLog.md)
 - [ReviewLoggedSchema](docs/ReviewLoggedSchema.md)
 - [ReviewPage](docs/ReviewPage.md)
 - [ReviewQueue](docs/ReviewQueue.md)
 - [ReviewQueueCounts](docs/ReviewQueueCounts.md)
 - [ReviewResult](docs/ReviewResult.md)
 - [ReviewType](docs/ReviewType.md)
 - [ReviewUndoRequest](docs/ReviewUndoRequest.md)
 - [ReviewUndoneSchema](docs/ReviewUndoneSchema.md)
 - [SchedulePreview](docs/SchedulePreview.md)
 - [SchedulePreviewOption](docs/SchedulePreviewOption.md)
 - [Scope](docs/Scope.md)
 - [Sentence](docs/Sentence.md)
 - [SentencePage](docs/SentencePage.md)
 - [SentenceToken](docs/SentenceToken.md)
 - [Setting](docs/Setting.md)
 - [SettingList](docs/SettingList.md)
 - [SettingsPatch](docs/SettingsPatch.md)
 - [SettingsSetSchema](docs/SettingsSetSchema.md)
 - [SpanOrigin](docs/SpanOrigin.md)
 - [StatsCalibration](docs/StatsCalibration.md)
 - [StatsCalibrationBinsInner](docs/StatsCalibrationBinsInner.md)
 - [StatsForecast](docs/StatsForecast.md)
 - [StatsForecastDaysInner](docs/StatsForecastDaysInner.md)
 - [StatsForecastHoursInner](docs/StatsForecastHoursInner.md)
 - [StatsHeatmap](docs/StatsHeatmap.md)
 - [StatsHeatmapDaysInner](docs/StatsHeatmapDaysInner.md)
 - [StatsKnownWords](docs/StatsKnownWords.md)
 - [StatsKnownWordsCurrent](docs/StatsKnownWordsCurrent.md)
 - [StatsKnownWordsSeriesInner](docs/StatsKnownWordsSeriesInner.md)
 - [StatsRetention](docs/StatsRetention.md)
 - [StatsSummary](docs/StatsSummary.md)
 - [StatsSummaryDoneToday](docs/StatsSummaryDoneToday.md)
 - [StatsSummaryDue](docs/StatsSummaryDue.md)
 - [StatsSummaryTotals](docs/StatsSummaryTotals.md)
 - [TatoebaSentence](docs/TatoebaSentence.md)
 - [Template](docs/Template.md)
 - [Token](docs/Token.md)
 - [TokenCreate](docs/TokenCreate.md)
 - [TokenCreated](docs/TokenCreated.md)
 - [TokenKind](docs/TokenKind.md)
 - [TokenList](docs/TokenList.md)
 - [TtsRequest](docs/TtsRequest.md)
 - [WordStatus](docs/WordStatus.md)
 - [WordStatusClearedSchema](docs/WordStatusClearedSchema.md)
 - [WordStatusSet](docs/WordStatusSet.md)
 - [WordStatusSetSchema](docs/WordStatusSetSchema.md)
 - [WordStatusValue](docs/WordStatusValue.md)


<a id="documentation-for-authorization"></a>
## Documentation For Authorization


Authentication schemes defined for the API:
<a id="bearerAuth"></a>
### bearerAuth

- **Type**: Bearer authentication

<a id="sessionCookie"></a>
### sessionCookie

- **Type**: API key
- **API key parameter name**: kotobaya_session
- **Location**: 


## Author




## Request headers

Header names are compared case-insensitively in all Python client libraries.
Request headers are copied; supplying `_headers` does not change the caller's dict
or the client's shared defaults. The public `_headers` argument retains its dict
validation and existing value serialization. Pydantic can coerce mappings such as HTTPX `Headers` to a dict,
combining repeated fields into a single value; `_headers` does not provide a
repeated-field or list-of-pairs API.

Precedence, from lowest to highest:

1. `_headers` (within one dict, the last inserted case variant wins).
2. Explicit OpenAPI header parameters and generated `Content-Type` (or `_content_type`).
   Generated `Accept` is added only when neither `_headers` nor explicit header
   parameters contain an `Accept` case variant.
3. `ApiClient.default_headers` (including headers set with `set_default_header`).
4. The client's `cookie` setting, for `Cookie`.
5. Authentication headers. A non-empty `_request_auth` replaces configured
   authentication for that call; `{}` retains configured authentication. Cookie
   authentication appends to the selected `Cookie` value.

This retains the existing precedence for identically spelled names and extends it
to case variants. To override a generated content type, use `_content_type`;
client defaults still take precedence over it. The winning header's spelling and
value are retained for replacements. Cookie authentication uses the canonical
`Cookie` spelling and appends its value. Values containing legitimate
comma-separated lists are not split or deduplicated. This does not remove
duplicate values inside a header or change the handling of repeated fields
supplied directly to the transport.
