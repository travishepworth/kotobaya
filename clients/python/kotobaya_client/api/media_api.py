"""
    Kotobaya API

    Versioned REST/JSON interface at `/api/v1`. The web app uses nothing else; the Android app uses `sync`, `blobs`, `dict/release`, `devices/pair`, `devices/self` and whatever conveniences it likes.  Implementers: this file is the contract. If something you need is missing or wrong, do not work around it; return `CONTRACT_CHANGE_REQUEST`. Additive changes stay in v1; breaking changes go to `/api/v2`.  ## 1. Conventions  - **JSON.** Request and response bodies are `application/json; charset=utf-8` unless stated.   Property names are `snake_case` and equal the column names of `contracts/db/V1__init.sql`   wherever a property mirrors a column; the column `COMMENT` is then the definition. - **Unknown properties.** Servers reject unknown request properties and unknown query   parameters with `422 validation_failed`. Clients MUST ignore unknown response properties and   MUST treat unknown enum values in responses as \"other\" (v1 may add both). - **Absent vs null.** In `PATCH` bodies an absent property means \"leave unchanged\"; `null` means   \"set to NULL\" where the property is nullable and is a validation error otherwise.   Responses always include every documented property; nullable ones are `null`, never absent,   unless the property is documented as \"omitted when ...\". - **Ids.** Row ids are UUIDv7 in canonical lowercase `8-4-4-4-12` text. Uppercase ids in   requests are rejected with `422`. \"Entry\" always means a JMdict sequence number (`seq`,   JSON integer). - **Time.** Instants are RFC 3339 UTC strings with millisecond precision and a `Z` suffix,   e.g. `2026-11-12T09:30:00.000Z`. Servers accept any RFC 3339 offset on input and normalise   to UTC. A \"study day\" is a local date `YYYY-MM-DD` computed from the settings   `scheduler.timezone` and `scheduler.day_rollover_hour`: study day D covers local time   `D rollover:00` (inclusive) to `D+1 rollover:00` (exclusive). - **Text offsets** into sentence text are UTF-16 code units, half-open `[start, end)`. - **Blobs** (audio, images, uploads, snapshots) are addressed by the lowercase hex SHA-256 of   their bytes and transferred through `/blobs/{hash}`, never inline in JSON.  ## 2. Authentication and scopes  Three credential kinds, all rows of `app.api_token` (stored hashed):  | Kind | How obtained | Sent as | Scopes | |---|---|---|---| | `session` | `POST /auth/login` | cookie `kotobaya_session` (HTTP-only) | all five | | `device` | `POST /devices/pair` | `Authorization: Bearer kbd_...` | `read`, `write`, `review` | | `personal` | `POST /tokens` | `Authorization: Bearer kbp_...` | chosen at creation |  Token strings are `<3-letter kind prefix>_<40 characters of [A-Za-z0-9]>` (`kbs_`, `kbd_`, `kbp_`), generated from a CSPRNG. `token_hash` = lowercase hex SHA-256 of the whole string; `token_prefix` = its first 8 characters. A token is valid iff `revoked_at IS NULL` and (`expires_at IS NULL` or `expires_at > now()`) and, when it has a `device_id`, that device is not revoked. Validity is checked on every request (no caching longer than 5 seconds), so revocation takes effect at once.  Scopes are independent (no scope implies another):  | Scope | Grants | |---|---| | `read` | Every `GET` of learning, media and reference data; `POST /parse`; `GET /events`; `GET /sync/pull`; exports. | | `write` | Create, change, delete notes, cards, decks, grammar, note types, presets, settings, word status, parse overrides, media metadata; upload blobs; `POST /sync/push` (non-review ops). | | `review` | `POST /review/answer`, `POST /review/undo`, `POST /drill/session`, `POST /cards/bulk/schedule`; `review.*` ops inside `POST /sync/push` (per op; without it the op is rejected with `insufficient_scope`). | | `import` | Start and cancel import, parse, dictionary and optimizer jobs; upload blobs. | | `admin` | Devices, pairing, tokens (a device's own row: `/devices/self` with `read`/`write`). |  Each operation lists the scope it needs in its `security` requirement: the array after the scheme name. When two alternatives are listed for one scheme the caller needs either. A request with no or an invalid credential gets `401 unauthenticated`; a valid credential lacking the scope gets `403 insufficient_scope`. Operations with `security: []` are public.  There are exactly two places where who may call is not just \"scheme + scope\", and both are declared in the operation, not only in prose: - `GET/PATCH /devices/self` (`read` / `write`, bearer only): the token must be bound to a   device, else `403 device_required`. A device never needs `admin` to manage its own row;   `/devices/{device_id}` is admin only. - `POST /ankiconnect` (`x-auth: body-key`): the token may instead be sent in the JSON body   property `key`; the empty `{}` alternative in its `security` only signals that no header is   needed. The scope for `key` is `x-body-key-scope`.  **CSRF.** A request authenticated by the session cookie whose method is not `GET` or `HEAD` MUST carry the header `X-Kotobaya-Csrf: 1`; otherwise `403 csrf_required`. The cookie is set with `HttpOnly; Secure; SameSite=Strict; Path=/api`. The server sends no CORS headers (the web app is served from the same origin). If both a cookie and an `Authorization` header are present, the header wins and the cookie is ignored.  **Audit.** Every request is logged with `token.id` and `token.name` (structured server log; there is no audit table or endpoint in v1). Ops record the token in `change.token_id`.  ## 3. Writes are sync ops  Learning data is only ever changed by applying sync ops (`contracts/sync`). Every REST operation that changes learning data is a convenience wrapper: the server builds the op(s) named in the operation's `x-emits-ops`, applies them through the same op-apply path as `POST /sync/push` in one transaction, and they then appear in `GET /sync/pull` for every device. Op names are those of `contracts/db/SCHEMA.md` section 4; `contracts/sync` is authoritative for names and payloads.  For an op the server builds on behalf of a REST call:  - `op_id` is a fresh UUIDv7; origin `device_id` is the **server device**   `00000000-0000-7000-8000-000000000001` (also for session and device tokens: only one party   ever generates HLCs for a given device id); `hlc` comes from the server's HLC clock;   `change.token_id` is the calling token. - A property absent from a `PATCH` body produces no register write. A property present with   the value the row already has still produces a write (it bumps the register's clock). - If a call changes nothing (documented per operation, e.g. deleting an already deleted   note), no op is emitted and the response is still successful. - Responses to mutating calls carry the header `Kotobaya-Seq`: the `change.seq` of the last op   the call emitted. A client holding a local replica knows it has seen the effect once its   pull cursor is at or past that value. The header is absent when no op was emitted.  **Idempotent creation.** Create bodies accept an optional client-generated `id` (UUIDv7). Re-sending a create with an `id` that already exists returns the existing resource with status `200` instead of `201` and emits nothing, whatever the rest of the body says. Clients that may retry MUST send `id`. When `id` is omitted the server generates one. `POST /review/answer` requires `id` (a duplicate review would corrupt scheduling); `POST /media/import` takes the same optional idempotency id as `media_id`.  The existence check and the emit are **one transaction that takes advisory lock `7033001` before the check** and holds it until commit (the lock `POST /sync/push` takes per op, SYNC.md 5.2): check whether `id` exists; if not, mint, apply and log the op(s) - for `createNote` the `note.created` and every `card.created` - then commit. Without the lock two concurrent retries of the same create both pass the check; the second `note.created` is a no-op on the row (SYNC.md 7.2) but its `card.created` ops carry fresh card ids, so the note would get duplicate cards. The same holds for every other id-idempotent call (`POST /review/answer`, `POST /review/undo`, `POST /media/import`), and a pushed op that creates the same id races safely because it takes the same lock.  **Tombstones and merges.** Deleted learning rows are tombstones (`deleted_at` set). `GET` by id of a deleted row returns `410 gone` unless `include_deleted=true`; lists omit deleted rows unless `include_deleted=true`. A note or card that lost a merge (`merged_into_id` set) is answered with its survivor: `GET`, `PATCH` and review calls addressed to the loser's id act on the survivor and the response body is the survivor (its `id` differs from the requested id; the response has `Content-Location` with the survivor's URL).  ## 4. Pagination  List operations that take `limit` and `cursor` return `{\"items\": [...], \"next_cursor\": ...}`. `next_cursor` is an opaque string, or `null` on the last page. Pass it back unchanged as `cursor` together with the same other parameters; changing any other parameter while passing a cursor is `422 validation_failed` (`pointer` `/cursor`). `limit` defaults to 50, maximum 500. Order is stable and documented per operation; rows created while paging may or may not appear.  **Cursor semantics (keyset, no snapshot).** A cursor encodes the sort-key values and the `id` of the last row returned; the next page is the rows strictly after that tuple in the current order, evaluated against the data at the time of that request. The order is not snapshotted. Consequently, while a client pages through a list sorted by a mutable key (`due_at`, `updated_at`, `lapses`, ...): - a row whose key changes so that it moves from after the cursor to before it is **skipped**; - a row whose key changes so that it moves from before the cursor to after it is **returned   again** (with its new values); - a row deleted while paging disappears; one created may or may not appear. Rows that do not change are returned exactly once. Clients MUST de-duplicate by `id` (last occurrence wins). A client that needs an exact set (bulk actions, exports) uses an `id`-sorted list where offered, `POST /cards/bulk` with `query`, or `GET /export`. Lists sorted by an immutable key (`id`, `created_at`) have no skips or repeats. An empty result is `{\"items\": [], \"next_cursor\": null}` with status `200`.  ## 5. Errors  Every non-2xx response has the body `application/problem+json` (RFC 9457), schema `Problem`. `code` is the stable machine-readable identifier; `type` is `urn:kotobaya:problem:<code>`.  | Status | `code` | When | |---|---|---| | 400 | `invalid_request` | Body is not valid JSON, wrong content type framing, malformed header; `POST /sync/push` body not valid against `PushRequestLenient`. | | 401 | `unauthenticated` | No credential, or it is unknown, expired or revoked. | | 401 | `invalid_credentials` | Wrong password or TOTP code (`POST /auth/login`). | | 401 | `totp_required` | TOTP is enabled and `totp_code` was not sent. | | 401 | `invalid_pairing_code` | Pairing code unknown, expired or already used. | | 403 | `insufficient_scope` | Credential lacks the scope; `required_scope` names it. | | 403 | `csrf_required` | Cookie-authenticated write without `X-Kotobaya-Csrf`. | | 403 | `device_required` | `POST /sync/push` or `/devices/self` with a credential that has no `device_id`. | | 404 | `not_found` | No such resource (also: id of another resource kind). | | 409 | `already_exists` | Natural-key collision; `existing_id` names the holder. | | 409 | `builtin_immutable` | Deleting a built-in note type or the Default preset, or removing a built-in field. | | 409 | `in_use` | Deleting a note type that live notes use. | | 409 | `deck_read_only` | Changing items or deleting a `media`/`list` deck through deck endpoints. | | 409 | `not_applicable` | The resource kind does not support the operation (e.g. items of a smart deck). | | 409 | `card_not_reviewable` | Card is suspended. | | 409 | `job_not_cancellable` | Job already finished. | | 410 | `gone` | Tombstoned row requested without `include_deleted=true`. | | 413 | `payload_too_large` | Body over the limit in `GET /meta`. On `POST /sync/push` only for the whole batch (the client halves it); one op over `max_op_bytes` is a per-op `op_too_large` result instead. | | 415 | `unsupported_media_type` | Wrong `Content-Type`. | | 422 | `validation_failed` | Well-formed but invalid; `errors[]` lists each problem. | | 422 | `invalid_query` | Card search string does not parse; `errors[0].position` is the UTF-16 offset. | | 422 | `bulk_too_large` | Bulk selector matched more than 5000 cards. | | 422 | `hash_mismatch` | Uploaded bytes do not hash to the `{hash}` in the path. | | 429 | `rate_limited` | Too many login or pairing attempts; see `Retry-After`. | | 500 | `internal` | Bug. Safe to retry idempotent calls. | | 503 | `unavailable` | Database or object store unreachable. Retry with backoff. |  Check order: 401, then 403, then 404/410, then 415/400, then 422, then 409.  ## 6. Events  `GET /events` is a server-sent events stream; see that operation. Event payload schemas are `Event*` in components. 

    The version of the OpenAPI document: 1.0.0
    Generated by OpenAPI Generator (https://openapi-generator.tech)

    Do not edit the class manually.
"""  # noqa: E501


