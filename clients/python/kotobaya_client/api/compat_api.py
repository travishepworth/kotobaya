"""
    Kotobaya API

    Versioned REST/JSON interface at `/api/v1`. The web app uses nothing else; the Android app uses `sync`, `blobs`, `dict/release`, `devices/pair`, `devices/self` and whatever conveniences it likes.  Implementers: this file is the contract. If something you need is missing or wrong, do not work around it; return `CONTRACT_CHANGE_REQUEST`. Additive changes stay in v1; breaking changes go to `/api/v2`.  ## 1. Conventions  - **JSON.** Request and response bodies are `application/json; charset=utf-8` unless stated.   Property names are `snake_case` and equal the column names of `contracts/db/V1__init.sql`   wherever a property mirrors a column; the column `COMMENT` is then the definition. - **Unknown properties.** Servers reject unknown request properties and unknown query   parameters with `422 validation_failed`. Clients MUST ignore unknown response properties and   MUST treat unknown enum values in responses as \"other\" (v1 may add both). - **Absent vs null.** In `PATCH` bodies an absent property means \"leave unchanged\"; `null` means   \"set to NULL\" where the property is nullable and is a validation error otherwise.   Responses always include every documented property; nullable ones are `null`, never absent,   unless the property is documented as \"omitted when ...\". - **Ids.** Row ids are UUIDv7 in canonical lowercase `8-4-4-4-12` text. Uppercase ids in   requests are rejected with `422`. \"Entry\" always means a JMdict sequence number (`seq`,   JSON integer). - **Time.** Instants are RFC 3339 UTC strings with millisecond precision and a `Z` suffix,   e.g. `2026-11-12T09:30:00.000Z`. Servers accept any RFC 3339 offset on input and normalise   to UTC. A \"study day\" is a local date `YYYY-MM-DD` computed from the settings   `scheduler.timezone` and `scheduler.day_rollover_hour`: study day D covers local time   `D rollover:00` (inclusive) to `D+1 rollover:00` (exclusive). - **Text offsets** into sentence text are UTF-16 code units, half-open `[start, end)`. - **Blobs** (audio, images, uploads, snapshots) are addressed by the lowercase hex SHA-256 of   their bytes and transferred through `/blobs/{hash}`, never inline in JSON.  ## 2. Authentication and scopes  Three credential kinds, all rows of `app.api_token` (stored hashed):  | Kind | How obtained | Sent as | Scopes | |---|---|---|---| | `session` | `POST /auth/login` | cookie `kotobaya_session` (HTTP-only) | all five | | `device` | `POST /devices/pair` | `Authorization: Bearer kbd_...` | `read`, `write`, `review` | | `personal` | `POST /tokens` | `Authorization: Bearer kbp_...` | chosen at creation |  Token strings are `<3-letter kind prefix>_<40 characters of [A-Za-z0-9]>` (`kbs_`, `kbd_`, `kbp_`), generated from a CSPRNG. `token_hash` = lowercase hex SHA-256 of the whole string; `token_prefix` = its first 8 characters. A token is valid iff `revoked_at IS NULL` and (`expires_at IS NULL` or `expires_at > now()`) and, when it has a `device_id`, that device is not revoked. Validity is checked on every request (no caching longer than 5 seconds), so revocation takes effect at once.  Scopes are independent (no scope implies another):  | Scope | Grants | |---|---| | `read` | Every `GET` of learning, media and reference data; `POST /parse`; `GET /events`; `GET /sync/pull`; exports. | | `write` | Create, change, delete notes, cards, decks, grammar, note types, presets, settings, word status, parse overrides, media metadata; upload blobs; `POST /sync/push` (non-review ops). | | `review` | `POST /review/answer`, `POST /review/undo`, `POST /drill/session`, `POST /cards/bulk/schedule`; `review.*` ops inside `POST /sync/push` (per op; without it the op is rejected with `insufficient_scope`). | | `import` | Start and cancel import, parse, dictionary and optimizer jobs; upload blobs. | | `admin` | Devices, pairing, tokens (a device's own row: `/devices/self` with `read`/`write`). |  Each operation lists the scope it needs in its `security` requirement: the array after the scheme name. When two alternatives are listed for one scheme the caller needs either. A request with no or an invalid credential gets `401 unauthenticated`; a valid credential lacking the scope gets `403 insufficient_scope`. Operations with `security: []` are public. An empty scope array (`bearerAuth: []`, `sessionCookie: []`) means any valid credential, whatever its scopes: only `GET /auth/session` and `POST /client-errors`.  There are exactly two places where who may call is not just \"scheme + scope\", and both are declared in the operation, not only in prose: - `GET/PATCH /devices/self` (`read` / `write`, bearer only): the token must be bound to a   device, else `403 device_required`. A device never needs `admin` to manage its own row;   `/devices/{device_id}` is admin only. - `POST /ankiconnect` (`x-auth: body-key`): the token may instead be sent in the JSON body   property `key`; the empty `{}` alternative in its `security` only signals that no header is   needed. The scope for `key` is `x-body-key-scope`.  **CSRF.** A request authenticated by the session cookie whose method is not `GET` or `HEAD` MUST carry the header `X-Kotobaya-Csrf: 1`; otherwise `403 csrf_required`. The cookie is set with `HttpOnly; Secure; SameSite=Strict; Path=/api`. The server sends no CORS headers (the web app is served from the same origin). If both a cookie and an `Authorization` header are present, the header wins and the cookie is ignored.  **Audit.** Every request is logged with `token.id` and `token.name` (structured server log; there is no audit table or endpoint in v1). Ops record the token in `change.token_id`.  ## 3. Writes are sync ops  Learning data is only ever changed by applying sync ops (`contracts/sync`). Every REST operation that changes learning data is a convenience wrapper: the server builds the op(s) named in the operation's `x-emits-ops`, applies them through the same op-apply path as `POST /sync/push` in one transaction, and they then appear in `GET /sync/pull` for every device. Op names are those of `contracts/db/SCHEMA.md` section 4; `contracts/sync` is authoritative for names and payloads.  For an op the server builds on behalf of a REST call:  - `op_id` is a fresh UUIDv7; origin `device_id` is the **server device**   `00000000-0000-7000-8000-000000000001` (also for session and device tokens: only one party   ever generates HLCs for a given device id); `hlc` comes from the server's HLC clock;   `change.token_id` is the calling token. - A property absent from a `PATCH` body produces no register write. A property present with   the value the row already has still produces a write (it bumps the register's clock). - If a call changes nothing (documented per operation, e.g. deleting an already deleted   note), no op is emitted and the response is still successful. - **Never an invalid op (C-6).** If a request would make the server build an op that fails   its schema or an op-level rule in `contracts/sync` - a tag containing ECMA-262 whitespace   (e.g. U+3000 or U+00A0), U+0000 in any string (field values and open JSON objects   included), an integer above its op maximum (`2147483647` for int4 columns) or an instant   after `9999-12-31T23:59:59.999Z` - the call is `422 validation_failed` with a field error   at that request property, code `invalid_value` (or `out_of_range` / `too_long` where the   operation's own request bound fails first). Nothing is emitted; never a `500`.   SYNC.md 2.1 \"Storable values\" and \"Input that would mint an invalid op\". - Responses to mutating calls carry the header `Kotobaya-Seq`: the `change.seq` of the last op   the call emitted. A client holding a local replica knows it has seen the effect once its   pull cursor is at or past that value. The header is absent when no op was emitted.  **Idempotent creation.** Create bodies accept an optional client-generated `id` (UUIDv7). Re-sending a create with an `id` that already exists returns the existing resource with status `200` instead of `201` and emits nothing, whatever the rest of the body says. Clients that may retry MUST send `id`. When `id` is omitted the server generates one. `POST /review/answer` requires `id` (a duplicate review would corrupt scheduling); `POST /media/import` takes the same optional idempotency id as `media_id`.  The existence check and the emit are **one transaction that takes advisory lock `7033001` before the check** and holds it until commit (the lock `POST /sync/push` takes per op, SYNC.md 5.2): check whether `id` exists; if not, mint, apply and log the op(s) - for `createNote` the `note.created` and every `card.created` - then commit. Without the lock two concurrent retries of the same create both pass the check; the second `note.created` is a no-op on the row (SYNC.md 7.2) but its `card.created` ops carry fresh card ids, so the note would get duplicate cards. The same holds for every other id-idempotent call (`POST /review/answer`, `POST /review/undo`, `POST /media/import`), and a pushed op that creates the same id races safely because it takes the same lock.  **Tombstones and merges.** Deleted learning rows are tombstones (`deleted_at` set). `GET` by id of a deleted row returns `410 gone` unless `include_deleted=true`; lists omit deleted rows unless `include_deleted=true`. A note or card that lost a merge (`merged_into_id` set) is answered with its survivor: `GET`, `PATCH` and review calls addressed to the loser's id act on the survivor and the response body is the survivor (its `id` differs from the requested id; the response has `Content-Location` with the survivor's URL).  ## 4. Pagination  List operations that take `limit` and `cursor` return `{\"items\": [...], \"next_cursor\": ...}`. `next_cursor` is an opaque string, or `null` on the last page. Pass it back unchanged as `cursor` together with the same other parameters; changing any other parameter while passing a cursor is `422 validation_failed` (`pointer` `/cursor`). `limit` defaults to 50, maximum 500. Order is stable and documented per operation; rows created while paging may or may not appear.  **Cursor semantics (keyset, no snapshot).** A cursor encodes the sort-key values and the `id` of the last row returned; the next page is the rows strictly after that tuple in the current order, evaluated against the data at the time of that request. The order is not snapshotted. Consequently, while a client pages through a list sorted by a mutable key (`due_at`, `updated_at`, `lapses`, ...): - a row whose key changes so that it moves from after the cursor to before it is **skipped**; - a row whose key changes so that it moves from before the cursor to after it is **returned   again** (with its new values); - a row deleted while paging disappears; one created may or may not appear. Rows that do not change are returned exactly once. Clients MUST de-duplicate by `id` (last occurrence wins). A client that needs an exact set (bulk actions, exports) uses an `id`-sorted list where offered, `POST /cards/bulk` with `query`, or `GET /export`. Lists sorted by an immutable key (`id`, `created_at`) have no skips or repeats. An empty result is `{\"items\": [], \"next_cursor\": null}` with status `200`.  ## 5. Errors  Every non-2xx response has the body `application/problem+json` (RFC 9457), schema `Problem`. `code` is the stable machine-readable identifier; `type` is `urn:kotobaya:problem:<code>`.  | Status | `code` | When | |---|---|---| | 400 | `invalid_request` | Body is not valid JSON, wrong content type framing, malformed header; `POST /sync/push` body not valid against `PushRequestLenient`. | | 401 | `unauthenticated` | No credential, or it is unknown, expired or revoked. | | 401 | `invalid_credentials` | Wrong password or TOTP code (`POST /auth/login`). | | 401 | `totp_required` | TOTP is enabled and `totp_code` was not sent. | | 401 | `invalid_pairing_code` | Pairing code unknown, expired or already used. | | 403 | `insufficient_scope` | Credential lacks the scope; `required_scope` names it. | | 403 | `csrf_required` | Cookie-authenticated write without `X-Kotobaya-Csrf`. | | 403 | `device_required` | `POST /sync/push` or `/devices/self` with a credential that has no `device_id`. | | 404 | `not_found` | No such resource (also: id of another resource kind). | | 409 | `already_exists` | Natural-key collision; `existing_id` names the holder. | | 409 | `builtin_immutable` | Deleting a built-in note type or the Default preset, or removing a built-in field. | | 409 | `in_use` | Deleting a note type that live notes use. | | 409 | `deck_read_only` | Changing items or deleting a `media`/`list` deck through deck endpoints. | | 409 | `not_applicable` | The resource kind does not support the operation (e.g. items of a smart deck). | | 409 | `card_not_reviewable` | Card is suspended. | | 409 | `job_not_cancellable` | Job already finished. | | 410 | `gone` | Tombstoned row requested without `include_deleted=true`. | | 413 | `payload_too_large` | Body over the limit in `GET /meta`. On `POST /sync/push` only for the whole batch (the client halves it); one op over `max_op_bytes` is a per-op `op_too_large` result instead. `POST /client-errors`: body over 1 MiB or more than 20 items. | | 415 | `unsupported_media_type` | Wrong `Content-Type`. | | 422 | `validation_failed` | Well-formed but invalid; `errors[]` lists each problem. | | 422 | `invalid_query` | Card search string does not parse; `errors[0].position` is the UTF-16 offset. | | 422 | `bulk_too_large` | Bulk selector matched more than 5000 cards. | | 422 | `hash_mismatch` | Uploaded bytes do not hash to the `{hash}` in the path. | | 429 | `rate_limited` | Too many login or pairing attempts, or too many client error reports (`POST /client-errors`); see `Retry-After`. | | 500 | `internal` | Bug. Safe to retry idempotent calls. | | 503 | `unavailable` | Database or object store unreachable. Retry with backoff. |  Check order: 401, then 403, then 404/410, then 415/400, then 422, then 409.  ## 6. Events  `GET /events` is a server-sent events stream; see that operation. Event payload schemas are `Event*` in components. 

    The version of the OpenAPI document: 1.0.0
    Generated by OpenAPI Generator (https://openapi-generator.tech)

    Do not edit the class manually.
"""  # noqa: E501


