"""
    Kotobaya API

    Versioned REST/JSON interface at `/api/v1`. The web app uses nothing else; the Android app uses `sync`, `blobs`, `dict/release`, `devices/pair`, `devices/self` and whatever conveniences it likes.  Implementers: this file is the contract. If something you need is missing or wrong, do not work around it; return `CONTRACT_CHANGE_REQUEST`. Additive changes stay in v1; breaking changes go to `/api/v2`.  ## 1. Conventions  - **JSON.** Request and response bodies are `application/json; charset=utf-8` unless stated.   Property names are `snake_case` and equal the column names of `contracts/db/V1__init.sql`   wherever a property mirrors a column; the column `COMMENT` is then the definition. - **Unknown properties.** Servers reject unknown request properties and unknown query   parameters with `422 validation_failed`. Clients MUST ignore unknown response properties and   MUST treat unknown enum values in responses as \"other\" (v1 may add both). - **Absent vs null.** In `PATCH` bodies an absent property means \"leave unchanged\"; `null` means   \"set to NULL\" where the property is nullable and is a validation error otherwise.   Responses always include every documented property; nullable ones are `null`, never absent,   unless the property is documented as \"omitted when ...\". - **Ids.** Row ids are UUIDv7 in canonical lowercase `8-4-4-4-12` text. Uppercase ids in   requests are rejected with `422`. \"Entry\" always means a JMdict sequence number (`seq`,   JSON integer). - **Time.** Instants are RFC 3339 UTC strings with millisecond precision and a `Z` suffix,   e.g. `2026-11-12T09:30:00.000Z`. Servers accept any RFC 3339 offset on input and normalise   to UTC. A \"study day\" is a local date `YYYY-MM-DD` computed from the settings   `scheduler.timezone` and `scheduler.day_rollover_hour`: study day D covers local time   `D rollover:00` (inclusive) to `D+1 rollover:00` (exclusive). - **Text offsets** into sentence text are UTF-16 code units, half-open `[start, end)`. - **Blobs** (audio, images, uploads, snapshots) are addressed by the lowercase hex SHA-256 of   their bytes and transferred through `/blobs/{hash}`, never inline in JSON.  ## 2. Authentication and scopes  Three credential kinds, all rows of `app.api_token` (stored hashed):  | Kind | How obtained | Sent as | Scopes | |---|---|---|---| | `session` | `POST /auth/login` | cookie `kotobaya_session` (HTTP-only) | all five | | `device` | `POST /devices/pair` | `Authorization: Bearer kbd_...` | `read`, `write`, `review` | | `personal` | `POST /tokens` | `Authorization: Bearer kbp_...` | chosen at creation |  Token strings are `<3-letter kind prefix>_<40 characters of [A-Za-z0-9]>` (`kbs_`, `kbd_`, `kbp_`), generated from a CSPRNG. `token_hash` = lowercase hex SHA-256 of the whole string; `token_prefix` = its first 8 characters. A token is valid iff `revoked_at IS NULL` and (`expires_at IS NULL` or `expires_at > now()`) and, when it has a `device_id`, that device is not revoked. Validity is checked on every request (no caching longer than 5 seconds), so revocation takes effect at once.  Scopes are independent (no scope implies another):  | Scope | Grants | |---|---| | `read` | Every `GET` of learning, media and reference data; `POST /parse`; `GET /events`; `GET /sync/pull`; exports. | | `write` | Create, change, delete notes, cards, decks, grammar, note types, presets, settings, word status, parse overrides, media metadata; upload blobs; `POST /sync/push` (non-review ops). | | `review` | `POST /review/answer`, `POST /review/undo`, `POST /drill/session`, `POST /cards/bulk/schedule`; `review.*` ops inside `POST /sync/push` (per op; without it the op is rejected with `insufficient_scope`). | | `import` | Start and cancel import, parse, dictionary and optimizer jobs; upload blobs. | | `admin` | Devices, pairing, tokens (a device's own row: `/devices/self` with `read`/`write`). |  Each operation lists the scope it needs in its `security` requirement: the array after the scheme name. When two alternatives are listed for one scheme the caller needs either. A request with no or an invalid credential gets `401 unauthenticated`; a valid credential lacking the scope gets `403 insufficient_scope`. Operations with `security: []` are public.  There are exactly two places where who may call is not just \"scheme + scope\", and both are declared in the operation, not only in prose: - `GET/PATCH /devices/self` (`read` / `write`, bearer only): the token must be bound to a   device, else `403 device_required`. A device never needs `admin` to manage its own row;   `/devices/{device_id}` is admin only. - `POST /ankiconnect` (`x-auth: body-key`): the token may instead be sent in the JSON body   property `key`; the empty `{}` alternative in its `security` only signals that no header is   needed. The scope for `key` is `x-body-key-scope`.  **CSRF.** A request authenticated by the session cookie whose method is not `GET` or `HEAD` MUST carry the header `X-Kotobaya-Csrf: 1`; otherwise `403 csrf_required`. The cookie is set with `HttpOnly; Secure; SameSite=Strict; Path=/api`. The server sends no CORS headers (the web app is served from the same origin). If both a cookie and an `Authorization` header are present, the header wins and the cookie is ignored.  **Audit.** Every request is logged with `token.id` and `token.name` (structured server log; there is no audit table or endpoint in v1). Ops record the token in `change.token_id`.  ## 3. Writes are sync ops  Learning data is only ever changed by applying sync ops (`contracts/sync`). Every REST operation that changes learning data is a convenience wrapper: the server builds the op(s) named in the operation's `x-emits-ops`, applies them through the same op-apply path as `POST /sync/push` in one transaction, and they then appear in `GET /sync/pull` for every device. Op names are those of `contracts/db/SCHEMA.md` section 4; `contracts/sync` is authoritative for names and payloads.  For an op the server builds on behalf of a REST call:  - `op_id` is a fresh UUIDv7; origin `device_id` is the **server device**   `00000000-0000-7000-8000-000000000001` (also for session and device tokens: only one party   ever generates HLCs for a given device id); `hlc` comes from the server's HLC clock;   `change.token_id` is the calling token. - A property absent from a `PATCH` body produces no register write. A property present with   the value the row already has still produces a write (it bumps the register's clock). - If a call changes nothing (documented per operation, e.g. deleting an already deleted   note), no op is emitted and the response is still successful. - **Never an invalid op (C-6).** If a request would make the server build an op that fails   its schema or an op-level rule in `contracts/sync` - a tag containing ECMA-262 whitespace   (e.g. U+3000 or U+00A0), U+0000 in any string (field values and open JSON objects   included), an integer above its op maximum (`2147483647` for int4 columns) or an instant   after `9999-12-31T23:59:59.999Z` - the call is `422 validation_failed` with a field error   at that request property, code `invalid_value` (or `out_of_range` / `too_long` where the   operation's own request bound fails first). Nothing is emitted; never a `500`.   SYNC.md 2.1 \"Storable values\" and \"Input that would mint an invalid op\". - Responses to mutating calls carry the header `Kotobaya-Seq`: the `change.seq` of the last op   the call emitted. A client holding a local replica knows it has seen the effect once its   pull cursor is at or past that value. The header is absent when no op was emitted.  **Idempotent creation.** Create bodies accept an optional client-generated `id` (UUIDv7). Re-sending a create with an `id` that already exists returns the existing resource with status `200` instead of `201` and emits nothing, whatever the rest of the body says. Clients that may retry MUST send `id`. When `id` is omitted the server generates one. `POST /review/answer` requires `id` (a duplicate review would corrupt scheduling); `POST /media/import` takes the same optional idempotency id as `media_id`.  The existence check and the emit are **one transaction that takes advisory lock `7033001` before the check** and holds it until commit (the lock `POST /sync/push` takes per op, SYNC.md 5.2): check whether `id` exists; if not, mint, apply and log the op(s) - for `createNote` the `note.created` and every `card.created` - then commit. Without the lock two concurrent retries of the same create both pass the check; the second `note.created` is a no-op on the row (SYNC.md 7.2) but its `card.created` ops carry fresh card ids, so the note would get duplicate cards. The same holds for every other id-idempotent call (`POST /review/answer`, `POST /review/undo`, `POST /media/import`), and a pushed op that creates the same id races safely because it takes the same lock.  **Tombstones and merges.** Deleted learning rows are tombstones (`deleted_at` set). `GET` by id of a deleted row returns `410 gone` unless `include_deleted=true`; lists omit deleted rows unless `include_deleted=true`. A note or card that lost a merge (`merged_into_id` set) is answered with its survivor: `GET`, `PATCH` and review calls addressed to the loser's id act on the survivor and the response body is the survivor (its `id` differs from the requested id; the response has `Content-Location` with the survivor's URL).  ## 4. Pagination  List operations that take `limit` and `cursor` return `{\"items\": [...], \"next_cursor\": ...}`. `next_cursor` is an opaque string, or `null` on the last page. Pass it back unchanged as `cursor` together with the same other parameters; changing any other parameter while passing a cursor is `422 validation_failed` (`pointer` `/cursor`). `limit` defaults to 50, maximum 500. Order is stable and documented per operation; rows created while paging may or may not appear.  **Cursor semantics (keyset, no snapshot).** A cursor encodes the sort-key values and the `id` of the last row returned; the next page is the rows strictly after that tuple in the current order, evaluated against the data at the time of that request. The order is not snapshotted. Consequently, while a client pages through a list sorted by a mutable key (`due_at`, `updated_at`, `lapses`, ...): - a row whose key changes so that it moves from after the cursor to before it is **skipped**; - a row whose key changes so that it moves from before the cursor to after it is **returned   again** (with its new values); - a row deleted while paging disappears; one created may or may not appear. Rows that do not change are returned exactly once. Clients MUST de-duplicate by `id` (last occurrence wins). A client that needs an exact set (bulk actions, exports) uses an `id`-sorted list where offered, `POST /cards/bulk` with `query`, or `GET /export`. Lists sorted by an immutable key (`id`, `created_at`) have no skips or repeats. An empty result is `{\"items\": [], \"next_cursor\": null}` with status `200`.  ## 5. Errors  Every non-2xx response has the body `application/problem+json` (RFC 9457), schema `Problem`. `code` is the stable machine-readable identifier; `type` is `urn:kotobaya:problem:<code>`.  | Status | `code` | When | |---|---|---| | 400 | `invalid_request` | Body is not valid JSON, wrong content type framing, malformed header; `POST /sync/push` body not valid against `PushRequestLenient`. | | 401 | `unauthenticated` | No credential, or it is unknown, expired or revoked. | | 401 | `invalid_credentials` | Wrong password or TOTP code (`POST /auth/login`). | | 401 | `totp_required` | TOTP is enabled and `totp_code` was not sent. | | 401 | `invalid_pairing_code` | Pairing code unknown, expired or already used. | | 403 | `insufficient_scope` | Credential lacks the scope; `required_scope` names it. | | 403 | `csrf_required` | Cookie-authenticated write without `X-Kotobaya-Csrf`. | | 403 | `device_required` | `POST /sync/push` or `/devices/self` with a credential that has no `device_id`. | | 404 | `not_found` | No such resource (also: id of another resource kind). | | 409 | `already_exists` | Natural-key collision; `existing_id` names the holder. | | 409 | `builtin_immutable` | Deleting a built-in note type or the Default preset, or removing a built-in field. | | 409 | `in_use` | Deleting a note type that live notes use. | | 409 | `deck_read_only` | Changing items or deleting a `media`/`list` deck through deck endpoints. | | 409 | `not_applicable` | The resource kind does not support the operation (e.g. items of a smart deck). | | 409 | `card_not_reviewable` | Card is suspended. | | 409 | `job_not_cancellable` | Job already finished. | | 410 | `gone` | Tombstoned row requested without `include_deleted=true`. | | 413 | `payload_too_large` | Body over the limit in `GET /meta`. On `POST /sync/push` only for the whole batch (the client halves it); one op over `max_op_bytes` is a per-op `op_too_large` result instead. | | 415 | `unsupported_media_type` | Wrong `Content-Type`. | | 422 | `validation_failed` | Well-formed but invalid; `errors[]` lists each problem. | | 422 | `invalid_query` | Card search string does not parse; `errors[0].position` is the UTF-16 offset. | | 422 | `bulk_too_large` | Bulk selector matched more than 5000 cards. | | 422 | `hash_mismatch` | Uploaded bytes do not hash to the `{hash}` in the path. | | 429 | `rate_limited` | Too many login or pairing attempts; see `Retry-After`. | | 500 | `internal` | Bug. Safe to retry idempotent calls. | | 503 | `unavailable` | Database or object store unreachable. Retry with backoff. |  Check order: 401, then 403, then 404/410, then 415/400, then 422, then 409.  ## 6. Events  `GET /events` is a server-sent events stream; see that operation. Event payload schemas are `Event*` in components. 

    The version of the OpenAPI document: 1.0.0
    Generated by OpenAPI Generator (https://openapi-generator.tech)

    Do not edit the class manually.
"""  # noqa: E501



