/* tslint:disable */
/* eslint-disable */
/**
 * Kotobaya API
 * Versioned REST/JSON interface at `/api/v1`. The web app uses nothing else; the Android app uses `sync`, `blobs`, `dict/release`, `devices/pair`, `devices/self` and whatever conveniences it likes.  Implementers: this file is the contract. If something you need is missing or wrong, do not work around it; return `CONTRACT_CHANGE_REQUEST`. Additive changes stay in v1; breaking changes go to `/api/v2`.  ## 1. Conventions  - **JSON.** Request and response bodies are `application/json; charset=utf-8` unless stated.   Property names are `snake_case` and equal the column names of `contracts/db/V1__init.sql`   wherever a property mirrors a column; the column `COMMENT` is then the definition. - **Unknown properties.** Servers reject unknown request properties and unknown query   parameters with `422 validation_failed`. Clients MUST ignore unknown response properties and   MUST treat unknown enum values in responses as \"other\" (v1 may add both). - **Absent vs null.** In `PATCH` bodies an absent property means \"leave unchanged\"; `null` means   \"set to NULL\" where the property is nullable and is a validation error otherwise.   Responses always include every documented property; nullable ones are `null`, never absent,   unless the property is documented as \"omitted when ...\". - **Ids.** Row ids are UUIDv7 in canonical lowercase `8-4-4-4-12` text. Uppercase ids in   requests are rejected with `422`. \"Entry\" always means a JMdict sequence number (`seq`,   JSON integer). - **Time.** Instants are RFC 3339 UTC strings with millisecond precision and a `Z` suffix,   e.g. `2026-11-12T09:30:00.000Z`. Servers accept any RFC 3339 offset on input and normalise   to UTC. A \"study day\" is a local date `YYYY-MM-DD` computed from the settings   `scheduler.timezone` and `scheduler.day_rollover_hour`: study day D covers local time   `D rollover:00` (inclusive) to `D+1 rollover:00` (exclusive). - **Text offsets** into sentence text are UTF-16 code units, half-open `[start, end)`. - **Blobs** (audio, images, uploads, snapshots) are addressed by the lowercase hex SHA-256 of   their bytes and transferred through `/blobs/{hash}`, never inline in JSON.  ## 2. Authentication and scopes  Three credential kinds, all rows of `app.api_token` (stored hashed):  | Kind | How obtained | Sent as | Scopes | |---|---|---|---| | `session` | `POST /auth/login` | cookie `kotobaya_session` (HTTP-only) | all five | | `device` | `POST /devices/pair` | `Authorization: Bearer kbd_...` | `read`, `write`, `review` | | `personal` | `POST /tokens` | `Authorization: Bearer kbp_...` | chosen at creation |  Token strings are `<3-letter kind prefix>_<40 characters of [A-Za-z0-9]>` (`kbs_`, `kbd_`, `kbp_`), generated from a CSPRNG. `token_hash` = lowercase hex SHA-256 of the whole string; `token_prefix` = its first 8 characters. A token is valid iff `revoked_at IS NULL` and (`expires_at IS NULL` or `expires_at > now()`) and, when it has a `device_id`, that device is not revoked. Validity is checked on every request (no caching longer than 5 seconds), so revocation takes effect at once.  Scopes are independent (no scope implies another):  | Scope | Grants | |---|---| | `read` | Every `GET` of learning, media and reference data; `POST /parse`; `GET /events`; `GET /sync/pull`; exports. | | `write` | Create, change, delete notes, cards, decks, grammar, note types, presets, settings, word status, parse overrides, media metadata; upload blobs; `POST /sync/push` (non-review ops). | | `review` | `POST /review/answer`, `POST /review/undo`, `POST /drill/session`, `POST /cards/bulk/schedule`; `review.*` ops inside `POST /sync/push` (per op; without it the op is rejected with `insufficient_scope`). | | `import` | Start and cancel import, parse, dictionary and optimizer jobs; upload blobs. | | `admin` | Devices, pairing, tokens (a device\'s own row: `/devices/self` with `read`/`write`). |  Each operation lists the scope it needs in its `security` requirement: the array after the scheme name. When two alternatives are listed for one scheme the caller needs either. A request with no or an invalid credential gets `401 unauthenticated`; a valid credential lacking the scope gets `403 insufficient_scope`. Operations with `security: []` are public. An empty scope array (`bearerAuth: []`, `sessionCookie: []`) means any valid credential, whatever its scopes: only `GET /auth/session` and `POST /client-errors`.  There are exactly two places where who may call is not just \"scheme + scope\", and both are declared in the operation, not only in prose: - `GET/PATCH /devices/self` (`read` / `write`, bearer only): the token must be bound to a   device, else `403 device_required`. A device never needs `admin` to manage its own row;   `/devices/{device_id}` is admin only. - `POST /ankiconnect` (`x-auth: body-key`): the token may instead be sent in the JSON body   property `key`; the empty `{}` alternative in its `security` only signals that no header is   needed. The scope for `key` is `x-body-key-scope`.  **CSRF.** A request authenticated by the session cookie whose method is not `GET` or `HEAD` MUST carry the header `X-Kotobaya-Csrf: 1`; otherwise `403 csrf_required`. The cookie is set with `HttpOnly; Secure; SameSite=Strict; Path=/api`. The server sends no CORS headers (the web app is served from the same origin). If both a cookie and an `Authorization` header are present, the header wins and the cookie is ignored.  **Audit.** Every request is logged with `token.id` and `token.name` (structured server log; there is no audit table or endpoint in v1). Ops record the token in `change.token_id`.  ## 3. Writes are sync ops  Learning data is only ever changed by applying sync ops (`contracts/sync`). Every REST operation that changes learning data is a convenience wrapper: the server builds the op(s) named in the operation\'s `x-emits-ops`, applies them through the same op-apply path as `POST /sync/push` in one transaction, and they then appear in `GET /sync/pull` for every device. Op names are those of `contracts/db/SCHEMA.md` section 4; `contracts/sync` is authoritative for names and payloads.  For an op the server builds on behalf of a REST call:  - `op_id` is a fresh UUIDv7; origin `device_id` is the **server device**   `00000000-0000-7000-8000-000000000001` (also for session and device tokens: only one party   ever generates HLCs for a given device id); `hlc` comes from the server\'s HLC clock;   `change.token_id` is the calling token. - A property absent from a `PATCH` body produces no register write. A property present with   the value the row already has still produces a write (it bumps the register\'s clock). - If a call changes nothing (documented per operation, e.g. deleting an already deleted   note), no op is emitted and the response is still successful. - **Never an invalid op (C-6).** If a request would make the server build an op that fails   its schema or an op-level rule in `contracts/sync` - a tag containing ECMA-262 whitespace   (e.g. U+3000 or U+00A0), U+0000 in any string (field values and open JSON objects   included), an integer above its op maximum (`2147483647` for int4 columns) or an instant   after `9999-12-31T23:59:59.999Z` - the call is `422 validation_failed` with a field error   at that request property, code `invalid_value` (or `out_of_range` / `too_long` where the   operation\'s own request bound fails first). Nothing is emitted; never a `500`.   SYNC.md 2.1 \"Storable values\" and \"Input that would mint an invalid op\". - Responses to mutating calls carry the header `Kotobaya-Seq`: the `change.seq` of the last op   the call emitted. A client holding a local replica knows it has seen the effect once its   pull cursor is at or past that value. The header is absent when no op was emitted.  **Idempotent creation.** Create bodies accept an optional client-generated `id` (UUIDv7). Re-sending a create with an `id` that already exists returns the existing resource with status `200` instead of `201` and emits nothing, whatever the rest of the body says. Clients that may retry MUST send `id`. When `id` is omitted the server generates one. `POST /review/answer` requires `id` (a duplicate review would corrupt scheduling); `POST /media/import` takes the same optional idempotency id as `media_id`.  The existence check and the emit are **one transaction that takes advisory lock `7033001` before the check** and holds it until commit (the lock `POST /sync/push` takes per op, SYNC.md 5.2): check whether `id` exists; if not, mint, apply and log the op(s) - for `createNote` the `note.created` and every `card.created` - then commit. Without the lock two concurrent retries of the same create both pass the check; the second `note.created` is a no-op on the row (SYNC.md 7.2) but its `card.created` ops carry fresh card ids, so the note would get duplicate cards. The same holds for every other id-idempotent call (`POST /review/answer`, `POST /review/undo`, `POST /media/import`), and a pushed op that creates the same id races safely because it takes the same lock.  **Tombstones and merges.** Deleted learning rows are tombstones (`deleted_at` set). `GET` by id of a deleted row returns `410 gone` unless `include_deleted=true`; lists omit deleted rows unless `include_deleted=true`. A note or card that lost a merge (`merged_into_id` set) is answered with its survivor: `GET`, `PATCH` and review calls addressed to the loser\'s id act on the survivor and the response body is the survivor (its `id` differs from the requested id; the response has `Content-Location` with the survivor\'s URL).  ## 4. Pagination  List operations that take `limit` and `cursor` return `{\"items\": [...], \"next_cursor\": ...}`. `next_cursor` is an opaque string, or `null` on the last page. Pass it back unchanged as `cursor` together with the same other parameters; changing any other parameter while passing a cursor is `422 validation_failed` (`pointer` `/cursor`). `limit` defaults to 50, maximum 500. Order is stable and documented per operation; rows created while paging may or may not appear.  **Cursor semantics (keyset, no snapshot).** A cursor encodes the sort-key values and the `id` of the last row returned; the next page is the rows strictly after that tuple in the current order, evaluated against the data at the time of that request. The order is not snapshotted. Consequently, while a client pages through a list sorted by a mutable key (`due_at`, `updated_at`, `lapses`, ...): - a row whose key changes so that it moves from after the cursor to before it is **skipped**; - a row whose key changes so that it moves from before the cursor to after it is **returned   again** (with its new values); - a row deleted while paging disappears; one created may or may not appear. Rows that do not change are returned exactly once. Clients MUST de-duplicate by `id` (last occurrence wins). A client that needs an exact set (bulk actions, exports) uses an `id`-sorted list where offered, `POST /cards/bulk` with `query`, or `GET /export`. Lists sorted by an immutable key (`id`, `created_at`) have no skips or repeats. An empty result is `{\"items\": [], \"next_cursor\": null}` with status `200`.  ## 5. Errors  Every non-2xx response has the body `application/problem+json` (RFC 9457), schema `Problem`. `code` is the stable machine-readable identifier; `type` is `urn:kotobaya:problem:<code>`.  | Status | `code` | When | |---|---|---| | 400 | `invalid_request` | Body is not valid JSON, wrong content type framing, malformed header; `POST /sync/push` body not valid against `PushRequestLenient`. | | 401 | `unauthenticated` | No credential, or it is unknown, expired or revoked. | | 401 | `invalid_credentials` | Wrong password or TOTP code (`POST /auth/login`). | | 401 | `totp_required` | TOTP is enabled and `totp_code` was not sent. | | 401 | `invalid_pairing_code` | Pairing code unknown, expired or already used. | | 403 | `insufficient_scope` | Credential lacks the scope; `required_scope` names it. | | 403 | `csrf_required` | Cookie-authenticated write without `X-Kotobaya-Csrf`. | | 403 | `device_required` | `POST /sync/push` or `/devices/self` with a credential that has no `device_id`. | | 404 | `not_found` | No such resource (also: id of another resource kind). | | 409 | `already_exists` | Natural-key collision; `existing_id` names the holder. | | 409 | `builtin_immutable` | Deleting a built-in note type or the Default preset, or removing a built-in field. | | 409 | `in_use` | Deleting a note type that live notes use. | | 409 | `deck_read_only` | Changing items or deleting a `media`/`list` deck through deck endpoints. | | 409 | `not_applicable` | The resource kind does not support the operation (e.g. items of a smart deck). | | 409 | `card_not_reviewable` | Card is suspended. | | 409 | `job_not_cancellable` | Job already finished. | | 410 | `gone` | Tombstoned row requested without `include_deleted=true`. | | 413 | `payload_too_large` | Body over the limit in `GET /meta`. On `POST /sync/push` only for the whole batch (the client halves it); one op over `max_op_bytes` is a per-op `op_too_large` result instead. `POST /client-errors`: body over 1 MiB or more than 20 items. | | 415 | `unsupported_media_type` | Wrong `Content-Type`. | | 422 | `validation_failed` | Well-formed but invalid; `errors[]` lists each problem. | | 422 | `invalid_query` | Card search string does not parse; `errors[0].position` is the UTF-16 offset. | | 422 | `bulk_too_large` | Bulk selector matched more than 5000 cards. | | 422 | `hash_mismatch` | Uploaded bytes do not hash to the `{hash}` in the path. | | 429 | `rate_limited` | Too many login or pairing attempts, or too many client error reports (`POST /client-errors`); see `Retry-After`. | | 500 | `internal` | Bug. Safe to retry idempotent calls. | | 503 | `unavailable` | Database or object store unreachable. Retry with backoff. |  Check order: 401, then 403, then 404/410, then 415/400, then 422, then 409.  ## 6. Events  `GET /events` is a server-sent events stream; see that operation. Event payload schemas are `Event*` in components. 
 *
 * The version of the OpenAPI document: 1.0.0
 * 
 *
 * NOTE: This class is auto generated by OpenAPI Generator (https://openapi-generator.tech).
 * https://openapi-generator.tech
 * Do not edit the class manually.
 */

