/* tslint:disable */
/* eslint-disable */
/**
 * Kotobaya API
 * Versioned REST/JSON interface at `/api/v1`. The web app uses nothing else; the Android app uses `sync`, `blobs`, `dict/release`, `devices/pair`, `devices/self` and whatever conveniences it likes.  Implementers: this file is the contract. If something you need is missing or wrong, do not work around it; return `CONTRACT_CHANGE_REQUEST`. Additive changes stay in v1; breaking changes go to `/api/v2`.  ## 1. Conventions  - **JSON.** Request and response bodies are `application/json; charset=utf-8` unless stated.   Property names are `snake_case` and equal the column names of `contracts/db/V1__init.sql`   wherever a property mirrors a column; the column `COMMENT` is then the definition. - **Unknown properties.** Servers reject unknown request properties and unknown query   parameters with `422 validation_failed`. Clients MUST ignore unknown response properties and   MUST treat unknown enum values in responses as \"other\" (v1 may add both). - **Absent vs null.** In `PATCH` bodies an absent property means \"leave unchanged\"; `null` means   \"set to NULL\" where the property is nullable and is a validation error otherwise.   Responses always include every documented property; nullable ones are `null`, never absent,   unless the property is documented as \"omitted when ...\". - **Ids.** Row ids are UUIDv7 in canonical lowercase `8-4-4-4-12` text. Uppercase ids in   requests are rejected with `422`. \"Entry\" always means a JMdict sequence number (`seq`,   JSON integer). - **Time.** Instants are RFC 3339 UTC strings with millisecond precision and a `Z` suffix,   e.g. `2026-11-12T09:30:00.000Z`. Servers accept any RFC 3339 offset on input and normalise   to UTC. A \"study day\" is a local date `YYYY-MM-DD` computed from the settings   `scheduler.timezone` and `scheduler.day_rollover_hour`: study day D covers local time   `D rollover:00` (inclusive) to `D+1 rollover:00` (exclusive). - **Text offsets** into sentence text are UTF-16 code units, half-open `[start, end)`. - **Blobs** (audio, images, uploads, snapshots) are addressed by the lowercase hex SHA-256 of   their bytes and transferred through `/blobs/{hash}`, never inline in JSON.  ## 2. Authentication and scopes  Three credential kinds, all rows of `app.api_token` (stored hashed):  | Kind | How obtained | Sent as | Scopes | |---|---|---|---| | `session` | `POST /auth/login` | cookie `kotobaya_session` (HTTP-only) | all five | | `device` | `POST /devices/pair` | `Authorization: Bearer kbd_...` | `read`, `write`, `review` | | `personal` | `POST /tokens` | `Authorization: Bearer kbp_...` | chosen at creation |  Token strings are `<3-letter kind prefix>_<40 characters of [A-Za-z0-9]>` (`kbs_`, `kbd_`, `kbp_`), generated from a CSPRNG. `token_hash` = lowercase hex SHA-256 of the whole string; `token_prefix` = its first 8 characters. A token is valid iff `revoked_at IS NULL` and (`expires_at IS NULL` or `expires_at > now()`) and, when it has a `device_id`, that device is not revoked. Validity is checked on every request (no caching longer than 5 seconds), so revocation takes effect at once.  Scopes are independent (no scope implies another):  | Scope | Grants | |---|---| | `read` | Every `GET` of learning, media and reference data; `POST /parse`; `GET /events`; `GET /sync/pull`; exports. | | `write` | Create, change, delete notes, cards, decks, grammar, note types, presets, settings, word status, parse overrides, media metadata; upload blobs; `POST /sync/push` (non-review ops). | | `review` | `POST /review/answer`, `POST /review/undo`, `POST /drill/session`, `POST /cards/bulk/schedule`; `review.*` ops inside `POST /sync/push` (per op; without it the op is rejected with `insufficient_scope`). | | `import` | Start and cancel import, parse, dictionary and optimizer jobs; upload blobs. | | `admin` | Devices, pairing, tokens (a device\'s own row: `/devices/self` with `read`/`write`). |  Each operation lists the scope it needs in its `security` requirement: the array after the scheme name. When two alternatives are listed for one scheme the caller needs either. A request with no or an invalid credential gets `401 unauthenticated`; a valid credential lacking the scope gets `403 insufficient_scope`. Operations with `security: []` are public.  There are exactly two places where who may call is not just \"scheme + scope\", and both are declared in the operation, not only in prose: - `GET/PATCH /devices/self` (`read` / `write`, bearer only): the token must be bound to a   device, else `403 device_required`. A device never needs `admin` to manage its own row;   `/devices/{device_id}` is admin only. - `POST /ankiconnect` (`x-auth: body-key`): the token may instead be sent in the JSON body   property `key`; the empty `{}` alternative in its `security` only signals that no header is   needed. The scope for `key` is `x-body-key-scope`.  **CSRF.** A request authenticated by the session cookie whose method is not `GET` or `HEAD` MUST carry the header `X-Kotobaya-Csrf: 1`; otherwise `403 csrf_required`. The cookie is set with `HttpOnly; Secure; SameSite=Strict; Path=/api`. The server sends no CORS headers (the web app is served from the same origin). If both a cookie and an `Authorization` header are present, the header wins and the cookie is ignored.  **Audit.** Every request is logged with `token.id` and `token.name` (structured server log; there is no audit table or endpoint in v1). Ops record the token in `change.token_id`.  ## 3. Writes are sync ops  Learning data is only ever changed by applying sync ops (`contracts/sync`). Every REST operation that changes learning data is a convenience wrapper: the server builds the op(s) named in the operation\'s `x-emits-ops`, applies them through the same op-apply path as `POST /sync/push` in one transaction, and they then appear in `GET /sync/pull` for every device. Op names are those of `contracts/db/SCHEMA.md` section 4; `contracts/sync` is authoritative for names and payloads.  For an op the server builds on behalf of a REST call:  - `op_id` is a fresh UUIDv7; origin `device_id` is the **server device**   `00000000-0000-7000-8000-000000000001` (also for session and device tokens: only one party   ever generates HLCs for a given device id); `hlc` comes from the server\'s HLC clock;   `change.token_id` is the calling token. - A property absent from a `PATCH` body produces no register write. A property present with   the value the row already has still produces a write (it bumps the register\'s clock). - If a call changes nothing (documented per operation, e.g. deleting an already deleted   note), no op is emitted and the response is still successful. - **Never an invalid op (C-6).** If a request would make the server build an op that fails   its schema or an op-level rule in `contracts/sync` - a tag containing ECMA-262 whitespace   (e.g. U+3000 or U+00A0), U+0000 in any string (field values and open JSON objects   included), an integer above its op maximum (`2147483647` for int4 columns) or an instant   after `9999-12-31T23:59:59.999Z` - the call is `422 validation_failed` with a field error   at that request property, code `invalid_value` (or `out_of_range` / `too_long` where the   operation\'s own request bound fails first). Nothing is emitted; never a `500`.   SYNC.md 2.1 \"Storable values\" and \"Input that would mint an invalid op\". - Responses to mutating calls carry the header `Kotobaya-Seq`: the `change.seq` of the last op   the call emitted. A client holding a local replica knows it has seen the effect once its   pull cursor is at or past that value. The header is absent when no op was emitted.  **Idempotent creation.** Create bodies accept an optional client-generated `id` (UUIDv7). Re-sending a create with an `id` that already exists returns the existing resource with status `200` instead of `201` and emits nothing, whatever the rest of the body says. Clients that may retry MUST send `id`. When `id` is omitted the server generates one. `POST /review/answer` requires `id` (a duplicate review would corrupt scheduling); `POST /media/import` takes the same optional idempotency id as `media_id`.  The existence check and the emit are **one transaction that takes advisory lock `7033001` before the check** and holds it until commit (the lock `POST /sync/push` takes per op, SYNC.md 5.2): check whether `id` exists; if not, mint, apply and log the op(s) - for `createNote` the `note.created` and every `card.created` - then commit. Without the lock two concurrent retries of the same create both pass the check; the second `note.created` is a no-op on the row (SYNC.md 7.2) but its `card.created` ops carry fresh card ids, so the note would get duplicate cards. The same holds for every other id-idempotent call (`POST /review/answer`, `POST /review/undo`, `POST /media/import`), and a pushed op that creates the same id races safely because it takes the same lock.  **Tombstones and merges.** Deleted learning rows are tombstones (`deleted_at` set). `GET` by id of a deleted row returns `410 gone` unless `include_deleted=true`; lists omit deleted rows unless `include_deleted=true`. A note or card that lost a merge (`merged_into_id` set) is answered with its survivor: `GET`, `PATCH` and review calls addressed to the loser\'s id act on the survivor and the response body is the survivor (its `id` differs from the requested id; the response has `Content-Location` with the survivor\'s URL).  ## 4. Pagination  List operations that take `limit` and `cursor` return `{\"items\": [...], \"next_cursor\": ...}`. `next_cursor` is an opaque string, or `null` on the last page. Pass it back unchanged as `cursor` together with the same other parameters; changing any other parameter while passing a cursor is `422 validation_failed` (`pointer` `/cursor`). `limit` defaults to 50, maximum 500. Order is stable and documented per operation; rows created while paging may or may not appear.  **Cursor semantics (keyset, no snapshot).** A cursor encodes the sort-key values and the `id` of the last row returned; the next page is the rows strictly after that tuple in the current order, evaluated against the data at the time of that request. The order is not snapshotted. Consequently, while a client pages through a list sorted by a mutable key (`due_at`, `updated_at`, `lapses`, ...): - a row whose key changes so that it moves from after the cursor to before it is **skipped**; - a row whose key changes so that it moves from before the cursor to after it is **returned   again** (with its new values); - a row deleted while paging disappears; one created may or may not appear. Rows that do not change are returned exactly once. Clients MUST de-duplicate by `id` (last occurrence wins). A client that needs an exact set (bulk actions, exports) uses an `id`-sorted list where offered, `POST /cards/bulk` with `query`, or `GET /export`. Lists sorted by an immutable key (`id`, `created_at`) have no skips or repeats. An empty result is `{\"items\": [], \"next_cursor\": null}` with status `200`.  ## 5. Errors  Every non-2xx response has the body `application/problem+json` (RFC 9457), schema `Problem`. `code` is the stable machine-readable identifier; `type` is `urn:kotobaya:problem:<code>`.  | Status | `code` | When | |---|---|---| | 400 | `invalid_request` | Body is not valid JSON, wrong content type framing, malformed header; `POST /sync/push` body not valid against `PushRequestLenient`. | | 401 | `unauthenticated` | No credential, or it is unknown, expired or revoked. | | 401 | `invalid_credentials` | Wrong password or TOTP code (`POST /auth/login`). | | 401 | `totp_required` | TOTP is enabled and `totp_code` was not sent. | | 401 | `invalid_pairing_code` | Pairing code unknown, expired or already used. | | 403 | `insufficient_scope` | Credential lacks the scope; `required_scope` names it. | | 403 | `csrf_required` | Cookie-authenticated write without `X-Kotobaya-Csrf`. | | 403 | `device_required` | `POST /sync/push` or `/devices/self` with a credential that has no `device_id`. | | 404 | `not_found` | No such resource (also: id of another resource kind). | | 409 | `already_exists` | Natural-key collision; `existing_id` names the holder. | | 409 | `builtin_immutable` | Deleting a built-in note type or the Default preset, or removing a built-in field. | | 409 | `in_use` | Deleting a note type that live notes use. | | 409 | `deck_read_only` | Changing items or deleting a `media`/`list` deck through deck endpoints. | | 409 | `not_applicable` | The resource kind does not support the operation (e.g. items of a smart deck). | | 409 | `card_not_reviewable` | Card is suspended. | | 409 | `job_not_cancellable` | Job already finished. | | 410 | `gone` | Tombstoned row requested without `include_deleted=true`. | | 413 | `payload_too_large` | Body over the limit in `GET /meta`. On `POST /sync/push` only for the whole batch (the client halves it); one op over `max_op_bytes` is a per-op `op_too_large` result instead. | | 415 | `unsupported_media_type` | Wrong `Content-Type`. | | 422 | `validation_failed` | Well-formed but invalid; `errors[]` lists each problem. | | 422 | `invalid_query` | Card search string does not parse; `errors[0].position` is the UTF-16 offset. | | 422 | `bulk_too_large` | Bulk selector matched more than 5000 cards. | | 422 | `hash_mismatch` | Uploaded bytes do not hash to the `{hash}` in the path. | | 429 | `rate_limited` | Too many login or pairing attempts; see `Retry-After`. | | 500 | `internal` | Bug. Safe to retry idempotent calls. | | 503 | `unavailable` | Database or object store unreachable. Retry with backoff. |  Check order: 401, then 403, then 404/410, then 415/400, then 422, then 409.  ## 6. Events  `GET /events` is a server-sent events stream; see that operation. Event payload schemas are `Event*` in components. 
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
    type CardPage,
    CardPageFromJSON,
    CardPageToJSON,
} from '../models/CardPage';
import {
    type Confusable,
    ConfusableFromJSON,
    ConfusableToJSON,
} from '../models/Confusable';
import {
    type ConfusableList,
    ConfusableListFromJSON,
    ConfusableListToJSON,
} from '../models/ConfusableList';
import {
    type ConfusablePage,
    ConfusablePageFromJSON,
    ConfusablePageToJSON,
} from '../models/ConfusablePage';
import {
    type ConfusableReason,
    ConfusableReasonFromJSON,
    ConfusableReasonToJSON,
} from '../models/ConfusableReason';
import {
    type ConfusableSet,
    ConfusableSetFromJSON,
    ConfusableSetToJSON,
} from '../models/ConfusableSet';
import {
    type ConfusableStatus,
    ConfusableStatusFromJSON,
    ConfusableStatusToJSON,
} from '../models/ConfusableStatus';
import {
    type HotKanjiList,
    HotKanjiListFromJSON,
    HotKanjiListToJSON,
} from '../models/HotKanjiList';
import {
    type HotWordList,
    HotWordListFromJSON,
    HotWordListToJSON,
} from '../models/HotWordList';
import {
    type Problem,
    ProblemFromJSON,
    ProblemToJSON,
} from '../models/Problem';
import {
    type WordStatus,
    WordStatusFromJSON,
    WordStatusToJSON,
} from '../models/WordStatus';
import {
    type WordStatusSet,
    WordStatusSetFromJSON,
    WordStatusSetToJSON,
} from '../models/WordStatusSet';

export interface ClearWordStatusRequest {
    /**
     * JMdict sequence number of the entry.
     */
    seq: number;
}