import datetime
from dateutil.parser import parse
from enum import Enum
import decimal
import json
import mimetypes
import os
import re
import tempfile
import uuid

from urllib.parse import quote
from typing import Any, Tuple, Optional, List, Dict, Union
from pydantic import SecretStr

from kotobaya_client.configuration import Configuration
from kotobaya_client.api_response import ApiResponse, T as ApiResponseT
import kotobaya_client.models
from kotobaya_client import rest
from kotobaya_client.exceptions import (
    ApiValueError,
    ApiException,
    BadRequestException,
    UnauthorizedException,
    ForbiddenException,
    NotFoundException,
    ServiceException
)

RequestSerialized = Tuple[str, str, Dict[str, str], Optional[str], List[str]]

class ApiClient:
    """Generic API client for OpenAPI client library builds.

    OpenAPI generic API client. This client handles the client-
    server communication, and is invariant across implementations. Specifics of
    the methods and models for each application are generated from the OpenAPI
    templates.

    :param configuration: .Configuration object for this client
    :param header_name: a header to pass when making calls to the API.
    :param header_value: a header value to pass when making calls to
        the API.
    :param cookie: a cookie to include in the header when making calls
        to the API
    """

    PRIMITIVE_TYPES = (float, bool, bytes, str, int)
    NATIVE_TYPES_MAPPING = {
        'int': int,
        'long': int, # TODO remove as only py3 is supported?
        'float': float,
        'str': str,
        'bool': bool,
        'date': datetime.date,
        'datetime': datetime.datetime,
        'decimal': decimal.Decimal,
        'UUID': uuid.UUID,
        'object': object,
    }
    _pool = None

    def __init__(
        self,
        configuration=None,
        header_name=None,
        header_value=None,
        cookie=None
    ) -> None:
        # use default configuration if none is provided
        if configuration is None:
            configuration = Configuration.get_default()
        self.configuration = configuration

        self.rest_client = rest.RESTClientObject(configuration)
        self.default_headers = {}
        if header_name is not None:
            self.default_headers[header_name] = header_value
        self.cookie = cookie
        # Set default User-Agent.
        self.user_agent = 'OpenAPI-Generator/1.0.0/python'
        self.client_side_validation = configuration.client_side_validation

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        pass

    @property
    def user_agent(self):
        """User agent for this API client"""
        for name, value in reversed(self.default_headers.items()):
            if name.lower() == 'user-agent':
                return value
        raise KeyError('User-Agent')

    @user_agent.setter
    def user_agent(self, value):
        self._set_header(self.default_headers, 'User-Agent', value)

    def set_default_header(self, header_name, header_value):
        self._set_header(self.default_headers, header_name, header_value)


    @staticmethod
    def _set_header(headers: Dict[str, Any], name: str, value: Any) -> None:
        """Replace a header case-insensitively, retaining the winning spelling."""
        for key in list(headers):
            if key.lower() == name.lower():
                del headers[key]
        headers[name] = value

    @classmethod
    def _merge_headers(cls, *sources: Optional[Dict[str, Any]]) -> Dict[str, Any]:
        """Copy headers; later sources and later entries in each dict win."""
        headers: Dict[str, Any] = {}
        for source in sources:
            for name, value in (source or {}).items():
                cls._set_header(headers, name, value)
        return headers

    _default = None

    @classmethod
    def get_default(cls):
        """Return new instance of ApiClient.

        This method returns newly created, based on default constructor,
        object of ApiClient class or returns a copy of default
        ApiClient.

        :return: The ApiClient object.
        """
        if cls._default is None:
            cls._default = ApiClient()
        return cls._default

    @classmethod
    def set_default(cls, default):
        """Set default instance of ApiClient.

        It stores default ApiClient.

        :param default: object of ApiClient.
        """
        cls._default = default

    def param_serialize(
        self,
        method,
        resource_path,
        path_params=None,
        query_params=None,
        header_params=None,
        body=None,
        post_params=None,
        files=None, auth_settings=None,
        collection_formats=None,
        _host=None,
        _request_auth=None
    ) -> RequestSerialized:

        """Builds the HTTP request params needed by the request.
        :param method: Method to call.
        :param resource_path: Path to method endpoint.
        :param path_params: Path parameters in the url.
        :param query_params: Query parameters in the url.
        :param header_params: Header parameters to be
            placed in the request header.
        :param body: Request body.
        :param post_params dict: Request post form parameters,
            for `application/x-www-form-urlencoded`, `multipart/form-data`.
        :param auth_settings list: Auth Settings names for the request.
        :param files dict: key -> filename, value -> filepath,
            for `multipart/form-data`.
        :param collection_formats: dict of collection formats for path, query,
            header, and post parameters.
        :param _request_auth: set to override the auth_settings for an a single
                              request; this effectively ignores the authentication
                              in the spec for a single request.
        :return: tuple of form (path, http_method, query_params, header_params,
            body, post_params, files)
        """

        config = self.configuration

        # header parameters
        header_params = self._merge_headers(header_params, self.default_headers)
        if self.cookie:
            self._set_header(header_params, 'Cookie', self.cookie)
        if header_params:
            header_params = self.sanitize_for_serialization(header_params)
            header_params = dict(
                self.parameters_to_tuples(header_params,collection_formats)
            )

        # path parameters
        if path_params:
            path_params = self.sanitize_for_serialization(path_params)
            path_params = self.parameters_to_tuples(
                path_params,
                collection_formats
            )
            for k, v in path_params:
                # specified safe chars, encode everything
                resource_path = resource_path.replace(
                    '{%s}' % k,
                    quote(str(v), safe=config.safe_chars_for_path_param)
                )

        # post parameters
        if post_params or files:
            post_params = post_params if post_params else []
            post_params = self.sanitize_for_serialization(post_params)
            post_params = self.parameters_to_tuples(
                post_params,
                collection_formats
            )
            if files:
                post_params.extend(self.files_parameters(files))

        # auth setting
        self.update_params_for_auth(
            header_params,
            query_params,
            auth_settings,
            resource_path,
            method,
            body,
            request_auth=_request_auth
        )

        # body
        if body:
            body = self.sanitize_for_serialization(body)

        # request url
        if _host is None or self.configuration.ignore_operation_servers:
            url = self.configuration.host + resource_path
        else:
            # use server/host defined in path or operation instead
            url = _host + resource_path

        # query parameters
        if query_params:
            query_params = self.sanitize_for_serialization(query_params)
            url_query = self.parameters_to_url_query(
                query_params,
                collection_formats
            )
            url += "?" + url_query

        return method, url, header_params, body, post_params

    def call_api(
        self,
        method,
        url,
        header_params=None,
        body=None,
        post_params=None,
        _request_timeout=None
    ) -> rest.RESTResponse:
        """Makes the HTTP request (synchronous)
        :param method: Method to call.
        :param url: Path to method endpoint.
        :param header_params: Header parameters to be
            placed in the request header.
        :param body: Request body.
        :param post_params dict: Request post form parameters,
            for `application/x-www-form-urlencoded`, `multipart/form-data`.
        :param _request_timeout: timeout setting for this request.
        :return: RESTResponse
        """

        try:
            # perform request and return response
            response_data = self.rest_client.request(
                method, url,
                headers=header_params,
                body=body, post_params=post_params,
                _request_timeout=_request_timeout
            )

        except ApiException as e:
            raise e

        return response_data

    def response_deserialize(
        self,
        response_data: rest.RESTResponse,
        response_types_map: Optional[Dict[str, ApiResponseT]]=None
    ) -> ApiResponse[ApiResponseT]:
        """Deserializes response into an object.
        :param response_data: RESTResponse object to be deserialized.
        :param response_types_map: dict of response types.
        :return: ApiResponse
        """

        msg = "RESTResponse.read() must be called before passing it to response_deserialize()"
        assert response_data.data is not None, msg

        response_type = response_types_map.get(str(response_data.status), None)
        if not response_type and isinstance(response_data.status, int) and 100 <= response_data.status <= 599:
            # if not found, look for '1XX', '2XX', etc.
            response_type = response_types_map.get(str(response_data.status)[0] + "XX", None)

        # If the response_type has not matched (eg. did not match the previous if statements) and the default response is available, use it.
        if response_type is None and str(response_data.status) not in response_types_map \
            and (not isinstance(response_data.status, int) or not 100 <= response_data.status <= 599 or str(response_data.status)[0] + "XX" not in response_types_map) \
            and 'default' in response_types_map:
            response_type = response_types_map['default']

        # deserialize response data
        response_text = None
        return_data = None
        try:
            if response_type in ("bytearray", "bytes"):
                return_data = response_data.data
            elif response_type == "file":
                return_data = self.__deserialize_file(response_data)
            elif response_type is not None:
                match = None
                content_type = response_data.headers.get('content-type')
                if content_type is not None:
                    match = re.search(r"charset=([a-zA-Z\-\d]+)[\s;]?", content_type)
                encoding = match.group(1) if match else "utf-8"
                response_text = response_data.data.decode(encoding)
                return_data = self.deserialize(response_text, response_type, content_type)
        finally:
            if not 200 <= response_data.status <= 299:
                raise ApiException.from_response(
                    http_resp=response_data,
                    body=response_text,
                    data=return_data,
                )

        return ApiResponse(
            status_code = response_data.status,
            data = return_data,
            headers = response_data.headers,
            raw_data = response_data.data
        )

    def sanitize_for_serialization(self, obj):
        """Builds a JSON POST object.

        If obj is None, return None.
        If obj is SecretStr, return obj.get_secret_value()
        If obj is str, int, long, float, bool, return directly.
        If obj is datetime.datetime, datetime.date
            convert to string in iso8601 format.
        If obj is decimal.Decimal return string representation.
        If obj is list, sanitize each element in the list.
        If obj is dict, return the dict.
        If obj is OpenAPI model, return the properties dict.

        :param obj: The data to serialize.
        :return: The serialized form of data.
        """
        if obj is None:
            return None
        elif isinstance(obj, Enum):
            return obj.value
        elif isinstance(obj, SecretStr):
            return obj.get_secret_value()
        elif isinstance(obj, self.PRIMITIVE_TYPES):
            return obj
        elif isinstance(obj, uuid.UUID):
            return str(obj)
        elif isinstance(obj, list):
            return [
                self.sanitize_for_serialization(sub_obj) for sub_obj in obj
            ]
        elif isinstance(obj, tuple):
            return tuple(
                self.sanitize_for_serialization(sub_obj) for sub_obj in obj
            )
        elif isinstance(obj, (datetime.datetime, datetime.date)):
            return obj.isoformat()
        elif isinstance(obj, decimal.Decimal):
            return str(obj)
        elif isinstance(obj, dict):
            return {
                key: self.sanitize_for_serialization(val)
                for key, val in obj.items()
            }

        # Convert model obj to dict except
        # attributes `openapi_types`, `attribute_map`
        # and attributes which value is not None.
        # Convert attribute name to json key in
        # model definition for request.
        if hasattr(obj, 'to_dict') and callable(getattr(obj, 'to_dict')):
            obj_dict = obj.to_dict()
        else:
            obj_dict = obj.__dict__

        return self.sanitize_for_serialization(obj_dict)


    def deserialize(self, response_text: str, response_type: str, content_type: Optional[str]):
        """Deserializes response into an object.

        :param response: RESTResponse object to be deserialized.
        :param response_type: class literal for
            deserialized object, or string of class name.
        :param content_type: content type of response.

        :return: deserialized object.
        """

        # fetch data from response object
        if content_type is None:
            try:
                data = json.loads(response_text)
            except ValueError:
                data = response_text
        elif re.match(r'^application/(json|[\w!#$&.+\-^_]+\+json)\s*(;|$)', content_type, re.IGNORECASE):
            if response_text == "":
                data = ""
            else:
                data = json.loads(response_text)
        elif re.match(r'^text\/[a-z.+-]+\s*(;|$)', content_type, re.IGNORECASE):
            data = response_text
        else:
            raise ApiException(
                status=0,
                reason="Unsupported content type: {0}".format(content_type)
            )

        return self.__deserialize(data, response_type)

    def __deserialize(self, data, klass):
        """Deserializes dict, list, str into an object.

        :param data: dict, list or str.
        :param klass: class literal, or string of class name.

        :return: object.
        """
        if data is None:
            return None

        if isinstance(klass, str):
            if klass.startswith('Optional['):
                m = re.match(r'Optional\[(.*)]', klass)
                assert m is not None, "Malformed Optional type definition"
                # data is not None here, so the optionality is already resolved
                return self.__deserialize(data, m.group(1))

            if klass.startswith('List['):
                m = re.match(r'List\[(.*)]', klass)
                assert m is not None, "Malformed List type definition"
                sub_kls = m.group(1)
                return [self.__deserialize(sub_data, sub_kls)
                        for sub_data in data]

            if klass.startswith('Dict['):
                m = re.match(r'Dict\[([^,]*), (.*)]', klass)
                assert m is not None, "Malformed Dict type definition"
                sub_kls = m.group(2)
                return {k: self.__deserialize(v, sub_kls)
                        for k, v in data.items()}

            # convert str to class
            if klass in self.NATIVE_TYPES_MAPPING:
                klass = self.NATIVE_TYPES_MAPPING[klass]
            else:
                klass = getattr(kotobaya_client.models, klass)

        if klass in self.PRIMITIVE_TYPES:
            return self.__deserialize_primitive(data, klass)
        elif klass is object:
            return self.__deserialize_object(data)
        elif klass is datetime.date:
            return self.__deserialize_date(data)
        elif klass is datetime.datetime:
            return self.__deserialize_datetime(data)
        elif klass is decimal.Decimal:
            return decimal.Decimal(data)
        elif klass is uuid.UUID:
            return uuid.UUID(data)
        elif issubclass(klass, Enum):
            return self.__deserialize_enum(data, klass)
        else:
            return self.__deserialize_model(data, klass)

    def parameters_to_tuples(self, params, collection_formats):
        """Get parameters as list of tuples, formatting collections.

        :param params: Parameters as dict or list of two-tuples
        :param dict collection_formats: Parameter collection formats
        :return: Parameters as list of tuples, collections formatted
        """
        new_params: List[Tuple[str, str]] = []
        if collection_formats is None:
            collection_formats = {}
        for k, v in params.items() if isinstance(params, dict) else params:
            if isinstance(v, bool):
                v = str(v).lower()
            if k in collection_formats:
                collection_format = collection_formats[k]
                if collection_format == 'multi':
                    new_params.extend(
                        (k, str(value).lower() if isinstance(value, bool) else value)
                        for value in v
                    )
                else:
                    if collection_format == 'ssv':
                        delimiter = ' '
                    elif collection_format == 'tsv':
                        delimiter = '\t'
                    elif collection_format == 'pipes':
                        delimiter = '|'
                    else:  # csv is the default
                        delimiter = ','
                    new_params.append(
                        (k, delimiter.join(
                            str(value).lower() if isinstance(value, bool) else str(value)
                            for value in v)))
            else:
                new_params.append((k, v))
        return new_params

    def explode_query_object(self, name, obj):
        """form style, explode: one query parameter per entry, keyed by the property name; a list repeats the name, None is left out"""
        obj = self.sanitize_for_serialization(obj)
        if not isinstance(obj, dict):
            obj = {name: obj}
        return [(k, item) for k, v in obj.items() for item in (v if isinstance(v, (list, tuple)) else [v]) if item is not None]

    def parameters_to_url_query(self, params, collection_formats):
        """Get parameters as list of tuples, formatting collections.

        :param params: Parameters as dict or list of two-tuples
        :param dict collection_formats: Parameter collection formats
        :return: URL query string (e.g. a=Hello%20World&b=123)
        """
        new_params: List[Tuple[str, str]] = []
        if collection_formats is None:
            collection_formats = {}
        for k, v in params.items() if isinstance(params, dict) else params:
            if isinstance(v, bool):
                v = str(v).lower()
            if isinstance(v, (int, float)):
                v = str(v)
            if isinstance(v, dict):
                v = json.dumps(v)

            # a collection format applies only to a list; an exploded entry may share a declared array parameter's name
            if k in collection_formats and isinstance(v, (list, tuple)):
                collection_format = collection_formats[k]
                if collection_format == 'multi':
                    new_params.extend(
                        (quote(str(k)), quote(str(value).lower() if isinstance(value, bool) else str(value)))
                        for value in v
                    )
                else:
                    if collection_format == 'ssv':
                        delimiter = ' '
                    elif collection_format == 'tsv':
                        delimiter = '\t'
                    elif collection_format == 'pipes':
                        delimiter = '|'
                    else:  # csv is the default
                        delimiter = ','
                    new_params.append(
                        (quote(str(k)), delimiter.join(
                            quote(str(value).lower() if isinstance(value, bool) else str(value))
                            for value in v))
                    )
            else:
                # names are quoted too: an exploded object's names are runtime data
                new_params.append((quote(str(k)), quote(str(v))))

        return "&".join(["=".join(map(str, item)) for item in new_params])

    def files_parameters(
        self,
        files: Dict[str, Union[str, bytes, List[str], List[bytes], Tuple[str, bytes]]],
    ):
        """Builds form parameters.

        :param files: File parameters.
        :return: Form parameters with files.
        """
        params = []
        for k, v in files.items():
            if isinstance(v, str):
                with open(v, 'rb') as f:
                    filename = os.path.basename(f.name)
                    filedata = f.read()
            elif isinstance(v, bytes):
                filename = k
                filedata = v
            elif isinstance(v, tuple):
                filename, filedata = v
            elif isinstance(v, list):
                for file_param in v:
                    params.extend(self.files_parameters({k: file_param}))
                continue
            else:
                raise ValueError("Unsupported file value")
            mimetype = (
                mimetypes.guess_type(filename)[0]
                or 'application/octet-stream'
            )
            params.append(
                tuple([k, tuple([filename, filedata, mimetype])])
            )
        return params

    def select_header_accept(self, accepts: List[str]) -> Optional[str]:
        """Returns `Accept` based on an array of accepts provided.

        :param accepts: List of headers.
        :return: Accept (e.g. application/json).
        """
        if not accepts:
            return None

        for accept in accepts:
            if re.search('json', accept, re.IGNORECASE):
                return accept

        return accepts[0]

    def select_header_content_type(self, content_types):
        """Returns `Content-Type` based on an array of content_types provided.

        :param content_types: List of content-types.
        :return: Content-Type (e.g. application/json).
        """
        if not content_types:
            return None

        for content_type in content_types:
            if re.search('json', content_type, re.IGNORECASE):
                return content_type

        return content_types[0]

    def update_params_for_auth(
        self,
        headers,
        queries,
        auth_settings,
        resource_path,
        method,
        body,
        request_auth=None
    ) -> None:
        """Updates header and query params based on authentication setting.

        :param headers: Header parameters dict to be updated.
        :param queries: Query parameters tuple list to be updated.
        :param auth_settings: Authentication setting identifiers list.
        :resource_path: A string representation of the HTTP request resource path.
        :method: A string representation of the HTTP request method.
        :body: A object representing the body of the HTTP request.
        The object type is the return value of sanitize_for_serialization().
        :param request_auth: if set, the provided settings will
                             override the token in the configuration.
        """
        if not auth_settings:
            return

        if request_auth:
            self._apply_auth_params(
                headers,
                queries,
                resource_path,
                method,
                body,
                request_auth
            )
        else:
            for auth in auth_settings:
                auth_setting = self.configuration.auth_settings().get(auth)
                if auth_setting:
                    self._apply_auth_params(
                        headers,
                        queries,
                        resource_path,
                        method,
                        body,
                        auth_setting
                    )

    def _apply_auth_params(
        self,
        headers,
        queries,
        resource_path,
        method,
        body,
        auth_setting
    ) -> None:
        """Updates the request parameters based on a single auth_setting

        :param headers: Header parameters dict to be updated.
        :param queries: Query parameters tuple list to be updated.
        :resource_path: A string representation of the HTTP request resource path.
        :method: A string representation of the HTTP request method.
        :body: A object representing the body of the HTTP request.
        The object type is the return value of sanitize_for_serialization().
        :param auth_setting: auth settings for the endpoint
        """
        if auth_setting['in'] == 'cookie':
            for key in list(headers):
                if key.lower() == 'cookie':
                    self._set_header(headers, 'Cookie', headers[key])
                    break
            if not headers.get('Cookie'):
                headers['Cookie'] = ""
            else:
                headers['Cookie'] += "; "
            # Account for cookie value containing spaces and special characters, excluding base64 delimiters
            cookie_value = quote(str(auth_setting['value']), safe="!#$%&'()*+-./:<=>?@[]^_`{|}~%+/=")
            headers['Cookie'] += f"{auth_setting['key']}={cookie_value}"
        elif auth_setting['in'] == 'header':
            if auth_setting['type'] != 'http-signature':
                self._set_header(headers, auth_setting['key'], auth_setting['value'])
        elif auth_setting['in'] == 'query':
            queries.append((auth_setting['key'], auth_setting['value']))
        else:
            raise ApiValueError(
                'Authentication token must be in `query` or `header`'
            )

    def __deserialize_file(self, response):
        """Deserializes body to file

        Saves response body into a file in a temporary folder,
        using the filename from the `Content-Disposition` header if provided.

        handle file downloading
        save response body into a tmp file and return the instance

        :param response:  RESTResponse.
        :return: file path.
        """
        fd, path = tempfile.mkstemp(dir=self.configuration.temp_folder_path)
        os.close(fd)
        os.remove(path)

        content_disposition = response.headers.get("Content-Disposition")
        if content_disposition:
            m = re.search(
                r'filename=[\'"]?([^\'"\s]+)[\'"]?',
                content_disposition
            )
            assert m is not None, "Unexpected 'content-disposition' header value"
            filename = os.path.basename(m.group(1))  # Strip any directory traversal
            if filename in ("", ".", ".."):  # fall back to tmp filename
                filename = os.path.basename(path)
            path = os.path.join(os.path.dirname(path), filename)

        with open(path, "wb") as f:
            f.write(response.data)

        return path

    def __deserialize_primitive(self, data, klass):
        """Deserializes string to primitive type.

        :param data: str.
        :param klass: class literal.

        :return: int, long, float, str, bool.
        """
        try:
            return klass(data)
        except UnicodeEncodeError:
            return str(data)
        except TypeError:
            return data

    def __deserialize_object(self, value):
        """Return an original value.

        :return: object.
        """
        return value

    def __deserialize_date(self, string):
        """Deserializes string to date.

        :param string: str.
        :return: date.
        """
        try:
            return parse(string).date()
        except ImportError:
            return string
        except ValueError:
            raise rest.ApiException(
                status=0,
                reason="Failed to parse `{0}` as date object".format(string)
            )

    def __deserialize_datetime(self, string):
        """Deserializes string to datetime.

        The string should be in iso8601 datetime format.

        :param string: str.
        :return: datetime.
        """
        try:
            return parse(string)
        except ImportError:
            return string
        except ValueError:
            raise rest.ApiException(
                status=0,
                reason=(
                    "Failed to parse `{0}` as datetime object"
                    .format(string)
                )
            )

    def __deserialize_enum(self, data, klass):
        """Deserializes primitive type to enum.

        :param data: primitive type.
        :param klass: class literal.
        :return: enum value.
        """
        try:
            return klass(data)
        except ValueError:
            raise rest.ApiException(
                status=0,
                reason=(
                    "Failed to parse `{0}` as `{1}`"
                    .format(data, klass)
                )
            )

    def __deserialize_model(self, data, klass):
        """Deserializes list or dict to model.

        :param data: dict, list.
        :param klass: class literal.
        :return: model object.
        """

        return klass.from_dict(data)
