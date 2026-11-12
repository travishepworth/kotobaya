"""
    Kotobaya API

    Versioned REST/JSON interface at `/api/v1`. The web app uses nothing else; the Android app uses `sync`, `blobs`, `dict/release`, `devices/pair`, `devices/self` and whatever conveniences it likes.  Implementers: this file is the contract. If something you need is missing or wrong, do not work around it; return `CONTRACT_CHANGE_REQUEST`. Additive changes stay in v1; breaking changes go to `/api/v2`.  ## 1. Conventions  - **JSON.** Request and response bodies are `application/json; charset=utf-8` unless stated.   Property names are `snake_case` and equal the column names of `contracts/db/V1__init.sql`   wherever a property mirrors a column; the column `COMMENT` is then the definition. - **Unknown properties.** Servers reject unknown request properties and unknown query   parameters with `422 validation_failed`. Clients MUST ignore unknown response properties and   MUST treat unknown enum values in responses as \"other\" (v1 may add both). - **Absent vs null.** In `PATCH` bodies an absent property means \"leave unchanged\"; `null` means   \"set to NULL\" where the property is nullable and is a validation error otherwise.   Responses always include every documented property; nullable ones are `null`, never absent,   unless the property is documented as \"omitted when ...\". - **Ids.** Row ids are UUIDv7 in canonical lowercase `8-4-4-4-12` text. Uppercase ids in   requests are rejected with `422`. \"Entry\" always means a JMdict sequence number (`seq`,   JSON integer). - **Time.** Instants are RFC 3339 UTC strings with millisecond precision and a `Z` suffix,   e.g. `2026-11-12T09:30:00.000Z`. Servers accept any RFC 3339 offset on input and normalise   to UTC. A \"study day\" is a local date `YYYY-MM-DD` computed from the settings   `scheduler.timezone` and `scheduler.day_rollover_hour`: study day D covers local time   `D rollover:00` (inclusive) to `D+1 rollover:00` (exclusive). - **Text offsets** into sentence text are UTF-16 code units, half-open `[start, end)`. - **Blobs** (audio, images, uploads, snapshots) are addressed by the lowercase hex SHA-256 of   their bytes and transferred through `/blobs/{hash}`, never inline in JSON.  ## 2. Authentication and scopes  Three credential kinds, all rows of `app.api_token` (stored hashed):  | Kind | How obtained | Sent as | Scopes | |---|---|---|---| | `session` | `POST /auth/login` | cookie `kotobaya_session` (HTTP-only) | all five | | `device` | `POST /devices/pair` | `Authorization: Bearer kbd_...` | `read`, `write`, `review` | | `personal` | `POST /tokens` | `Authorization: Bearer kbp_...` | chosen at creation |  Token strings are `<3-letter kind prefix>_<40 characters of [A-Za-z0-9]>` (`kbs_`, `kbd_`, `kbp_`), generated from a CSPRNG. `token_hash` = lowercase hex SHA-256 of the whole string; `token_prefix` = its first 8 characters. A token is valid iff `revoked_at IS NULL` and (`expires_at IS NULL` or `expires_at > now()`) and, when it has a `device_id`, that device is not revoked. Validity is checked on every request (no caching longer than 5 seconds), so revocation takes effect at once.  Scopes are independent (no scope implies another):  | Scope | Grants | |---|---| | `read` | Every `GET` of learning, media and reference data; `POST /parse`; `GET /events`; `GET /sync/pull`; exports. | | `write` | Create, change, delete notes, cards, decks, grammar, note types, presets, settings, word status, parse overrides, media metadata; upload blobs; `POST /sync/push` (non-review ops). | | `review` | `POST /review/answer`, `POST /review/undo`, `POST /drill/session`, `POST /cards/bulk/schedule`; `review.*` ops inside `POST /sync/push` (per op; without it the op is rejected with `insufficient_scope`). | | `import` | Start and cancel import, parse, dictionary and optimizer jobs; upload blobs. | | `admin` | Devices, pairing, tokens (a device's own row: `/devices/self` with `read`/`write`). |  Each operation lists the scope it needs in its `security` requirement: the array after the scheme name. When two alternatives are listed for one scheme the caller needs either. A request with no or an invalid credential gets `401 unauthenticated`; a valid credential lacking the scope gets `403 insufficient_scope`. Operations with `security: []` are public.  There are exactly two places where who may call is not just \"scheme + scope\", and both are declared in the operation, not only in prose: - `GET/PATCH /devices/self` (`read` / `write`, bearer only): the token must be bound to a   device, else `403 device_required`. A device never needs `admin` to manage its own row;   `/devices/{device_id}` is admin only. - `POST /ankiconnect` (`x-auth: body-key`): the token may instead be sent in the JSON body   property `key`; the empty `{}` alternative in its `security` only signals that no header is   needed. The scope for `key` is `x-body-key-scope`.  **CSRF.** A request authenticated by the session cookie whose method is not `GET` or `HEAD` MUST carry the header `X-Kotobaya-Csrf: 1`; otherwise `403 csrf_required`. The cookie is set with `HttpOnly; Secure; SameSite=Strict; Path=/api`. The server sends no CORS headers (the web app is served from the same origin). If both a cookie and an `Authorization` header are present, the header wins and the cookie is ignored.  **Audit.** Every request is logged with `token.id` and `token.name` (structured server log; there is no audit table or endpoint in v1). Ops record the token in `change.token_id`.  ## 3. Writes are sync ops  Learning data is only ever changed by applying sync ops (`contracts/sync`). Every REST operation that changes learning data is a convenience wrapper: the server builds the op(s) named in the operation's `x-emits-ops`, applies them through the same op-apply path as `POST /sync/push` in one transaction, and they then appear in `GET /sync/pull` for every device. Op names are those of `contracts/db/SCHEMA.md` section 4; `contracts/sync` is authoritative for names and payloads.  For an op the server builds on behalf of a REST call:  - `op_id` is a fresh UUIDv7; origin `device_id` is the **server device**   `00000000-0000-7000-8000-000000000001` (also for session and device tokens: only one party   ever generates HLCs for a given device id); `hlc` comes from the server's HLC clock;   `change.token_id` is the calling token. - A property absent from a `PATCH` body produces no register write. A property present with   the value the row already has still produces a write (it bumps the register's clock). - If a call changes nothing (documented per operation, e.g. deleting an already deleted   note), no op is emitted and the response is still successful. - **Never an invalid op (C-6).** If a request would make the server build an op that fails   its schema or an op-level rule in `contracts/sync` - a tag containing ECMA-262 whitespace   (e.g. U+3000 or U+00A0), U+0000 in any string (field values and open JSON objects   included), an integer above its op maximum (`2147483647` for int4 columns) or an instant   after `9999-12-31T23:59:59.999Z` - the call is `422 validation_failed` with a field error   at that request property, code `invalid_value` (or `out_of_range` / `too_long` where the   operation's own request bound fails first). Nothing is emitted; never a `500`.   SYNC.md 2.1 \"Storable values\" and \"Input that would mint an invalid op\". - Responses to mutating calls carry the header `Kotobaya-Seq`: the `change.seq` of the last op   the call emitted. A client holding a local replica knows it has seen the effect once its   pull cursor is at or past that value. The header is absent when no op was emitted.  **Idempotent creation.** Create bodies accept an optional client-generated `id` (UUIDv7). Re-sending a create with an `id` that already exists returns the existing resource with status `200` instead of `201` and emits nothing, whatever the rest of the body says. Clients that may retry MUST send `id`. When `id` is omitted the server generates one. `POST /review/answer` requires `id` (a duplicate review would corrupt scheduling); `POST /media/import` takes the same optional idempotency id as `media_id`.  The existence check and the emit are **one transaction that takes advisory lock `7033001` before the check** and holds it until commit (the lock `POST /sync/push` takes per op, SYNC.md 5.2): check whether `id` exists; if not, mint, apply and log the op(s) - for `createNote` the `note.created` and every `card.created` - then commit. Without the lock two concurrent retries of the same create both pass the check; the second `note.created` is a no-op on the row (SYNC.md 7.2) but its `card.created` ops carry fresh card ids, so the note would get duplicate cards. The same holds for every other id-idempotent call (`POST /review/answer`, `POST /review/undo`, `POST /media/import`), and a pushed op that creates the same id races safely because it takes the same lock.  **Tombstones and merges.** Deleted learning rows are tombstones (`deleted_at` set). `GET` by id of a deleted row returns `410 gone` unless `include_deleted=true`; lists omit deleted rows unless `include_deleted=true`. A note or card that lost a merge (`merged_into_id` set) is answered with its survivor: `GET`, `PATCH` and review calls addressed to the loser's id act on the survivor and the response body is the survivor (its `id` differs from the requested id; the response has `Content-Location` with the survivor's URL).  ## 4. Pagination  List operations that take `limit` and `cursor` return `{\"items\": [...], \"next_cursor\": ...}`. `next_cursor` is an opaque string, or `null` on the last page. Pass it back unchanged as `cursor` together with the same other parameters; changing any other parameter while passing a cursor is `422 validation_failed` (`pointer` `/cursor`). `limit` defaults to 50, maximum 500. Order is stable and documented per operation; rows created while paging may or may not appear.  **Cursor semantics (keyset, no snapshot).** A cursor encodes the sort-key values and the `id` of the last row returned; the next page is the rows strictly after that tuple in the current order, evaluated against the data at the time of that request. The order is not snapshotted. Consequently, while a client pages through a list sorted by a mutable key (`due_at`, `updated_at`, `lapses`, ...): - a row whose key changes so that it moves from after the cursor to before it is **skipped**; - a row whose key changes so that it moves from before the cursor to after it is **returned   again** (with its new values); - a row deleted while paging disappears; one created may or may not appear. Rows that do not change are returned exactly once. Clients MUST de-duplicate by `id` (last occurrence wins). A client that needs an exact set (bulk actions, exports) uses an `id`-sorted list where offered, `POST /cards/bulk` with `query`, or `GET /export`. Lists sorted by an immutable key (`id`, `created_at`) have no skips or repeats. An empty result is `{\"items\": [], \"next_cursor\": null}` with status `200`.  ## 5. Errors  Every non-2xx response has the body `application/problem+json` (RFC 9457), schema `Problem`. `code` is the stable machine-readable identifier; `type` is `urn:kotobaya:problem:<code>`.  | Status | `code` | When | |---|---|---| | 400 | `invalid_request` | Body is not valid JSON, wrong content type framing, malformed header; `POST /sync/push` body not valid against `PushRequestLenient`. | | 401 | `unauthenticated` | No credential, or it is unknown, expired or revoked. | | 401 | `invalid_credentials` | Wrong password or TOTP code (`POST /auth/login`). | | 401 | `totp_required` | TOTP is enabled and `totp_code` was not sent. | | 401 | `invalid_pairing_code` | Pairing code unknown, expired or already used. | | 403 | `insufficient_scope` | Credential lacks the scope; `required_scope` names it. | | 403 | `csrf_required` | Cookie-authenticated write without `X-Kotobaya-Csrf`. | | 403 | `device_required` | `POST /sync/push` or `/devices/self` with a credential that has no `device_id`. | | 404 | `not_found` | No such resource (also: id of another resource kind). | | 409 | `already_exists` | Natural-key collision; `existing_id` names the holder. | | 409 | `builtin_immutable` | Deleting a built-in note type or the Default preset, or removing a built-in field. | | 409 | `in_use` | Deleting a note type that live notes use. | | 409 | `deck_read_only` | Changing items or deleting a `media`/`list` deck through deck endpoints. | | 409 | `not_applicable` | The resource kind does not support the operation (e.g. items of a smart deck). | | 409 | `card_not_reviewable` | Card is suspended. | | 409 | `job_not_cancellable` | Job already finished. | | 410 | `gone` | Tombstoned row requested without `include_deleted=true`. | | 413 | `payload_too_large` | Body over the limit in `GET /meta`. On `POST /sync/push` only for the whole batch (the client halves it); one op over `max_op_bytes` is a per-op `op_too_large` result instead. | | 415 | `unsupported_media_type` | Wrong `Content-Type`. | | 422 | `validation_failed` | Well-formed but invalid; `errors[]` lists each problem. | | 422 | `invalid_query` | Card search string does not parse; `errors[0].position` is the UTF-16 offset. | | 422 | `bulk_too_large` | Bulk selector matched more than 5000 cards. | | 422 | `hash_mismatch` | Uploaded bytes do not hash to the `{hash}` in the path. | | 429 | `rate_limited` | Too many login or pairing attempts; see `Retry-After`. | | 500 | `internal` | Bug. Safe to retry idempotent calls. | | 503 | `unavailable` | Database or object store unreachable. Retry with backoff. |  Check order: 401, then 403, then 404/410, then 415/400, then 422, then 409.  ## 6. Events  `GET /events` is a server-sent events stream; see that operation. Event payload schemas are `Event*` in components. 

    The version of the OpenAPI document: 1.0.0
    Generated by OpenAPI Generator (https://openapi-generator.tech)

    Do not edit the class manually.
"""  # noqa: E501