import * as runtime from '../runtime';
import {
    type Device,
    DeviceFromJSON,
    DeviceToJSON,
} from '../models/Device';
import {
    type DeviceList,
    DeviceListFromJSON,
    DeviceListToJSON,
} from '../models/DeviceList';
import {
    type DevicePatch,
    DevicePatchFromJSON,
    DevicePatchToJSON,
} from '../models/DevicePatch';
import {
    type PairRequest,
    PairRequestFromJSON,
    PairRequestToJSON,
} from '../models/PairRequest';
import {
    type PairResponse,
    PairResponseFromJSON,
    PairResponseToJSON,
} from '../models/PairResponse';
import {
    type Pairing,
    PairingFromJSON,
    PairingToJSON,
} from '../models/Pairing';
import {
    type Problem,
    ProblemFromJSON,
    ProblemToJSON,
} from '../models/Problem';

export interface GetDeviceRequest {
    /**
     * Device id.
     */
    deviceId: string;
}

export interface PairDeviceRequest {
    /**
     * 
     */
    pairRequest: PairRequest;
}

export interface RevokeDeviceRequest {
    /**
     * Device id.
     */
    deviceId: string;
}

export interface UpdateDeviceRequest {
    /**
     * Device id.
     */
    deviceId: string;
    /**
     * 
     */
    devicePatch: DevicePatch;
}

