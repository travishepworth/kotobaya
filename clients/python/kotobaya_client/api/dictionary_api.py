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

from pydantic import Field, StrictBool, field_validator
from typing import List, Optional
from typing_extensions import Annotated
from kotobaya_client.models.dict_entry import DictEntry
from kotobaya_client.models.dict_entry_summary_page import DictEntrySummaryPage
from kotobaya_client.models.dict_headword_list import DictHeadwordList
from kotobaya_client.models.dict_release import DictRelease
from kotobaya_client.models.entry_examples import EntryExamples
from kotobaya_client.models.job import Job
from kotobaya_client.models.job_payload_dict_refresh import JobPayloadDictRefresh
from kotobaya_client.models.kanji import Kanji
from kotobaya_client.models.kanji_word_page import KanjiWordPage

from kotobaya_client.api_client import ApiClient, RequestSerialized
from kotobaya_client.api_response import ApiResponse
from kotobaya_client.rest import RESTResponseType


class DictionaryApi:
    """NOTE: This class is auto generated by OpenAPI Generator
    Ref: https://openapi-generator.tech

    Do not edit the class manually.
    """

    def __init__(self, api_client=None) -> None:
        if api_client is None:
            api_client = ApiClient.get_default()
        self.api_client = api_client


    @validate_call
    def get_dict_entry(
        self,
        seq: Annotated[int, Field(strict=True, ge=1, description="JMdict sequence number of the entry.")],
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
    ) -> DictEntry:
        """One JMdict entry with frequency, pitch, kanji and your status.

        Retired entries are still returned (`retired` is true). Unknown `seq` is `404`.

        :param seq: JMdict sequence number of the entry. (required)
        :type seq: int
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

        _param = self._get_dict_entry_serialize(
            seq=seq,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "DictEntry",
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
    def get_dict_entry_with_http_info(
        self,
        seq: Annotated[int, Field(strict=True, ge=1, description="JMdict sequence number of the entry.")],
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
    ) -> ApiResponse[DictEntry]:
        """One JMdict entry with frequency, pitch, kanji and your status.

        Retired entries are still returned (`retired` is true). Unknown `seq` is `404`.

        :param seq: JMdict sequence number of the entry. (required)
        :type seq: int
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

        _param = self._get_dict_entry_serialize(
            seq=seq,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "DictEntry",
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
    def get_dict_entry_without_preload_content(
        self,
        seq: Annotated[int, Field(strict=True, ge=1, description="JMdict sequence number of the entry.")],
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
        """One JMdict entry with frequency, pitch, kanji and your status.

        Retired entries are still returned (`retired` is true). Unknown `seq` is `404`.

        :param seq: JMdict sequence number of the entry. (required)
        :type seq: int
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

        _param = self._get_dict_entry_serialize(
            seq=seq,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "DictEntry",
            '404': "Problem",
            'default': "Problem",
        }
        response_data = self.api_client.call_api(
            *_param,
            _request_timeout=_request_timeout
        )
        return response_data.response


    def _get_dict_entry_serialize(
        self,
        seq,
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
        if seq is not None:
            _path_params['seq'] = seq
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
            resource_path='/dict/entries/{seq}',
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
    def get_dict_release(
        self,
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
    ) -> DictRelease:
        """The current dictionary release and its downloadable device builds.

        The phone compares `id` with the release of its dictionary file and, when behind, downloads an artifact through `GET /blobs/{hash}`: a `diff` whose `from_release` equals its release and whose `scope` matches, else the `full` build. `404` when no release has been loaded yet. Package and diff formats, hashes and the full install/update algorithm: `contracts/db/DICT_PACKAGE.md`. 

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

        _param = self._get_dict_release_serialize(
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "DictRelease",
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
    def get_dict_release_with_http_info(
        self,
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
    ) -> ApiResponse[DictRelease]:
        """The current dictionary release and its downloadable device builds.

        The phone compares `id` with the release of its dictionary file and, when behind, downloads an artifact through `GET /blobs/{hash}`: a `diff` whose `from_release` equals its release and whose `scope` matches, else the `full` build. `404` when no release has been loaded yet. Package and diff formats, hashes and the full install/update algorithm: `contracts/db/DICT_PACKAGE.md`. 

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

        _param = self._get_dict_release_serialize(
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "DictRelease",
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
    def get_dict_release_without_preload_content(
        self,
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
        """The current dictionary release and its downloadable device builds.

        The phone compares `id` with the release of its dictionary file and, when behind, downloads an artifact through `GET /blobs/{hash}`: a `diff` whose `from_release` equals its release and whose `scope` matches, else the `full` build. `404` when no release has been loaded yet. Package and diff formats, hashes and the full install/update algorithm: `contracts/db/DICT_PACKAGE.md`. 

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

        _param = self._get_dict_release_serialize(
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "DictRelease",
            '404': "Problem",
            'default': "Problem",
        }
        response_data = self.api_client.call_api(
            *_param,
            _request_timeout=_request_timeout
        )
        return response_data.response


    def _get_dict_release_serialize(
        self,
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
            resource_path='/dict/release',
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
    def get_kanji(
        self,
        literal: Annotated[str, Field(min_length=1, strict=True, max_length=2, description="Exactly one kanji (one Unicode code point), percent-encoded UTF-8. The literal path segment `hot` is the separate operation `listHotKanji`.")],
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
    ) -> Kanji:
        """One kanji from KANJIDIC2 with components and your kanji note, if any.

        More than one code point is `422`; a character not in `ref.kanji` is `404`. (`maxLength: 2` because a code point outside the BMP is two UTF-16 units.) 

        :param literal: Exactly one kanji (one Unicode code point), percent-encoded UTF-8. The literal path segment `hot` is the separate operation `listHotKanji`. (required)
        :type literal: str
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

        _param = self._get_kanji_serialize(
            literal=literal,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "Kanji",
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
    def get_kanji_with_http_info(
        self,
        literal: Annotated[str, Field(min_length=1, strict=True, max_length=2, description="Exactly one kanji (one Unicode code point), percent-encoded UTF-8. The literal path segment `hot` is the separate operation `listHotKanji`.")],
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
    ) -> ApiResponse[Kanji]:
        """One kanji from KANJIDIC2 with components and your kanji note, if any.

        More than one code point is `422`; a character not in `ref.kanji` is `404`. (`maxLength: 2` because a code point outside the BMP is two UTF-16 units.) 

        :param literal: Exactly one kanji (one Unicode code point), percent-encoded UTF-8. The literal path segment `hot` is the separate operation `listHotKanji`. (required)
        :type literal: str
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

        _param = self._get_kanji_serialize(
            literal=literal,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "Kanji",
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
    def get_kanji_without_preload_content(
        self,
        literal: Annotated[str, Field(min_length=1, strict=True, max_length=2, description="Exactly one kanji (one Unicode code point), percent-encoded UTF-8. The literal path segment `hot` is the separate operation `listHotKanji`.")],
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
        """One kanji from KANJIDIC2 with components and your kanji note, if any.

        More than one code point is `422`; a character not in `ref.kanji` is `404`. (`maxLength: 2` because a code point outside the BMP is two UTF-16 units.) 

        :param literal: Exactly one kanji (one Unicode code point), percent-encoded UTF-8. The literal path segment `hot` is the separate operation `listHotKanji`. (required)
        :type literal: str
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

        _param = self._get_kanji_serialize(
            literal=literal,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "Kanji",
            '404': "Problem",
            'default': "Problem",
        }
        response_data = self.api_client.call_api(
            *_param,
            _request_timeout=_request_timeout
        )
        return response_data.response


    def _get_kanji_serialize(
        self,
        literal,
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
        if literal is not None:
            _path_params['literal'] = literal
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
            resource_path='/kanji/{literal}',
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
    def list_dict_headwords(
        self,
        seq: Annotated[List[Annotated[int, Field(strict=True, ge=1)]], Field(min_length=1, max_length=500, description="1 to 500 JMdict sequence numbers, comma-separated. Duplicates collapse; unknown values are omitted from the result.")],
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
    ) -> DictHeadwordList:
        """Headword, reading and first-sense glosses for up to 500 entries in one call (C-18).

        Batch label lookup for clients that hold only `entry_seq` values (e.g. a page of referenced notes whose `fields` are `{}`). Read-only; no overlay (`status`, `note_id`), no paging. Use `getDictEntry` for the full entry.  **`seq`.** One query parameter holding 1 to 500 JMdict sequence numbers separated by commas (`seq=1358280,1358300`; a percent-encoded comma `%2C` is the same thing). Each value is a base-10 integer >= 1 with no sign, spaces or decimals. Any of these is `422` (`validation_failed`, pointer `/seq`) and nothing is looked up: the parameter is missing, empty (`seq=`) or given more than once (`seq=1&seq=2`); an element is empty (`1,,2`, trailing comma), not an integer, below 1 or above the int64 range; more than 500 elements. The 500 limit counts elements as sent, before de-duplication. Duplicates are allowed and collapse: `seq=5,5` behaves as `seq=5`.  **Result.** One item per distinct requested `seq` that exists in `ref.dict_entry`, ordered by `seq` ascending (not in request order). A `seq` with no row is omitted - not an error, and not `404` even when no requested `seq` exists (`items: []`). Retired entries are returned, as in `getDictEntry` (a note may still reference one). Clients match items to their rows by `seq` and keep their fallback label for omitted ones.  **Item fields** have exactly the values `getDictEntry` and `searchDictionary` report for the same entry: `headword` = first kanji form in JMdict order, or the first reading when the entry has no kanji form; `headword_reading` = first reading (equal to `headword` for kana-only entries); `glosses` = English glosses of the first sense, in order (the first element is the short meaning; empty array if the first sense has no English gloss).  **Request size.** 500 seven-digit values with percent-encoded commas make a request line of about 5 kB. The server and every proxy in front of it MUST accept request lines of at least 8192 bytes for this operation (Netty's default of 4096 is too small).  The response depends only on the dictionary release, not on the caller; it carries no cache headers in v1. 

        :param seq: 1 to 500 JMdict sequence numbers, comma-separated. Duplicates collapse; unknown values are omitted from the result. (required)
        :type seq: List[int]
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

        _param = self._list_dict_headwords_serialize(
            seq=seq,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "DictHeadwordList",
            '401': "Problem",
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
    def list_dict_headwords_with_http_info(
        self,
        seq: Annotated[List[Annotated[int, Field(strict=True, ge=1)]], Field(min_length=1, max_length=500, description="1 to 500 JMdict sequence numbers, comma-separated. Duplicates collapse; unknown values are omitted from the result.")],
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
    ) -> ApiResponse[DictHeadwordList]:
        """Headword, reading and first-sense glosses for up to 500 entries in one call (C-18).

        Batch label lookup for clients that hold only `entry_seq` values (e.g. a page of referenced notes whose `fields` are `{}`). Read-only; no overlay (`status`, `note_id`), no paging. Use `getDictEntry` for the full entry.  **`seq`.** One query parameter holding 1 to 500 JMdict sequence numbers separated by commas (`seq=1358280,1358300`; a percent-encoded comma `%2C` is the same thing). Each value is a base-10 integer >= 1 with no sign, spaces or decimals. Any of these is `422` (`validation_failed`, pointer `/seq`) and nothing is looked up: the parameter is missing, empty (`seq=`) or given more than once (`seq=1&seq=2`); an element is empty (`1,,2`, trailing comma), not an integer, below 1 or above the int64 range; more than 500 elements. The 500 limit counts elements as sent, before de-duplication. Duplicates are allowed and collapse: `seq=5,5` behaves as `seq=5`.  **Result.** One item per distinct requested `seq` that exists in `ref.dict_entry`, ordered by `seq` ascending (not in request order). A `seq` with no row is omitted - not an error, and not `404` even when no requested `seq` exists (`items: []`). Retired entries are returned, as in `getDictEntry` (a note may still reference one). Clients match items to their rows by `seq` and keep their fallback label for omitted ones.  **Item fields** have exactly the values `getDictEntry` and `searchDictionary` report for the same entry: `headword` = first kanji form in JMdict order, or the first reading when the entry has no kanji form; `headword_reading` = first reading (equal to `headword` for kana-only entries); `glosses` = English glosses of the first sense, in order (the first element is the short meaning; empty array if the first sense has no English gloss).  **Request size.** 500 seven-digit values with percent-encoded commas make a request line of about 5 kB. The server and every proxy in front of it MUST accept request lines of at least 8192 bytes for this operation (Netty's default of 4096 is too small).  The response depends only on the dictionary release, not on the caller; it carries no cache headers in v1. 

        :param seq: 1 to 500 JMdict sequence numbers, comma-separated. Duplicates collapse; unknown values are omitted from the result. (required)
        :type seq: List[int]
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

        _param = self._list_dict_headwords_serialize(
            seq=seq,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "DictHeadwordList",
            '401': "Problem",
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
    def list_dict_headwords_without_preload_content(
        self,
        seq: Annotated[List[Annotated[int, Field(strict=True, ge=1)]], Field(min_length=1, max_length=500, description="1 to 500 JMdict sequence numbers, comma-separated. Duplicates collapse; unknown values are omitted from the result.")],
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
        """Headword, reading and first-sense glosses for up to 500 entries in one call (C-18).

        Batch label lookup for clients that hold only `entry_seq` values (e.g. a page of referenced notes whose `fields` are `{}`). Read-only; no overlay (`status`, `note_id`), no paging. Use `getDictEntry` for the full entry.  **`seq`.** One query parameter holding 1 to 500 JMdict sequence numbers separated by commas (`seq=1358280,1358300`; a percent-encoded comma `%2C` is the same thing). Each value is a base-10 integer >= 1 with no sign, spaces or decimals. Any of these is `422` (`validation_failed`, pointer `/seq`) and nothing is looked up: the parameter is missing, empty (`seq=`) or given more than once (`seq=1&seq=2`); an element is empty (`1,,2`, trailing comma), not an integer, below 1 or above the int64 range; more than 500 elements. The 500 limit counts elements as sent, before de-duplication. Duplicates are allowed and collapse: `seq=5,5` behaves as `seq=5`.  **Result.** One item per distinct requested `seq` that exists in `ref.dict_entry`, ordered by `seq` ascending (not in request order). A `seq` with no row is omitted - not an error, and not `404` even when no requested `seq` exists (`items: []`). Retired entries are returned, as in `getDictEntry` (a note may still reference one). Clients match items to their rows by `seq` and keep their fallback label for omitted ones.  **Item fields** have exactly the values `getDictEntry` and `searchDictionary` report for the same entry: `headword` = first kanji form in JMdict order, or the first reading when the entry has no kanji form; `headword_reading` = first reading (equal to `headword` for kana-only entries); `glosses` = English glosses of the first sense, in order (the first element is the short meaning; empty array if the first sense has no English gloss).  **Request size.** 500 seven-digit values with percent-encoded commas make a request line of about 5 kB. The server and every proxy in front of it MUST accept request lines of at least 8192 bytes for this operation (Netty's default of 4096 is too small).  The response depends only on the dictionary release, not on the caller; it carries no cache headers in v1. 

        :param seq: 1 to 500 JMdict sequence numbers, comma-separated. Duplicates collapse; unknown values are omitted from the result. (required)
        :type seq: List[int]
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

        _param = self._list_dict_headwords_serialize(
            seq=seq,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "DictHeadwordList",
            '401': "Problem",
            '422': "Problem",
            'default': "Problem",
        }
        response_data = self.api_client.call_api(
            *_param,
            _request_timeout=_request_timeout
        )
        return response_data.response


    def _list_dict_headwords_serialize(
        self,
        seq,
        _request_auth,
        _content_type,
        _headers,
        _host_index,
    ) -> RequestSerialized:

        _host = None

        _collection_formats: Dict[str, str] = {
            'seq': 'csv',
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
        if seq is not None:
            
            _query_params.append(('seq', seq))
            
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
            resource_path='/dict/headwords',
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
    def list_entry_examples(
        self,
        seq: Annotated[int, Field(strict=True, ge=1, description="JMdict sequence number of the entry.")],
        limit: Annotated[Optional[Annotated[int, Field(le=50, strict=True, ge=1)]], Field(description="Maximum number of sentences per list.")] = None,
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
    ) -> EntryExamples:
        """Example sentences for an entry, from your media and from Tatoeba.

        `media`: up to `limit` sentences from imported media that contain the entry (resolved through `occurrence.sentence_ids`), sentences with the fewest unknown words first, then by media `created_at` descending, then sentence id. `tatoeba`: up to `limit` rows of `ref.example_sentence` linked to the entry, shortest text first, then id. Either list may be empty. Not paginated. Unknown `seq` is `404`. 

        :param seq: JMdict sequence number of the entry. (required)
        :type seq: int
        :param limit: Maximum number of sentences per list.
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

        _param = self._list_entry_examples_serialize(
            seq=seq,
            limit=limit,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "EntryExamples",
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
    def list_entry_examples_with_http_info(
        self,
        seq: Annotated[int, Field(strict=True, ge=1, description="JMdict sequence number of the entry.")],
        limit: Annotated[Optional[Annotated[int, Field(le=50, strict=True, ge=1)]], Field(description="Maximum number of sentences per list.")] = None,
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
    ) -> ApiResponse[EntryExamples]:
        """Example sentences for an entry, from your media and from Tatoeba.

        `media`: up to `limit` sentences from imported media that contain the entry (resolved through `occurrence.sentence_ids`), sentences with the fewest unknown words first, then by media `created_at` descending, then sentence id. `tatoeba`: up to `limit` rows of `ref.example_sentence` linked to the entry, shortest text first, then id. Either list may be empty. Not paginated. Unknown `seq` is `404`. 

        :param seq: JMdict sequence number of the entry. (required)
        :type seq: int
        :param limit: Maximum number of sentences per list.
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

        _param = self._list_entry_examples_serialize(
            seq=seq,
            limit=limit,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "EntryExamples",
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
    def list_entry_examples_without_preload_content(
        self,
        seq: Annotated[int, Field(strict=True, ge=1, description="JMdict sequence number of the entry.")],
        limit: Annotated[Optional[Annotated[int, Field(le=50, strict=True, ge=1)]], Field(description="Maximum number of sentences per list.")] = None,
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
        """Example sentences for an entry, from your media and from Tatoeba.

        `media`: up to `limit` sentences from imported media that contain the entry (resolved through `occurrence.sentence_ids`), sentences with the fewest unknown words first, then by media `created_at` descending, then sentence id. `tatoeba`: up to `limit` rows of `ref.example_sentence` linked to the entry, shortest text first, then id. Either list may be empty. Not paginated. Unknown `seq` is `404`. 

        :param seq: JMdict sequence number of the entry. (required)
        :type seq: int
        :param limit: Maximum number of sentences per list.
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

        _param = self._list_entry_examples_serialize(
            seq=seq,
            limit=limit,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "EntryExamples",
            '404': "Problem",
            'default': "Problem",
        }
        response_data = self.api_client.call_api(
            *_param,
            _request_timeout=_request_timeout
        )
        return response_data.response


    def _list_entry_examples_serialize(
        self,
        seq,
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
        if seq is not None:
            _path_params['seq'] = seq
        # process the query parameters
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
            resource_path='/dict/entries/{seq}/examples',
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
    def list_kanji_words(
        self,
        literal: Annotated[str, Field(min_length=1, strict=True, max_length=2, description="Exactly one Unicode code point, percent-encoded UTF-8, matched exactly as given against `ref.entry_kanji.literal` (no normalisation, same rules as `getKanji`).")],
        deck_id: Annotated[Optional[Annotated[str, Field(strict=True)]], Field(description="Only entries that are live items of this deck, ordered by in-deck frequency. Must be a live deck (`404` otherwise).")] = None,
        mine: Annotated[Optional[StrictBool], Field(description="Only entries that are a live item of at least one of your live decks or have a live vocab note.")] = None,
        limit: Annotated[Optional[Annotated[int, Field(le=200, strict=True, ge=1)]], Field(description="Page size.")] = None,
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
    ) -> KanjiWordPage:
        """Dictionary entries whose kanji forms contain this kanji, most frequent first, with your deck membership (C-17).

        Rows: the live (`retired_release_id IS NULL`) `ref.dict_entry` rows `e` with a `ref.entry_kanji` row `(e.seq, literal)`. Retired entries are never returned.  **Literal.** More than one code point (or an empty string) is `422`. Unlike `getKanji`, a character absent from `ref.kanji` is **not** `404`: `ref.entry_kanji.literal` need not exist in `ref.kanji`, so such a character, or any code point no entry contains (kana, Latin...), is simply an empty page (`items: []`, `next_cursor: null`).  **Filters** (combine with AND): - `deck_id`: only entries `e` with a live item of that deck: an `app.deck_item` row with   `deck_id` = the parameter, `entry_seq = e.seq` and `deleted_at IS NULL`. The deck must   be a live deck of the caller (`app.deck.deleted_at IS NULL`), else `404` - a deleted   deck is `404` even though its rows remain. A live deck with no matching item (including   every `smart` deck, which has no items) gives an empty page. - `mine=true`: only entries that are a live item (as above) of at least one live deck of   the caller, **or** that have a live vocab note (`app.note` with `entry_seq = e.seq`,   `deleted_at IS NULL`, base type `vocab` - the same note the `note_id` overlay reports).   Word status alone (e.g. `known` set by hand) does not count. With `deck_id` also given,   `mine=true` adds nothing (every result is already in one of your decks). - Note-keyed deck items (`deck_item.note_id`, `entry_seq IS NULL`) never match: only   `entry_seq` items count, here and in `decks`.  **Order** (total; `seq` is unique): - `deck_id` given: that deck's `deck_item.occurrence_count` descending (in-deck frequency;   all `0` for non-media decks, so this degrades to the global order), then   `freq_rank` ascending with NULLs last, then `seq` ascending. - Otherwise: `freq_rank` ascending with NULLs last, then `seq` ascending.  **Paging.** Keyset pagination: the cursor encodes the sort key of the last returned row and the `literal`, `deck_id` and `mine` it was issued for; reusing it with a different `literal`, `deck_id` or `mine` is `422`. Pages are not a snapshot: if `occurrence_count` or `freq_rank` changes between pages (media reparse, dictionary refresh), a row may be skipped or repeated; clients that page through MUST de-duplicate by `seq`. `next_cursor` is null on the last page (it MAY be non-null on a page that happens to end exactly at the last row; the following page is then empty).  **Item.** Every `DictEntrySummary` field with the same meaning as in `searchDictionary` (caller's `status` and `note_id` overlay), plus `decks`: the caller's live decks (`app.deck.deleted_at IS NULL`) that hold the entry as a live item (`deck_item.deleted_at IS NULL`), ordered by `name` in code-point order (`COLLATE \"C\"`), then `id`. Empty array when none. Independent of `deck_id`/`mine`: with `deck_id`, that deck is always among them. `decks[].occurrence_count` is that deck's `deck_item.occurrence_count`. 

        :param literal: Exactly one Unicode code point, percent-encoded UTF-8, matched exactly as given against `ref.entry_kanji.literal` (no normalisation, same rules as `getKanji`). (required)
        :type literal: str
        :param deck_id: Only entries that are live items of this deck, ordered by in-deck frequency. Must be a live deck (`404` otherwise).
        :type deck_id: str
        :param mine: Only entries that are a live item of at least one of your live decks or have a live vocab note.
        :type mine: bool
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

        _param = self._list_kanji_words_serialize(
            literal=literal,
            deck_id=deck_id,
            mine=mine,
            limit=limit,
            cursor=cursor,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "KanjiWordPage",
            '404': "Problem",
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
    def list_kanji_words_with_http_info(
        self,
        literal: Annotated[str, Field(min_length=1, strict=True, max_length=2, description="Exactly one Unicode code point, percent-encoded UTF-8, matched exactly as given against `ref.entry_kanji.literal` (no normalisation, same rules as `getKanji`).")],
        deck_id: Annotated[Optional[Annotated[str, Field(strict=True)]], Field(description="Only entries that are live items of this deck, ordered by in-deck frequency. Must be a live deck (`404` otherwise).")] = None,
        mine: Annotated[Optional[StrictBool], Field(description="Only entries that are a live item of at least one of your live decks or have a live vocab note.")] = None,
        limit: Annotated[Optional[Annotated[int, Field(le=200, strict=True, ge=1)]], Field(description="Page size.")] = None,
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
    ) -> ApiResponse[KanjiWordPage]:
        """Dictionary entries whose kanji forms contain this kanji, most frequent first, with your deck membership (C-17).

        Rows: the live (`retired_release_id IS NULL`) `ref.dict_entry` rows `e` with a `ref.entry_kanji` row `(e.seq, literal)`. Retired entries are never returned.  **Literal.** More than one code point (or an empty string) is `422`. Unlike `getKanji`, a character absent from `ref.kanji` is **not** `404`: `ref.entry_kanji.literal` need not exist in `ref.kanji`, so such a character, or any code point no entry contains (kana, Latin...), is simply an empty page (`items: []`, `next_cursor: null`).  **Filters** (combine with AND): - `deck_id`: only entries `e` with a live item of that deck: an `app.deck_item` row with   `deck_id` = the parameter, `entry_seq = e.seq` and `deleted_at IS NULL`. The deck must   be a live deck of the caller (`app.deck.deleted_at IS NULL`), else `404` - a deleted   deck is `404` even though its rows remain. A live deck with no matching item (including   every `smart` deck, which has no items) gives an empty page. - `mine=true`: only entries that are a live item (as above) of at least one live deck of   the caller, **or** that have a live vocab note (`app.note` with `entry_seq = e.seq`,   `deleted_at IS NULL`, base type `vocab` - the same note the `note_id` overlay reports).   Word status alone (e.g. `known` set by hand) does not count. With `deck_id` also given,   `mine=true` adds nothing (every result is already in one of your decks). - Note-keyed deck items (`deck_item.note_id`, `entry_seq IS NULL`) never match: only   `entry_seq` items count, here and in `decks`.  **Order** (total; `seq` is unique): - `deck_id` given: that deck's `deck_item.occurrence_count` descending (in-deck frequency;   all `0` for non-media decks, so this degrades to the global order), then   `freq_rank` ascending with NULLs last, then `seq` ascending. - Otherwise: `freq_rank` ascending with NULLs last, then `seq` ascending.  **Paging.** Keyset pagination: the cursor encodes the sort key of the last returned row and the `literal`, `deck_id` and `mine` it was issued for; reusing it with a different `literal`, `deck_id` or `mine` is `422`. Pages are not a snapshot: if `occurrence_count` or `freq_rank` changes between pages (media reparse, dictionary refresh), a row may be skipped or repeated; clients that page through MUST de-duplicate by `seq`. `next_cursor` is null on the last page (it MAY be non-null on a page that happens to end exactly at the last row; the following page is then empty).  **Item.** Every `DictEntrySummary` field with the same meaning as in `searchDictionary` (caller's `status` and `note_id` overlay), plus `decks`: the caller's live decks (`app.deck.deleted_at IS NULL`) that hold the entry as a live item (`deck_item.deleted_at IS NULL`), ordered by `name` in code-point order (`COLLATE \"C\"`), then `id`. Empty array when none. Independent of `deck_id`/`mine`: with `deck_id`, that deck is always among them. `decks[].occurrence_count` is that deck's `deck_item.occurrence_count`. 

        :param literal: Exactly one Unicode code point, percent-encoded UTF-8, matched exactly as given against `ref.entry_kanji.literal` (no normalisation, same rules as `getKanji`). (required)
        :type literal: str
        :param deck_id: Only entries that are live items of this deck, ordered by in-deck frequency. Must be a live deck (`404` otherwise).
        :type deck_id: str
        :param mine: Only entries that are a live item of at least one of your live decks or have a live vocab note.
        :type mine: bool
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

        _param = self._list_kanji_words_serialize(
            literal=literal,
            deck_id=deck_id,
            mine=mine,
            limit=limit,
            cursor=cursor,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "KanjiWordPage",
            '404': "Problem",
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
    def list_kanji_words_without_preload_content(
        self,
        literal: Annotated[str, Field(min_length=1, strict=True, max_length=2, description="Exactly one Unicode code point, percent-encoded UTF-8, matched exactly as given against `ref.entry_kanji.literal` (no normalisation, same rules as `getKanji`).")],
        deck_id: Annotated[Optional[Annotated[str, Field(strict=True)]], Field(description="Only entries that are live items of this deck, ordered by in-deck frequency. Must be a live deck (`404` otherwise).")] = None,
        mine: Annotated[Optional[StrictBool], Field(description="Only entries that are a live item of at least one of your live decks or have a live vocab note.")] = None,
        limit: Annotated[Optional[Annotated[int, Field(le=200, strict=True, ge=1)]], Field(description="Page size.")] = None,
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
        """Dictionary entries whose kanji forms contain this kanji, most frequent first, with your deck membership (C-17).

        Rows: the live (`retired_release_id IS NULL`) `ref.dict_entry` rows `e` with a `ref.entry_kanji` row `(e.seq, literal)`. Retired entries are never returned.  **Literal.** More than one code point (or an empty string) is `422`. Unlike `getKanji`, a character absent from `ref.kanji` is **not** `404`: `ref.entry_kanji.literal` need not exist in `ref.kanji`, so such a character, or any code point no entry contains (kana, Latin...), is simply an empty page (`items: []`, `next_cursor: null`).  **Filters** (combine with AND): - `deck_id`: only entries `e` with a live item of that deck: an `app.deck_item` row with   `deck_id` = the parameter, `entry_seq = e.seq` and `deleted_at IS NULL`. The deck must   be a live deck of the caller (`app.deck.deleted_at IS NULL`), else `404` - a deleted   deck is `404` even though its rows remain. A live deck with no matching item (including   every `smart` deck, which has no items) gives an empty page. - `mine=true`: only entries that are a live item (as above) of at least one live deck of   the caller, **or** that have a live vocab note (`app.note` with `entry_seq = e.seq`,   `deleted_at IS NULL`, base type `vocab` - the same note the `note_id` overlay reports).   Word status alone (e.g. `known` set by hand) does not count. With `deck_id` also given,   `mine=true` adds nothing (every result is already in one of your decks). - Note-keyed deck items (`deck_item.note_id`, `entry_seq IS NULL`) never match: only   `entry_seq` items count, here and in `decks`.  **Order** (total; `seq` is unique): - `deck_id` given: that deck's `deck_item.occurrence_count` descending (in-deck frequency;   all `0` for non-media decks, so this degrades to the global order), then   `freq_rank` ascending with NULLs last, then `seq` ascending. - Otherwise: `freq_rank` ascending with NULLs last, then `seq` ascending.  **Paging.** Keyset pagination: the cursor encodes the sort key of the last returned row and the `literal`, `deck_id` and `mine` it was issued for; reusing it with a different `literal`, `deck_id` or `mine` is `422`. Pages are not a snapshot: if `occurrence_count` or `freq_rank` changes between pages (media reparse, dictionary refresh), a row may be skipped or repeated; clients that page through MUST de-duplicate by `seq`. `next_cursor` is null on the last page (it MAY be non-null on a page that happens to end exactly at the last row; the following page is then empty).  **Item.** Every `DictEntrySummary` field with the same meaning as in `searchDictionary` (caller's `status` and `note_id` overlay), plus `decks`: the caller's live decks (`app.deck.deleted_at IS NULL`) that hold the entry as a live item (`deck_item.deleted_at IS NULL`), ordered by `name` in code-point order (`COLLATE \"C\"`), then `id`. Empty array when none. Independent of `deck_id`/`mine`: with `deck_id`, that deck is always among them. `decks[].occurrence_count` is that deck's `deck_item.occurrence_count`. 

        :param literal: Exactly one Unicode code point, percent-encoded UTF-8, matched exactly as given against `ref.entry_kanji.literal` (no normalisation, same rules as `getKanji`). (required)
        :type literal: str
        :param deck_id: Only entries that are live items of this deck, ordered by in-deck frequency. Must be a live deck (`404` otherwise).
        :type deck_id: str
        :param mine: Only entries that are a live item of at least one of your live decks or have a live vocab note.
        :type mine: bool
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

        _param = self._list_kanji_words_serialize(
            literal=literal,
            deck_id=deck_id,
            mine=mine,
            limit=limit,
            cursor=cursor,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "KanjiWordPage",
            '404': "Problem",
            '422': "Problem",
            'default': "Problem",
        }
        response_data = self.api_client.call_api(
            *_param,
            _request_timeout=_request_timeout
        )
        return response_data.response


    def _list_kanji_words_serialize(
        self,
        literal,
        deck_id,
        mine,
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
        if literal is not None:
            _path_params['literal'] = literal
        # process the query parameters
        if deck_id is not None:
            
            _query_params.append(('deck_id', deck_id))
            
        if mine is not None:
            
            _query_params.append(('mine', mine))
            
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
            resource_path='/kanji/{literal}/words',
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
    def refresh_dictionary(
        self,
        job_payload_dict_refresh: Optional[JobPayloadDictRefresh] = None,
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
        """Queue a dictionary refresh (download sources, build and load a new release).

        Enqueues a job of kind `dict-refresh` (payload `JobPayloadDictRefresh`, dedupe key `dict-refresh`). An already queued or running refresh is returned with `200`. An absent body equals `{}`. 

        :param job_payload_dict_refresh:
        :type job_payload_dict_refresh: JobPayloadDictRefresh
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

        _param = self._refresh_dictionary_serialize(
            job_payload_dict_refresh=job_payload_dict_refresh,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "Job",
            '202': "Job",
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
    def refresh_dictionary_with_http_info(
        self,
        job_payload_dict_refresh: Optional[JobPayloadDictRefresh] = None,
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
        """Queue a dictionary refresh (download sources, build and load a new release).

        Enqueues a job of kind `dict-refresh` (payload `JobPayloadDictRefresh`, dedupe key `dict-refresh`). An already queued or running refresh is returned with `200`. An absent body equals `{}`. 

        :param job_payload_dict_refresh:
        :type job_payload_dict_refresh: JobPayloadDictRefresh
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

        _param = self._refresh_dictionary_serialize(
            job_payload_dict_refresh=job_payload_dict_refresh,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "Job",
            '202': "Job",
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
    def refresh_dictionary_without_preload_content(
        self,
        job_payload_dict_refresh: Optional[JobPayloadDictRefresh] = None,
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
        """Queue a dictionary refresh (download sources, build and load a new release).

        Enqueues a job of kind `dict-refresh` (payload `JobPayloadDictRefresh`, dedupe key `dict-refresh`). An already queued or running refresh is returned with `200`. An absent body equals `{}`. 

        :param job_payload_dict_refresh:
        :type job_payload_dict_refresh: JobPayloadDictRefresh
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

        _param = self._refresh_dictionary_serialize(
            job_payload_dict_refresh=job_payload_dict_refresh,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "Job",
            '202': "Job",
            'default': "Problem",
        }
        response_data = self.api_client.call_api(
            *_param,
            _request_timeout=_request_timeout
        )
        return response_data.response


    def _refresh_dictionary_serialize(
        self,
        job_payload_dict_refresh,
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
        if job_payload_dict_refresh is not None:
            _body_params = job_payload_dict_refresh


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
            resource_path='/dict/refresh',
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
    def search_dictionary(
        self,
        q: Annotated[str, Field(min_length=1, strict=True, max_length=100, description="Search text, 1 to 100 characters after trimming.")],
        common_only: Annotated[Optional[StrictBool], Field(description="Only entries with `is_common = true`.")] = None,
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
    ) -> DictEntrySummaryPage:
        """Search JMdict entries by kanji, kana, romaji or English.

        `q` is trimmed and NFKC-normalised. Interpretation, in order: 1. Contains kanji or kana: katakana is folded to hiragana and matched against    `dict_entry_form.norm` - 1 or 2 characters: exact or prefix; 3 or more: substring. 2. Otherwise (Latin letters): the server's romaji-to-kana conversion (wanakana-equivalent:    Hepburn and common IME spellings, `nn`/`n'` for ん, doubled consonant for っ) is tried    and, if the whole string converts, matched as in 1; the string is also matched    case-insensitively as a substring of English glosses. Both result sets are merged. No deinflection: the input is matched as given (`たべた` does not find `食べる` unless a form of that entry contains `たべた`). Deinflection is the parser's job (`POST /parse`), not this search's (C-2). Retired entries are never returned. Order: exact form match first, then prefix, then the rest; inside each group `freq_rank` ascending with NULLs last, then `seq`. An empty or whitespace-only `q` is `422`. No match is an empty page. Each item carries the caller's `status` and `note_id` overlay. 

        :param q: Search text, 1 to 100 characters after trimming. (required)
        :type q: str
        :param common_only: Only entries with `is_common = true`.
        :type common_only: bool
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

        _param = self._search_dictionary_serialize(
            q=q,
            common_only=common_only,
            limit=limit,
            cursor=cursor,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "DictEntrySummaryPage",
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
    def search_dictionary_with_http_info(
        self,
        q: Annotated[str, Field(min_length=1, strict=True, max_length=100, description="Search text, 1 to 100 characters after trimming.")],
        common_only: Annotated[Optional[StrictBool], Field(description="Only entries with `is_common = true`.")] = None,
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
    ) -> ApiResponse[DictEntrySummaryPage]:
        """Search JMdict entries by kanji, kana, romaji or English.

        `q` is trimmed and NFKC-normalised. Interpretation, in order: 1. Contains kanji or kana: katakana is folded to hiragana and matched against    `dict_entry_form.norm` - 1 or 2 characters: exact or prefix; 3 or more: substring. 2. Otherwise (Latin letters): the server's romaji-to-kana conversion (wanakana-equivalent:    Hepburn and common IME spellings, `nn`/`n'` for ん, doubled consonant for っ) is tried    and, if the whole string converts, matched as in 1; the string is also matched    case-insensitively as a substring of English glosses. Both result sets are merged. No deinflection: the input is matched as given (`たべた` does not find `食べる` unless a form of that entry contains `たべた`). Deinflection is the parser's job (`POST /parse`), not this search's (C-2). Retired entries are never returned. Order: exact form match first, then prefix, then the rest; inside each group `freq_rank` ascending with NULLs last, then `seq`. An empty or whitespace-only `q` is `422`. No match is an empty page. Each item carries the caller's `status` and `note_id` overlay. 

        :param q: Search text, 1 to 100 characters after trimming. (required)
        :type q: str
        :param common_only: Only entries with `is_common = true`.
        :type common_only: bool
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

        _param = self._search_dictionary_serialize(
            q=q,
            common_only=common_only,
            limit=limit,
            cursor=cursor,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "DictEntrySummaryPage",
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
    def search_dictionary_without_preload_content(
        self,
        q: Annotated[str, Field(min_length=1, strict=True, max_length=100, description="Search text, 1 to 100 characters after trimming.")],
        common_only: Annotated[Optional[StrictBool], Field(description="Only entries with `is_common = true`.")] = None,
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
        """Search JMdict entries by kanji, kana, romaji or English.

        `q` is trimmed and NFKC-normalised. Interpretation, in order: 1. Contains kanji or kana: katakana is folded to hiragana and matched against    `dict_entry_form.norm` - 1 or 2 characters: exact or prefix; 3 or more: substring. 2. Otherwise (Latin letters): the server's romaji-to-kana conversion (wanakana-equivalent:    Hepburn and common IME spellings, `nn`/`n'` for ん, doubled consonant for っ) is tried    and, if the whole string converts, matched as in 1; the string is also matched    case-insensitively as a substring of English glosses. Both result sets are merged. No deinflection: the input is matched as given (`たべた` does not find `食べる` unless a form of that entry contains `たべた`). Deinflection is the parser's job (`POST /parse`), not this search's (C-2). Retired entries are never returned. Order: exact form match first, then prefix, then the rest; inside each group `freq_rank` ascending with NULLs last, then `seq`. An empty or whitespace-only `q` is `422`. No match is an empty page. Each item carries the caller's `status` and `note_id` overlay. 

        :param q: Search text, 1 to 100 characters after trimming. (required)
        :type q: str
        :param common_only: Only entries with `is_common = true`.
        :type common_only: bool
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

        _param = self._search_dictionary_serialize(
            q=q,
            common_only=common_only,
            limit=limit,
            cursor=cursor,
            _request_auth=_request_auth,
            _content_type=_content_type,
            _headers=_headers,
            _host_index=_host_index
        )

        _response_types_map: Dict[str, Optional[str]] = {
            '200': "DictEntrySummaryPage",
            '422': "Problem",
            'default': "Problem",
        }
        response_data = self.api_client.call_api(
            *_param,
            _request_timeout=_request_timeout
        )
        return response_data.response


    def _search_dictionary_serialize(
        self,
        q,
        common_only,
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
        if q is not None:
            
            _query_params.append(('q', q))
            
        if common_only is not None:
            
            _query_params.append(('common_only', common_only))
            
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
            resource_path='/dict/search',
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