import warnings
from pydantic import validate_call, Field, StrictFloat, StrictStr, StrictInt
from typing import Any, Dict, List, Optional, Tuple, Union
from typing_extensions import Annotated

from pydantic import Field, StrictBool, StrictStr, field_validator
from typing import List, Optional
from typing_extensions import Annotated
from kotobaya_client.models.coverage import Coverage
from kotobaya_client.models.job import Job
from kotobaya_client.models.jpdb_deck_import_request import JpdbDeckImportRequest
from kotobaya_client.models.jpdb_history_import_request import JpdbHistoryImportRequest
from kotobaya_client.models.media import Media
from kotobaya_client.models.media_import_request import MediaImportRequest
from kotobaya_client.models.media_kind import MediaKind
from kotobaya_client.models.media_page import MediaPage
from kotobaya_client.models.media_patch import MediaPatch
from kotobaya_client.models.sentence import Sentence
from kotobaya_client.models.sentence_page import SentencePage

from kotobaya_client.api_client import ApiClient, RequestSerialized
from kotobaya_client.api_response import ApiResponse
from kotobaya_client.rest import RESTResponseType


class MediaApi:
    """NOTE: This class is auto generated by OpenAPI Generator
    Ref: https://openapi-generator.tech

    Do not edit the class manually.
    """

    def __init__(self, api_client=None) -> None:
        if api_client is None:
            api_client = ApiClient.get_default()
        self.api_client = api_client


    @validate_call
    def delete_media(
        self,
        media_id: Annotated[str, Field(strict=True, description="Media id.")],
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
    ) -> None:
        """Delete a media, its deck and its parsed content.

        Server emits `media.deleted` and, as a consequence in the same transaction, `deck.deleted` for the media deck; then hard-deletes the media's segments, sentences, occurrences and detected grammar spans (SCHEMA.md 4.3). Notes mined from it, manual spans and reviews stay. Queued or running jobs for the media are cancelled. Already deleted: `204`, no op. 

        :param media_id: Media id. (required)
        :type media_id: str
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

        _param = self._delete_media_serialize(
            media_id=media_id,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '204': None,
            '404': "Problem",
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
    def delete_media_with_http_info(
        self,
        media_id: Annotated[str, Field(strict=True, description="Media id.")],
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
    ) -> ApiResponse[None]:
        """Delete a media, its deck and its parsed content.

        Server emits `media.deleted` and, as a consequence in the same transaction, `deck.deleted` for the media deck; then hard-deletes the media's segments, sentences, occurrences and detected grammar spans (SCHEMA.md 4.3). Notes mined from it, manual spans and reviews stay. Queued or running jobs for the media are cancelled. Already deleted: `204`, no op. 

        :param media_id: Media id. (required)
        :type media_id: str
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

        _param = self._delete_media_serialize(
            media_id=media_id,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '204': None,
            '404': "Problem",
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
    def delete_media_without_preload_content(
        self,
        media_id: Annotated[str, Field(strict=True, description="Media id.")],
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
        """Delete a media, its deck and its parsed content.

        Server emits `media.deleted` and, as a consequence in the same transaction, `deck.deleted` for the media deck; then hard-deletes the media's segments, sentences, occurrences and detected grammar spans (SCHEMA.md 4.3). Notes mined from it, manual spans and reviews stay. Queued or running jobs for the media are cancelled. Already deleted: `204`, no op. 

        :param media_id: Media id. (required)
        :type media_id: str
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

        _param = self._delete_media_serialize(
            media_id=media_id,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '204': None,
            '404': "Problem",
            'default': "Problem",
        }
        response_data = self.api_client.call_api(
            *_param,
            _request_timeout=_request_timeout
        )
        return response_data.response


    def _delete_media_serialize(
        self,
        media_id,
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
        if media_id is not None:
            _path_params['media_id'] = media_id
        # process the query parameters
        # process the header parameters
        # process the form parameters
        # process the body parameter


        # set the HTTP header `Accept`
        if not any(key.lower() == 'accept' for key in _header_params):
            _header_params['Accept'] = self.api_client.select_header_accept(
                [
                    'application/problem+json'
                ]
            )


        # authentication setting
        _auth_settings: List[str] = [
            'sessionCookie', 
            'bearerAuth'
        ]

        return self.api_client.param_serialize(
            method='DELETE',
            resource_path='/media/{media_id}',
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
    def get_media(
        self,
        media_id: Annotated[str, Field(strict=True, description="Media id.")],
        include_deleted: Annotated[Optional[StrictBool], Field(description="Also return tombstoned rows (`deleted_at` set).")] = None,
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
    ) -> Media:
        """One media with its segments.


        :param media_id: Media id. (required)
        :type media_id: str
        :param include_deleted: Also return tombstoned rows (`deleted_at` set).
        :type include_deleted: bool
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

        _param = self._get_media_serialize(
            media_id=media_id,
            include_deleted=include_deleted,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "Media",
            '404': "Problem",
            '410': "Problem",
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
    def get_media_with_http_info(
        self,
        media_id: Annotated[str, Field(strict=True, description="Media id.")],
        include_deleted: Annotated[Optional[StrictBool], Field(description="Also return tombstoned rows (`deleted_at` set).")] = None,
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
    ) -> ApiResponse[Media]:
        """One media with its segments.


        :param media_id: Media id. (required)
        :type media_id: str
        :param include_deleted: Also return tombstoned rows (`deleted_at` set).
        :type include_deleted: bool
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

        _param = self._get_media_serialize(
            media_id=media_id,
            include_deleted=include_deleted,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "Media",
            '404': "Problem",
            '410': "Problem",
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
    def get_media_without_preload_content(
        self,
        media_id: Annotated[str, Field(strict=True, description="Media id.")],
        include_deleted: Annotated[Optional[StrictBool], Field(description="Also return tombstoned rows (`deleted_at` set).")] = None,
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
        """One media with its segments.


        :param media_id: Media id. (required)
        :type media_id: str
        :param include_deleted: Also return tombstoned rows (`deleted_at` set).
        :type include_deleted: bool
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

        _param = self._get_media_serialize(
            media_id=media_id,
            include_deleted=include_deleted,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "Media",
            '404': "Problem",
            '410': "Problem",
            'default': "Problem",
        }
        response_data = self.api_client.call_api(
            *_param,
            _request_timeout=_request_timeout
        )
        return response_data.response


    def _get_media_serialize(
        self,
        media_id,
        include_deleted,
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
        if media_id is not None:
            _path_params['media_id'] = media_id
        # process the query parameters
        if include_deleted is not None:
            
            _query_params.append(('include_deleted', include_deleted))
            
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
            resource_path='/media/{media_id}',
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
    def get_media_coverage(
        self,
        media_id: Annotated[str, Field(strict=True, description="Media id.")],
        segment_id: Annotated[Optional[Annotated[str, Field(strict=True)]], Field(description="Restrict to one segment of this media (`404` if it is not one).")] = None,
        hide: Annotated[Optional[List[StrictStr]], Field(description="Classes left out of every coverage number, comma-separated. `names`, `particles`, `auxiliary` = entries whose `occurrence.pos_class` is `name`, `particle`, `auxiliary`; `ignored` = entries whose word status is `blacklisted` or `ignored`; `unmapped` = tokens that matched no dictionary entry. Default `ignored`. Pass an empty value (`hide=`) to hide nothing. ")] = None,
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
    ) -> Coverage:
        """Coverage of a media (or one segment) by your word statuses.

        Computed at request time from `occurrence`, `media_segment` and `word_status`, so it always reflects the current statuses. Definitions are on the `Coverage` schema. With `segment_id` the numbers cover only that segment and `segments` has that one element. Media with `content_version = 0` (not parsed yet) returns all-zero numbers. 

        :param media_id: Media id. (required)
        :type media_id: str
        :param segment_id: Restrict to one segment of this media (`404` if it is not one).
        :type segment_id: str
        :param hide: Classes left out of every coverage number, comma-separated. `names`, `particles`, `auxiliary` = entries whose `occurrence.pos_class` is `name`, `particle`, `auxiliary`; `ignored` = entries whose word status is `blacklisted` or `ignored`; `unmapped` = tokens that matched no dictionary entry. Default `ignored`. Pass an empty value (`hide=`) to hide nothing. 
        :type hide: List[str]
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

        _param = self._get_media_coverage_serialize(
            media_id=media_id,
            segment_id=segment_id,
            hide=hide,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "Coverage",
            '404': "Problem",
            '410': "Problem",
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
    def get_media_coverage_with_http_info(
        self,
        media_id: Annotated[str, Field(strict=True, description="Media id.")],
        segment_id: Annotated[Optional[Annotated[str, Field(strict=True)]], Field(description="Restrict to one segment of this media (`404` if it is not one).")] = None,
        hide: Annotated[Optional[List[StrictStr]], Field(description="Classes left out of every coverage number, comma-separated. `names`, `particles`, `auxiliary` = entries whose `occurrence.pos_class` is `name`, `particle`, `auxiliary`; `ignored` = entries whose word status is `blacklisted` or `ignored`; `unmapped` = tokens that matched no dictionary entry. Default `ignored`. Pass an empty value (`hide=`) to hide nothing. ")] = None,
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
    ) -> ApiResponse[Coverage]:
        """Coverage of a media (or one segment) by your word statuses.

        Computed at request time from `occurrence`, `media_segment` and `word_status`, so it always reflects the current statuses. Definitions are on the `Coverage` schema. With `segment_id` the numbers cover only that segment and `segments` has that one element. Media with `content_version = 0` (not parsed yet) returns all-zero numbers. 

        :param media_id: Media id. (required)
        :type media_id: str
        :param segment_id: Restrict to one segment of this media (`404` if it is not one).
        :type segment_id: str
        :param hide: Classes left out of every coverage number, comma-separated. `names`, `particles`, `auxiliary` = entries whose `occurrence.pos_class` is `name`, `particle`, `auxiliary`; `ignored` = entries whose word status is `blacklisted` or `ignored`; `unmapped` = tokens that matched no dictionary entry. Default `ignored`. Pass an empty value (`hide=`) to hide nothing. 
        :type hide: List[str]
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

        _param = self._get_media_coverage_serialize(
            media_id=media_id,
            segment_id=segment_id,
            hide=hide,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "Coverage",
            '404': "Problem",
            '410': "Problem",
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
    def get_media_coverage_without_preload_content(
        self,
        media_id: Annotated[str, Field(strict=True, description="Media id.")],
        segment_id: Annotated[Optional[Annotated[str, Field(strict=True)]], Field(description="Restrict to one segment of this media (`404` if it is not one).")] = None,
        hide: Annotated[Optional[List[StrictStr]], Field(description="Classes left out of every coverage number, comma-separated. `names`, `particles`, `auxiliary` = entries whose `occurrence.pos_class` is `name`, `particle`, `auxiliary`; `ignored` = entries whose word status is `blacklisted` or `ignored`; `unmapped` = tokens that matched no dictionary entry. Default `ignored`. Pass an empty value (`hide=`) to hide nothing. ")] = None,
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
        """Coverage of a media (or one segment) by your word statuses.

        Computed at request time from `occurrence`, `media_segment` and `word_status`, so it always reflects the current statuses. Definitions are on the `Coverage` schema. With `segment_id` the numbers cover only that segment and `segments` has that one element. Media with `content_version = 0` (not parsed yet) returns all-zero numbers. 

        :param media_id: Media id. (required)
        :type media_id: str
        :param segment_id: Restrict to one segment of this media (`404` if it is not one).
        :type segment_id: str
        :param hide: Classes left out of every coverage number, comma-separated. `names`, `particles`, `auxiliary` = entries whose `occurrence.pos_class` is `name`, `particle`, `auxiliary`; `ignored` = entries whose word status is `blacklisted` or `ignored`; `unmapped` = tokens that matched no dictionary entry. Default `ignored`. Pass an empty value (`hide=`) to hide nothing. 
        :type hide: List[str]
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

        _param = self._get_media_coverage_serialize(
            media_id=media_id,
            segment_id=segment_id,
            hide=hide,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "Coverage",
            '404': "Problem",
            '410': "Problem",
            'default': "Problem",
        }
        response_data = self.api_client.call_api(
            *_param,
            _request_timeout=_request_timeout
        )
        return response_data.response


    def _get_media_coverage_serialize(
        self,
        media_id,
        segment_id,
        hide,
        _request_auth,
        _content_type,
        _headers,
        _host_index,
    ) -> RequestSerialized:

        _host = None

        _collection_formats: Dict[str, str] = {
            'hide': 'csv',
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
        if media_id is not None:
            _path_params['media_id'] = media_id
        # process the query parameters
        if segment_id is not None:
            
            _query_params.append(('segment_id', segment_id))
            
        if hide is not None:
            
            _query_params.append(('hide', hide))
            
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
            resource_path='/media/{media_id}/coverage',
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
    def get_sentence(
        self,
        sentence_id: Annotated[str, Field(strict=True, description="Sentence id.")],
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
    ) -> Sentence:
        """One sentence with tokens and grammar spans.

        `404` when the sentence does not exist (never imported, or purged with its media or by a re-parse).

        :param sentence_id: Sentence id. (required)
        :type sentence_id: str
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

        _param = self._get_sentence_serialize(
            sentence_id=sentence_id,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "Sentence",
            '404': "Problem",
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
    def get_sentence_with_http_info(
        self,
        sentence_id: Annotated[str, Field(strict=True, description="Sentence id.")],
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
    ) -> ApiResponse[Sentence]:
        """One sentence with tokens and grammar spans.

        `404` when the sentence does not exist (never imported, or purged with its media or by a re-parse).

        :param sentence_id: Sentence id. (required)
        :type sentence_id: str
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

        _param = self._get_sentence_serialize(
            sentence_id=sentence_id,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "Sentence",
            '404': "Problem",
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
    def get_sentence_without_preload_content(
        self,
        sentence_id: Annotated[str, Field(strict=True, description="Sentence id.")],
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
        """One sentence with tokens and grammar spans.

        `404` when the sentence does not exist (never imported, or purged with its media or by a re-parse).

        :param sentence_id: Sentence id. (required)
        :type sentence_id: str
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

        _param = self._get_sentence_serialize(
            sentence_id=sentence_id,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "Sentence",
            '404': "Problem",
            'default': "Problem",
        }
        response_data = self.api_client.call_api(
            *_param,
            _request_timeout=_request_timeout
        )
        return response_data.response


    def _get_sentence_serialize(
        self,
        sentence_id,
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
        if sentence_id is not None:
            _path_params['sentence_id'] = sentence_id
        # process the query parameters
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
            resource_path='/sentences/{sentence_id}',
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
    def import_jpdb_deck(
        self,
        jpdb_deck_import_request: JpdbDeckImportRequest,
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
    ) -> Job:
        """Import a pre-parsed jpdb media deck (vocabulary with occurrence counts).

        Enqueues a job of kind `jpdb-deck-import` (dedupe key `jpdb-deck-import:<jpdb_id>`) and returns it with `202`. If a live media with this `jpdb_id` exists the job updates it (same `media_id`, new content version); otherwise `media_id` is pre-allocated as in `POST /media/import`. The job emits, as server ops, `media.created` and `deck.created` (first import only), `media.content_published`, `deck.content_published`. The result has one segment of kind `whole` and no sentences. Vocabulary that maps to no JMdict entry is listed in the job result (`unmapped`), never silently dropped. An equivalent queued or running job is returned with `200`. 

        :param jpdb_deck_import_request: (required)
        :type jpdb_deck_import_request: JpdbDeckImportRequest
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

        _param = self._import_jpdb_deck_serialize(
            jpdb_deck_import_request=jpdb_deck_import_request,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "Job",
            '202': "Job",
            '422': "Problem",
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
    def import_jpdb_deck_with_http_info(
        self,
        jpdb_deck_import_request: JpdbDeckImportRequest,
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
    ) -> ApiResponse[Job]:
        """Import a pre-parsed jpdb media deck (vocabulary with occurrence counts).

        Enqueues a job of kind `jpdb-deck-import` (dedupe key `jpdb-deck-import:<jpdb_id>`) and returns it with `202`. If a live media with this `jpdb_id` exists the job updates it (same `media_id`, new content version); otherwise `media_id` is pre-allocated as in `POST /media/import`. The job emits, as server ops, `media.created` and `deck.created` (first import only), `media.content_published`, `deck.content_published`. The result has one segment of kind `whole` and no sentences. Vocabulary that maps to no JMdict entry is listed in the job result (`unmapped`), never silently dropped. An equivalent queued or running job is returned with `200`. 

        :param jpdb_deck_import_request: (required)
        :type jpdb_deck_import_request: JpdbDeckImportRequest
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

        _param = self._import_jpdb_deck_serialize(
            jpdb_deck_import_request=jpdb_deck_import_request,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "Job",
            '202': "Job",
            '422': "Problem",
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
    def import_jpdb_deck_without_preload_content(
        self,
        jpdb_deck_import_request: JpdbDeckImportRequest,
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
        """Import a pre-parsed jpdb media deck (vocabulary with occurrence counts).

        Enqueues a job of kind `jpdb-deck-import` (dedupe key `jpdb-deck-import:<jpdb_id>`) and returns it with `202`. If a live media with this `jpdb_id` exists the job updates it (same `media_id`, new content version); otherwise `media_id` is pre-allocated as in `POST /media/import`. The job emits, as server ops, `media.created` and `deck.created` (first import only), `media.content_published`, `deck.content_published`. The result has one segment of kind `whole` and no sentences. Vocabulary that maps to no JMdict entry is listed in the job result (`unmapped`), never silently dropped. An equivalent queued or running job is returned with `200`. 

        :param jpdb_deck_import_request: (required)
        :type jpdb_deck_import_request: JpdbDeckImportRequest
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

        _param = self._import_jpdb_deck_serialize(
            jpdb_deck_import_request=jpdb_deck_import_request,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "Job",
            '202': "Job",
            '422': "Problem",
            'default': "Problem",
        }
        response_data = self.api_client.call_api(
            *_param,
            _request_timeout=_request_timeout
        )
        return response_data.response


    def _import_jpdb_deck_serialize(
        self,
        jpdb_deck_import_request,
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
        if jpdb_deck_import_request is not None:
            _body_params = jpdb_deck_import_request


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
            resource_path='/media/import/jpdb',
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
    def import_jpdb_history(
        self,
        jpdb_history_import_request: JpdbHistoryImportRequest,
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
    ) -> Job:
        """Import jpdb review history and word statuses from a jpdb export file.

        Upload the export JSON with `PUT /blobs/{hash}` (kind `upload`), then call this. Enqueues a job of kind `jpdb-history-import` (dedupe key `jpdb-history-import`). The job emits, as server ops: `note.created` and `card.created` for vocabulary and kanji without a note or card, `review.logged` for every historical review (`import_source = jpdb`, deterministic review ids, see \"Review ids\", so that re-running imports nothing twice), and `word.status_set` (payload `origin = jpdb_import`, stored as `override_origin`) for known-only words. The observed export carries no never-forget or blacklist data, so none is written. Records that cannot be mapped are counted and listed in the job result. An equivalent queued or running job is returned with `200`. Mapping rules follow ADR-008 and ADR-009 (C-4).  **Export arrays (C-2, C-4; spikes/jpdb-map/REPORT.md \"Export structure\").** The export is one object whose array members are read as follows; any other member (top level, row or event) is ignored, a missing or non-array member counts as empty, and `from_anki` is accepted with any value (`true` included) and ignored. | Array | Row | Becomes | |---|---|---| | `cards_vocabulary_jp_en` | `vid`, `spelling`, `reading`, `reviews[]` | vocab note (built-in Vocab type `00000000-0000-7000-8000-000000000101`, `entry_seq` = the mapped JMdict seq, `origin = jpdb_import`) with exactly one card, kind `meaning` (JP → EN) | | `cards_vocabulary_en_jp` | same | nothing: counted `vocabulary_en_jp_ignored`, its events `reviews_skipped`; no note, card, status or review (ADR-009(1): no production cards) | | `cards_kanji_char_keyword` | `character`, `reviews[]`, optional `keyword` | kanji note, card kind `keyword` (kanji → keyword) | | `cards_kanji_keyword_char` | `character`, `reviews[]`, optional `keyword` | kanji note, card kind `writing` (keyword → kanji), created **suspended** |  Tolerated drift: a row whose `reviews` is missing or not an array has zero events; a vocab row with a missing or non-integer `vid` is mapped by text only and, if unmapped, listed with `jpdb_vid = 0`; a missing `spelling` / `reading` reads as `\"\"`. An element of `reviews[]` that is not an object with an integer `timestamp` and a string `grade` is *malformed* (counted `reviews_unknown_grade`).  **Vocab rows and groups.** Each `cards_vocabulary_jp_en` row is mapped to a JMdict seq (vid lookup first; spikes/jpdb-map/REPORT.md \"Mapping\") or listed under `unmapped`. Mapped rows are grouped by seq: rows sharing a seq (about 260 vids repeat in 2-3 rows with different spellings; two vids mapping to one seq are also one group) form **one group** = one note and one `meaning` card, whose history is the union of the rows' events in ascending `timestamp` order (ties: array order, row order, element order). Within a group, an event with the same `timestamp` and `grade` as an earlier one is an **exact duplicate**: dropped and counted `reviews_duplicate_dropped` (malformed events are never duplicates). The group's kept, well-formed events decide its fate: - all of them are `known` and there is at least one: **status-only** (ADR-009(3)). No   note or card. If `import_word_status`, one `word.status_set` status `known`, `origin =   jpdb_import` for the seq, unless the word already has a live override (any status,   any origin; then nothing is written, so a re-run never overwrites the user's choice).   Every row of the group counts `vocabulary_status_only`. - otherwise (at least one non-`known` event, or no well-formed events at all):   **carded**. Every row counts `vocabulary_carded`. The group maps to the live note with   that `entry_seq` if one exists (`notes_existing`, once per seq), else `note.created`   (`notes_created`). The job then ensures a live card of kind `meaning` on it   (`card.created`, `cards_created`, only if none exists) - only that kind, never the   Vocab type's other default kinds (`reading`, `production`); existing cards of other   kinds are left alone and get no history. A `known` event of a carded group writes   nothing (FSRS schedules from the reviews; no override). So `notes_created + notes_existing` = distinct seqs of carded groups, and `cards_created <= notes_created + notes_existing` (about equal to `notes_created`).  **Kanji rows and groups.** A row whose `character` is exactly one Unicode code point is mapped; any other is listed under `kanji_unmapped`. Mapped rows are grouped by (`character`, card kind); a group's events are merged and de-duplicated exactly as for vocab. The character maps to the live note with that `kanji_literal` if one exists (counted `kanji_notes_existing`, once per character), else to a new note: `note.created` with `note_type_id` = the built-in Kanji type (`00000000-0000-7000-8000-000000000102`, base type `kanji`), `kanji_literal` = `character`, `fields` = `{\"literal\": character}` plus `\"keyword\": <text>` when a row of that character carries a `keyword` member that is a non-empty string after trimming (ADR-009(4); the first such row in array order `cards_kanji_char_keyword`, `cards_kanji_keyword_char`, then row order; the observed export has no `keyword` member, so it is normally absent and the renderer fills it from `ref.kanji`; user-editable either way). `readings` and `notes` are never written. An existing note's fields are never changed. `origin = jpdb_import`. Both arrays naming the same character share one note. The job then ensures a live card of the group's kind (`keyword` or `writing`) - only that kind, never the note type's other default kinds. A card is created **with `suspended = true` in its `card.created` payload** (no separate `card.suspended` op; counted `kanji_cards_suspended`) when its kind is `writing` (ADR-009(1): preserved with its history, never shown unless the user unsuspends it) or when the group is known-only (all kept well-formed events `known`, at least one; ADR-009(3)). A card that already exists keeps its `suspended` state (a re-run never re-suspends a card the user unsuspended). Every mapped kanji row counts `kanji_carded`, known-only ones included (kanji have no word status). A character missing from `ref.kanji` is still imported.  **Grades** (each kept, well-formed event of a carded vocab group or a kanji group; `timestamp` is Unix seconds UTC → `reviewed_at` = timestamp × 1000, `utc_offset_min = null`, `duration_ms = null`, `review_type = scheduled`, `import_source = jpdb`; grades match exactly, case-sensitive): | `grade` | Effect | |---|---| | `nothing` | `review.logged` rating 1, button `nothing` | | `something` | rating 1, button `something` | | `hard` | rating 2, button `hard` (FSRS Hard; ADR-008) | | `okay` | rating 3, button `okay` | | `easy` | rating 4, button `easy` | | `pass` | rating 3, button `null` | | `fail` | rating 1, button `null` | | `unknown` | no review; the card's creation is all it means. Counted `status_events_unknown` | | `known` | no review. Status-only vocab group: the word status above. Carded vocab group or any kanji group: nothing (a known-only kanji card is created suspended). Counted `status_events_known` | | anything else | nothing; counted `reviews_unknown_grade` |  **Review ids.** Each rating event gets `id` = a UUIDv7 whose 48-bit timestamp is `reviewed_at` and whose other bits come from `h = SHA-256(UTF-8(name))`, `name = \"jpdb-review/v1|\" + key + \"|\" + timestamp + \"|\" + grade + \"|\" + occ`: `key` is `\"v\" + vid` (decimal, the event's own row's vid; `\"v0\"` if absent) for vocab, `\"k:keyword:\" + character` or `\"k:writing:\" + character` for kanji; `timestamp` is the export's decimal seconds; `occ` is the 0-based index of the event among the group's kept events with the same `timestamp`, in group order. Bytes: `b[0..5]` = `reviewed_at` big-endian, `b[6] = 0x70 | (h[0] & 0x0F)`, `b[7] = h[1]`, `b[8] = 0x80 | (h[2] & 0x3F)`, `b[9..15] = h[3..9]`. A rating event whose id already exists in `review_log` (any state, undone included) is not emitted again (`reviews_already_imported`). Rating ops of one card are emitted in group order, after the card's `card.created` (if any).  **`import_reviews = false`**: nothing but word statuses is written. Rows are bucketed by the same rules (`*_carded` then means \"would get a note and card\"); `notes_created`, `notes_existing`, `cards_created`, `kanji_notes_*`, `kanji_cards_*` are 0; rating events count as `reviews_skipped`; status, duplicate and unknown-grade events as usual. **`import_word_status = false`**: no `word.status_set`; bucketing is unchanged.  **Counting.** Every input record - each row of the four arrays and each element of its `reviews[]` - is counted in exactly one bucket of `JobResultJpdbHistory` (its description gives the three sums that must hold and the order in which an event's bucket is decided). 

        :param jpdb_history_import_request: (required)
        :type jpdb_history_import_request: JpdbHistoryImportRequest
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

        _param = self._import_jpdb_history_serialize(
            jpdb_history_import_request=jpdb_history_import_request,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "Job",
            '202': "Job",
            '422': "Problem",
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
    def import_jpdb_history_with_http_info(
        self,
        jpdb_history_import_request: JpdbHistoryImportRequest,
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
    ) -> ApiResponse[Job]:
        """Import jpdb review history and word statuses from a jpdb export file.

        Upload the export JSON with `PUT /blobs/{hash}` (kind `upload`), then call this. Enqueues a job of kind `jpdb-history-import` (dedupe key `jpdb-history-import`). The job emits, as server ops: `note.created` and `card.created` for vocabulary and kanji without a note or card, `review.logged` for every historical review (`import_source = jpdb`, deterministic review ids, see \"Review ids\", so that re-running imports nothing twice), and `word.status_set` (payload `origin = jpdb_import`, stored as `override_origin`) for known-only words. The observed export carries no never-forget or blacklist data, so none is written. Records that cannot be mapped are counted and listed in the job result. An equivalent queued or running job is returned with `200`. Mapping rules follow ADR-008 and ADR-009 (C-4).  **Export arrays (C-2, C-4; spikes/jpdb-map/REPORT.md \"Export structure\").** The export is one object whose array members are read as follows; any other member (top level, row or event) is ignored, a missing or non-array member counts as empty, and `from_anki` is accepted with any value (`true` included) and ignored. | Array | Row | Becomes | |---|---|---| | `cards_vocabulary_jp_en` | `vid`, `spelling`, `reading`, `reviews[]` | vocab note (built-in Vocab type `00000000-0000-7000-8000-000000000101`, `entry_seq` = the mapped JMdict seq, `origin = jpdb_import`) with exactly one card, kind `meaning` (JP → EN) | | `cards_vocabulary_en_jp` | same | nothing: counted `vocabulary_en_jp_ignored`, its events `reviews_skipped`; no note, card, status or review (ADR-009(1): no production cards) | | `cards_kanji_char_keyword` | `character`, `reviews[]`, optional `keyword` | kanji note, card kind `keyword` (kanji → keyword) | | `cards_kanji_keyword_char` | `character`, `reviews[]`, optional `keyword` | kanji note, card kind `writing` (keyword → kanji), created **suspended** |  Tolerated drift: a row whose `reviews` is missing or not an array has zero events; a vocab row with a missing or non-integer `vid` is mapped by text only and, if unmapped, listed with `jpdb_vid = 0`; a missing `spelling` / `reading` reads as `\"\"`. An element of `reviews[]` that is not an object with an integer `timestamp` and a string `grade` is *malformed* (counted `reviews_unknown_grade`).  **Vocab rows and groups.** Each `cards_vocabulary_jp_en` row is mapped to a JMdict seq (vid lookup first; spikes/jpdb-map/REPORT.md \"Mapping\") or listed under `unmapped`. Mapped rows are grouped by seq: rows sharing a seq (about 260 vids repeat in 2-3 rows with different spellings; two vids mapping to one seq are also one group) form **one group** = one note and one `meaning` card, whose history is the union of the rows' events in ascending `timestamp` order (ties: array order, row order, element order). Within a group, an event with the same `timestamp` and `grade` as an earlier one is an **exact duplicate**: dropped and counted `reviews_duplicate_dropped` (malformed events are never duplicates). The group's kept, well-formed events decide its fate: - all of them are `known` and there is at least one: **status-only** (ADR-009(3)). No   note or card. If `import_word_status`, one `word.status_set` status `known`, `origin =   jpdb_import` for the seq, unless the word already has a live override (any status,   any origin; then nothing is written, so a re-run never overwrites the user's choice).   Every row of the group counts `vocabulary_status_only`. - otherwise (at least one non-`known` event, or no well-formed events at all):   **carded**. Every row counts `vocabulary_carded`. The group maps to the live note with   that `entry_seq` if one exists (`notes_existing`, once per seq), else `note.created`   (`notes_created`). The job then ensures a live card of kind `meaning` on it   (`card.created`, `cards_created`, only if none exists) - only that kind, never the   Vocab type's other default kinds (`reading`, `production`); existing cards of other   kinds are left alone and get no history. A `known` event of a carded group writes   nothing (FSRS schedules from the reviews; no override). So `notes_created + notes_existing` = distinct seqs of carded groups, and `cards_created <= notes_created + notes_existing` (about equal to `notes_created`).  **Kanji rows and groups.** A row whose `character` is exactly one Unicode code point is mapped; any other is listed under `kanji_unmapped`. Mapped rows are grouped by (`character`, card kind); a group's events are merged and de-duplicated exactly as for vocab. The character maps to the live note with that `kanji_literal` if one exists (counted `kanji_notes_existing`, once per character), else to a new note: `note.created` with `note_type_id` = the built-in Kanji type (`00000000-0000-7000-8000-000000000102`, base type `kanji`), `kanji_literal` = `character`, `fields` = `{\"literal\": character}` plus `\"keyword\": <text>` when a row of that character carries a `keyword` member that is a non-empty string after trimming (ADR-009(4); the first such row in array order `cards_kanji_char_keyword`, `cards_kanji_keyword_char`, then row order; the observed export has no `keyword` member, so it is normally absent and the renderer fills it from `ref.kanji`; user-editable either way). `readings` and `notes` are never written. An existing note's fields are never changed. `origin = jpdb_import`. Both arrays naming the same character share one note. The job then ensures a live card of the group's kind (`keyword` or `writing`) - only that kind, never the note type's other default kinds. A card is created **with `suspended = true` in its `card.created` payload** (no separate `card.suspended` op; counted `kanji_cards_suspended`) when its kind is `writing` (ADR-009(1): preserved with its history, never shown unless the user unsuspends it) or when the group is known-only (all kept well-formed events `known`, at least one; ADR-009(3)). A card that already exists keeps its `suspended` state (a re-run never re-suspends a card the user unsuspended). Every mapped kanji row counts `kanji_carded`, known-only ones included (kanji have no word status). A character missing from `ref.kanji` is still imported.  **Grades** (each kept, well-formed event of a carded vocab group or a kanji group; `timestamp` is Unix seconds UTC → `reviewed_at` = timestamp × 1000, `utc_offset_min = null`, `duration_ms = null`, `review_type = scheduled`, `import_source = jpdb`; grades match exactly, case-sensitive): | `grade` | Effect | |---|---| | `nothing` | `review.logged` rating 1, button `nothing` | | `something` | rating 1, button `something` | | `hard` | rating 2, button `hard` (FSRS Hard; ADR-008) | | `okay` | rating 3, button `okay` | | `easy` | rating 4, button `easy` | | `pass` | rating 3, button `null` | | `fail` | rating 1, button `null` | | `unknown` | no review; the card's creation is all it means. Counted `status_events_unknown` | | `known` | no review. Status-only vocab group: the word status above. Carded vocab group or any kanji group: nothing (a known-only kanji card is created suspended). Counted `status_events_known` | | anything else | nothing; counted `reviews_unknown_grade` |  **Review ids.** Each rating event gets `id` = a UUIDv7 whose 48-bit timestamp is `reviewed_at` and whose other bits come from `h = SHA-256(UTF-8(name))`, `name = \"jpdb-review/v1|\" + key + \"|\" + timestamp + \"|\" + grade + \"|\" + occ`: `key` is `\"v\" + vid` (decimal, the event's own row's vid; `\"v0\"` if absent) for vocab, `\"k:keyword:\" + character` or `\"k:writing:\" + character` for kanji; `timestamp` is the export's decimal seconds; `occ` is the 0-based index of the event among the group's kept events with the same `timestamp`, in group order. Bytes: `b[0..5]` = `reviewed_at` big-endian, `b[6] = 0x70 | (h[0] & 0x0F)`, `b[7] = h[1]`, `b[8] = 0x80 | (h[2] & 0x3F)`, `b[9..15] = h[3..9]`. A rating event whose id already exists in `review_log` (any state, undone included) is not emitted again (`reviews_already_imported`). Rating ops of one card are emitted in group order, after the card's `card.created` (if any).  **`import_reviews = false`**: nothing but word statuses is written. Rows are bucketed by the same rules (`*_carded` then means \"would get a note and card\"); `notes_created`, `notes_existing`, `cards_created`, `kanji_notes_*`, `kanji_cards_*` are 0; rating events count as `reviews_skipped`; status, duplicate and unknown-grade events as usual. **`import_word_status = false`**: no `word.status_set`; bucketing is unchanged.  **Counting.** Every input record - each row of the four arrays and each element of its `reviews[]` - is counted in exactly one bucket of `JobResultJpdbHistory` (its description gives the three sums that must hold and the order in which an event's bucket is decided). 

        :param jpdb_history_import_request: (required)
        :type jpdb_history_import_request: JpdbHistoryImportRequest
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

        _param = self._import_jpdb_history_serialize(
            jpdb_history_import_request=jpdb_history_import_request,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "Job",
            '202': "Job",
            '422': "Problem",
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
    def import_jpdb_history_without_preload_content(
        self,
        jpdb_history_import_request: JpdbHistoryImportRequest,
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
        """Import jpdb review history and word statuses from a jpdb export file.

        Upload the export JSON with `PUT /blobs/{hash}` (kind `upload`), then call this. Enqueues a job of kind `jpdb-history-import` (dedupe key `jpdb-history-import`). The job emits, as server ops: `note.created` and `card.created` for vocabulary and kanji without a note or card, `review.logged` for every historical review (`import_source = jpdb`, deterministic review ids, see \"Review ids\", so that re-running imports nothing twice), and `word.status_set` (payload `origin = jpdb_import`, stored as `override_origin`) for known-only words. The observed export carries no never-forget or blacklist data, so none is written. Records that cannot be mapped are counted and listed in the job result. An equivalent queued or running job is returned with `200`. Mapping rules follow ADR-008 and ADR-009 (C-4).  **Export arrays (C-2, C-4; spikes/jpdb-map/REPORT.md \"Export structure\").** The export is one object whose array members are read as follows; any other member (top level, row or event) is ignored, a missing or non-array member counts as empty, and `from_anki` is accepted with any value (`true` included) and ignored. | Array | Row | Becomes | |---|---|---| | `cards_vocabulary_jp_en` | `vid`, `spelling`, `reading`, `reviews[]` | vocab note (built-in Vocab type `00000000-0000-7000-8000-000000000101`, `entry_seq` = the mapped JMdict seq, `origin = jpdb_import`) with exactly one card, kind `meaning` (JP → EN) | | `cards_vocabulary_en_jp` | same | nothing: counted `vocabulary_en_jp_ignored`, its events `reviews_skipped`; no note, card, status or review (ADR-009(1): no production cards) | | `cards_kanji_char_keyword` | `character`, `reviews[]`, optional `keyword` | kanji note, card kind `keyword` (kanji → keyword) | | `cards_kanji_keyword_char` | `character`, `reviews[]`, optional `keyword` | kanji note, card kind `writing` (keyword → kanji), created **suspended** |  Tolerated drift: a row whose `reviews` is missing or not an array has zero events; a vocab row with a missing or non-integer `vid` is mapped by text only and, if unmapped, listed with `jpdb_vid = 0`; a missing `spelling` / `reading` reads as `\"\"`. An element of `reviews[]` that is not an object with an integer `timestamp` and a string `grade` is *malformed* (counted `reviews_unknown_grade`).  **Vocab rows and groups.** Each `cards_vocabulary_jp_en` row is mapped to a JMdict seq (vid lookup first; spikes/jpdb-map/REPORT.md \"Mapping\") or listed under `unmapped`. Mapped rows are grouped by seq: rows sharing a seq (about 260 vids repeat in 2-3 rows with different spellings; two vids mapping to one seq are also one group) form **one group** = one note and one `meaning` card, whose history is the union of the rows' events in ascending `timestamp` order (ties: array order, row order, element order). Within a group, an event with the same `timestamp` and `grade` as an earlier one is an **exact duplicate**: dropped and counted `reviews_duplicate_dropped` (malformed events are never duplicates). The group's kept, well-formed events decide its fate: - all of them are `known` and there is at least one: **status-only** (ADR-009(3)). No   note or card. If `import_word_status`, one `word.status_set` status `known`, `origin =   jpdb_import` for the seq, unless the word already has a live override (any status,   any origin; then nothing is written, so a re-run never overwrites the user's choice).   Every row of the group counts `vocabulary_status_only`. - otherwise (at least one non-`known` event, or no well-formed events at all):   **carded**. Every row counts `vocabulary_carded`. The group maps to the live note with   that `entry_seq` if one exists (`notes_existing`, once per seq), else `note.created`   (`notes_created`). The job then ensures a live card of kind `meaning` on it   (`card.created`, `cards_created`, only if none exists) - only that kind, never the   Vocab type's other default kinds (`reading`, `production`); existing cards of other   kinds are left alone and get no history. A `known` event of a carded group writes   nothing (FSRS schedules from the reviews; no override). So `notes_created + notes_existing` = distinct seqs of carded groups, and `cards_created <= notes_created + notes_existing` (about equal to `notes_created`).  **Kanji rows and groups.** A row whose `character` is exactly one Unicode code point is mapped; any other is listed under `kanji_unmapped`. Mapped rows are grouped by (`character`, card kind); a group's events are merged and de-duplicated exactly as for vocab. The character maps to the live note with that `kanji_literal` if one exists (counted `kanji_notes_existing`, once per character), else to a new note: `note.created` with `note_type_id` = the built-in Kanji type (`00000000-0000-7000-8000-000000000102`, base type `kanji`), `kanji_literal` = `character`, `fields` = `{\"literal\": character}` plus `\"keyword\": <text>` when a row of that character carries a `keyword` member that is a non-empty string after trimming (ADR-009(4); the first such row in array order `cards_kanji_char_keyword`, `cards_kanji_keyword_char`, then row order; the observed export has no `keyword` member, so it is normally absent and the renderer fills it from `ref.kanji`; user-editable either way). `readings` and `notes` are never written. An existing note's fields are never changed. `origin = jpdb_import`. Both arrays naming the same character share one note. The job then ensures a live card of the group's kind (`keyword` or `writing`) - only that kind, never the note type's other default kinds. A card is created **with `suspended = true` in its `card.created` payload** (no separate `card.suspended` op; counted `kanji_cards_suspended`) when its kind is `writing` (ADR-009(1): preserved with its history, never shown unless the user unsuspends it) or when the group is known-only (all kept well-formed events `known`, at least one; ADR-009(3)). A card that already exists keeps its `suspended` state (a re-run never re-suspends a card the user unsuspended). Every mapped kanji row counts `kanji_carded`, known-only ones included (kanji have no word status). A character missing from `ref.kanji` is still imported.  **Grades** (each kept, well-formed event of a carded vocab group or a kanji group; `timestamp` is Unix seconds UTC → `reviewed_at` = timestamp × 1000, `utc_offset_min = null`, `duration_ms = null`, `review_type = scheduled`, `import_source = jpdb`; grades match exactly, case-sensitive): | `grade` | Effect | |---|---| | `nothing` | `review.logged` rating 1, button `nothing` | | `something` | rating 1, button `something` | | `hard` | rating 2, button `hard` (FSRS Hard; ADR-008) | | `okay` | rating 3, button `okay` | | `easy` | rating 4, button `easy` | | `pass` | rating 3, button `null` | | `fail` | rating 1, button `null` | | `unknown` | no review; the card's creation is all it means. Counted `status_events_unknown` | | `known` | no review. Status-only vocab group: the word status above. Carded vocab group or any kanji group: nothing (a known-only kanji card is created suspended). Counted `status_events_known` | | anything else | nothing; counted `reviews_unknown_grade` |  **Review ids.** Each rating event gets `id` = a UUIDv7 whose 48-bit timestamp is `reviewed_at` and whose other bits come from `h = SHA-256(UTF-8(name))`, `name = \"jpdb-review/v1|\" + key + \"|\" + timestamp + \"|\" + grade + \"|\" + occ`: `key` is `\"v\" + vid` (decimal, the event's own row's vid; `\"v0\"` if absent) for vocab, `\"k:keyword:\" + character` or `\"k:writing:\" + character` for kanji; `timestamp` is the export's decimal seconds; `occ` is the 0-based index of the event among the group's kept events with the same `timestamp`, in group order. Bytes: `b[0..5]` = `reviewed_at` big-endian, `b[6] = 0x70 | (h[0] & 0x0F)`, `b[7] = h[1]`, `b[8] = 0x80 | (h[2] & 0x3F)`, `b[9..15] = h[3..9]`. A rating event whose id already exists in `review_log` (any state, undone included) is not emitted again (`reviews_already_imported`). Rating ops of one card are emitted in group order, after the card's `card.created` (if any).  **`import_reviews = false`**: nothing but word statuses is written. Rows are bucketed by the same rules (`*_carded` then means \"would get a note and card\"); `notes_created`, `notes_existing`, `cards_created`, `kanji_notes_*`, `kanji_cards_*` are 0; rating events count as `reviews_skipped`; status, duplicate and unknown-grade events as usual. **`import_word_status = false`**: no `word.status_set`; bucketing is unchanged.  **Counting.** Every input record - each row of the four arrays and each element of its `reviews[]` - is counted in exactly one bucket of `JobResultJpdbHistory` (its description gives the three sums that must hold and the order in which an event's bucket is decided). 

        :param jpdb_history_import_request: (required)
        :type jpdb_history_import_request: JpdbHistoryImportRequest
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

        _param = self._import_jpdb_history_serialize(
            jpdb_history_import_request=jpdb_history_import_request,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "Job",
            '202': "Job",
            '422': "Problem",
            'default': "Problem",
        }
        response_data = self.api_client.call_api(
            *_param,
            _request_timeout=_request_timeout
        )
        return response_data.response


    def _import_jpdb_history_serialize(
        self,
        jpdb_history_import_request,
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
        if jpdb_history_import_request is not None:
            _body_params = jpdb_history_import_request


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
            resource_path='/import/jpdb/history',
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
    def import_media(
        self,
        media_import_request: MediaImportRequest,
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
    ) -> Job:
        """Import your own file(s), a URL or pasted text as media (worker job).

        Enqueues a job of kind `media-import` and returns it with `202`. Files are uploaded first with `PUT /blobs/{hash}` (kind `upload`) and referenced by hash; there is no multipart variant.  - `source: file` - `files` required (1 to 500). Each file becomes one segment in array   order (an EPUB becomes one segment per chapter, in book order). Every `blob_hash` must   exist (`422` naming the element otherwise). - `source: url` - `url` required (http/https); fetched by the worker with readability   extraction; one segment. - `source: paste` - `text` required (1 to 1,000,000 characters); one segment. - New media: `title` and `kind` required. The server pre-allocates `media_id` (returned   in `job.payload.media_id`); the job creates the `media` row, its deck (`deck.id` =   `media_id`) and all content, emitting as server ops `media.created`, `deck.created`,   `media.content_published`, `deck.content_published`. `GET /media/{media_id}` is `404`   until the job has created the row. - Existing media: pass `media_id` (must be live, with `source` equal to this request's);   the new segments are appended after the existing ones and a new content version is   published. `title`/`kind`/`deck` must then be absent. - Dedupe key `media-import:<media_id>`: a second import into the same media while one is   queued or running is `409 already_exists` (`existing_id` = the job id). - **Idempotent retry: `import_id`.** Optional client-generated UUIDv7, minted once per   user action and reused on every retry; it becomes the job's `id`, and for new media   also the new `media_id` (and so the deck id). If a job with this id already exists   (any status), or - for new media - a `media` row with this id exists (live or deleted),   the server enqueues nothing and answers `200` with that job (or, if the job row is   gone, the most recent `media-import` job for that media; `404` if none), whatever the   rest of the body says. Without `import_id` a retry after a lost `202` imports twice   (a second media and deck); clients that may retry MUST send it. An `import_id` used by   a job of another kind is `409 already_exists` (`existing_id` = that job). 

        :param media_import_request: (required)
        :type media_import_request: MediaImportRequest
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

        _param = self._import_media_serialize(
            media_import_request=media_import_request,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "Job",
            '202': "Job",
            '404': "Problem",
            '409': "Problem",
            '422': "Problem",
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
    def import_media_with_http_info(
        self,
        media_import_request: MediaImportRequest,
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
    ) -> ApiResponse[Job]:
        """Import your own file(s), a URL or pasted text as media (worker job).

        Enqueues a job of kind `media-import` and returns it with `202`. Files are uploaded first with `PUT /blobs/{hash}` (kind `upload`) and referenced by hash; there is no multipart variant.  - `source: file` - `files` required (1 to 500). Each file becomes one segment in array   order (an EPUB becomes one segment per chapter, in book order). Every `blob_hash` must   exist (`422` naming the element otherwise). - `source: url` - `url` required (http/https); fetched by the worker with readability   extraction; one segment. - `source: paste` - `text` required (1 to 1,000,000 characters); one segment. - New media: `title` and `kind` required. The server pre-allocates `media_id` (returned   in `job.payload.media_id`); the job creates the `media` row, its deck (`deck.id` =   `media_id`) and all content, emitting as server ops `media.created`, `deck.created`,   `media.content_published`, `deck.content_published`. `GET /media/{media_id}` is `404`   until the job has created the row. - Existing media: pass `media_id` (must be live, with `source` equal to this request's);   the new segments are appended after the existing ones and a new content version is   published. `title`/`kind`/`deck` must then be absent. - Dedupe key `media-import:<media_id>`: a second import into the same media while one is   queued or running is `409 already_exists` (`existing_id` = the job id). - **Idempotent retry: `import_id`.** Optional client-generated UUIDv7, minted once per   user action and reused on every retry; it becomes the job's `id`, and for new media   also the new `media_id` (and so the deck id). If a job with this id already exists   (any status), or - for new media - a `media` row with this id exists (live or deleted),   the server enqueues nothing and answers `200` with that job (or, if the job row is   gone, the most recent `media-import` job for that media; `404` if none), whatever the   rest of the body says. Without `import_id` a retry after a lost `202` imports twice   (a second media and deck); clients that may retry MUST send it. An `import_id` used by   a job of another kind is `409 already_exists` (`existing_id` = that job). 

        :param media_import_request: (required)
        :type media_import_request: MediaImportRequest
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

        _param = self._import_media_serialize(
            media_import_request=media_import_request,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "Job",
            '202': "Job",
            '404': "Problem",
            '409': "Problem",
            '422': "Problem",
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
    def import_media_without_preload_content(
        self,
        media_import_request: MediaImportRequest,
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
        """Import your own file(s), a URL or pasted text as media (worker job).

        Enqueues a job of kind `media-import` and returns it with `202`. Files are uploaded first with `PUT /blobs/{hash}` (kind `upload`) and referenced by hash; there is no multipart variant.  - `source: file` - `files` required (1 to 500). Each file becomes one segment in array   order (an EPUB becomes one segment per chapter, in book order). Every `blob_hash` must   exist (`422` naming the element otherwise). - `source: url` - `url` required (http/https); fetched by the worker with readability   extraction; one segment. - `source: paste` - `text` required (1 to 1,000,000 characters); one segment. - New media: `title` and `kind` required. The server pre-allocates `media_id` (returned   in `job.payload.media_id`); the job creates the `media` row, its deck (`deck.id` =   `media_id`) and all content, emitting as server ops `media.created`, `deck.created`,   `media.content_published`, `deck.content_published`. `GET /media/{media_id}` is `404`   until the job has created the row. - Existing media: pass `media_id` (must be live, with `source` equal to this request's);   the new segments are appended after the existing ones and a new content version is   published. `title`/`kind`/`deck` must then be absent. - Dedupe key `media-import:<media_id>`: a second import into the same media while one is   queued or running is `409 already_exists` (`existing_id` = the job id). - **Idempotent retry: `import_id`.** Optional client-generated UUIDv7, minted once per   user action and reused on every retry; it becomes the job's `id`, and for new media   also the new `media_id` (and so the deck id). If a job with this id already exists   (any status), or - for new media - a `media` row with this id exists (live or deleted),   the server enqueues nothing and answers `200` with that job (or, if the job row is   gone, the most recent `media-import` job for that media; `404` if none), whatever the   rest of the body says. Without `import_id` a retry after a lost `202` imports twice   (a second media and deck); clients that may retry MUST send it. An `import_id` used by   a job of another kind is `409 already_exists` (`existing_id` = that job). 

        :param media_import_request: (required)
        :type media_import_request: MediaImportRequest
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

        _param = self._import_media_serialize(
            media_import_request=media_import_request,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "Job",
            '202': "Job",
            '404': "Problem",
            '409': "Problem",
            '422': "Problem",
            'default': "Problem",
        }
        response_data = self.api_client.call_api(
            *_param,
            _request_timeout=_request_timeout
        )
        return response_data.response


    def _import_media_serialize(
        self,
        media_import_request,
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
        if media_import_request is not None:
            _body_params = media_import_request


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
            resource_path='/media/import',
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
    def list_media(
        self,
        kind: Annotated[Optional[MediaKind], Field(description="Only media of this kind.")] = None,
        q: Annotated[Optional[Annotated[str, Field(min_length=1, strict=True, max_length=200)]], Field(description="Case-insensitive substring of the title.")] = None,
        include_deleted: Annotated[Optional[StrictBool], Field(description="Also return tombstoned rows (`deleted_at` set).")] = None,
        limit: Annotated[Optional[Annotated[int, Field(le=500, strict=True, ge=1)]], Field(description="Page size.")] = None,
        cursor: Annotated[Optional[Annotated[str, Field(min_length=1, strict=True, max_length=1024)]], Field(description="Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`.")] = None,
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
    ) -> MediaPage:
        """List the media library.

        Ordered by `created_at` descending, then `id` descending.

        :param kind: Only media of this kind.
        :type kind: MediaKind
        :param q: Case-insensitive substring of the title.
        :type q: str
        :param include_deleted: Also return tombstoned rows (`deleted_at` set).
        :type include_deleted: bool
        :param limit: Page size.
        :type limit: int
        :param cursor: Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`.
        :type cursor: str
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

        _param = self._list_media_serialize(
            kind=kind,
            q=q,
            include_deleted=include_deleted,
            limit=limit,
            cursor=cursor,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "MediaPage",
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
    def list_media_with_http_info(
        self,
        kind: Annotated[Optional[MediaKind], Field(description="Only media of this kind.")] = None,
        q: Annotated[Optional[Annotated[str, Field(min_length=1, strict=True, max_length=200)]], Field(description="Case-insensitive substring of the title.")] = None,
        include_deleted: Annotated[Optional[StrictBool], Field(description="Also return tombstoned rows (`deleted_at` set).")] = None,
        limit: Annotated[Optional[Annotated[int, Field(le=500, strict=True, ge=1)]], Field(description="Page size.")] = None,
        cursor: Annotated[Optional[Annotated[str, Field(min_length=1, strict=True, max_length=1024)]], Field(description="Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`.")] = None,
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
    ) -> ApiResponse[MediaPage]:
        """List the media library.

        Ordered by `created_at` descending, then `id` descending.

        :param kind: Only media of this kind.
        :type kind: MediaKind
        :param q: Case-insensitive substring of the title.
        :type q: str
        :param include_deleted: Also return tombstoned rows (`deleted_at` set).
        :type include_deleted: bool
        :param limit: Page size.
        :type limit: int
        :param cursor: Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`.
        :type cursor: str
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

        _param = self._list_media_serialize(
            kind=kind,
            q=q,
            include_deleted=include_deleted,
            limit=limit,
            cursor=cursor,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "MediaPage",
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
    def list_media_without_preload_content(
        self,
        kind: Annotated[Optional[MediaKind], Field(description="Only media of this kind.")] = None,
        q: Annotated[Optional[Annotated[str, Field(min_length=1, strict=True, max_length=200)]], Field(description="Case-insensitive substring of the title.")] = None,
        include_deleted: Annotated[Optional[StrictBool], Field(description="Also return tombstoned rows (`deleted_at` set).")] = None,
        limit: Annotated[Optional[Annotated[int, Field(le=500, strict=True, ge=1)]], Field(description="Page size.")] = None,
        cursor: Annotated[Optional[Annotated[str, Field(min_length=1, strict=True, max_length=1024)]], Field(description="Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`.")] = None,
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
        """List the media library.

        Ordered by `created_at` descending, then `id` descending.

        :param kind: Only media of this kind.
        :type kind: MediaKind
        :param q: Case-insensitive substring of the title.
        :type q: str
        :param include_deleted: Also return tombstoned rows (`deleted_at` set).
        :type include_deleted: bool
        :param limit: Page size.
        :type limit: int
        :param cursor: Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`.
        :type cursor: str
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

        _param = self._list_media_serialize(
            kind=kind,
            q=q,
            include_deleted=include_deleted,
            limit=limit,
            cursor=cursor,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "MediaPage",
            'default': "Problem",
        }
        response_data = self.api_client.call_api(
            *_param,
            _request_timeout=_request_timeout
        )
        return response_data.response


    def _list_media_serialize(
        self,
        kind,
        q,
        include_deleted,
        limit,
        cursor,
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
        if kind is not None:
            
            _query_params.append(('kind', kind.value))
            
        if q is not None:
            
            _query_params.append(('q', q))
            
        if include_deleted is not None:
            
            _query_params.append(('include_deleted', include_deleted))
            
        if limit is not None:
            
            _query_params.append(('limit', limit))
            
        if cursor is not None:
            
            _query_params.append(('cursor', cursor))
            
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
            resource_path='/media',
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
    def list_media_sentences(
        self,
        media_id: Annotated[str, Field(strict=True, description="Media id.")],
        filter: Annotated[Optional[StrictStr], Field(description="`all` (default); `i+1` = sentences with exactly one unknown word; `i+0` = none. \"Unknown word\" = a distinct entry among the sentence's tokens whose effective word status is `new`; tokens of class `particle`, `auxiliary`, `name`, unmapped tokens and entries with status `blacklisted`/`ignored` are not counted. Because an unencoded `+` in a query string decodes to a space, servers MUST also accept `i 1` and `i 0`. ")] = None,
        segment_id: Annotated[Optional[Annotated[str, Field(strict=True)]], Field(description="Only sentences of this segment.")] = None,
        q: Annotated[Optional[Annotated[str, Field(min_length=1, strict=True, max_length=200)]], Field(description="Substring of the sentence text (exact characters, no normalisation).")] = None,
        entry_seq: Annotated[Optional[Annotated[int, Field(strict=True, ge=1)]], Field(description="Only sentences with a token mapped to this entry.")] = None,
        grammar_point_id: Annotated[Optional[Annotated[str, Field(strict=True)]], Field(description="Only sentences with a live span of this grammar point.")] = None,
        limit: Annotated[Optional[Annotated[int, Field(le=500, strict=True, ge=1)]], Field(description="Page size.")] = None,
        cursor: Annotated[Optional[Annotated[str, Field(min_length=1, strict=True, max_length=1024)]], Field(description="Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`.")] = None,
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
    ) -> SentencePage:
        """Sentences of a media, optionally only i+1 sentences.

        Same as `GET /sentences` with `media_id` fixed. Ordered by segment `ordinal`, then sentence `ordinal`. jpdb-imported media have no sentences (empty page). 

        :param media_id: Media id. (required)
        :type media_id: str
        :param filter: `all` (default); `i+1` = sentences with exactly one unknown word; `i+0` = none. \"Unknown word\" = a distinct entry among the sentence's tokens whose effective word status is `new`; tokens of class `particle`, `auxiliary`, `name`, unmapped tokens and entries with status `blacklisted`/`ignored` are not counted. Because an unencoded `+` in a query string decodes to a space, servers MUST also accept `i 1` and `i 0`. 
        :type filter: str
        :param segment_id: Only sentences of this segment.
        :type segment_id: str
        :param q: Substring of the sentence text (exact characters, no normalisation).
        :type q: str
        :param entry_seq: Only sentences with a token mapped to this entry.
        :type entry_seq: int
        :param grammar_point_id: Only sentences with a live span of this grammar point.
        :type grammar_point_id: str
        :param limit: Page size.
        :type limit: int
        :param cursor: Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`.
        :type cursor: str
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

        _param = self._list_media_sentences_serialize(
            media_id=media_id,
            filter=filter,
            segment_id=segment_id,
            q=q,
            entry_seq=entry_seq,
            grammar_point_id=grammar_point_id,
            limit=limit,
            cursor=cursor,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "SentencePage",
            '404': "Problem",
            '410': "Problem",
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
    def list_media_sentences_with_http_info(
        self,
        media_id: Annotated[str, Field(strict=True, description="Media id.")],
        filter: Annotated[Optional[StrictStr], Field(description="`all` (default); `i+1` = sentences with exactly one unknown word; `i+0` = none. \"Unknown word\" = a distinct entry among the sentence's tokens whose effective word status is `new`; tokens of class `particle`, `auxiliary`, `name`, unmapped tokens and entries with status `blacklisted`/`ignored` are not counted. Because an unencoded `+` in a query string decodes to a space, servers MUST also accept `i 1` and `i 0`. ")] = None,
        segment_id: Annotated[Optional[Annotated[str, Field(strict=True)]], Field(description="Only sentences of this segment.")] = None,
        q: Annotated[Optional[Annotated[str, Field(min_length=1, strict=True, max_length=200)]], Field(description="Substring of the sentence text (exact characters, no normalisation).")] = None,
        entry_seq: Annotated[Optional[Annotated[int, Field(strict=True, ge=1)]], Field(description="Only sentences with a token mapped to this entry.")] = None,
        grammar_point_id: Annotated[Optional[Annotated[str, Field(strict=True)]], Field(description="Only sentences with a live span of this grammar point.")] = None,
        limit: Annotated[Optional[Annotated[int, Field(le=500, strict=True, ge=1)]], Field(description="Page size.")] = None,
        cursor: Annotated[Optional[Annotated[str, Field(min_length=1, strict=True, max_length=1024)]], Field(description="Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`.")] = None,
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
    ) -> ApiResponse[SentencePage]:
        """Sentences of a media, optionally only i+1 sentences.

        Same as `GET /sentences` with `media_id` fixed. Ordered by segment `ordinal`, then sentence `ordinal`. jpdb-imported media have no sentences (empty page). 

        :param media_id: Media id. (required)
        :type media_id: str
        :param filter: `all` (default); `i+1` = sentences with exactly one unknown word; `i+0` = none. \"Unknown word\" = a distinct entry among the sentence's tokens whose effective word status is `new`; tokens of class `particle`, `auxiliary`, `name`, unmapped tokens and entries with status `blacklisted`/`ignored` are not counted. Because an unencoded `+` in a query string decodes to a space, servers MUST also accept `i 1` and `i 0`. 
        :type filter: str
        :param segment_id: Only sentences of this segment.
        :type segment_id: str
        :param q: Substring of the sentence text (exact characters, no normalisation).
        :type q: str
        :param entry_seq: Only sentences with a token mapped to this entry.
        :type entry_seq: int
        :param grammar_point_id: Only sentences with a live span of this grammar point.
        :type grammar_point_id: str
        :param limit: Page size.
        :type limit: int
        :param cursor: Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`.
        :type cursor: str
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

        _param = self._list_media_sentences_serialize(
            media_id=media_id,
            filter=filter,
            segment_id=segment_id,
            q=q,
            entry_seq=entry_seq,
            grammar_point_id=grammar_point_id,
            limit=limit,
            cursor=cursor,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "SentencePage",
            '404': "Problem",
            '410': "Problem",
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
    def list_media_sentences_without_preload_content(
        self,
        media_id: Annotated[str, Field(strict=True, description="Media id.")],
        filter: Annotated[Optional[StrictStr], Field(description="`all` (default); `i+1` = sentences with exactly one unknown word; `i+0` = none. \"Unknown word\" = a distinct entry among the sentence's tokens whose effective word status is `new`; tokens of class `particle`, `auxiliary`, `name`, unmapped tokens and entries with status `blacklisted`/`ignored` are not counted. Because an unencoded `+` in a query string decodes to a space, servers MUST also accept `i 1` and `i 0`. ")] = None,
        segment_id: Annotated[Optional[Annotated[str, Field(strict=True)]], Field(description="Only sentences of this segment.")] = None,
        q: Annotated[Optional[Annotated[str, Field(min_length=1, strict=True, max_length=200)]], Field(description="Substring of the sentence text (exact characters, no normalisation).")] = None,
        entry_seq: Annotated[Optional[Annotated[int, Field(strict=True, ge=1)]], Field(description="Only sentences with a token mapped to this entry.")] = None,
        grammar_point_id: Annotated[Optional[Annotated[str, Field(strict=True)]], Field(description="Only sentences with a live span of this grammar point.")] = None,
        limit: Annotated[Optional[Annotated[int, Field(le=500, strict=True, ge=1)]], Field(description="Page size.")] = None,
        cursor: Annotated[Optional[Annotated[str, Field(min_length=1, strict=True, max_length=1024)]], Field(description="Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`.")] = None,
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
        """Sentences of a media, optionally only i+1 sentences.

        Same as `GET /sentences` with `media_id` fixed. Ordered by segment `ordinal`, then sentence `ordinal`. jpdb-imported media have no sentences (empty page). 

        :param media_id: Media id. (required)
        :type media_id: str
        :param filter: `all` (default); `i+1` = sentences with exactly one unknown word; `i+0` = none. \"Unknown word\" = a distinct entry among the sentence's tokens whose effective word status is `new`; tokens of class `particle`, `auxiliary`, `name`, unmapped tokens and entries with status `blacklisted`/`ignored` are not counted. Because an unencoded `+` in a query string decodes to a space, servers MUST also accept `i 1` and `i 0`. 
        :type filter: str
        :param segment_id: Only sentences of this segment.
        :type segment_id: str
        :param q: Substring of the sentence text (exact characters, no normalisation).
        :type q: str
        :param entry_seq: Only sentences with a token mapped to this entry.
        :type entry_seq: int
        :param grammar_point_id: Only sentences with a live span of this grammar point.
        :type grammar_point_id: str
        :param limit: Page size.
        :type limit: int
        :param cursor: Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`.
        :type cursor: str
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

        _param = self._list_media_sentences_serialize(
            media_id=media_id,
            filter=filter,
            segment_id=segment_id,
            q=q,
            entry_seq=entry_seq,
            grammar_point_id=grammar_point_id,
            limit=limit,
            cursor=cursor,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "SentencePage",
            '404': "Problem",
            '410': "Problem",
            'default': "Problem",
        }
        response_data = self.api_client.call_api(
            *_param,
            _request_timeout=_request_timeout
        )
        return response_data.response


    def _list_media_sentences_serialize(
        self,
        media_id,
        filter,
        segment_id,
        q,
        entry_seq,
        grammar_point_id,
        limit,
        cursor,
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
        if media_id is not None:
            _path_params['media_id'] = media_id
        # process the query parameters
        if filter is not None:
            
            _query_params.append(('filter', filter))
            
        if segment_id is not None:
            
            _query_params.append(('segment_id', segment_id))
            
        if q is not None:
            
            _query_params.append(('q', q))
            
        if entry_seq is not None:
            
            _query_params.append(('entry_seq', entry_seq))
            
        if grammar_point_id is not None:
            
            _query_params.append(('grammar_point_id', grammar_point_id))
            
        if limit is not None:
            
            _query_params.append(('limit', limit))
            
        if cursor is not None:
            
            _query_params.append(('cursor', cursor))
            
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
            resource_path='/media/{media_id}/sentences',
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
    def list_sentences(
        self,
        media_id: Annotated[Optional[Annotated[str, Field(strict=True)]], Field(description="Only sentences of this media.")] = None,
        filter: Annotated[Optional[StrictStr], Field(description="`all` (default); `i+1` = sentences with exactly one unknown word; `i+0` = none. \"Unknown word\" = a distinct entry among the sentence's tokens whose effective word status is `new`; tokens of class `particle`, `auxiliary`, `name`, unmapped tokens and entries with status `blacklisted`/`ignored` are not counted. Because an unencoded `+` in a query string decodes to a space, servers MUST also accept `i 1` and `i 0`. ")] = None,
        segment_id: Annotated[Optional[Annotated[str, Field(strict=True)]], Field(description="Only sentences of this segment.")] = None,
        q: Annotated[Optional[Annotated[str, Field(min_length=1, strict=True, max_length=200)]], Field(description="Substring of the sentence text (exact characters, no normalisation).")] = None,
        entry_seq: Annotated[Optional[Annotated[int, Field(strict=True, ge=1)]], Field(description="Only sentences with a token mapped to this entry.")] = None,
        grammar_point_id: Annotated[Optional[Annotated[str, Field(strict=True)]], Field(description="Only sentences with a live span of this grammar point.")] = None,
        limit: Annotated[Optional[Annotated[int, Field(le=500, strict=True, ge=1)]], Field(description="Page size.")] = None,
        cursor: Annotated[Optional[Annotated[str, Field(min_length=1, strict=True, max_length=1024)]], Field(description="Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`.")] = None,
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
    ) -> SentencePage:
        """Search sentences across all media.

        All filters combine with AND. Ordered by media `created_at` descending, media id, segment `ordinal`, sentence `ordinal`. Sentences of deleted media do not exist any more and are never returned. LLM-generated sentences (`origin = generated`) are included and must be labelled by clients. Each sentence carries its live grammar spans (detected and manual). 

        :param media_id: Only sentences of this media.
        :type media_id: str
        :param filter: `all` (default); `i+1` = sentences with exactly one unknown word; `i+0` = none. \"Unknown word\" = a distinct entry among the sentence's tokens whose effective word status is `new`; tokens of class `particle`, `auxiliary`, `name`, unmapped tokens and entries with status `blacklisted`/`ignored` are not counted. Because an unencoded `+` in a query string decodes to a space, servers MUST also accept `i 1` and `i 0`. 
        :type filter: str
        :param segment_id: Only sentences of this segment.
        :type segment_id: str
        :param q: Substring of the sentence text (exact characters, no normalisation).
        :type q: str
        :param entry_seq: Only sentences with a token mapped to this entry.
        :type entry_seq: int
        :param grammar_point_id: Only sentences with a live span of this grammar point.
        :type grammar_point_id: str
        :param limit: Page size.
        :type limit: int
        :param cursor: Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`.
        :type cursor: str
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

        _param = self._list_sentences_serialize(
            media_id=media_id,
            filter=filter,
            segment_id=segment_id,
            q=q,
            entry_seq=entry_seq,
            grammar_point_id=grammar_point_id,
            limit=limit,
            cursor=cursor,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "SentencePage",
            '422': "Problem",
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
    def list_sentences_with_http_info(
        self,
        media_id: Annotated[Optional[Annotated[str, Field(strict=True)]], Field(description="Only sentences of this media.")] = None,
        filter: Annotated[Optional[StrictStr], Field(description="`all` (default); `i+1` = sentences with exactly one unknown word; `i+0` = none. \"Unknown word\" = a distinct entry among the sentence's tokens whose effective word status is `new`; tokens of class `particle`, `auxiliary`, `name`, unmapped tokens and entries with status `blacklisted`/`ignored` are not counted. Because an unencoded `+` in a query string decodes to a space, servers MUST also accept `i 1` and `i 0`. ")] = None,
        segment_id: Annotated[Optional[Annotated[str, Field(strict=True)]], Field(description="Only sentences of this segment.")] = None,
        q: Annotated[Optional[Annotated[str, Field(min_length=1, strict=True, max_length=200)]], Field(description="Substring of the sentence text (exact characters, no normalisation).")] = None,
        entry_seq: Annotated[Optional[Annotated[int, Field(strict=True, ge=1)]], Field(description="Only sentences with a token mapped to this entry.")] = None,
        grammar_point_id: Annotated[Optional[Annotated[str, Field(strict=True)]], Field(description="Only sentences with a live span of this grammar point.")] = None,
        limit: Annotated[Optional[Annotated[int, Field(le=500, strict=True, ge=1)]], Field(description="Page size.")] = None,
        cursor: Annotated[Optional[Annotated[str, Field(min_length=1, strict=True, max_length=1024)]], Field(description="Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`.")] = None,
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
    ) -> ApiResponse[SentencePage]:
        """Search sentences across all media.

        All filters combine with AND. Ordered by media `created_at` descending, media id, segment `ordinal`, sentence `ordinal`. Sentences of deleted media do not exist any more and are never returned. LLM-generated sentences (`origin = generated`) are included and must be labelled by clients. Each sentence carries its live grammar spans (detected and manual). 

        :param media_id: Only sentences of this media.
        :type media_id: str
        :param filter: `all` (default); `i+1` = sentences with exactly one unknown word; `i+0` = none. \"Unknown word\" = a distinct entry among the sentence's tokens whose effective word status is `new`; tokens of class `particle`, `auxiliary`, `name`, unmapped tokens and entries with status `blacklisted`/`ignored` are not counted. Because an unencoded `+` in a query string decodes to a space, servers MUST also accept `i 1` and `i 0`. 
        :type filter: str
        :param segment_id: Only sentences of this segment.
        :type segment_id: str
        :param q: Substring of the sentence text (exact characters, no normalisation).
        :type q: str
        :param entry_seq: Only sentences with a token mapped to this entry.
        :type entry_seq: int
        :param grammar_point_id: Only sentences with a live span of this grammar point.
        :type grammar_point_id: str
        :param limit: Page size.
        :type limit: int
        :param cursor: Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`.
        :type cursor: str
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

        _param = self._list_sentences_serialize(
            media_id=media_id,
            filter=filter,
            segment_id=segment_id,
            q=q,
            entry_seq=entry_seq,
            grammar_point_id=grammar_point_id,
            limit=limit,
            cursor=cursor,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "SentencePage",
            '422': "Problem",
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
    def list_sentences_without_preload_content(
        self,
        media_id: Annotated[Optional[Annotated[str, Field(strict=True)]], Field(description="Only sentences of this media.")] = None,
        filter: Annotated[Optional[StrictStr], Field(description="`all` (default); `i+1` = sentences with exactly one unknown word; `i+0` = none. \"Unknown word\" = a distinct entry among the sentence's tokens whose effective word status is `new`; tokens of class `particle`, `auxiliary`, `name`, unmapped tokens and entries with status `blacklisted`/`ignored` are not counted. Because an unencoded `+` in a query string decodes to a space, servers MUST also accept `i 1` and `i 0`. ")] = None,
        segment_id: Annotated[Optional[Annotated[str, Field(strict=True)]], Field(description="Only sentences of this segment.")] = None,
        q: Annotated[Optional[Annotated[str, Field(min_length=1, strict=True, max_length=200)]], Field(description="Substring of the sentence text (exact characters, no normalisation).")] = None,
        entry_seq: Annotated[Optional[Annotated[int, Field(strict=True, ge=1)]], Field(description="Only sentences with a token mapped to this entry.")] = None,
        grammar_point_id: Annotated[Optional[Annotated[str, Field(strict=True)]], Field(description="Only sentences with a live span of this grammar point.")] = None,
        limit: Annotated[Optional[Annotated[int, Field(le=500, strict=True, ge=1)]], Field(description="Page size.")] = None,
        cursor: Annotated[Optional[Annotated[str, Field(min_length=1, strict=True, max_length=1024)]], Field(description="Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`.")] = None,
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
        """Search sentences across all media.

        All filters combine with AND. Ordered by media `created_at` descending, media id, segment `ordinal`, sentence `ordinal`. Sentences of deleted media do not exist any more and are never returned. LLM-generated sentences (`origin = generated`) are included and must be labelled by clients. Each sentence carries its live grammar spans (detected and manual). 

        :param media_id: Only sentences of this media.
        :type media_id: str
        :param filter: `all` (default); `i+1` = sentences with exactly one unknown word; `i+0` = none. \"Unknown word\" = a distinct entry among the sentence's tokens whose effective word status is `new`; tokens of class `particle`, `auxiliary`, `name`, unmapped tokens and entries with status `blacklisted`/`ignored` are not counted. Because an unencoded `+` in a query string decodes to a space, servers MUST also accept `i 1` and `i 0`. 
        :type filter: str
        :param segment_id: Only sentences of this segment.
        :type segment_id: str
        :param q: Substring of the sentence text (exact characters, no normalisation).
        :type q: str
        :param entry_seq: Only sentences with a token mapped to this entry.
        :type entry_seq: int
        :param grammar_point_id: Only sentences with a live span of this grammar point.
        :type grammar_point_id: str
        :param limit: Page size.
        :type limit: int
        :param cursor: Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`.
        :type cursor: str
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

        _param = self._list_sentences_serialize(
            media_id=media_id,
            filter=filter,
            segment_id=segment_id,
            q=q,
            entry_seq=entry_seq,
            grammar_point_id=grammar_point_id,
            limit=limit,
            cursor=cursor,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "SentencePage",
            '422': "Problem",
            'default': "Problem",
        }
        response_data = self.api_client.call_api(
            *_param,
            _request_timeout=_request_timeout
        )
        return response_data.response


    def _list_sentences_serialize(
        self,
        media_id,
        filter,
        segment_id,
        q,
        entry_seq,
        grammar_point_id,
        limit,
        cursor,
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
        if media_id is not None:
            
            _query_params.append(('media_id', media_id))
            
        if filter is not None:
            
            _query_params.append(('filter', filter))
            
        if segment_id is not None:
            
            _query_params.append(('segment_id', segment_id))
            
        if q is not None:
            
            _query_params.append(('q', q))
            
        if entry_seq is not None:
            
            _query_params.append(('entry_seq', entry_seq))
            
        if grammar_point_id is not None:
            
            _query_params.append(('grammar_point_id', grammar_point_id))
            
        if limit is not None:
            
            _query_params.append(('limit', limit))
            
        if cursor is not None:
            
            _query_params.append(('cursor', cursor))
            
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
            resource_path='/sentences',
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
    def reparse_media(
        self,
        media_id: Annotated[str, Field(strict=True, description="Media id.")],
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
    ) -> Job:
        """Re-parse a media (after parse overrides or a dictionary refresh).

        Enqueues a job of kind `media-parse` (dedupe key `media-parse:<media_id>`); an equivalent queued or running job is returned with `200`. The job keeps segment and sentence ids as SCHEMA.md requires and makes the server emit `media.content_published` and `deck.content_published`. Media with `source = jpdb` has nothing to parse: `409 not_applicable`. 

        :param media_id: Media id. (required)
        :type media_id: str
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

        _param = self._reparse_media_serialize(
            media_id=media_id,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "Job",
            '202': "Job",
            '404': "Problem",
            '409': "Problem",
            '410': "Problem",
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
    def reparse_media_with_http_info(
        self,
        media_id: Annotated[str, Field(strict=True, description="Media id.")],
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
    ) -> ApiResponse[Job]:
        """Re-parse a media (after parse overrides or a dictionary refresh).

        Enqueues a job of kind `media-parse` (dedupe key `media-parse:<media_id>`); an equivalent queued or running job is returned with `200`. The job keeps segment and sentence ids as SCHEMA.md requires and makes the server emit `media.content_published` and `deck.content_published`. Media with `source = jpdb` has nothing to parse: `409 not_applicable`. 

        :param media_id: Media id. (required)
        :type media_id: str
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

        _param = self._reparse_media_serialize(
            media_id=media_id,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "Job",
            '202': "Job",
            '404': "Problem",
            '409': "Problem",
            '410': "Problem",
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
    def reparse_media_without_preload_content(
        self,
        media_id: Annotated[str, Field(strict=True, description="Media id.")],
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
        """Re-parse a media (after parse overrides or a dictionary refresh).

        Enqueues a job of kind `media-parse` (dedupe key `media-parse:<media_id>`); an equivalent queued or running job is returned with `200`. The job keeps segment and sentence ids as SCHEMA.md requires and makes the server emit `media.content_published` and `deck.content_published`. Media with `source = jpdb` has nothing to parse: `409 not_applicable`. 

        :param media_id: Media id. (required)
        :type media_id: str
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

        _param = self._reparse_media_serialize(
            media_id=media_id,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "Job",
            '202': "Job",
            '404': "Problem",
            '409': "Problem",
            '410': "Problem",
            'default': "Problem",
        }
        response_data = self.api_client.call_api(
            *_param,
            _request_timeout=_request_timeout
        )
        return response_data.response


    def _reparse_media_serialize(
        self,
        media_id,
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
        if media_id is not None:
            _path_params['media_id'] = media_id
        # process the query parameters
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
            method='POST',
            resource_path='/media/{media_id}/reparse',
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
    def update_media(
        self,
        media_id: Annotated[str, Field(strict=True, description="Media id.")],
        media_patch: MediaPatch,
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
    ) -> Media:
        """Change a media's title, kind or cover.

        Server emits one `media.updated` with the registers present (`title`, `kind`, `cover_blob_hash`). `cover_blob_hash` must be an existing blob of kind `image`, or `null` to remove the cover. The media deck's name is not changed (patch the deck). Empty body: `422`. 

        :param media_id: Media id. (required)
        :type media_id: str
        :param media_patch: (required)
        :type media_patch: MediaPatch
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

        _param = self._update_media_serialize(
            media_id=media_id,
            media_patch=media_patch,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "Media",
            '404': "Problem",
            '410': "Problem",
            '422': "Problem",
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
    def update_media_with_http_info(
        self,
        media_id: Annotated[str, Field(strict=True, description="Media id.")],
        media_patch: MediaPatch,
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
    ) -> ApiResponse[Media]:
        """Change a media's title, kind or cover.

        Server emits one `media.updated` with the registers present (`title`, `kind`, `cover_blob_hash`). `cover_blob_hash` must be an existing blob of kind `image`, or `null` to remove the cover. The media deck's name is not changed (patch the deck). Empty body: `422`. 

        :param media_id: Media id. (required)
        :type media_id: str
        :param media_patch: (required)
        :type media_patch: MediaPatch
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

        _param = self._update_media_serialize(
            media_id=media_id,
            media_patch=media_patch,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "Media",
            '404': "Problem",
            '410': "Problem",
            '422': "Problem",
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
    def update_media_without_preload_content(
        self,
        media_id: Annotated[str, Field(strict=True, description="Media id.")],
        media_patch: MediaPatch,
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
        """Change a media's title, kind or cover.

        Server emits one `media.updated` with the registers present (`title`, `kind`, `cover_blob_hash`). `cover_blob_hash` must be an existing blob of kind `image`, or `null` to remove the cover. The media deck's name is not changed (patch the deck). Empty body: `422`. 

        :param media_id: Media id. (required)
        :type media_id: str
        :param media_patch: (required)
        :type media_patch: MediaPatch
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

        _param = self._update_media_serialize(
            media_id=media_id,
            media_patch=media_patch,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "Media",
            '404': "Problem",
            '410': "Problem",
            '422': "Problem",
            'default': "Problem",
        }
        response_data = self.api_client.call_api(
            *_param,
            _request_timeout=_request_timeout
        )
        return response_data.response


    def _update_media_serialize(
        self,
        media_id,
        media_patch,
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
        if media_id is not None:
            _path_params['media_id'] = media_id
        # process the query parameters
        # process the header parameters
        # process the form parameters
        # process the body parameter
        if media_patch is not None:
            _body_params = media_patch


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
            method='PATCH',
            resource_path='/media/{media_id}',
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