export interface UpdateOwnDeviceRequest {
    /**
     * 
     */
    devicePatch: DevicePatch;
}

/**
 * 
 */
export class DevicesApi extends runtime.BaseAPI {

    /**
     * Creates request options for createPairing without sending the request
     */
    async createPairingRequestOpts(): Promise<runtime.RequestOpts> {
        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};

        if (this.configuration && this.configuration.accessToken) {
            const token = this.configuration.accessToken;
            const tokenString = await token("bearerAuth", ["admin"]);

            if (tokenString) {
                headerParameters["Authorization"] = `Bearer ${tokenString}`;
            }
        }

        let urlPath = `/devices/pairings`;

        return {
            path: urlPath,
            method: 'POST',
            headers: headerParameters,
            query: queryParameters,
        };
    }

    /**
     * Returns a code valid for 10 minutes and usable once. Pairing codes are held by the server (in memory is acceptable in v1: a restart invalidates outstanding codes; the response to `POST /devices/pair` is then `401 invalid_pairing_code` and the user creates a new code). Any number of codes may be outstanding. The QR code encodes `pairing_uri` verbatim. 
     * Create a one-time pairing code (shown as a QR code by the web app).
     */
    async createPairingRaw(initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<Pairing>> {
        const requestOptions = await this.createPairingRequestOpts();
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => PairingFromJSON(jsonValue));
    }

    /**
     * Returns a code valid for 10 minutes and usable once. Pairing codes are held by the server (in memory is acceptable in v1: a restart invalidates outstanding codes; the response to `POST /devices/pair` is then `401 invalid_pairing_code` and the user creates a new code). Any number of codes may be outstanding. The QR code encodes `pairing_uri` verbatim. 
     * Create a one-time pairing code (shown as a QR code by the web app).
     */
    async createPairing(initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<Pairing> {
        const response = await this.createPairingRaw(initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for getDevice without sending the request
     */
    async getDeviceRequestOpts(requestParameters: GetDeviceRequest): Promise<runtime.RequestOpts> {
        if (requestParameters['deviceId'] == null) {
            throw new runtime.RequiredError(
                'deviceId',
                'Required parameter "deviceId" was null or undefined when calling getDevice().'
            );
        }

        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};

        if (this.configuration && this.configuration.accessToken) {
            const token = this.configuration.accessToken;
            const tokenString = await token("bearerAuth", ["admin"]);

            if (tokenString) {
                headerParameters["Authorization"] = `Bearer ${tokenString}`;
            }
        }

        let urlPath = `/devices/{device_id}`;
        urlPath = urlPath.replace('{device_id}', encodeURIComponent(String(requestParameters['deviceId'])));

        return {
            path: urlPath,
            method: 'GET',
            headers: headerParameters,
            query: queryParameters,
        };
    }

    /**
     * Admin only. A device reads its own row through `GET /devices/self`.
     * One device.
     */
    async getDeviceRaw(requestParameters: GetDeviceRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<Device>> {
        const requestOptions = await this.getDeviceRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => DeviceFromJSON(jsonValue));
    }

    /**
     * Admin only. A device reads its own row through `GET /devices/self`.
     * One device.
     */
    async getDevice(requestParameters: GetDeviceRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<Device> {
        const response = await this.getDeviceRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for getOwnDevice without sending the request
     */
    async getOwnDeviceRequestOpts(): Promise<runtime.RequestOpts> {
        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};

        if (this.configuration && this.configuration.accessToken) {
            const token = this.configuration.accessToken;
            const tokenString = await token("bearerAuth", ["read"]);

            if (tokenString) {
                headerParameters["Authorization"] = `Bearer ${tokenString}`;
            }
        }

        let urlPath = `/devices/self`;

        return {
            path: urlPath,
            method: 'GET',
            headers: headerParameters,
            query: queryParameters,
        };
    }

    /**
     * For tokens bound to a device (`device` tokens, and `personal` tokens created with `with_device`). Any other credential (session cookie, personal token without a device) gets `403 device_required`. Needs only `read`, so a phone can read its own row without the `admin` scope. A revoked device\'s tokens are invalid, so this never returns a revoked row. `self` is a literal path segment and takes precedence over `/devices/{device_id}`. 
     * The device the calling token is bound to.
     */
    async getOwnDeviceRaw(initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<Device>> {
        const requestOptions = await this.getOwnDeviceRequestOpts();
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => DeviceFromJSON(jsonValue));
    }

    /**
     * For tokens bound to a device (`device` tokens, and `personal` tokens created with `with_device`). Any other credential (session cookie, personal token without a device) gets `403 device_required`. Needs only `read`, so a phone can read its own row without the `admin` scope. A revoked device\'s tokens are invalid, so this never returns a revoked row. `self` is a literal path segment and takes precedence over `/devices/{device_id}`. 
     * The device the calling token is bound to.
     */
    async getOwnDevice(initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<Device> {
        const response = await this.getOwnDeviceRaw(initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for listDevices without sending the request
     */
    async listDevicesRequestOpts(): Promise<runtime.RequestOpts> {
        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};

        if (this.configuration && this.configuration.accessToken) {
            const token = this.configuration.accessToken;
            const tokenString = await token("bearerAuth", ["admin"]);

            if (tokenString) {
                headerParameters["Authorization"] = `Bearer ${tokenString}`;
            }
        }

        let urlPath = `/devices`;

        return {
            path: urlPath,
            method: 'GET',
            headers: headerParameters,
            query: queryParameters,
        };
    }

    /**
     * Not paginated (a handful of rows). Ordered by `paired_at` ascending, then `id`.
     * List devices, including revoked ones and the built-in server device.
     */
    async listDevicesRaw(initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<DeviceList>> {
        const requestOptions = await this.listDevicesRequestOpts();
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => DeviceListFromJSON(jsonValue));
    }

    /**
     * Not paginated (a handful of rows). Ordered by `paired_at` ascending, then `id`.
     * List devices, including revoked ones and the built-in server device.
     */
    async listDevices(initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<DeviceList> {
        const response = await this.listDevicesRaw(initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for pairDevice without sending the request
     */
    async pairDeviceRequestOpts(requestParameters: PairDeviceRequest): Promise<runtime.RequestOpts> {
        if (requestParameters['pairRequest'] == null) {
            throw new runtime.RequiredError(
                'pairRequest',
                'Required parameter "pairRequest" was null or undefined when calling pairDevice().'
            );
        }

        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};

        headerParameters['Content-Type'] = 'application/json';


        let urlPath = `/devices/pair`;

        return {
            path: urlPath,
            method: 'POST',
            headers: headerParameters,
            query: queryParameters,
            body: PairRequestToJSON(requestParameters['pairRequest']),
        };
    }

    /**
     * Public (the code is the credential). In one transaction: consumes the code, upserts the `device` row with the client-generated `device_id`, revokes every earlier token of that device, inserts an `api_token` of kind `device` (name = device name, scopes `read`, `write`, `review`, no expiry) and returns the token string - the only time it is ever shown. Emits the event `device.paired`.  Edge cases: - **Device id reuse** (contracts/sync/SYNC.md 3.3). A device id is the node id of that   replica\'s HLC, so it may be reused only by the same install with its local database   intact (its `hlc_last` never went backwards). Clients MUST mint a new UUIDv7   `device_id` on first launch, after a reinstall, after clearing app data and after   restoring the local database from a backup, and MUST send their existing id only when   re-pairing an intact install (typically after the device was revoked, or its token   was lost). The server cannot detect every violation; it checks what it can (next item). - **`hlc_last` check** (optional field). A client re-pairing an existing `device_id`   SHOULD send its persisted `sync_state.hlc_last`. Inside the pairing transaction, if   `hlc_last` is present and is below (byte-wise, SYNC.md 3.1) the greatest `change.hlc`   logged with `device_id` = this device, the local database went backwards (typically   an Android Auto Backup restore, or a copied database) and reusing the id could repeat   HLCs: `422 validation_failed` with `errors[0].pointer = /hlc_last`,   `errors[0].code = hlc_regressed`. The transaction is rolled back, so the code is   **not** consumed; the client mints a new UUIDv7 `device_id`, resets its replica state   as for a new device, and pairs again with the same code. `hlc_last` whose device part   is not `device_id` is also `422 validation_failed` (`code = invalid_value`). Equal to   the greatest logged HLC is fine (it is the last op the device minted). Absent, or a   `device_id` that has never logged an op: no check. The server reads the maximum under   advisory lock `7033001`, so a concurrent push cannot slip between the check and the commit. - `device_id` already exists and the request re-pairs that intact install: the row is   reused, `name`, `kind`, `push_topic` are overwritten, `revoked_at` is cleared,   `paired_at` is set to now. Ops the device pushed earlier stay; its unpushed outbox can   now be pushed. - `device_id` is the server device id: `422 validation_failed`. - Both checks above run before the code is consumed; on `422` the code stays valid. - Code unknown, expired or used: `401 invalid_pairing_code`. More than 10 failures from   one client address in 15 minutes: `429 rate_limited`. - Two requests race with one code: exactly one succeeds. 
     * Exchange a pairing code for a long-lived device token.
     */
    async pairDeviceRaw(requestParameters: PairDeviceRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<PairResponse>> {
        const requestOptions = await this.pairDeviceRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => PairResponseFromJSON(jsonValue));
    }

    /**
     * Public (the code is the credential). In one transaction: consumes the code, upserts the `device` row with the client-generated `device_id`, revokes every earlier token of that device, inserts an `api_token` of kind `device` (name = device name, scopes `read`, `write`, `review`, no expiry) and returns the token string - the only time it is ever shown. Emits the event `device.paired`.  Edge cases: - **Device id reuse** (contracts/sync/SYNC.md 3.3). A device id is the node id of that   replica\'s HLC, so it may be reused only by the same install with its local database   intact (its `hlc_last` never went backwards). Clients MUST mint a new UUIDv7   `device_id` on first launch, after a reinstall, after clearing app data and after   restoring the local database from a backup, and MUST send their existing id only when   re-pairing an intact install (typically after the device was revoked, or its token   was lost). The server cannot detect every violation; it checks what it can (next item). - **`hlc_last` check** (optional field). A client re-pairing an existing `device_id`   SHOULD send its persisted `sync_state.hlc_last`. Inside the pairing transaction, if   `hlc_last` is present and is below (byte-wise, SYNC.md 3.1) the greatest `change.hlc`   logged with `device_id` = this device, the local database went backwards (typically   an Android Auto Backup restore, or a copied database) and reusing the id could repeat   HLCs: `422 validation_failed` with `errors[0].pointer = /hlc_last`,   `errors[0].code = hlc_regressed`. The transaction is rolled back, so the code is   **not** consumed; the client mints a new UUIDv7 `device_id`, resets its replica state   as for a new device, and pairs again with the same code. `hlc_last` whose device part   is not `device_id` is also `422 validation_failed` (`code = invalid_value`). Equal to   the greatest logged HLC is fine (it is the last op the device minted). Absent, or a   `device_id` that has never logged an op: no check. The server reads the maximum under   advisory lock `7033001`, so a concurrent push cannot slip between the check and the commit. - `device_id` already exists and the request re-pairs that intact install: the row is   reused, `name`, `kind`, `push_topic` are overwritten, `revoked_at` is cleared,   `paired_at` is set to now. Ops the device pushed earlier stay; its unpushed outbox can   now be pushed. - `device_id` is the server device id: `422 validation_failed`. - Both checks above run before the code is consumed; on `422` the code stays valid. - Code unknown, expired or used: `401 invalid_pairing_code`. More than 10 failures from   one client address in 15 minutes: `429 rate_limited`. - Two requests race with one code: exactly one succeeds. 
     * Exchange a pairing code for a long-lived device token.
     */
    async pairDevice(requestParameters: PairDeviceRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<PairResponse> {
        const response = await this.pairDeviceRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for revokeDevice without sending the request
     */
    async revokeDeviceRequestOpts(requestParameters: RevokeDeviceRequest): Promise<runtime.RequestOpts> {
        if (requestParameters['deviceId'] == null) {
            throw new runtime.RequiredError(
                'deviceId',
                'Required parameter "deviceId" was null or undefined when calling revokeDevice().'
            );
        }

        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};

        if (this.configuration && this.configuration.accessToken) {
            const token = this.configuration.accessToken;
            const tokenString = await token("bearerAuth", ["admin"]);

            if (tokenString) {
                headerParameters["Authorization"] = `Bearer ${tokenString}`;
            }
        }

        let urlPath = `/devices/{device_id}`;
        urlPath = urlPath.replace('{device_id}', encodeURIComponent(String(requestParameters['deviceId'])));

        return {
            path: urlPath,
            method: 'DELETE',
            headers: headerParameters,
            query: queryParameters,
        };
    }

    /**
     * Sets `device.revoked_at` and `api_token.revoked_at` for every token with this `device_id` in one transaction. The row is kept (ops reference it). Ops already accepted stay; ops the device has not pushed yet are lost unless the same intact install is paired again with the same `device_id` (`POST /devices/pair`). Idempotent: revoking a revoked device returns `204`. The server device cannot be revoked: `409 not_applicable`. Emits the event `device.revoked`. 
     * Revoke a device and all its tokens.
     */
    async revokeDeviceRaw(requestParameters: RevokeDeviceRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<void>> {
        const requestOptions = await this.revokeDeviceRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.VoidApiResponse(response);
    }

    /**
     * Sets `device.revoked_at` and `api_token.revoked_at` for every token with this `device_id` in one transaction. The row is kept (ops reference it). Ops already accepted stay; ops the device has not pushed yet are lost unless the same intact install is paired again with the same `device_id` (`POST /devices/pair`). Idempotent: revoking a revoked device returns `204`. The server device cannot be revoked: `409 not_applicable`. Emits the event `device.revoked`. 
     * Revoke a device and all its tokens.
     */
    async revokeDevice(requestParameters: RevokeDeviceRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<void> {
        await this.revokeDeviceRaw(requestParameters, initOverrides);
    }

    /**
     * Creates request options for updateDevice without sending the request
     */
    async updateDeviceRequestOpts(requestParameters: UpdateDeviceRequest): Promise<runtime.RequestOpts> {
        if (requestParameters['deviceId'] == null) {
            throw new runtime.RequiredError(
                'deviceId',
                'Required parameter "deviceId" was null or undefined when calling updateDevice().'
            );
        }

        if (requestParameters['devicePatch'] == null) {
            throw new runtime.RequiredError(
                'devicePatch',
                'Required parameter "devicePatch" was null or undefined when calling updateDevice().'
            );
        }

        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};

        headerParameters['Content-Type'] = 'application/json';

        if (this.configuration && this.configuration.accessToken) {
            const token = this.configuration.accessToken;
            const tokenString = await token("bearerAuth", ["admin"]);

            if (tokenString) {
                headerParameters["Authorization"] = `Bearer ${tokenString}`;
            }
        }

        let urlPath = `/devices/{device_id}`;
        urlPath = urlPath.replace('{device_id}', encodeURIComponent(String(requestParameters['deviceId'])));

        return {
            path: urlPath,
            method: 'PATCH',
            headers: headerParameters,
            query: queryParameters,
            body: DevicePatchToJSON(requestParameters['devicePatch']),
        };
    }

    /**
     * Direct write to the system table `device` (not learning data, no op). Admin only; a device changes its own row through `PATCH /devices/self`. The server device and revoked devices cannot be patched: `409 not_applicable`. 
     * Rename a device or change its push topic.
     */
    async updateDeviceRaw(requestParameters: UpdateDeviceRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<Device>> {
        const requestOptions = await this.updateDeviceRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => DeviceFromJSON(jsonValue));
    }

    /**
     * Direct write to the system table `device` (not learning data, no op). Admin only; a device changes its own row through `PATCH /devices/self`. The server device and revoked devices cannot be patched: `409 not_applicable`. 
     * Rename a device or change its push topic.
     */
    async updateDevice(requestParameters: UpdateDeviceRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<Device> {
        const response = await this.updateDeviceRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for updateOwnDevice without sending the request
     */
    async updateOwnDeviceRequestOpts(requestParameters: UpdateOwnDeviceRequest): Promise<runtime.RequestOpts> {
        if (requestParameters['devicePatch'] == null) {
            throw new runtime.RequiredError(
                'devicePatch',
                'Required parameter "devicePatch" was null or undefined when calling updateOwnDevice().'
            );
        }

        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};

        headerParameters['Content-Type'] = 'application/json';

        if (this.configuration && this.configuration.accessToken) {
            const token = this.configuration.accessToken;
            const tokenString = await token("bearerAuth", ["write"]);

            if (tokenString) {
                headerParameters["Authorization"] = `Bearer ${tokenString}`;
            }
        }

        let urlPath = `/devices/self`;

        return {
            path: urlPath,
            method: 'PATCH',
            headers: headerParameters,
            query: queryParameters,
            body: DevicePatchToJSON(requestParameters['devicePatch']),
        };
    }

    /**
     * Same body and effect as `PATCH /devices/{device_id}`, applied to the device the calling token is bound to; this is how the phone registers its UnifiedPush endpoint. Needs only `write`. Credentials not bound to a device get `403 device_required`. Direct write to the system table `device` (not learning data, no op). 
     * Rename the caller\'s device or change its push topic.
     */
    async updateOwnDeviceRaw(requestParameters: UpdateOwnDeviceRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<Device>> {
        const requestOptions = await this.updateOwnDeviceRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => DeviceFromJSON(jsonValue));
    }

    /**
     * Same body and effect as `PATCH /devices/{device_id}`, applied to the device the calling token is bound to; this is how the phone registers its UnifiedPush endpoint. Needs only `write`. Credentials not bound to a device get `403 device_required`. Direct write to the system table `device` (not learning data, no op). 
     * Rename the caller\'s device or change its push topic.
     */
    async updateOwnDevice(requestParameters: UpdateOwnDeviceRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<Device> {
        const response = await this.updateOwnDeviceRaw(requestParameters, initOverrides);
        return await response.value();
    }

}
