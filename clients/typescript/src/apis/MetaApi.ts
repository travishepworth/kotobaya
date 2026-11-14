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
    type ClientErrorReport,
    ClientErrorReportFromJSON,
    ClientErrorReportToJSON,
} from '../models/ClientErrorReport';
import {
    type ClientErrorReportAccepted,
    ClientErrorReportAcceptedFromJSON,
    ClientErrorReportAcceptedToJSON,
} from '../models/ClientErrorReportAccepted';
import {
    type Health,
    HealthFromJSON,
    HealthToJSON,
} from '../models/Health';
import {
    type Meta,
    MetaFromJSON,
    MetaToJSON,
} from '../models/Meta';
import {
    type Problem,
    ProblemFromJSON,
    ProblemToJSON,
} from '../models/Problem';

export interface ReportClientErrorsRequest {
    /**
     * 
     */
    clientErrorReport: ClientErrorReport;
}

/**
 * 
 */
export class MetaApi extends runtime.BaseAPI {

    /**
     * Creates request options for getHealth without sending the request
     */
    async getHealthRequestOpts(): Promise<runtime.RequestOpts> {
        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};


        let urlPath = `/health`;

        return {
            path: urlPath,
            method: 'GET',
            headers: headerParameters,
            query: queryParameters,
        };
    }

    /**
     * Public. `200` when the API can reach Postgres, `503` (problem `unavailable`) otherwise. Does not check the object store or the worker. 
     * Liveness and readiness probe.
     */
    async getHealthRaw(initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<Health>> {
        const requestOptions = await this.getHealthRequestOpts();
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => HealthFromJSON(jsonValue));
    }

    /**
     * Public. `200` when the API can reach Postgres, `503` (problem `unavailable`) otherwise. Does not check the object store or the worker. 
     * Liveness and readiness probe.
     */
    async getHealth(initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<Health> {
        const response = await this.getHealthRaw(initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for getMeta without sending the request
     */
    async getMetaRequestOpts(): Promise<runtime.RequestOpts> {
        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};

        if (this.configuration && this.configuration.accessToken) {
            const token = this.configuration.accessToken;
            const tokenString = await token("bearerAuth", ["read"]);

            if (tokenString) {
                headerParameters["Authorization"] = `Bearer ${tokenString}`;
            }
        }

        let urlPath = `/meta`;

        return {
            path: urlPath,
            method: 'GET',
            headers: headerParameters,
            query: queryParameters,
        };
    }

    /**
     * Server versions, limits and sync head.
     */
    async getMetaRaw(initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<Meta>> {
        const requestOptions = await this.getMetaRequestOpts();
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => MetaFromJSON(jsonValue));
    }

    /**
     * Server versions, limits and sync head.
     */
    async getMeta(initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<Meta> {
        const response = await this.getMetaRaw(initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for reportClientErrors without sending the request
     */
    async reportClientErrorsRequestOpts(requestParameters: ReportClientErrorsRequest): Promise<runtime.RequestOpts> {
        if (requestParameters['clientErrorReport'] == null) {
            throw new runtime.RequiredError(
                'clientErrorReport',
                'Required parameter "clientErrorReport" was null or undefined when calling reportClientErrors().'
            );
        }

        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};

        headerParameters['Content-Type'] = 'application/json';

        if (this.configuration && this.configuration.accessToken) {
            const token = this.configuration.accessToken;
            const tokenString = await token("bearerAuth", []);

            if (tokenString) {
                headerParameters["Authorization"] = `Bearer ${tokenString}`;
            }
        }

        let urlPath = `/client-errors`;

        return {
            path: urlPath,
            method: 'POST',
            headers: headerParameters,
            query: queryParameters,
            body: ClientErrorReportToJSON(requestParameters['clientErrorReport']),
        };
    }

    /**
     * Added by C-23 (OBS-1, ADR-024). A client sends a batch of errors it observed (uncaught exceptions, unhandled promise rejections, crashes, failed API calls); the server writes them to its structured log and **stores nothing**: no table, no blob, no op, no job. Duplicate log lines from a retried request are possible and harmless.  **Who may call.** Any valid credential of any kind (`session`, `device`, `personal`) with any scopes, like `getPrincipal`. No credential, or an expired or revoked one: `401 unauthenticated`. Cookie-authenticated calls need `X-Kotobaya-Csrf: 1` like every other cookie write (`403 csrf_required`), so web clients send reports with `fetch(..., {keepalive: true})`, not `navigator.sendBeacon` (which cannot set headers).  **Limits and check order** (fixed constants, not published in `GET /meta`): 1. `401` / `403` as for every operation. 2. Rate limit, requests: per credential (`api_token.id`) at most **12 requests** in any    rolling 60-second window. The 13th is `429 rate_limited` before the body is read. 3. Body over **1 MiB** (`1048576` bytes of raw request body): `413 payload_too_large`. 4. Body not JSON: `400 invalid_request`. Wrong content type: `415`. 5. More than **20** `items`: `413 payload_too_large`. The client splits the batch and    resends the halves; one item within the field limits always fits in 1 MiB. 6. Any field violation (empty `items`, `message` over 2048 characters, `stack` over    16384, unknown property, bad enum, ...): `422 validation_failed` with one `errors[]`    element per problem (`required`, `unknown_field`, `invalid_type`, `invalid_value`,    `out_of_range`, `too_long`). The whole batch is rejected; the server logs one WARN    line naming the first problem, not the items. Clients MUST truncate `message` and    `stack` to the limits before sending (characters are Unicode code points, as JSON    Schema `maxLength` counts) and SHOULD end a truncated value with `…`. 7. Rate limit, items: per credential at most **120 items** in any rolling 60-second    window; a batch that would exceed it is `429 rate_limited` as a whole (no partial    acceptance) and none of its items are logged. 8. `202`: every item is logged. `429` carries `Retry-After` (whole seconds, at least 1, until the window admits the request). Every request that passes step 2 counts toward the request limit, whatever its outcome; only `202` requests count toward the item limit. The server logs at most one WARN line per credential per 60-second window saying reports were refused (token name, refused request and item counts). Limiter state is in memory per API replica (like the login limit) and resets on restart.  **What the server logs** (normative for OBS-1s). One line per item, logger `kotobaya.client_error`, level `ERROR` for `level: error` and `WARN` for `level: warn`, with the key-value fields `client`, `app_version`, `kind`, `occurred_at` (normalised to UTC), `route`, `client_request_id` (the item\'s `request_id`; the request\'s own id stays `request_id`), `http_method`, `http_path`, `http_status`, `count`, the calling token\'s name and device id (from the credential, never from the body), `message` and `stack`. Absent optional properties are omitted from the line. Every string from the body is escaped before logging so one item is exactly one line: CR, LF, TAB and every other C0/C1 control character (including U+0000) become `\\r`, `\\n`, `\\t` or `\\u00XX`, and U+2028/U+2029 become `\\u2028`/`\\u2029`. `route` and `http_path` are cut at the first `?` or `#` before logging (query strings and fragments can hold search text). When `dropped > 0` the server also logs one WARN line with `dropped`, the client, app version and token name.  **Clock skew.** `occurred_at` is the client\'s clock and is never rejected for being in the past or the future (only for not being an RFC 3339 instant, or for being after `9999-12-31T23:59:59.999Z`); the log line\'s own timestamp is the server\'s receive time.  **Privacy.** No PII beyond the token name the server adds. Clients MUST NOT put request or response bodies, note or card content, search strings, tokens, cookies or `Authorization` values into any property; `message` is the error\'s own message.  **Client obligations** (normative for OBS-1c): - Never report a failure of `reportClientErrors` itself (no feedback loop). - Fold identical reports (same `client`, `kind`, `level`, `message`, `route`,   `http_method`, `http_path`, `http_status`) into one item: `count` is the number of   occurrences, `occurred_at` the first one. - Keep a bounded queue (web: in memory; Android: persisted, so it survives offline time   and restarts) of at most 100 pending items; on overflow drop the oldest and add 1 to   `dropped`. Send `dropped` with the next batch and reset it only when that batch is   answered `202`. - `202`: remove the sent items. `413`: split the batch and resend. `429`: keep the   items and wait `Retry-After`. Network failure, `500`, `503`: keep the items and retry   with exponential backoff (at least 30 s, at most 15 min). `401`: keep the items and do   not send until a credential is available again. Any other status: drop the batch and   add its item count to `dropped` (it can never succeed). 
     * Report client-side errors (web, Android) to the server log.
     */
    async reportClientErrorsRaw(requestParameters: ReportClientErrorsRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<ClientErrorReportAccepted>> {
        const requestOptions = await this.reportClientErrorsRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => ClientErrorReportAcceptedFromJSON(jsonValue));
    }

    /**
     * Added by C-23 (OBS-1, ADR-024). A client sends a batch of errors it observed (uncaught exceptions, unhandled promise rejections, crashes, failed API calls); the server writes them to its structured log and **stores nothing**: no table, no blob, no op, no job. Duplicate log lines from a retried request are possible and harmless.  **Who may call.** Any valid credential of any kind (`session`, `device`, `personal`) with any scopes, like `getPrincipal`. No credential, or an expired or revoked one: `401 unauthenticated`. Cookie-authenticated calls need `X-Kotobaya-Csrf: 1` like every other cookie write (`403 csrf_required`), so web clients send reports with `fetch(..., {keepalive: true})`, not `navigator.sendBeacon` (which cannot set headers).  **Limits and check order** (fixed constants, not published in `GET /meta`): 1. `401` / `403` as for every operation. 2. Rate limit, requests: per credential (`api_token.id`) at most **12 requests** in any    rolling 60-second window. The 13th is `429 rate_limited` before the body is read. 3. Body over **1 MiB** (`1048576` bytes of raw request body): `413 payload_too_large`. 4. Body not JSON: `400 invalid_request`. Wrong content type: `415`. 5. More than **20** `items`: `413 payload_too_large`. The client splits the batch and    resends the halves; one item within the field limits always fits in 1 MiB. 6. Any field violation (empty `items`, `message` over 2048 characters, `stack` over    16384, unknown property, bad enum, ...): `422 validation_failed` with one `errors[]`    element per problem (`required`, `unknown_field`, `invalid_type`, `invalid_value`,    `out_of_range`, `too_long`). The whole batch is rejected; the server logs one WARN    line naming the first problem, not the items. Clients MUST truncate `message` and    `stack` to the limits before sending (characters are Unicode code points, as JSON    Schema `maxLength` counts) and SHOULD end a truncated value with `…`. 7. Rate limit, items: per credential at most **120 items** in any rolling 60-second    window; a batch that would exceed it is `429 rate_limited` as a whole (no partial    acceptance) and none of its items are logged. 8. `202`: every item is logged. `429` carries `Retry-After` (whole seconds, at least 1, until the window admits the request). Every request that passes step 2 counts toward the request limit, whatever its outcome; only `202` requests count toward the item limit. The server logs at most one WARN line per credential per 60-second window saying reports were refused (token name, refused request and item counts). Limiter state is in memory per API replica (like the login limit) and resets on restart.  **What the server logs** (normative for OBS-1s). One line per item, logger `kotobaya.client_error`, level `ERROR` for `level: error` and `WARN` for `level: warn`, with the key-value fields `client`, `app_version`, `kind`, `occurred_at` (normalised to UTC), `route`, `client_request_id` (the item\'s `request_id`; the request\'s own id stays `request_id`), `http_method`, `http_path`, `http_status`, `count`, the calling token\'s name and device id (from the credential, never from the body), `message` and `stack`. Absent optional properties are omitted from the line. Every string from the body is escaped before logging so one item is exactly one line: CR, LF, TAB and every other C0/C1 control character (including U+0000) become `\\r`, `\\n`, `\\t` or `\\u00XX`, and U+2028/U+2029 become `\\u2028`/`\\u2029`. `route` and `http_path` are cut at the first `?` or `#` before logging (query strings and fragments can hold search text). When `dropped > 0` the server also logs one WARN line with `dropped`, the client, app version and token name.  **Clock skew.** `occurred_at` is the client\'s clock and is never rejected for being in the past or the future (only for not being an RFC 3339 instant, or for being after `9999-12-31T23:59:59.999Z`); the log line\'s own timestamp is the server\'s receive time.  **Privacy.** No PII beyond the token name the server adds. Clients MUST NOT put request or response bodies, note or card content, search strings, tokens, cookies or `Authorization` values into any property; `message` is the error\'s own message.  **Client obligations** (normative for OBS-1c): - Never report a failure of `reportClientErrors` itself (no feedback loop). - Fold identical reports (same `client`, `kind`, `level`, `message`, `route`,   `http_method`, `http_path`, `http_status`) into one item: `count` is the number of   occurrences, `occurred_at` the first one. - Keep a bounded queue (web: in memory; Android: persisted, so it survives offline time   and restarts) of at most 100 pending items; on overflow drop the oldest and add 1 to   `dropped`. Send `dropped` with the next batch and reset it only when that batch is   answered `202`. - `202`: remove the sent items. `413`: split the batch and resend. `429`: keep the   items and wait `Retry-After`. Network failure, `500`, `503`: keep the items and retry   with exponential backoff (at least 30 s, at most 15 min). `401`: keep the items and do   not send until a credential is available again. Any other status: drop the batch and   add its item count to `dropped` (it can never succeed). 
     * Report client-side errors (web, Android) to the server log.
     */
    async reportClientErrors(requestParameters: ReportClientErrorsRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<ClientErrorReportAccepted> {
        const response = await this.reportClientErrorsRaw(requestParameters, initOverrides);
        return await response.value();
    }

}