export interface GetWordStatusRequest {
    /**
     * JMdict sequence number of the entry.
     */
    seq: number;
}

export interface ListConfusablesRequest {
    /**
     * Only pairs with this status. Default - all three.
     */
    status?: ConfusableStatus;
    /**
     * Only pairs involving this note.
     */
    noteId?: string;
    /**
     * Page size.
     */
    limit?: number;
    /**
     * Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`.
     */
    cursor?: string;
}

export interface ListHotKanjiRequest {
    /**
     * Maximum number of kanji.
     */
    limit?: number;
}

export interface ListHotWordsRequest {
    /**
     * Maximum number of words.
     */
    limit?: number;
}

export interface ListLeechesRequest {
    /**
     * Page size.
     */
    limit?: number;
    /**
     * Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`.
     */
    cursor?: string;
}

export interface ListWordConfusablesRequest {
    /**
     * JMdict sequence number of the entry.
     */
    seq: number;
    /**
     * Only pairs with this status. Default - `suggested` and `confirmed`.
     */
    status?: ConfusableStatus;
}

export interface SetConfusableRequest {
    /**
     * One note of the pair. The two ids may be given in either order; the server stores the smaller first.
     */
    noteAId: string;
    /**
     * The other note. Must differ from `note_a_id` (`422`).
     */
    noteBId: string;
    /**
     * Why the two are confusable.
     */
    reason: ConfusableReason;
    /**
     * 
     */
    confusableSet: ConfusableSet;
}