import warnings
from pydantic import validate_call, Field, StrictFloat, StrictStr, StrictInt
from typing import Any, Dict, List, Optional, Tuple, Union
from typing_extensions import Annotated

from kotobaya_client.models.anki_connect_request import AnkiConnectRequest
from kotobaya_client.models.anki_connect_response import AnkiConnectResponse

from kotobaya_client.api_client import ApiClient, RequestSerialized
from kotobaya_client.api_response import ApiResponse
from kotobaya_client.rest import RESTResponseType


class CompatApi:
    """NOTE: This class is auto generated by OpenAPI Generator
    Ref: https://openapi-generator.tech

    Do not edit the class manually.
    """

    def __init__(self, api_client=None) -> None:
        if api_client is None:
            api_client = ApiClient.get_default()
        self.api_client = api_client


    @validate_call
    def anki_connect(
        self,
        anki_connect_request: AnkiConnectRequest,
        _request_timeout: Union[
            None,
            Annotated[StrictFloat, Field(gt=0)],
            Tuple[
                Annotated[StrictFloat, Field(gt=0)],
                Annotated[StrictFloat, Field(gt=0)]
            ]
        ] = None,
        _request_auth: Optional[Dict[StrictStr, Any]] = None,
        _content_type: Optional[StrictStr] = None,
        _headers: Optional[Dict[StrictStr, Any]] = None,
        _host_index: Annotated[StrictInt, Field(ge=0, le=0)] = 0,
    ) -> AnkiConnectResponse:
        """AnkiConnect-compatible subset for Yomitan and other mining tools.

        Speaks AnkiConnect protocol version 6: the request is `{\"action\", \"version\", \"params\", \"key\"}`; the response is **always HTTP 200** with `{\"result\", \"error\"}` where exactly one is non-null (`error` is a string). Requests with `version` below 6 or absent are answered in the same v6 shape. Protocol-level failures (unknown action, bad params, auth) are reported in `error`, not as HTTP errors; only a body that is not JSON gets `400`.  **Auth** (`x-auth: body-key`). Either the usual credentials, or - because Yomitan can only send an API key in the body - a personal access token in `key`. The empty alternative `{}` in `security` exists only so that generic clients and gateways let a request without an `Authorization` header or cookie through; it does **not** make the operation public: the server authenticates such a request by `key` (same validity and revocation rules as a bearer token, `token_hash` lookup) and requires the scope named in `x-body-key-scope`. If both a header/cookie and `key` are present, the header/cookie wins (`key` is ignored). Only the `version` action needs no credential. Without a valid credential the response is `{\"result\": null, \"error\": \"valid api key must be provided\"}`. Needs the `write` scope (also for the read-only actions, to keep one rule). Cookie-authenticated calls need the CSRF header as usual. The server answers `OPTIONS` preflights for this path only with `Access-Control-Allow-Origin: *` and allows the headers `Content-Type`; cookies are never accepted cross-origin.  **Ids.** AnkiConnect note ids are integers. `anki_id(note)` = the first 48 bits of the note's UUIDv7 (Unix milliseconds) × 32 + the 5 bits following the version nibble (always below 2^53). It is derived, not stored; a lookup by `anki_id` resolves to the live note with the smallest id among those sharing it.  | Action | Params | Result | Behaviour | |---|---|---|---| | `version` | - | `6` | No auth needed. | | `deckNames` | - | array of strings | Names of live `user` decks, ascending, de-duplicated. If there is none, `[\"Kotobaya\"]` (notes added to that name go to no deck). | | `modelNames` | - | array of strings | Names of live note types, built-ins first. | | `modelFieldNames` | `modelName` | array of strings | The field **keys** of that note type, in order. Unknown name: error `model was not found: <name>`. | | `canAddNotes` | `notes` (array of note objects) | array of booleans | Per note, false when `addNote` would fail as a duplicate or its model is unknown. | | `addNote` | `note` | integer `anki_id` | See below. | | `findNotes` | `query` | array of integers | Supports the forms Yomitan sends: `\"<field>:<value>\"` terms (exact match of that field after stripping HTML, optionally quoted, combined with spaces = AND), `deck:\"<name>\"`, and `nid:<id>`; anything else matches nothing. Returns `anki_id`s of live notes, ascending. | | `storeMediaFile` | `filename`, and one of `data` (base64), `url` | the stored filename | Stores the bytes as a blob (kind by extension: audio or image; else `upload`). Returns `<sha256>.<original extension>`; a `[sound:<that name>]` or `<img src=\"<that name>\">` in a later `addNote` field is rewritten to `[sound:<sha256>]` / `<img src=\"blob:<sha256>\">`. |  **`addNote`.** `note` = `{deckName, modelName, fields: {<key>: <html>}, tags: [..], options: {allowDuplicate}, audio/picture: [{url|data, filename, fields: [..]}]}`. The server resolves `modelName` to a note type and matches a dictionary entry with the core's matcher from the fields `expression` and `reading` when the base type is `vocab` (no match: `entry_seq` null). It then emits `note.created` (origin `ankiconnect`), `card.created` per default kind and, when `deckName` names a live user deck, `deck.item_put` - exactly as `POST /notes`. `audio`/`picture` attachments are stored as blobs and appended to the named fields. Duplicate rule: if a live note already references the matched entry, or (no entry) a live note of the same note type has the same first-field value, the result is error `cannot create note because it is a duplicate` - unless `options.allowDuplicate` is true and there is no matched entry (a second note for the same entry is never created; with `allowDuplicate` the existing note's `anki_id` is returned instead). 

        :param anki_connect_request: (required)
        :type anki_connect_request: AnkiConnectRequest
        :param _request_timeout: timeout setting for this request. If one
                                 number provided, it will be total request
                                 timeout. It can also be a pair (tuple) of
                                 (connection, read) timeouts.
        :type _request_timeout: int, tuple(int, int), optional
        :param _request_auth: set to override the auth_settings for an a single
                              request; this effectively ignores the
                              authentication in the spec for a single request.
        :type _request_auth: dict, optional
        :param _content_type: force content-type for the request.
        :type _content_type: str, Optional
        :param _headers: set to override the headers for a single
                         request; this effectively ignores the headers
                         in the spec for a single request.
        :type _headers: dict, optional
        :param _host_index: set to override the host_index for a single
                            request; this effectively ignores the host_index
                            in the spec for a single request.
        :type _host_index: int, optional
        :return: Returns the result object.
        """ # noqa: E501

        _param = self._anki_connect_serialize(
            anki_connect_request=anki_connect_request,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "AnkiConnectResponse",
            'default': "Problem",
        }
        response_data = self.api_client.call_api(
            *_param,
            _request_timeout=_request_timeout
        )
        response_data.read()
        return self.api_client.response_deserialize(
            response_data=response_data,
            response_types_map=_response_types_map,
        ).data


    @validate_call
    def anki_connect_with_http_info(
        self,
        anki_connect_request: AnkiConnectRequest,
        _request_timeout: Union[
            None,
            Annotated[StrictFloat, Field(gt=0)],
            Tuple[
                Annotated[StrictFloat, Field(gt=0)],
                Annotated[StrictFloat, Field(gt=0)]
            ]
        ] = None,
        _request_auth: Optional[Dict[StrictStr, Any]] = None,
        _content_type: Optional[StrictStr] = None,
        _headers: Optional[Dict[StrictStr, Any]] = None,
        _host_index: Annotated[StrictInt, Field(ge=0, le=0)] = 0,
    ) -> ApiResponse[AnkiConnectResponse]:
        """AnkiConnect-compatible subset for Yomitan and other mining tools.

        Speaks AnkiConnect protocol version 6: the request is `{\"action\", \"version\", \"params\", \"key\"}`; the response is **always HTTP 200** with `{\"result\", \"error\"}` where exactly one is non-null (`error` is a string). Requests with `version` below 6 or absent are answered in the same v6 shape. Protocol-level failures (unknown action, bad params, auth) are reported in `error`, not as HTTP errors; only a body that is not JSON gets `400`.  **Auth** (`x-auth: body-key`). Either the usual credentials, or - because Yomitan can only send an API key in the body - a personal access token in `key`. The empty alternative `{}` in `security` exists only so that generic clients and gateways let a request without an `Authorization` header or cookie through; it does **not** make the operation public: the server authenticates such a request by `key` (same validity and revocation rules as a bearer token, `token_hash` lookup) and requires the scope named in `x-body-key-scope`. If both a header/cookie and `key` are present, the header/cookie wins (`key` is ignored). Only the `version` action needs no credential. Without a valid credential the response is `{\"result\": null, \"error\": \"valid api key must be provided\"}`. Needs the `write` scope (also for the read-only actions, to keep one rule). Cookie-authenticated calls need the CSRF header as usual. The server answers `OPTIONS` preflights for this path only with `Access-Control-Allow-Origin: *` and allows the headers `Content-Type`; cookies are never accepted cross-origin.  **Ids.** AnkiConnect note ids are integers. `anki_id(note)` = the first 48 bits of the note's UUIDv7 (Unix milliseconds) × 32 + the 5 bits following the version nibble (always below 2^53). It is derived, not stored; a lookup by `anki_id` resolves to the live note with the smallest id among those sharing it.  | Action | Params | Result | Behaviour | |---|---|---|---| | `version` | - | `6` | No auth needed. | | `deckNames` | - | array of strings | Names of live `user` decks, ascending, de-duplicated. If there is none, `[\"Kotobaya\"]` (notes added to that name go to no deck). | | `modelNames` | - | array of strings | Names of live note types, built-ins first. | | `modelFieldNames` | `modelName` | array of strings | The field **keys** of that note type, in order. Unknown name: error `model was not found: <name>`. | | `canAddNotes` | `notes` (array of note objects) | array of booleans | Per note, false when `addNote` would fail as a duplicate or its model is unknown. | | `addNote` | `note` | integer `anki_id` | See below. | | `findNotes` | `query` | array of integers | Supports the forms Yomitan sends: `\"<field>:<value>\"` terms (exact match of that field after stripping HTML, optionally quoted, combined with spaces = AND), `deck:\"<name>\"`, and `nid:<id>`; anything else matches nothing. Returns `anki_id`s of live notes, ascending. | | `storeMediaFile` | `filename`, and one of `data` (base64), `url` | the stored filename | Stores the bytes as a blob (kind by extension: audio or image; else `upload`). Returns `<sha256>.<original extension>`; a `[sound:<that name>]` or `<img src=\"<that name>\">` in a later `addNote` field is rewritten to `[sound:<sha256>]` / `<img src=\"blob:<sha256>\">`. |  **`addNote`.** `note` = `{deckName, modelName, fields: {<key>: <html>}, tags: [..], options: {allowDuplicate}, audio/picture: [{url|data, filename, fields: [..]}]}`. The server resolves `modelName` to a note type and matches a dictionary entry with the core's matcher from the fields `expression` and `reading` when the base type is `vocab` (no match: `entry_seq` null). It then emits `note.created` (origin `ankiconnect`), `card.created` per default kind and, when `deckName` names a live user deck, `deck.item_put` - exactly as `POST /notes`. `audio`/`picture` attachments are stored as blobs and appended to the named fields. Duplicate rule: if a live note already references the matched entry, or (no entry) a live note of the same note type has the same first-field value, the result is error `cannot create note because it is a duplicate` - unless `options.allowDuplicate` is true and there is no matched entry (a second note for the same entry is never created; with `allowDuplicate` the existing note's `anki_id` is returned instead). 

        :param anki_connect_request: (required)
        :type anki_connect_request: AnkiConnectRequest
        :param _request_timeout: timeout setting for this request. If one
                                 number provided, it will be total request
                                 timeout. It can also be a pair (tuple) of
                                 (connection, read) timeouts.
        :type _request_timeout: int, tuple(int, int), optional
        :param _request_auth: set to override the auth_settings for an a single
                              request; this effectively ignores the
                              authentication in the spec for a single request.
        :type _request_auth: dict, optional
        :param _content_type: force content-type for the request.
        :type _content_type: str, Optional
        :param _headers: set to override the headers for a single
                         request; this effectively ignores the headers
                         in the spec for a single request.
        :type _headers: dict, optional
        :param _host_index: set to override the host_index for a single
                            request; this effectively ignores the host_index
                            in the spec for a single request.
        :type _host_index: int, optional
        :return: Returns the result object.
        """ # noqa: E501

        _param = self._anki_connect_serialize(
            anki_connect_request=anki_connect_request,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "AnkiConnectResponse",
            'default': "Problem",
        }
        response_data = self.api_client.call_api(
            *_param,
            _request_timeout=_request_timeout
        )
        response_data.read()
        return self.api_client.response_deserialize(
            response_data=response_data,
            response_types_map=_response_types_map,
        )


    @validate_call
    def anki_connect_without_preload_content(
        self,
        anki_connect_request: AnkiConnectRequest,
        _request_timeout: Union[
            None,
            Annotated[StrictFloat, Field(gt=0)],
            Tuple[
                Annotated[StrictFloat, Field(gt=0)],
                Annotated[StrictFloat, Field(gt=0)]
            ]
        ] = None,
        _request_auth: Optional[Dict[StrictStr, Any]] = None,
        _content_type: Optional[StrictStr] = None,
        _headers: Optional[Dict[StrictStr, Any]] = None,
        _host_index: Annotated[StrictInt, Field(ge=0, le=0)] = 0,
    ) -> RESTResponseType:
        """AnkiConnect-compatible subset for Yomitan and other mining tools.

        Speaks AnkiConnect protocol version 6: the request is `{\"action\", \"version\", \"params\", \"key\"}`; the response is **always HTTP 200** with `{\"result\", \"error\"}` where exactly one is non-null (`error` is a string). Requests with `version` below 6 or absent are answered in the same v6 shape. Protocol-level failures (unknown action, bad params, auth) are reported in `error`, not as HTTP errors; only a body that is not JSON gets `400`.  **Auth** (`x-auth: body-key`). Either the usual credentials, or - because Yomitan can only send an API key in the body - a personal access token in `key`. The empty alternative `{}` in `security` exists only so that generic clients and gateways let a request without an `Authorization` header or cookie through; it does **not** make the operation public: the server authenticates such a request by `key` (same validity and revocation rules as a bearer token, `token_hash` lookup) and requires the scope named in `x-body-key-scope`. If both a header/cookie and `key` are present, the header/cookie wins (`key` is ignored). Only the `version` action needs no credential. Without a valid credential the response is `{\"result\": null, \"error\": \"valid api key must be provided\"}`. Needs the `write` scope (also for the read-only actions, to keep one rule). Cookie-authenticated calls need the CSRF header as usual. The server answers `OPTIONS` preflights for this path only with `Access-Control-Allow-Origin: *` and allows the headers `Content-Type`; cookies are never accepted cross-origin.  **Ids.** AnkiConnect note ids are integers. `anki_id(note)` = the first 48 bits of the note's UUIDv7 (Unix milliseconds) × 32 + the 5 bits following the version nibble (always below 2^53). It is derived, not stored; a lookup by `anki_id` resolves to the live note with the smallest id among those sharing it.  | Action | Params | Result | Behaviour | |---|---|---|---| | `version` | - | `6` | No auth needed. | | `deckNames` | - | array of strings | Names of live `user` decks, ascending, de-duplicated. If there is none, `[\"Kotobaya\"]` (notes added to that name go to no deck). | | `modelNames` | - | array of strings | Names of live note types, built-ins first. | | `modelFieldNames` | `modelName` | array of strings | The field **keys** of that note type, in order. Unknown name: error `model was not found: <name>`. | | `canAddNotes` | `notes` (array of note objects) | array of booleans | Per note, false when `addNote` would fail as a duplicate or its model is unknown. | | `addNote` | `note` | integer `anki_id` | See below. | | `findNotes` | `query` | array of integers | Supports the forms Yomitan sends: `\"<field>:<value>\"` terms (exact match of that field after stripping HTML, optionally quoted, combined with spaces = AND), `deck:\"<name>\"`, and `nid:<id>`; anything else matches nothing. Returns `anki_id`s of live notes, ascending. | | `storeMediaFile` | `filename`, and one of `data` (base64), `url` | the stored filename | Stores the bytes as a blob (kind by extension: audio or image; else `upload`). Returns `<sha256>.<original extension>`; a `[sound:<that name>]` or `<img src=\"<that name>\">` in a later `addNote` field is rewritten to `[sound:<sha256>]` / `<img src=\"blob:<sha256>\">`. |  **`addNote`.** `note` = `{deckName, modelName, fields: {<key>: <html>}, tags: [..], options: {allowDuplicate}, audio/picture: [{url|data, filename, fields: [..]}]}`. The server resolves `modelName` to a note type and matches a dictionary entry with the core's matcher from the fields `expression` and `reading` when the base type is `vocab` (no match: `entry_seq` null). It then emits `note.created` (origin `ankiconnect`), `card.created` per default kind and, when `deckName` names a live user deck, `deck.item_put` - exactly as `POST /notes`. `audio`/`picture` attachments are stored as blobs and appended to the named fields. Duplicate rule: if a live note already references the matched entry, or (no entry) a live note of the same note type has the same first-field value, the result is error `cannot create note because it is a duplicate` - unless `options.allowDuplicate` is true and there is no matched entry (a second note for the same entry is never created; with `allowDuplicate` the existing note's `anki_id` is returned instead). 

        :param anki_connect_request: (required)
        :type anki_connect_request: AnkiConnectRequest
        :param _request_timeout: timeout setting for this request. If one
                                 number provided, it will be total request
                                 timeout. It can also be a pair (tuple) of
                                 (connection, read) timeouts.
        :type _request_timeout: int, tuple(int, int), optional
        :param _request_auth: set to override the auth_settings for an a single
                              request; this effectively ignores the
                              authentication in the spec for a single request.
        :type _request_auth: dict, optional
        :param _content_type: force content-type for the request.
        :type _content_type: str, Optional
        :param _headers: set to override the headers for a single
                         request; this effectively ignores the headers
                         in the spec for a single request.
        :type _headers: dict, optional
        :param _host_index: set to override the host_index for a single
                            request; this effectively ignores the host_index
                            in the spec for a single request.
        :type _host_index: int, optional
        :return: Returns the result object.
        """ # noqa: E501

        _param = self._anki_connect_serialize(
            anki_connect_request=anki_connect_request,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "AnkiConnectResponse",
            'default': "Problem",
        }
        response_data = self.api_client.call_api(
            *_param,
            _request_timeout=_request_timeout
        )
        return response_data.response


    def _anki_connect_serialize(
        self,
        anki_connect_request,
        _request_auth,
        _content_type,
        _headers,
        _host_index,
    ) -> RequestSerialized:

        _host = None

        _collection_formats: Dict[str, str] = {
        }

        _path_params: Dict[str, str] = {}
        _query_params: List[Tuple[str, str]] = []
        _header_params: Dict[str, Any] = self.api_client._merge_headers(_headers)
        _form_params: List[Tuple[str, str]] = []
        _files: Dict[
            str, Union[str, bytes, List[str], List[bytes], List[Tuple[str, bytes]]]
        ] = {}
        _body_params: Optional[bytes] = None

        # process the path parameters
        # process the query parameters
        # process the header parameters
        # process the form parameters
        # process the body parameter
        if anki_connect_request is not None:
            _body_params = anki_connect_request


        # set the HTTP header `Accept`
        if not any(key.lower() == 'accept' for key in _header_params):
            _header_params['Accept'] = self.api_client.select_header_accept(
                [
                    'application/json', 
                    'application/problem+json'
                ]
            )

        # set the HTTP header `Content-Type`
        if _content_type:
            self.api_client._set_header(_header_params, 'Content-Type', _content_type)
        else:
            _default_content_type = (
                self.api_client.select_header_content_type(
                    [
                        'application/json'
                    ]
                )
            )
            if _default_content_type is not None:
                self.api_client._set_header(_header_params, 'Content-Type', _default_content_type)

        # authentication setting
        _auth_settings: List[str] = [
            'sessionCookie', 
            'bearerAuth'
        ]

        return self.api_client.param_serialize(
            method='POST',
            resource_path='/ankiconnect',
            path_params=_path_params,
            query_params=_query_params,
            header_params=_header_params,
            body=_body_params,
            post_params=_form_params,
            files=_files,
            auth_settings=_auth_settings,
            collection_formats=_collection_formats,
            _host=_host,
            _request_auth=_request_auth
        )