import warnings
from pydantic import validate_call, Field, StrictFloat, StrictStr, StrictInt
from typing import Any, Dict, List, Optional, Tuple, Union
from typing_extensions import Annotated

from pydantic import Field
from typing import Optional
from typing_extensions import Annotated
from kotobaya_client.models.pull_response import PullResponse
from kotobaya_client.models.push_request import PushRequest
from kotobaya_client.models.push_response import PushResponse

from kotobaya_client.api_client import ApiClient, RequestSerialized
from kotobaya_client.api_response import ApiResponse
from kotobaya_client.rest import RESTResponseType


class SyncApi:
    """NOTE: This class is auto generated by OpenAPI Generator
    Ref: https://openapi-generator.tech

    Do not edit the class manually.
    """

    def __init__(self, api_client=None) -> None:
        if api_client is None:
            api_client = ApiClient.get_default()
        self.api_client = api_client


    @validate_call
    def sync_pull(
        self,
        since: Annotated[Optional[Annotated[int, Field(strict=True, ge=0)]], Field(description="Return log entries with `seq` greater than this. `0` = from the beginning.")] = None,
        limit: Annotated[Optional[Annotated[int, Field(le=1000, strict=True, ge=1)]], Field(description="Maximum number of ops to return (`PullResponse.ops` has `maxItems` 1000). The server may return fewer; only `has_more` says whether more exist.")] = None,
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
    ) -> PullResponse:
        """Pull logged operations after a sequence number.

        **Body.** The `200` body is exactly `contracts/sync/batch.schema.json#/$defs/PullResponse` (no wrapper, no extra fields). `contracts/sync/SYNC.md` section 6 owns the semantics; this operation defines only the query parameters, auth and status codes.  **Lenient parsing.** The server produces `PullResponse`. A client SHOULD validate the body against `#/$defs/PullResponseLenient` (open `op_type` / `op_version`, unknown top-level properties ignored) and each op against `op.schema.json` only when it supports that type and version; at the first op it does not support it applies the ops before it and stops in state *upgrade required* (SYNC.md 6.1). Validating the whole page against `PullResponse` would make an old client reject a page it could partly apply.  **Semantics** (SYNC.md 6.1, restated, not extended): - `ops` are the `change` rows with `seq > since`, ascending by `seq`, at most `limit`, from   **every** origin, including ops the caller pushed itself and ops that had no effect.   There is no option to skip one's own ops. The server SHOULD also end a page before its   body would exceed `limits.max_push_bytes` (always at least one op), so pages stay   bounded although each op may be up to `limits.max_op_bytes`; `has_more` is then `true`. - `next_since` = the `seq` of the last op returned, or `since` when `ops` is empty.   `has_more` = rows exist after `next_since`; the client pulls again at once. - Every op carries its `seq` and `caused_by_op_id`. `seq` may have gaps. - The log is never pruned in v1: `since=0` returns the whole history (new device, rebuild). - Every response carries `high_water_seq`, `server_time` (`instant_ms`), `log_epoch` and   `durable_seq`. Before applying a page the client compares `log_epoch` and   `high_water_seq` with its stored state and starts a log reset on a mismatch (SYNC.md 6.2). - `since` greater than `high_water_seq` (the server was restored from an older backup) is   **not** an error: the answer is `200` with `ops: []`, `next_since = since`,   `has_more: false`, and the new `log_epoch` / smaller `high_water_seq` tell the client to   reset (SYNC.md 6.2). - Payloads are returned as logged; large content is never inline (ops such as   `deck.content_published` carry blob hashes to fetch with `GET /blobs/{hash}`).  Side effects when the token is bound to a device: sets `device.last_pull_seq = since` (what the device has acknowledged) and `last_seen_at`. Tokens without a device may pull (read-only replicas, scripts). 

        :param since: Return log entries with `seq` greater than this. `0` = from the beginning.
        :type since: int
        :param limit: Maximum number of ops to return (`PullResponse.ops` has `maxItems` 1000). The server may return fewer; only `has_more` says whether more exist.
        :type limit: int
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

        _param = self._sync_pull_serialize(
            since=since,
            limit=limit,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "PullResponse",
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
    def sync_pull_with_http_info(
        self,
        since: Annotated[Optional[Annotated[int, Field(strict=True, ge=0)]], Field(description="Return log entries with `seq` greater than this. `0` = from the beginning.")] = None,
        limit: Annotated[Optional[Annotated[int, Field(le=1000, strict=True, ge=1)]], Field(description="Maximum number of ops to return (`PullResponse.ops` has `maxItems` 1000). The server may return fewer; only `has_more` says whether more exist.")] = None,
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
    ) -> ApiResponse[PullResponse]:
        """Pull logged operations after a sequence number.

        **Body.** The `200` body is exactly `contracts/sync/batch.schema.json#/$defs/PullResponse` (no wrapper, no extra fields). `contracts/sync/SYNC.md` section 6 owns the semantics; this operation defines only the query parameters, auth and status codes.  **Lenient parsing.** The server produces `PullResponse`. A client SHOULD validate the body against `#/$defs/PullResponseLenient` (open `op_type` / `op_version`, unknown top-level properties ignored) and each op against `op.schema.json` only when it supports that type and version; at the first op it does not support it applies the ops before it and stops in state *upgrade required* (SYNC.md 6.1). Validating the whole page against `PullResponse` would make an old client reject a page it could partly apply.  **Semantics** (SYNC.md 6.1, restated, not extended): - `ops` are the `change` rows with `seq > since`, ascending by `seq`, at most `limit`, from   **every** origin, including ops the caller pushed itself and ops that had no effect.   There is no option to skip one's own ops. The server SHOULD also end a page before its   body would exceed `limits.max_push_bytes` (always at least one op), so pages stay   bounded although each op may be up to `limits.max_op_bytes`; `has_more` is then `true`. - `next_since` = the `seq` of the last op returned, or `since` when `ops` is empty.   `has_more` = rows exist after `next_since`; the client pulls again at once. - Every op carries its `seq` and `caused_by_op_id`. `seq` may have gaps. - The log is never pruned in v1: `since=0` returns the whole history (new device, rebuild). - Every response carries `high_water_seq`, `server_time` (`instant_ms`), `log_epoch` and   `durable_seq`. Before applying a page the client compares `log_epoch` and   `high_water_seq` with its stored state and starts a log reset on a mismatch (SYNC.md 6.2). - `since` greater than `high_water_seq` (the server was restored from an older backup) is   **not** an error: the answer is `200` with `ops: []`, `next_since = since`,   `has_more: false`, and the new `log_epoch` / smaller `high_water_seq` tell the client to   reset (SYNC.md 6.2). - Payloads are returned as logged; large content is never inline (ops such as   `deck.content_published` carry blob hashes to fetch with `GET /blobs/{hash}`).  Side effects when the token is bound to a device: sets `device.last_pull_seq = since` (what the device has acknowledged) and `last_seen_at`. Tokens without a device may pull (read-only replicas, scripts). 

        :param since: Return log entries with `seq` greater than this. `0` = from the beginning.
        :type since: int
        :param limit: Maximum number of ops to return (`PullResponse.ops` has `maxItems` 1000). The server may return fewer; only `has_more` says whether more exist.
        :type limit: int
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

        _param = self._sync_pull_serialize(
            since=since,
            limit=limit,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "PullResponse",
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
    def sync_pull_without_preload_content(
        self,
        since: Annotated[Optional[Annotated[int, Field(strict=True, ge=0)]], Field(description="Return log entries with `seq` greater than this. `0` = from the beginning.")] = None,
        limit: Annotated[Optional[Annotated[int, Field(le=1000, strict=True, ge=1)]], Field(description="Maximum number of ops to return (`PullResponse.ops` has `maxItems` 1000). The server may return fewer; only `has_more` says whether more exist.")] = None,
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
        """Pull logged operations after a sequence number.

        **Body.** The `200` body is exactly `contracts/sync/batch.schema.json#/$defs/PullResponse` (no wrapper, no extra fields). `contracts/sync/SYNC.md` section 6 owns the semantics; this operation defines only the query parameters, auth and status codes.  **Lenient parsing.** The server produces `PullResponse`. A client SHOULD validate the body against `#/$defs/PullResponseLenient` (open `op_type` / `op_version`, unknown top-level properties ignored) and each op against `op.schema.json` only when it supports that type and version; at the first op it does not support it applies the ops before it and stops in state *upgrade required* (SYNC.md 6.1). Validating the whole page against `PullResponse` would make an old client reject a page it could partly apply.  **Semantics** (SYNC.md 6.1, restated, not extended): - `ops` are the `change` rows with `seq > since`, ascending by `seq`, at most `limit`, from   **every** origin, including ops the caller pushed itself and ops that had no effect.   There is no option to skip one's own ops. The server SHOULD also end a page before its   body would exceed `limits.max_push_bytes` (always at least one op), so pages stay   bounded although each op may be up to `limits.max_op_bytes`; `has_more` is then `true`. - `next_since` = the `seq` of the last op returned, or `since` when `ops` is empty.   `has_more` = rows exist after `next_since`; the client pulls again at once. - Every op carries its `seq` and `caused_by_op_id`. `seq` may have gaps. - The log is never pruned in v1: `since=0` returns the whole history (new device, rebuild). - Every response carries `high_water_seq`, `server_time` (`instant_ms`), `log_epoch` and   `durable_seq`. Before applying a page the client compares `log_epoch` and   `high_water_seq` with its stored state and starts a log reset on a mismatch (SYNC.md 6.2). - `since` greater than `high_water_seq` (the server was restored from an older backup) is   **not** an error: the answer is `200` with `ops: []`, `next_since = since`,   `has_more: false`, and the new `log_epoch` / smaller `high_water_seq` tell the client to   reset (SYNC.md 6.2). - Payloads are returned as logged; large content is never inline (ops such as   `deck.content_published` carry blob hashes to fetch with `GET /blobs/{hash}`).  Side effects when the token is bound to a device: sets `device.last_pull_seq = since` (what the device has acknowledged) and `last_seen_at`. Tokens without a device may pull (read-only replicas, scripts). 

        :param since: Return log entries with `seq` greater than this. `0` = from the beginning.
        :type since: int
        :param limit: Maximum number of ops to return (`PullResponse.ops` has `maxItems` 1000). The server may return fewer; only `has_more` says whether more exist.
        :type limit: int
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

        _param = self._sync_pull_serialize(
            since=since,
            limit=limit,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "PullResponse",
            'default': "Problem",
        }
        response_data = self.api_client.call_api(
            *_param,
            _request_timeout=_request_timeout
        )
        return response_data.response


    def _sync_pull_serialize(
        self,
        since,
        limit,
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
        if since is not None:
            
            _query_params.append(('since', since))
            
        if limit is not None:
            
            _query_params.append(('limit', limit))
            
        # process the header parameters
        # process the form parameters
        # process the body parameter


        # set the HTTP header `Accept`
        if not any(key.lower() == 'accept' for key in _header_params):
            _header_params['Accept'] = self.api_client.select_header_accept(
                [
                    'application/json', 
                    'application/problem+json'
                ]
            )


        # authentication setting
        _auth_settings: List[str] = [
            'sessionCookie', 
            'bearerAuth'
        ]

        return self.api_client.param_serialize(
            method='GET',
            resource_path='/sync/pull',
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




    @validate_call
    def sync_push(
        self,
        push_request: PushRequest,
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
    ) -> PushResponse:
        """Push a batch of locally minted operations.

        **Bodies.** The request body is exactly `contracts/sync/batch.schema.json#/$defs/PushRequest` and the `200` body is exactly `#/$defs/PushResponse`; this operation adds no wrapper and no fields of its own. `contracts/sync/SYNC.md` sections 5.1-5.4 own the semantics (per-op procedure, result statuses `accepted` / `duplicate` / `rejected`, rejection codes, `high_water_seq`, `server_time` as `instant_ms`, `log_epoch`, `durable_seq`); this operation defines only the HTTP framing, auth and status codes. Where this text and SYNC.md disagree, SYNC.md wins.  **What the server validates as a whole.** The server validates the body against `#/$defs/PushRequestLenient` **only** (a JSON object with `ops`: 1-500 objects, each with a UUID `op_id`). If that fails the whole request is `400 invalid_request` and nothing is applied (`errors[]` points at the offending part). The schema `PushRequest` is what a correct client sends; the server never rejects a whole request because an op fails it.  **Duplicate keys (C-2, SYNC.md 5.2 \"Parsing the body\").** The server parses the body with a syntax-only, duplicate-tolerant JSON parse. A key repeated in the top-level body object, an element whose `op_id` member is repeated, or two elements with the same `op_id` and different contents fail `PushRequestLenient` (`400 invalid_request`). A key repeated anywhere inside one element of `ops` is NOT a 400: that op is `rejected` with `schema_invalid`, decided before `op_too_large` (its canonical size is undefined). Clients MUST send each op exactly as `CanonicalJson.encode` / `OpCodec.encode` produce it, which never contains a duplicate key.  **Everything else is per op** (SYNC.md 5.2, 5.3), in array order, and reported as a `rejected` result while the remaining ops are still processed: an op over `limits.max_op_bytes` (`op_too_large`, checked first; SYNC.md 5.4), unknown op type or version (`unsupported_op`), an op invalid against `op.schema.json` (`schema_invalid`), an op whose `device_id` is not the caller's device (`device_mismatch`), server-only ops (`server_only`), missing references (`unknown_reference`), a wrong device clock (`clock_ahead`, `clock_behind`; SYNC.md 3.4 and 10.4), `invalid_value`, `invalid_target`, `immutable`. This API adds one code to SYNC.md's open set: `insufficient_scope` - an op of type `review.*` pushed by a token without the `review` scope; checked right after `device_mismatch` (SYNC.md 5.3 order). Clients treat unknown codes as permanent rejections (SYNC.md 5.3).  **Request-level errors** (nothing applied, rows stay `pending`, SYNC.md 5.4): - `401 unauthenticated`; `403 insufficient_scope` (no `write`); `403 device_required`   (the token is not bound to a device: only `device` tokens and `personal` tokens created   with `with_device` may push; session cookies have no device). - `400 invalid_request`: not JSON, or `PushRequestLenient` fails (empty `ops`, more than   500 ops, an op without a valid `op_id`, ...). - `413 payload_too_large`: body over `limits.max_push_bytes` (`GET /meta`, default 8 MiB).   This is about the batch, never about one op: a single op over `limits.max_op_bytes`   (default 1 MiB, at most `max_push_bytes - 4096`) is a per-op `op_too_large` result in   a `200`. On a `413` the client does not back off (SYNC.md 5.4): it halves the batch   (the first `ceil(n/2)` ops, still in ascending `hlc`) and pushes again at once, down to   a single op. If a single op still gets `413` (the server's limits are inconsistent or   the client's view of them is stale), the client marks that row rejected with   `op_too_large` itself and continues with the next ops; it is then an ordinary rejected   op (SYNC.md 10). - `415`, `429`, `5xx` as everywhere. There is no whole-request `409` and no whole-request `422` on this operation.  **Status.** `200` whenever the body passed `PushRequestLenient`, even if every op was rejected. `results` has exactly one entry per pushed op, in request order. Ops the server emits as consequences (`note.merged`, `card.merged`, ...) are not in `results`; they arrive through `GET /sync/pull`.  **Retry.** On no response, `5xx` or `429` the client re-sends the same ops (SYNC.md 5.4); ops already committed come back as `duplicate` with their original `seq`. Before using a response the client compares `log_epoch` and `high_water_seq` with its stored state (SYNC.md 6.2, log reset) and checks `server_time` against its own clock (SYNC.md 3.4).  Side effects: updates `device.last_push_at` and `last_seen_at`; emits the event `sync.changed` when at least one op was accepted. 

        :param push_request: (required)
        :type push_request: PushRequest
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

        _param = self._sync_push_serialize(
            push_request=push_request,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "PushResponse",
            '400': "Problem",
            '403': "Problem",
            '413': "Problem",
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
    def sync_push_with_http_info(
        self,
        push_request: PushRequest,
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
    ) -> ApiResponse[PushResponse]:
        """Push a batch of locally minted operations.

        **Bodies.** The request body is exactly `contracts/sync/batch.schema.json#/$defs/PushRequest` and the `200` body is exactly `#/$defs/PushResponse`; this operation adds no wrapper and no fields of its own. `contracts/sync/SYNC.md` sections 5.1-5.4 own the semantics (per-op procedure, result statuses `accepted` / `duplicate` / `rejected`, rejection codes, `high_water_seq`, `server_time` as `instant_ms`, `log_epoch`, `durable_seq`); this operation defines only the HTTP framing, auth and status codes. Where this text and SYNC.md disagree, SYNC.md wins.  **What the server validates as a whole.** The server validates the body against `#/$defs/PushRequestLenient` **only** (a JSON object with `ops`: 1-500 objects, each with a UUID `op_id`). If that fails the whole request is `400 invalid_request` and nothing is applied (`errors[]` points at the offending part). The schema `PushRequest` is what a correct client sends; the server never rejects a whole request because an op fails it.  **Duplicate keys (C-2, SYNC.md 5.2 \"Parsing the body\").** The server parses the body with a syntax-only, duplicate-tolerant JSON parse. A key repeated in the top-level body object, an element whose `op_id` member is repeated, or two elements with the same `op_id` and different contents fail `PushRequestLenient` (`400 invalid_request`). A key repeated anywhere inside one element of `ops` is NOT a 400: that op is `rejected` with `schema_invalid`, decided before `op_too_large` (its canonical size is undefined). Clients MUST send each op exactly as `CanonicalJson.encode` / `OpCodec.encode` produce it, which never contains a duplicate key.  **Everything else is per op** (SYNC.md 5.2, 5.3), in array order, and reported as a `rejected` result while the remaining ops are still processed: an op over `limits.max_op_bytes` (`op_too_large`, checked first; SYNC.md 5.4), unknown op type or version (`unsupported_op`), an op invalid against `op.schema.json` (`schema_invalid`), an op whose `device_id` is not the caller's device (`device_mismatch`), server-only ops (`server_only`), missing references (`unknown_reference`), a wrong device clock (`clock_ahead`, `clock_behind`; SYNC.md 3.4 and 10.4), `invalid_value`, `invalid_target`, `immutable`. This API adds one code to SYNC.md's open set: `insufficient_scope` - an op of type `review.*` pushed by a token without the `review` scope; checked right after `device_mismatch` (SYNC.md 5.3 order). Clients treat unknown codes as permanent rejections (SYNC.md 5.3).  **Request-level errors** (nothing applied, rows stay `pending`, SYNC.md 5.4): - `401 unauthenticated`; `403 insufficient_scope` (no `write`); `403 device_required`   (the token is not bound to a device: only `device` tokens and `personal` tokens created   with `with_device` may push; session cookies have no device). - `400 invalid_request`: not JSON, or `PushRequestLenient` fails (empty `ops`, more than   500 ops, an op without a valid `op_id`, ...). - `413 payload_too_large`: body over `limits.max_push_bytes` (`GET /meta`, default 8 MiB).   This is about the batch, never about one op: a single op over `limits.max_op_bytes`   (default 1 MiB, at most `max_push_bytes - 4096`) is a per-op `op_too_large` result in   a `200`. On a `413` the client does not back off (SYNC.md 5.4): it halves the batch   (the first `ceil(n/2)` ops, still in ascending `hlc`) and pushes again at once, down to   a single op. If a single op still gets `413` (the server's limits are inconsistent or   the client's view of them is stale), the client marks that row rejected with   `op_too_large` itself and continues with the next ops; it is then an ordinary rejected   op (SYNC.md 10). - `415`, `429`, `5xx` as everywhere. There is no whole-request `409` and no whole-request `422` on this operation.  **Status.** `200` whenever the body passed `PushRequestLenient`, even if every op was rejected. `results` has exactly one entry per pushed op, in request order. Ops the server emits as consequences (`note.merged`, `card.merged`, ...) are not in `results`; they arrive through `GET /sync/pull`.  **Retry.** On no response, `5xx` or `429` the client re-sends the same ops (SYNC.md 5.4); ops already committed come back as `duplicate` with their original `seq`. Before using a response the client compares `log_epoch` and `high_water_seq` with its stored state (SYNC.md 6.2, log reset) and checks `server_time` against its own clock (SYNC.md 3.4).  Side effects: updates `device.last_push_at` and `last_seen_at`; emits the event `sync.changed` when at least one op was accepted. 

        :param push_request: (required)
        :type push_request: PushRequest
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

        _param = self._sync_push_serialize(
            push_request=push_request,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "PushResponse",
            '400': "Problem",
            '403': "Problem",
            '413': "Problem",
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
    def sync_push_without_preload_content(
        self,
        push_request: PushRequest,
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
        """Push a batch of locally minted operations.

        **Bodies.** The request body is exactly `contracts/sync/batch.schema.json#/$defs/PushRequest` and the `200` body is exactly `#/$defs/PushResponse`; this operation adds no wrapper and no fields of its own. `contracts/sync/SYNC.md` sections 5.1-5.4 own the semantics (per-op procedure, result statuses `accepted` / `duplicate` / `rejected`, rejection codes, `high_water_seq`, `server_time` as `instant_ms`, `log_epoch`, `durable_seq`); this operation defines only the HTTP framing, auth and status codes. Where this text and SYNC.md disagree, SYNC.md wins.  **What the server validates as a whole.** The server validates the body against `#/$defs/PushRequestLenient` **only** (a JSON object with `ops`: 1-500 objects, each with a UUID `op_id`). If that fails the whole request is `400 invalid_request` and nothing is applied (`errors[]` points at the offending part). The schema `PushRequest` is what a correct client sends; the server never rejects a whole request because an op fails it.  **Duplicate keys (C-2, SYNC.md 5.2 \"Parsing the body\").** The server parses the body with a syntax-only, duplicate-tolerant JSON parse. A key repeated in the top-level body object, an element whose `op_id` member is repeated, or two elements with the same `op_id` and different contents fail `PushRequestLenient` (`400 invalid_request`). A key repeated anywhere inside one element of `ops` is NOT a 400: that op is `rejected` with `schema_invalid`, decided before `op_too_large` (its canonical size is undefined). Clients MUST send each op exactly as `CanonicalJson.encode` / `OpCodec.encode` produce it, which never contains a duplicate key.  **Everything else is per op** (SYNC.md 5.2, 5.3), in array order, and reported as a `rejected` result while the remaining ops are still processed: an op over `limits.max_op_bytes` (`op_too_large`, checked first; SYNC.md 5.4), unknown op type or version (`unsupported_op`), an op invalid against `op.schema.json` (`schema_invalid`), an op whose `device_id` is not the caller's device (`device_mismatch`), server-only ops (`server_only`), missing references (`unknown_reference`), a wrong device clock (`clock_ahead`, `clock_behind`; SYNC.md 3.4 and 10.4), `invalid_value`, `invalid_target`, `immutable`. This API adds one code to SYNC.md's open set: `insufficient_scope` - an op of type `review.*` pushed by a token without the `review` scope; checked right after `device_mismatch` (SYNC.md 5.3 order). Clients treat unknown codes as permanent rejections (SYNC.md 5.3).  **Request-level errors** (nothing applied, rows stay `pending`, SYNC.md 5.4): - `401 unauthenticated`; `403 insufficient_scope` (no `write`); `403 device_required`   (the token is not bound to a device: only `device` tokens and `personal` tokens created   with `with_device` may push; session cookies have no device). - `400 invalid_request`: not JSON, or `PushRequestLenient` fails (empty `ops`, more than   500 ops, an op without a valid `op_id`, ...). - `413 payload_too_large`: body over `limits.max_push_bytes` (`GET /meta`, default 8 MiB).   This is about the batch, never about one op: a single op over `limits.max_op_bytes`   (default 1 MiB, at most `max_push_bytes - 4096`) is a per-op `op_too_large` result in   a `200`. On a `413` the client does not back off (SYNC.md 5.4): it halves the batch   (the first `ceil(n/2)` ops, still in ascending `hlc`) and pushes again at once, down to   a single op. If a single op still gets `413` (the server's limits are inconsistent or   the client's view of them is stale), the client marks that row rejected with   `op_too_large` itself and continues with the next ops; it is then an ordinary rejected   op (SYNC.md 10). - `415`, `429`, `5xx` as everywhere. There is no whole-request `409` and no whole-request `422` on this operation.  **Status.** `200` whenever the body passed `PushRequestLenient`, even if every op was rejected. `results` has exactly one entry per pushed op, in request order. Ops the server emits as consequences (`note.merged`, `card.merged`, ...) are not in `results`; they arrive through `GET /sync/pull`.  **Retry.** On no response, `5xx` or `429` the client re-sends the same ops (SYNC.md 5.4); ops already committed come back as `duplicate` with their original `seq`. Before using a response the client compares `log_epoch` and `high_water_seq` with its stored state (SYNC.md 6.2, log reset) and checks `server_time` against its own clock (SYNC.md 3.4).  Side effects: updates `device.last_push_at` and `last_seen_at`; emits the event `sync.changed` when at least one op was accepted. 

        :param push_request: (required)
        :type push_request: PushRequest
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

        _param = self._sync_push_serialize(
            push_request=push_request,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "PushResponse",
            '400': "Problem",
            '403': "Problem",
            '413': "Problem",
            'default': "Problem",
        }
        response_data = self.api_client.call_api(
            *_param,
            _request_timeout=_request_timeout
        )
        return response_data.response


    def _sync_push_serialize(
        self,
        push_request,
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
        if push_request is not None:
            _body_params = push_request


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
            resource_path='/sync/push',
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