export interface SetWordStatusRequest {
    /**
     * JMdict sequence number of the entry.
     */
    seq: number;
    /**
     * 
     */
    wordStatusSet: WordStatusSet;
}

/**
 * 
 */
export class WordsApi extends runtime.BaseAPI {

    /**
     * Creates request options for clearWordStatus without sending the request
     */
    async clearWordStatusRequestOpts(requestParameters: ClearWordStatusRequest): Promise<runtime.RequestOpts> {
        if (requestParameters['seq'] == null) {
            throw new runtime.RequiredError(
                'seq',
                'Required parameter "seq" was null or undefined when calling clearWordStatus().'
            );
        }

        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};

        if (this.configuration && this.configuration.accessToken) {
            const token = this.configuration.accessToken;
            const tokenString = await token("bearerAuth", ["write"]);

            if (tokenString) {
                headerParameters["Authorization"] = `Bearer ${tokenString}`;
            }
        }

        let urlPath = `/words/{seq}/status`;
        urlPath = urlPath.replace('{seq}', encodeURIComponent(String(requestParameters['seq'])));

        return {
            path: urlPath,
            method: 'DELETE',
            headers: headerParameters,
            query: queryParameters,
        };
    }

    /**
     * Server emits `word.status_cleared`. No override present (or no row): `200` with the current status, no op. 
     * Clear the override; the status falls back to the derived one.
     */
    async clearWordStatusRaw(requestParameters: ClearWordStatusRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<WordStatus>> {
        const requestOptions = await this.clearWordStatusRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => WordStatusFromJSON(jsonValue));
    }

    /**
     * Server emits `word.status_cleared`. No override present (or no row): `200` with the current status, no op. 
     * Clear the override; the status falls back to the derived one.
     */
    async clearWordStatus(requestParameters: ClearWordStatusRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<WordStatus> {
        const response = await this.clearWordStatusRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for getWordStatus without sending the request
     */
    async getWordStatusRequestOpts(requestParameters: GetWordStatusRequest): Promise<runtime.RequestOpts> {
        if (requestParameters['seq'] == null) {
            throw new runtime.RequiredError(
                'seq',
                'Required parameter "seq" was null or undefined when calling getWordStatus().'
            );
        }

        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};

        if (this.configuration && this.configuration.accessToken) {
            const token = this.configuration.accessToken;
            const tokenString = await token("bearerAuth", ["read"]);

            if (tokenString) {
                headerParameters["Authorization"] = `Bearer ${tokenString}`;
            }
        }

        let urlPath = `/words/{seq}/status`;
        urlPath = urlPath.replace('{seq}', encodeURIComponent(String(requestParameters['seq'])));

        return {
            path: urlPath,
            method: 'GET',
            headers: headerParameters,
            query: queryParameters,
        };
    }

    /**
     * An entry without a `word_status` row is reported as `status = new` with every other property null. The entry need not exist in the dictionary.
     * Word status of one entry.
     */
    async getWordStatusRaw(requestParameters: GetWordStatusRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<WordStatus>> {
        const requestOptions = await this.getWordStatusRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => WordStatusFromJSON(jsonValue));
    }

    /**
     * An entry without a `word_status` row is reported as `status = new` with every other property null. The entry need not exist in the dictionary.
     * Word status of one entry.
     */
    async getWordStatus(requestParameters: GetWordStatusRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<WordStatus> {
        const response = await this.getWordStatusRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for listConfusables without sending the request
     */
    async listConfusablesRequestOpts(requestParameters: ListConfusablesRequest): Promise<runtime.RequestOpts> {
        const queryParameters: any = {};

        if (requestParameters['status'] != null) {
            queryParameters['status'] = requestParameters['status'];
        }

        if (requestParameters['noteId'] != null) {
            queryParameters['note_id'] = requestParameters['noteId'];
        }

        if (requestParameters['limit'] != null) {
            queryParameters['limit'] = requestParameters['limit'];
        }

        if (requestParameters['cursor'] != null) {
            queryParameters['cursor'] = requestParameters['cursor'];
        }

        const headerParameters: runtime.HTTPHeaders = {};

        if (this.configuration && this.configuration.accessToken) {
            const token = this.configuration.accessToken;
            const tokenString = await token("bearerAuth", ["read"]);

            if (tokenString) {
                headerParameters["Authorization"] = `Bearer ${tokenString}`;
            }
        }

        let urlPath = `/confusables`;

        return {
            path: urlPath,
            method: 'GET',
            headers: headerParameters,
            query: queryParameters,
        };
    }

    /**
     * Ordered by `updated_at` descending, then `(note_a_id, note_b_id, reason)`. Pairs with a deleted note are hidden; merged notes are resolved to survivors.
     * All confusable pairs (any note type).
     */
    async listConfusablesRaw(requestParameters: ListConfusablesRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<ConfusablePage>> {
        const requestOptions = await this.listConfusablesRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => ConfusablePageFromJSON(jsonValue));
    }

    /**
     * Ordered by `updated_at` descending, then `(note_a_id, note_b_id, reason)`. Pairs with a deleted note are hidden; merged notes are resolved to survivors.
     * All confusable pairs (any note type).
     */
    async listConfusables(requestParameters: ListConfusablesRequest = {}, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<ConfusablePage> {
        const response = await this.listConfusablesRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for listHotKanji without sending the request
     */
    async listHotKanjiRequestOpts(requestParameters: ListHotKanjiRequest): Promise<runtime.RequestOpts> {
        const queryParameters: any = {};

        if (requestParameters['limit'] != null) {
            queryParameters['limit'] = requestParameters['limit'];
        }

        const headerParameters: runtime.HTTPHeaders = {};

        if (this.configuration && this.configuration.accessToken) {
            const token = this.configuration.accessToken;
            const tokenString = await token("bearerAuth", ["read"]);

            if (tokenString) {
                headerParameters["Authorization"] = `Bearer ${tokenString}`;
            }
        }

        let urlPath = `/kanji/hot`;

        return {
            path: urlPath,
            method: 'GET',
            headers: headerParameters,
            query: queryParameters,
        };
    }

    /**
     * Scores come from the shared core (hot-word score summed over every live vocab note whose entry contains the kanji, via `ref.entry_kanji`). Highest score first, then `literal`. Only kanji with a score above zero. Not paginated. 
     * Hot kanji - recent failures rolled up by kanji.
     */
    async listHotKanjiRaw(requestParameters: ListHotKanjiRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<HotKanjiList>> {
        const requestOptions = await this.listHotKanjiRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => HotKanjiListFromJSON(jsonValue));
    }

    /**
     * Scores come from the shared core (hot-word score summed over every live vocab note whose entry contains the kanji, via `ref.entry_kanji`). Highest score first, then `literal`. Only kanji with a score above zero. Not paginated. 
     * Hot kanji - recent failures rolled up by kanji.
     */
    async listHotKanji(requestParameters: ListHotKanjiRequest = {}, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<HotKanjiList> {
        const response = await this.listHotKanjiRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for listHotWords without sending the request
     */
    async listHotWordsRequestOpts(requestParameters: ListHotWordsRequest): Promise<runtime.RequestOpts> {
        const queryParameters: any = {};

        if (requestParameters['limit'] != null) {
            queryParameters['limit'] = requestParameters['limit'];
        }

        const headerParameters: runtime.HTTPHeaders = {};

        if (this.configuration && this.configuration.accessToken) {
            const token = this.configuration.accessToken;
            const tokenString = await token("bearerAuth", ["read"]);

            if (tokenString) {
                headerParameters["Authorization"] = `Bearer ${tokenString}`;
            }
        }

        let urlPath = `/words/hot`;

        return {
            path: urlPath,
            method: 'GET',
            headers: headerParameters,
            query: queryParameters,
        };
    }

    /**
     * Score from the shared core (failures over the last 14 days with decay, FSRS difficulty, lapse rate, relearning failures). Only live vocab notes with a score above zero; highest score first, then note id. Not paginated. 
     * Hot words - recent struggle, not lifetime.
     */
    async listHotWordsRaw(requestParameters: ListHotWordsRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<HotWordList>> {
        const requestOptions = await this.listHotWordsRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => HotWordListFromJSON(jsonValue));
    }

    /**
     * Score from the shared core (failures over the last 14 days with decay, FSRS difficulty, lapse rate, relearning failures). Only live vocab notes with a score above zero; highest score first, then note id. Not paginated. 
     * Hot words - recent struggle, not lifetime.
     */
    async listHotWords(requestParameters: ListHotWordsRequest = {}, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<HotWordList> {
        const response = await this.listHotWordsRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for listLeeches without sending the request
     */
    async listLeechesRequestOpts(requestParameters: ListLeechesRequest): Promise<runtime.RequestOpts> {
        const queryParameters: any = {};

        if (requestParameters['limit'] != null) {
            queryParameters['limit'] = requestParameters['limit'];
        }

        if (requestParameters['cursor'] != null) {
            queryParameters['cursor'] = requestParameters['cursor'];
        }

        const headerParameters: runtime.HTTPHeaders = {};

        if (this.configuration && this.configuration.accessToken) {
            const token = this.configuration.accessToken;
            const tokenString = await token("bearerAuth", ["read"]);

            if (tokenString) {
                headerParameters["Authorization"] = `Bearer ${tokenString}`;
            }
        }

        let urlPath = `/words/leeches`;

        return {
            path: urlPath,
            method: 'GET',
            headers: headerParameters,
            query: queryParameters,
        };
    }

    /**
     * Live cards of live notes with `lapses >= scheduler.leech_threshold`, suspended ones included. Ordered by `lapses` descending, then card id. Covers every note type, not only vocab. Equivalent to `GET /cards?query=tag:leech&sort=-lapses`. 
     * Leech cards (lapses at or above `scheduler.leech_threshold`).
     */
    async listLeechesRaw(requestParameters: ListLeechesRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<CardPage>> {
        const requestOptions = await this.listLeechesRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => CardPageFromJSON(jsonValue));
    }

    /**
     * Live cards of live notes with `lapses >= scheduler.leech_threshold`, suspended ones included. Ordered by `lapses` descending, then card id. Covers every note type, not only vocab. Equivalent to `GET /cards?query=tag:leech&sort=-lapses`. 
     * Leech cards (lapses at or above `scheduler.leech_threshold`).
     */
    async listLeeches(requestParameters: ListLeechesRequest = {}, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<CardPage> {
        const response = await this.listLeechesRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for listWordConfusables without sending the request
     */
    async listWordConfusablesRequestOpts(requestParameters: ListWordConfusablesRequest): Promise<runtime.RequestOpts> {
        if (requestParameters['seq'] == null) {
            throw new runtime.RequiredError(
                'seq',
                'Required parameter "seq" was null or undefined when calling listWordConfusables().'
            );
        }

        const queryParameters: any = {};

        if (requestParameters['status'] != null) {
            queryParameters['status'] = requestParameters['status'];
        }

        const headerParameters: runtime.HTTPHeaders = {};

        if (this.configuration && this.configuration.accessToken) {
            const token = this.configuration.accessToken;
            const tokenString = await token("bearerAuth", ["read"]);

            if (tokenString) {
                headerParameters["Authorization"] = `Bearer ${tokenString}`;
            }
        }

        let urlPath = `/words/{seq}/confusables`;
        urlPath = urlPath.replace('{seq}', encodeURIComponent(String(requestParameters['seq'])));

        return {
            path: urlPath,
            method: 'GET',
            headers: headerParameters,
            query: queryParameters,
        };
    }

    /**
     * Pairs involving the live vocab note of this entry, resolved through `merged_into_id`, pairs with a deleted note hidden. Ordered: `confirmed` first, then `suggested`; inside by `updated_at` descending. `rejected` pairs only with `status=rejected`. No note for the entry: empty list. Not paginated. 
     * Confusable partners of the word\'s note.
     */
    async listWordConfusablesRaw(requestParameters: ListWordConfusablesRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<ConfusableList>> {
        const requestOptions = await this.listWordConfusablesRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => ConfusableListFromJSON(jsonValue));
    }

    /**
     * Pairs involving the live vocab note of this entry, resolved through `merged_into_id`, pairs with a deleted note hidden. Ordered: `confirmed` first, then `suggested`; inside by `updated_at` descending. `rejected` pairs only with `status=rejected`. No note for the entry: empty list. Not paginated. 
     * Confusable partners of the word\'s note.
     */
    async listWordConfusables(requestParameters: ListWordConfusablesRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<ConfusableList> {
        const response = await this.listWordConfusablesRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for setConfusable without sending the request
     */
    async setConfusableRequestOpts(requestParameters: SetConfusableRequest): Promise<runtime.RequestOpts> {
        if (requestParameters['noteAId'] == null) {
            throw new runtime.RequiredError(
                'noteAId',
                'Required parameter "noteAId" was null or undefined when calling setConfusable().'
            );
        }

        if (requestParameters['noteBId'] == null) {
            throw new runtime.RequiredError(
                'noteBId',
                'Required parameter "noteBId" was null or undefined when calling setConfusable().'
            );
        }

        if (requestParameters['reason'] == null) {
            throw new runtime.RequiredError(
                'reason',
                'Required parameter "reason" was null or undefined when calling setConfusable().'
            );
        }

        if (requestParameters['confusableSet'] == null) {
            throw new runtime.RequiredError(
                'confusableSet',
                'Required parameter "confusableSet" was null or undefined when calling setConfusable().'
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

        let urlPath = `/confusables/{note_a_id}/{note_b_id}/{reason}`;
        urlPath = urlPath.replace('{note_a_id}', encodeURIComponent(String(requestParameters['noteAId'])));
        urlPath = urlPath.replace('{note_b_id}', encodeURIComponent(String(requestParameters['noteBId'])));
        urlPath = urlPath.replace('{reason}', encodeURIComponent(String(requestParameters['reason'])));

        return {
            path: urlPath,
            method: 'PUT',
            headers: headerParameters,
            query: queryParameters,
            body: ConfusableSetToJSON(requestParameters['confusableSet']),
        };
    }

    /**
     * Server emits `confusable.confirmed` or `confusable.rejected`. If the pair does not exist yet the op creates it (a hand-made pair is simply a confirmed one). Both notes must exist; merge losers are resolved to survivors first, and if both resolve to the same note the call is `422`. A user decision always beats a suggestion; between decisions the later clock wins. Rejected pairs are kept so they are not suggested again. 
     * Confirm or reject a suggested pair, or record a pair yourself.
     */
    async setConfusableRaw(requestParameters: SetConfusableRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<Confusable>> {
        const requestOptions = await this.setConfusableRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => ConfusableFromJSON(jsonValue));
    }

    /**
     * Server emits `confusable.confirmed` or `confusable.rejected`. If the pair does not exist yet the op creates it (a hand-made pair is simply a confirmed one). Both notes must exist; merge losers are resolved to survivors first, and if both resolve to the same note the call is `422`. A user decision always beats a suggestion; between decisions the later clock wins. Rejected pairs are kept so they are not suggested again. 
     * Confirm or reject a suggested pair, or record a pair yourself.
     */
    async setConfusable(requestParameters: SetConfusableRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<Confusable> {
        const response = await this.setConfusableRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for setWordStatus without sending the request
     */
    async setWordStatusRequestOpts(requestParameters: SetWordStatusRequest): Promise<runtime.RequestOpts> {
        if (requestParameters['seq'] == null) {
            throw new runtime.RequiredError(
                'seq',
                'Required parameter "seq" was null or undefined when calling setWordStatus().'
            );
        }

        if (requestParameters['wordStatusSet'] == null) {
            throw new runtime.RequiredError(
                'wordStatusSet',
                'Required parameter "wordStatusSet" was null or undefined when calling setWordStatus().'
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

        let urlPath = `/words/{seq}/status`;
        urlPath = urlPath.replace('{seq}', encodeURIComponent(String(requestParameters['seq'])));

        return {
            path: urlPath,
            method: 'PUT',
            headers: headerParameters,
            query: queryParameters,
            body: WordStatusSetToJSON(requestParameters['wordStatusSet']),
        };
    }

    /**
     * Server emits `word.status_set` with payload `origin = manual` (stored as `override_origin`). `new` and `learning` cannot be set (they are derived): to return to the derived status use `DELETE`. The entry need not exist in the dictionary and need not have a note. Setting the status it already has still emits (bumps the clock). 
     * Set an explicit status override (known, never-forget, blacklisted, ignored).
     */
    async setWordStatusRaw(requestParameters: SetWordStatusRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<WordStatus>> {
        const requestOptions = await this.setWordStatusRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => WordStatusFromJSON(jsonValue));
    }

    /**
     * Server emits `word.status_set` with payload `origin = manual` (stored as `override_origin`). `new` and `learning` cannot be set (they are derived): to return to the derived status use `DELETE`. The entry need not exist in the dictionary and need not have a note. Setting the status it already has still emits (bumps the clock). 
     * Set an explicit status override (known, never-forget, blacklisted, ignored).
     */
    async setWordStatus(requestParameters: SetWordStatusRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<WordStatus> {
        const response = await this.setWordStatusRaw(requestParameters, initOverrides);
        return await response.value();
    }

}
