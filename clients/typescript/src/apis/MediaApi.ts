/* tslint:disable */
/* eslint-disable */
/**
 * Kotobaya API
 * Versioned REST/JSON interface at `/api/v1`. The web app uses nothing else; the Android app uses `sync`, `blobs`, `dict/release`, `devices/pair`, `devices/self` and whatever conveniences it likes.  Implementers: this file is the contract. If something you need is missing or wrong, do not work around it; return `CONTRACT_CHANGE_REQUEST`. Additive changes stay in v1; breaking changes go to `/api/v2`.  ## 1. Conventions  - **JSON.** Request and response bodies are `application/json; charset=utf-8` unless stated.   Property names are `snake_case` and equal the column names of `contracts/db/V1__init.sql`   wherever a property mirrors a column; the column `COMMENT` is then the definition. - **Unknown properties.** Servers reject unknown request properties and unknown query   parameters with `422 validation_failed`. Clients MUST ignore unknown response properties and   MUST treat unknown enum values in responses as \"other\" (v1 may add both). - **Absent vs null.** In `PATCH` bodies an absent property means \"leave unchanged\"; `null` means   \"set to NULL\" where the property is nullable and is a validation error otherwise.   Responses always include every documented property; nullable ones are `null`, never absent,   unless the property is documented as \"omitted when ...\". - **Ids.** Row ids are UUIDv7 in canonical lowercase `8-4-4-4-12` text. Uppercase ids in   requests are rejected with `422`. \"Entry\" always means a JMdict sequence number (`seq`,   JSON integer). - **Time.** Instants are RFC 3339 UTC strings with millisecond precision and a `Z` suffix,   e.g. `2026-11-12T09:30:00.000Z`. Servers accept any RFC 3339 offset on input and normalise   to UTC. A \"study day\" is a local date `YYYY-MM-DD` computed from the settings   `scheduler.timezone` and `scheduler.day_rollover_hour`: study day D covers local time   `D rollover:00` (inclusive) to `D+1 rollover:00` (exclusive). - **Text offsets** into sentence text are UTF-16 code units, half-open `[start, end)`. - **Blobs** (audio, images, uploads, snapshots) are addressed by the lowercase hex SHA-256 of   their bytes and transferred through `/blobs/{hash}`, never inline in JSON.  ## 2. Authentication and scopes  Three credential kinds, all rows of `app.api_token` (stored hashed):  | Kind | How obtained | Sent as | Scopes | |---|---|---|---| | `session` | `POST /auth/login` | cookie `kotobaya_session` (HTTP-only) | all five | | `device` | `POST /devices/pair` | `Authorization: Bearer kbd_...` | `read`, `write`, `review` | | `personal` | `POST /tokens` | `Authorization: Bearer kbp_...` | chosen at creation |  Token strings are `<3-letter kind prefix>_<40 characters of [A-Za-z0-9]>` (`kbs_`, `kbd_`, `kbp_`), generated from a CSPRNG. `token_hash` = lowercase hex SHA-256 of the whole string; `token_prefix` = its first 8 characters. A token is valid iff `revoked_at IS NULL` and (`expires_at IS NULL` or `expires_at > now()`) and, when it has a `device_id`, that device is not revoked. Validity is checked on every request (no caching longer than 5 seconds), so revocation takes effect at once.  Scopes are independent (no scope implies another):  | Scope | Grants | |---|---| | `read` | Every `GET` of learning, media and reference data; `POST /parse`; `GET /events`; `GET /sync/pull`; exports. | | `write` | Create, change, delete notes, cards, decks, grammar, note types, presets, settings, word status, parse overrides, media metadata; upload blobs; `POST /sync/push` (non-review ops). | | `review` | `POST /review/answer`, `POST /review/undo`, `POST /drill/session`, `POST /cards/bulk/schedule`; `review.*` ops inside `POST /sync/push` (per op; without it the op is rejected with `insufficient_scope`). | | `import` | Start and cancel import, parse, dictionary and optimizer jobs; upload blobs. | | `admin` | Devices, pairing, tokens (a device\'s own row: `/devices/self` with `read`/`write`). |  Each operation lists the scope it needs in its `security` requirement: the array after the scheme name. When two alternatives are listed for one scheme the caller needs either. A request with no or an invalid credential gets `401 unauthenticated`; a valid credential lacking the scope gets `403 insufficient_scope`. Operations with `security: []` are public.  There are exactly two places where who may call is not just \"scheme + scope\", and both are declared in the operation, not only in prose: - `GET/PATCH /devices/self` (`read` / `write`, bearer only): the token must be bound to a   device, else `403 device_required`. A device never needs `admin` to manage its own row;   `/devices/{device_id}` is admin only. - `POST /ankiconnect` (`x-auth: body-key`): the token may instead be sent in the JSON body   property `key`; the empty `{}` alternative in its `security` only signals that no header is   needed. The scope for `key` is `x-body-key-scope`.  **CSRF.** A request authenticated by the session cookie whose method is not `GET` or `HEAD` MUST carry the header `X-Kotobaya-Csrf: 1`; otherwise `403 csrf_required`. The cookie is set with `HttpOnly; Secure; SameSite=Strict; Path=/api`. The server sends no CORS headers (the web app is served from the same origin). If both a cookie and an `Authorization` header are present, the header wins and the cookie is ignored.  **Audit.** Every request is logged with `token.id` and `token.name` (structured server log; there is no audit table or endpoint in v1). Ops record the token in `change.token_id`.  ## 3. Writes are sync ops  Learning data is only ever changed by applying sync ops (`contracts/sync`). Every REST operation that changes learning data is a convenience wrapper: the server builds the op(s) named in the operation\'s `x-emits-ops`, applies them through the same op-apply path as `POST /sync/push` in one transaction, and they then appear in `GET /sync/pull` for every device. Op names are those of `contracts/db/SCHEMA.md` section 4; `contracts/sync` is authoritative for names and payloads.  For an op the server builds on behalf of a REST call:  - `op_id` is a fresh UUIDv7; origin `device_id` is the **server device**   `00000000-0000-7000-8000-000000000001` (also for session and device tokens: only one party   ever generates HLCs for a given device id); `hlc` comes from the server\'s HLC clock;   `change.token_id` is the calling token. - A property absent from a `PATCH` body produces no register write. A property present with   the value the row already has still produces a write (it bumps the register\'s clock). - If a call changes nothing (documented per operation, e.g. deleting an already deleted   note), no op is emitted and the response is still successful. - Responses to mutating calls carry the header `Kotobaya-Seq`: the `change.seq` of the last op   the call emitted. A client holding a local replica knows it has seen the effect once its   pull cursor is at or past that value. The header is absent when no op was emitted.  **Idempotent creation.** Create bodies accept an optional client-generated `id` (UUIDv7). Re-sending a create with an `id` that already exists returns the existing resource with status `200` instead of `201` and emits nothing, whatever the rest of the body says. Clients that may retry MUST send `id`. When `id` is omitted the server generates one. `POST /review/answer` requires `id` (a duplicate review would corrupt scheduling); `POST /media/import` takes the same optional idempotency id as `media_id`.  The existence check and the emit are **one transaction that takes advisory lock `7033001` before the check** and holds it until commit (the lock `POST /sync/push` takes per op, SYNC.md 5.2): check whether `id` exists; if not, mint, apply and log the op(s) - for `createNote` the `note.created` and every `card.created` - then commit. Without the lock two concurrent retries of the same create both pass the check; the second `note.created` is a no-op on the row (SYNC.md 7.2) but its `card.created` ops carry fresh card ids, so the note would get duplicate cards. The same holds for every other id-idempotent call (`POST /review/answer`, `POST /review/undo`, `POST /media/import`), and a pushed op that creates the same id races safely because it takes the same lock.  **Tombstones and merges.** Deleted learning rows are tombstones (`deleted_at` set). `GET` by id of a deleted row returns `410 gone` unless `include_deleted=true`; lists omit deleted rows unless `include_deleted=true`. A note or card that lost a merge (`merged_into_id` set) is answered with its survivor: `GET`, `PATCH` and review calls addressed to the loser\'s id act on the survivor and the response body is the survivor (its `id` differs from the requested id; the response has `Content-Location` with the survivor\'s URL).  ## 4. Pagination  List operations that take `limit` and `cursor` return `{\"items\": [...], \"next_cursor\": ...}`. `next_cursor` is an opaque string, or `null` on the last page. Pass it back unchanged as `cursor` together with the same other parameters; changing any other parameter while passing a cursor is `422 validation_failed` (`pointer` `/cursor`). `limit` defaults to 50, maximum 500. Order is stable and documented per operation; rows created while paging may or may not appear.  **Cursor semantics (keyset, no snapshot).** A cursor encodes the sort-key values and the `id` of the last row returned; the next page is the rows strictly after that tuple in the current order, evaluated against the data at the time of that request. The order is not snapshotted. Consequently, while a client pages through a list sorted by a mutable key (`due_at`, `updated_at`, `lapses`, ...): - a row whose key changes so that it moves from after the cursor to before it is **skipped**; - a row whose key changes so that it moves from before the cursor to after it is **returned   again** (with its new values); - a row deleted while paging disappears; one created may or may not appear. Rows that do not change are returned exactly once. Clients MUST de-duplicate by `id` (last occurrence wins). A client that needs an exact set (bulk actions, exports) uses an `id`-sorted list where offered, `POST /cards/bulk` with `query`, or `GET /export`. Lists sorted by an immutable key (`id`, `created_at`) have no skips or repeats. An empty result is `{\"items\": [], \"next_cursor\": null}` with status `200`.  ## 5. Errors  Every non-2xx response has the body `application/problem+json` (RFC 9457), schema `Problem`. `code` is the stable machine-readable identifier; `type` is `urn:kotobaya:problem:<code>`.  | Status | `code` | When | |---|---|---| | 400 | `invalid_request` | Body is not valid JSON, wrong content type framing, malformed header; `POST /sync/push` body not valid against `PushRequestLenient`. | | 401 | `unauthenticated` | No credential, or it is unknown, expired or revoked. | | 401 | `invalid_credentials` | Wrong password or TOTP code (`POST /auth/login`). | | 401 | `totp_required` | TOTP is enabled and `totp_code` was not sent. | | 401 | `invalid_pairing_code` | Pairing code unknown, expired or already used. | | 403 | `insufficient_scope` | Credential lacks the scope; `required_scope` names it. | | 403 | `csrf_required` | Cookie-authenticated write without `X-Kotobaya-Csrf`. | | 403 | `device_required` | `POST /sync/push` or `/devices/self` with a credential that has no `device_id`. | | 404 | `not_found` | No such resource (also: id of another resource kind). | | 409 | `already_exists` | Natural-key collision; `existing_id` names the holder. | | 409 | `builtin_immutable` | Deleting a built-in note type or the Default preset, or removing a built-in field. | | 409 | `in_use` | Deleting a note type that live notes use. | | 409 | `deck_read_only` | Changing items or deleting a `media`/`list` deck through deck endpoints. | | 409 | `not_applicable` | The resource kind does not support the operation (e.g. items of a smart deck). | | 409 | `card_not_reviewable` | Card is suspended. | | 409 | `job_not_cancellable` | Job already finished. | | 410 | `gone` | Tombstoned row requested without `include_deleted=true`. | | 413 | `payload_too_large` | Body over the limit in `GET /meta`. On `POST /sync/push` only for the whole batch (the client halves it); one op over `max_op_bytes` is a per-op `op_too_large` result instead. | | 415 | `unsupported_media_type` | Wrong `Content-Type`. | | 422 | `validation_failed` | Well-formed but invalid; `errors[]` lists each problem. | | 422 | `invalid_query` | Card search string does not parse; `errors[0].position` is the UTF-16 offset. | | 422 | `bulk_too_large` | Bulk selector matched more than 5000 cards. | | 422 | `hash_mismatch` | Uploaded bytes do not hash to the `{hash}` in the path. | | 429 | `rate_limited` | Too many login or pairing attempts; see `Retry-After`. | | 500 | `internal` | Bug. Safe to retry idempotent calls. | | 503 | `unavailable` | Database or object store unreachable. Retry with backoff. |  Check order: 401, then 403, then 404/410, then 415/400, then 422, then 409.  ## 6. Events  `GET /events` is a server-sent events stream; see that operation. Event payload schemas are `Event*` in components. 
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
    type Coverage,
    CoverageFromJSON,
    CoverageToJSON,
} from '../models/Coverage';
import {
    type Job,
    JobFromJSON,
    JobToJSON,
} from '../models/Job';
import {
    type JpdbDeckImportRequest,
    JpdbDeckImportRequestFromJSON,
    JpdbDeckImportRequestToJSON,
} from '../models/JpdbDeckImportRequest';
import {
    type JpdbHistoryImportRequest,
    JpdbHistoryImportRequestFromJSON,
    JpdbHistoryImportRequestToJSON,
} from '../models/JpdbHistoryImportRequest';
import {
    type Media,
    MediaFromJSON,
    MediaToJSON,
} from '../models/Media';
import {
    type MediaImportRequest,
    MediaImportRequestFromJSON,
    MediaImportRequestToJSON,
} from '../models/MediaImportRequest';
import {
    type MediaKind,
    MediaKindFromJSON,
    MediaKindToJSON,
} from '../models/MediaKind';
import {
    type MediaPage,
    MediaPageFromJSON,
    MediaPageToJSON,
} from '../models/MediaPage';
import {
    type MediaPatch,
    MediaPatchFromJSON,
    MediaPatchToJSON,
} from '../models/MediaPatch';
import {
    type Problem,
    ProblemFromJSON,
    ProblemToJSON,
} from '../models/Problem';
import {
    type Sentence,
    SentenceFromJSON,
    SentenceToJSON,
} from '../models/Sentence';
import {
    type SentencePage,
    SentencePageFromJSON,
    SentencePageToJSON,
} from '../models/SentencePage';

export interface DeleteMediaRequest {
    /**
     * Media id.
     */
    mediaId: string;
}

export interface GetMediaRequest {
    /**
     * Media id.
     */
    mediaId: string;
    /**
     * Also return tombstoned rows (`deleted_at` set).
     */
    includeDeleted?: boolean;
}

export interface GetMediaCoverageRequest {
    /**
     * Media id.
     */
    mediaId: string;
    /**
     * Restrict to one segment of this media (`404` if it is not one).
     */
    segmentId?: string;
    /**
     * Classes left out of every coverage number, comma-separated. `names`, `particles`,
     * `auxiliary` = entries whose `occurrence.pos_class` is `name`, `particle`, `auxiliary`;
     * `ignored` = entries whose word status is `blacklisted` or `ignored`; `unmapped` = tokens
     * that matched no dictionary entry. Default `ignored`. Pass an empty value (`hide=`) to hide
     * nothing.
     * 
     */
    hide?: Set<GetMediaCoverageHideEnum>;
}

export interface GetSentenceRequest {
    /**
     * Sentence id.
     */
    sentenceId: string;
}

export interface ImportJpdbDeckRequest {
    /**
     * 
     */
    jpdbDeckImportRequest: JpdbDeckImportRequest;
}

export interface ImportJpdbHistoryRequest {
    /**
     * 
     */
    jpdbHistoryImportRequest: JpdbHistoryImportRequest;
}

export interface ImportMediaRequest {
    /**
     * 
     */
    mediaImportRequest: MediaImportRequest;
}

export interface ListMediaRequest {
    /**
     * Only media of this kind.
     */
    kind?: MediaKind;
    /**
     * Case-insensitive substring of the title.
     */
    q?: string;
    /**
     * Also return tombstoned rows (`deleted_at` set).
     */
    includeDeleted?: boolean;
    /**
     * Page size.
     */
    limit?: number;
    /**
     * Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`.
     */
    cursor?: string;
}

export interface ListMediaSentencesRequest {
    /**
     * Media id.
     */
    mediaId: string;
    /**
     * `all` (default); `i+1` = sentences with exactly one unknown word; `i+0` = none.
     * "Unknown word" = a distinct entry among the sentence's tokens whose effective word status
     * is `new`; tokens of class `particle`, `auxiliary`, `name`, unmapped tokens and entries
     * with status `blacklisted`/`ignored` are not counted. Because an unencoded `+` in a query
     * string decodes to a space, servers MUST also accept `i 1` and `i 0`.
     * 
     */
    filter?: ListMediaSentencesFilterEnum;
    /**
     * Only sentences of this segment.
     */
    segmentId?: string;
    /**
     * Substring of the sentence text (exact characters, no normalisation).
     */
    q?: string;
    /**
     * Only sentences with a token mapped to this entry.
     */
    entrySeq?: number;
    /**
     * Only sentences with a live span of this grammar point.
     */
    grammarPointId?: string;
    /**
     * Page size.
     */
    limit?: number;
    /**
     * Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`.
     */
    cursor?: string;
}

export interface ListSentencesRequest {
    /**
     * Only sentences of this media.
     */
    mediaId?: string;
    /**
     * `all` (default); `i+1` = sentences with exactly one unknown word; `i+0` = none.
     * "Unknown word" = a distinct entry among the sentence's tokens whose effective word status
     * is `new`; tokens of class `particle`, `auxiliary`, `name`, unmapped tokens and entries
     * with status `blacklisted`/`ignored` are not counted. Because an unencoded `+` in a query
     * string decodes to a space, servers MUST also accept `i 1` and `i 0`.
     * 
     */
    filter?: ListSentencesFilterEnum;
    /**
     * Only sentences of this segment.
     */
    segmentId?: string;
    /**
     * Substring of the sentence text (exact characters, no normalisation).
     */
    q?: string;
    /**
     * Only sentences with a token mapped to this entry.
     */
    entrySeq?: number;
    /**
     * Only sentences with a live span of this grammar point.
     */
    grammarPointId?: string;
    /**
     * Page size.
     */
    limit?: number;
    /**
     * Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`.
     */
    cursor?: string;
}

export interface ReparseMediaRequest {
    /**
     * Media id.
     */
    mediaId: string;
}

export interface UpdateMediaRequest {
    /**
     * Media id.
     */
    mediaId: string;
    /**
     * 
     */
    mediaPatch: MediaPatch;
}

/**
 * 
 */
export class MediaApi extends runtime.BaseAPI {

    /**
     * Creates request options for deleteMedia without sending the request
     */
    async deleteMediaRequestOpts(requestParameters: DeleteMediaRequest): Promise<runtime.RequestOpts> {
        if (requestParameters['mediaId'] == null) {
            throw new runtime.RequiredError(
                'mediaId',
                'Required parameter "mediaId" was null or undefined when calling deleteMedia().'
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

        let urlPath = `/media/{media_id}`;
        urlPath = urlPath.replace('{media_id}', encodeURIComponent(String(requestParameters['mediaId'])));

        return {
            path: urlPath,
            method: 'DELETE',
            headers: headerParameters,
            query: queryParameters,
        };
    }

    /**
     * Server emits `media.deleted` and, as a consequence in the same transaction, `deck.deleted` for the media deck; then hard-deletes the media\'s segments, sentences, occurrences and detected grammar spans (SCHEMA.md 4.3). Notes mined from it, manual spans and reviews stay. Queued or running jobs for the media are cancelled. Already deleted: `204`, no op. 
     * Delete a media, its deck and its parsed content.
     */
    async deleteMediaRaw(requestParameters: DeleteMediaRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<void>> {
        const requestOptions = await this.deleteMediaRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.VoidApiResponse(response);
    }

    /**
     * Server emits `media.deleted` and, as a consequence in the same transaction, `deck.deleted` for the media deck; then hard-deletes the media\'s segments, sentences, occurrences and detected grammar spans (SCHEMA.md 4.3). Notes mined from it, manual spans and reviews stay. Queued or running jobs for the media are cancelled. Already deleted: `204`, no op. 
     * Delete a media, its deck and its parsed content.
     */
    async deleteMedia(requestParameters: DeleteMediaRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<void> {
        await this.deleteMediaRaw(requestParameters, initOverrides);
    }

    /**
     * Creates request options for getMedia without sending the request
     */
    async getMediaRequestOpts(requestParameters: GetMediaRequest): Promise<runtime.RequestOpts> {
        if (requestParameters['mediaId'] == null) {
            throw new runtime.RequiredError(
                'mediaId',
                'Required parameter "mediaId" was null or undefined when calling getMedia().'
            );
        }

        const queryParameters: any = {};

        if (requestParameters['includeDeleted'] != null) {
            queryParameters['include_deleted'] = requestParameters['includeDeleted'];
        }

        const headerParameters: runtime.HTTPHeaders = {};

        if (this.configuration && this.configuration.accessToken) {
            const token = this.configuration.accessToken;
            const tokenString = await token("bearerAuth", ["read"]);

            if (tokenString) {
                headerParameters["Authorization"] = `Bearer ${tokenString}`;
            }
        }

        let urlPath = `/media/{media_id}`;
        urlPath = urlPath.replace('{media_id}', encodeURIComponent(String(requestParameters['mediaId'])));

        return {
            path: urlPath,
            method: 'GET',
            headers: headerParameters,
            query: queryParameters,
        };
    }

    /**
     * One media with its segments.
     */
    async getMediaRaw(requestParameters: GetMediaRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<Media>> {
        const requestOptions = await this.getMediaRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => MediaFromJSON(jsonValue));
    }

    /**
     * One media with its segments.
     */
    async getMedia(requestParameters: GetMediaRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<Media> {
        const response = await this.getMediaRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for getMediaCoverage without sending the request
     */
    async getMediaCoverageRequestOpts(requestParameters: GetMediaCoverageRequest): Promise<runtime.RequestOpts> {
        if (requestParameters['mediaId'] == null) {
            throw new runtime.RequiredError(
                'mediaId',
                'Required parameter "mediaId" was null or undefined when calling getMediaCoverage().'
            );
        }

        const queryParameters: any = {};

        if (requestParameters['segmentId'] != null) {
            queryParameters['segment_id'] = requestParameters['segmentId'];
        }

        if (requestParameters['hide'] != null) {
            queryParameters['hide'] = Array.from(requestParameters['hide'])!.join(runtime.COLLECTION_FORMATS["csv"]);
        }

        const headerParameters: runtime.HTTPHeaders = {};

        if (this.configuration && this.configuration.accessToken) {
            const token = this.configuration.accessToken;
            const tokenString = await token("bearerAuth", ["read"]);

            if (tokenString) {
                headerParameters["Authorization"] = `Bearer ${tokenString}`;
            }
        }

        let urlPath = `/media/{media_id}/coverage`;
        urlPath = urlPath.replace('{media_id}', encodeURIComponent(String(requestParameters['mediaId'])));

        return {
            path: urlPath,
            method: 'GET',
            headers: headerParameters,
            query: queryParameters,
        };
    }

    /**
     * Computed at request time from `occurrence`, `media_segment` and `word_status`, so it always reflects the current statuses. Definitions are on the `Coverage` schema. With `segment_id` the numbers cover only that segment and `segments` has that one element. Media with `content_version = 0` (not parsed yet) returns all-zero numbers. 
     * Coverage of a media (or one segment) by your word statuses.
     */
    async getMediaCoverageRaw(requestParameters: GetMediaCoverageRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<Coverage>> {
        const requestOptions = await this.getMediaCoverageRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => CoverageFromJSON(jsonValue));
    }

    /**
     * Computed at request time from `occurrence`, `media_segment` and `word_status`, so it always reflects the current statuses. Definitions are on the `Coverage` schema. With `segment_id` the numbers cover only that segment and `segments` has that one element. Media with `content_version = 0` (not parsed yet) returns all-zero numbers. 
     * Coverage of a media (or one segment) by your word statuses.
     */
    async getMediaCoverage(requestParameters: GetMediaCoverageRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<Coverage> {
        const response = await this.getMediaCoverageRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for getSentence without sending the request
     */
    async getSentenceRequestOpts(requestParameters: GetSentenceRequest): Promise<runtime.RequestOpts> {
        if (requestParameters['sentenceId'] == null) {
            throw new runtime.RequiredError(
                'sentenceId',
                'Required parameter "sentenceId" was null or undefined when calling getSentence().'
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

        let urlPath = `/sentences/{sentence_id}`;
        urlPath = urlPath.replace('{sentence_id}', encodeURIComponent(String(requestParameters['sentenceId'])));

        return {
            path: urlPath,
            method: 'GET',
            headers: headerParameters,
            query: queryParameters,
        };
    }

    /**
     * `404` when the sentence does not exist (never imported, or purged with its media or by a re-parse).
     * One sentence with tokens and grammar spans.
     */
    async getSentenceRaw(requestParameters: GetSentenceRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<Sentence>> {
        const requestOptions = await this.getSentenceRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => SentenceFromJSON(jsonValue));
    }

    /**
     * `404` when the sentence does not exist (never imported, or purged with its media or by a re-parse).
     * One sentence with tokens and grammar spans.
     */
    async getSentence(requestParameters: GetSentenceRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<Sentence> {
        const response = await this.getSentenceRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for importJpdbDeck without sending the request
     */
    async importJpdbDeckRequestOpts(requestParameters: ImportJpdbDeckRequest): Promise<runtime.RequestOpts> {
        if (requestParameters['jpdbDeckImportRequest'] == null) {
            throw new runtime.RequiredError(
                'jpdbDeckImportRequest',
                'Required parameter "jpdbDeckImportRequest" was null or undefined when calling importJpdbDeck().'
            );
        }

        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};

        headerParameters['Content-Type'] = 'application/json';

        if (this.configuration && this.configuration.accessToken) {
            const token = this.configuration.accessToken;
            const tokenString = await token("bearerAuth", ["import"]);

            if (tokenString) {
                headerParameters["Authorization"] = `Bearer ${tokenString}`;
            }
        }

        let urlPath = `/media/import/jpdb`;

        return {
            path: urlPath,
            method: 'POST',
            headers: headerParameters,
            query: queryParameters,
            body: JpdbDeckImportRequestToJSON(requestParameters['jpdbDeckImportRequest']),
        };
    }

    /**
     * Enqueues a job of kind `jpdb-deck-import` (dedupe key `jpdb-deck-import:<jpdb_id>`) and returns it with `202`. If a live media with this `jpdb_id` exists the job updates it (same `media_id`, new content version); otherwise `media_id` is pre-allocated as in `POST /media/import`. The job emits, as server ops, `media.created` and `deck.created` (first import only), `media.content_published`, `deck.content_published`. The result has one segment of kind `whole` and no sentences. Vocabulary that maps to no JMdict entry is listed in the job result (`unmapped`), never silently dropped. An equivalent queued or running job is returned with `200`. 
     * Import a pre-parsed jpdb media deck (vocabulary with occurrence counts).
     */
    async importJpdbDeckRaw(requestParameters: ImportJpdbDeckRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<Job>> {
        const requestOptions = await this.importJpdbDeckRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => JobFromJSON(jsonValue));
    }

    /**
     * Enqueues a job of kind `jpdb-deck-import` (dedupe key `jpdb-deck-import:<jpdb_id>`) and returns it with `202`. If a live media with this `jpdb_id` exists the job updates it (same `media_id`, new content version); otherwise `media_id` is pre-allocated as in `POST /media/import`. The job emits, as server ops, `media.created` and `deck.created` (first import only), `media.content_published`, `deck.content_published`. The result has one segment of kind `whole` and no sentences. Vocabulary that maps to no JMdict entry is listed in the job result (`unmapped`), never silently dropped. An equivalent queued or running job is returned with `200`. 
     * Import a pre-parsed jpdb media deck (vocabulary with occurrence counts).
     */
    async importJpdbDeck(requestParameters: ImportJpdbDeckRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<Job> {
        const response = await this.importJpdbDeckRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for importJpdbHistory without sending the request
     */
    async importJpdbHistoryRequestOpts(requestParameters: ImportJpdbHistoryRequest): Promise<runtime.RequestOpts> {
        if (requestParameters['jpdbHistoryImportRequest'] == null) {
            throw new runtime.RequiredError(
                'jpdbHistoryImportRequest',
                'Required parameter "jpdbHistoryImportRequest" was null or undefined when calling importJpdbHistory().'
            );
        }

        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};

        headerParameters['Content-Type'] = 'application/json';

        if (this.configuration && this.configuration.accessToken) {
            const token = this.configuration.accessToken;
            const tokenString = await token("bearerAuth", ["import"]);

            if (tokenString) {
                headerParameters["Authorization"] = `Bearer ${tokenString}`;
            }
        }

        let urlPath = `/import/jpdb/history`;

        return {
            path: urlPath,
            method: 'POST',
            headers: headerParameters,
            query: queryParameters,
            body: JpdbHistoryImportRequestToJSON(requestParameters['jpdbHistoryImportRequest']),
        };
    }

    /**
     * Upload the export JSON with `PUT /blobs/{hash}` (kind `upload`), then call this. Enqueues a job of kind `jpdb-history-import` (dedupe key `jpdb-history-import`). The job emits, as server ops: `note.created` and `card.created` for vocabulary and kanji without a note or card, `review.logged` for every historical review (`import_source = jpdb`, deterministic review ids, see \"Review ids\", so that re-running imports nothing twice), and `word.status_set` (payload `origin = jpdb_import`, stored as `override_origin`) for known-only words. The observed export carries no never-forget or blacklist data, so none is written. Records that cannot be mapped are counted and listed in the job result. An equivalent queued or running job is returned with `200`. Mapping rules follow ADR-008 and ADR-009 (C-4).  **Export arrays (C-2, C-4; spikes/jpdb-map/REPORT.md \"Export structure\").** The export is one object whose array members are read as follows; any other member (top level, row or event) is ignored, a missing or non-array member counts as empty, and `from_anki` is accepted with any value (`true` included) and ignored. | Array | Row | Becomes | |---|---|---| | `cards_vocabulary_jp_en` | `vid`, `spelling`, `reading`, `reviews[]` | vocab note (built-in Vocab type `00000000-0000-7000-8000-000000000101`, `entry_seq` = the mapped JMdict seq, `origin = jpdb_import`) with exactly one card, kind `meaning` (JP → EN) | | `cards_vocabulary_en_jp` | same | nothing: counted `vocabulary_en_jp_ignored`, its events `reviews_skipped`; no note, card, status or review (ADR-009(1): no production cards) | | `cards_kanji_char_keyword` | `character`, `reviews[]`, optional `keyword` | kanji note, card kind `keyword` (kanji → keyword) | | `cards_kanji_keyword_char` | `character`, `reviews[]`, optional `keyword` | kanji note, card kind `writing` (keyword → kanji), created **suspended** |  Tolerated drift: a row whose `reviews` is missing or not an array has zero events; a vocab row with a missing or non-integer `vid` is mapped by text only and, if unmapped, listed with `jpdb_vid = 0`; a missing `spelling` / `reading` reads as `\"\"`. An element of `reviews[]` that is not an object with an integer `timestamp` and a string `grade` is *malformed* (counted `reviews_unknown_grade`).  **Vocab rows and groups.** Each `cards_vocabulary_jp_en` row is mapped to a JMdict seq (vid lookup first; spikes/jpdb-map/REPORT.md \"Mapping\") or listed under `unmapped`. Mapped rows are grouped by seq: rows sharing a seq (about 260 vids repeat in 2-3 rows with different spellings; two vids mapping to one seq are also one group) form **one group** = one note and one `meaning` card, whose history is the union of the rows\' events in ascending `timestamp` order (ties: array order, row order, element order). Within a group, an event with the same `timestamp` and `grade` as an earlier one is an **exact duplicate**: dropped and counted `reviews_duplicate_dropped` (malformed events are never duplicates). The group\'s kept, well-formed events decide its fate: - all of them are `known` and there is at least one: **status-only** (ADR-009(3)). No   note or card. If `import_word_status`, one `word.status_set` status `known`, `origin =   jpdb_import` for the seq, unless the word already has a live override (any status,   any origin; then nothing is written, so a re-run never overwrites the user\'s choice).   Every row of the group counts `vocabulary_status_only`. - otherwise (at least one non-`known` event, or no well-formed events at all):   **carded**. Every row counts `vocabulary_carded`. The group maps to the live note with   that `entry_seq` if one exists (`notes_existing`, once per seq), else `note.created`   (`notes_created`). The job then ensures a live card of kind `meaning` on it   (`card.created`, `cards_created`, only if none exists) - only that kind, never the   Vocab type\'s other default kinds (`reading`, `production`); existing cards of other   kinds are left alone and get no history. A `known` event of a carded group writes   nothing (FSRS schedules from the reviews; no override). So `notes_created + notes_existing` = distinct seqs of carded groups, and `cards_created <= notes_created + notes_existing` (about equal to `notes_created`).  **Kanji rows and groups.** A row whose `character` is exactly one Unicode code point is mapped; any other is listed under `kanji_unmapped`. Mapped rows are grouped by (`character`, card kind); a group\'s events are merged and de-duplicated exactly as for vocab. The character maps to the live note with that `kanji_literal` if one exists (counted `kanji_notes_existing`, once per character), else to a new note: `note.created` with `note_type_id` = the built-in Kanji type (`00000000-0000-7000-8000-000000000102`, base type `kanji`), `kanji_literal` = `character`, `fields` = `{\"literal\": character}` plus `\"keyword\": <text>` when a row of that character carries a `keyword` member that is a non-empty string after trimming (ADR-009(4); the first such row in array order `cards_kanji_char_keyword`, `cards_kanji_keyword_char`, then row order; the observed export has no `keyword` member, so it is normally absent and the renderer fills it from `ref.kanji`; user-editable either way). `readings` and `notes` are never written. An existing note\'s fields are never changed. `origin = jpdb_import`. Both arrays naming the same character share one note. The job then ensures a live card of the group\'s kind (`keyword` or `writing`) - only that kind, never the note type\'s other default kinds. A card is created **with `suspended = true` in its `card.created` payload** (no separate `card.suspended` op; counted `kanji_cards_suspended`) when its kind is `writing` (ADR-009(1): preserved with its history, never shown unless the user unsuspends it) or when the group is known-only (all kept well-formed events `known`, at least one; ADR-009(3)). A card that already exists keeps its `suspended` state (a re-run never re-suspends a card the user unsuspended). Every mapped kanji row counts `kanji_carded`, known-only ones included (kanji have no word status). A character missing from `ref.kanji` is still imported.  **Grades** (each kept, well-formed event of a carded vocab group or a kanji group; `timestamp` is Unix seconds UTC → `reviewed_at` = timestamp × 1000, `utc_offset_min = null`, `duration_ms = null`, `review_type = scheduled`, `import_source = jpdb`; grades match exactly, case-sensitive): | `grade` | Effect | |---|---| | `nothing` | `review.logged` rating 1, button `nothing` | | `something` | rating 1, button `something` | | `hard` | rating 2, button `hard` (FSRS Hard; ADR-008) | | `okay` | rating 3, button `okay` | | `easy` | rating 4, button `easy` | | `pass` | rating 3, button `null` | | `fail` | rating 1, button `null` | | `unknown` | no review; the card\'s creation is all it means. Counted `status_events_unknown` | | `known` | no review. Status-only vocab group: the word status above. Carded vocab group or any kanji group: nothing (a known-only kanji card is created suspended). Counted `status_events_known` | | anything else | nothing; counted `reviews_unknown_grade` |  **Review ids.** Each rating event gets `id` = a UUIDv7 whose 48-bit timestamp is `reviewed_at` and whose other bits come from `h = SHA-256(UTF-8(name))`, `name = \"jpdb-review/v1|\" + key + \"|\" + timestamp + \"|\" + grade + \"|\" + occ`: `key` is `\"v\" + vid` (decimal, the event\'s own row\'s vid; `\"v0\"` if absent) for vocab, `\"k:keyword:\" + character` or `\"k:writing:\" + character` for kanji; `timestamp` is the export\'s decimal seconds; `occ` is the 0-based index of the event among the group\'s kept events with the same `timestamp`, in group order. Bytes: `b[0..5]` = `reviewed_at` big-endian, `b[6] = 0x70 | (h[0] & 0x0F)`, `b[7] = h[1]`, `b[8] = 0x80 | (h[2] & 0x3F)`, `b[9..15] = h[3..9]`. A rating event whose id already exists in `review_log` (any state, undone included) is not emitted again (`reviews_already_imported`). Rating ops of one card are emitted in group order, after the card\'s `card.created` (if any).  **`import_reviews = false`**: nothing but word statuses is written. Rows are bucketed by the same rules (`*_carded` then means \"would get a note and card\"); `notes_created`, `notes_existing`, `cards_created`, `kanji_notes_*`, `kanji_cards_*` are 0; rating events count as `reviews_skipped`; status, duplicate and unknown-grade events as usual. **`import_word_status = false`**: no `word.status_set`; bucketing is unchanged.  **Counting.** Every input record - each row of the four arrays and each element of its `reviews[]` - is counted in exactly one bucket of `JobResultJpdbHistory` (its description gives the three sums that must hold and the order in which an event\'s bucket is decided). 
     * Import jpdb review history and word statuses from a jpdb export file.
     */
    async importJpdbHistoryRaw(requestParameters: ImportJpdbHistoryRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<Job>> {
        const requestOptions = await this.importJpdbHistoryRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => JobFromJSON(jsonValue));
    }

    /**
     * Upload the export JSON with `PUT /blobs/{hash}` (kind `upload`), then call this. Enqueues a job of kind `jpdb-history-import` (dedupe key `jpdb-history-import`). The job emits, as server ops: `note.created` and `card.created` for vocabulary and kanji without a note or card, `review.logged` for every historical review (`import_source = jpdb`, deterministic review ids, see \"Review ids\", so that re-running imports nothing twice), and `word.status_set` (payload `origin = jpdb_import`, stored as `override_origin`) for known-only words. The observed export carries no never-forget or blacklist data, so none is written. Records that cannot be mapped are counted and listed in the job result. An equivalent queued or running job is returned with `200`. Mapping rules follow ADR-008 and ADR-009 (C-4).  **Export arrays (C-2, C-4; spikes/jpdb-map/REPORT.md \"Export structure\").** The export is one object whose array members are read as follows; any other member (top level, row or event) is ignored, a missing or non-array member counts as empty, and `from_anki` is accepted with any value (`true` included) and ignored. | Array | Row | Becomes | |---|---|---| | `cards_vocabulary_jp_en` | `vid`, `spelling`, `reading`, `reviews[]` | vocab note (built-in Vocab type `00000000-0000-7000-8000-000000000101`, `entry_seq` = the mapped JMdict seq, `origin = jpdb_import`) with exactly one card, kind `meaning` (JP → EN) | | `cards_vocabulary_en_jp` | same | nothing: counted `vocabulary_en_jp_ignored`, its events `reviews_skipped`; no note, card, status or review (ADR-009(1): no production cards) | | `cards_kanji_char_keyword` | `character`, `reviews[]`, optional `keyword` | kanji note, card kind `keyword` (kanji → keyword) | | `cards_kanji_keyword_char` | `character`, `reviews[]`, optional `keyword` | kanji note, card kind `writing` (keyword → kanji), created **suspended** |  Tolerated drift: a row whose `reviews` is missing or not an array has zero events; a vocab row with a missing or non-integer `vid` is mapped by text only and, if unmapped, listed with `jpdb_vid = 0`; a missing `spelling` / `reading` reads as `\"\"`. An element of `reviews[]` that is not an object with an integer `timestamp` and a string `grade` is *malformed* (counted `reviews_unknown_grade`).  **Vocab rows and groups.** Each `cards_vocabulary_jp_en` row is mapped to a JMdict seq (vid lookup first; spikes/jpdb-map/REPORT.md \"Mapping\") or listed under `unmapped`. Mapped rows are grouped by seq: rows sharing a seq (about 260 vids repeat in 2-3 rows with different spellings; two vids mapping to one seq are also one group) form **one group** = one note and one `meaning` card, whose history is the union of the rows\' events in ascending `timestamp` order (ties: array order, row order, element order). Within a group, an event with the same `timestamp` and `grade` as an earlier one is an **exact duplicate**: dropped and counted `reviews_duplicate_dropped` (malformed events are never duplicates). The group\'s kept, well-formed events decide its fate: - all of them are `known` and there is at least one: **status-only** (ADR-009(3)). No   note or card. If `import_word_status`, one `word.status_set` status `known`, `origin =   jpdb_import` for the seq, unless the word already has a live override (any status,   any origin; then nothing is written, so a re-run never overwrites the user\'s choice).   Every row of the group counts `vocabulary_status_only`. - otherwise (at least one non-`known` event, or no well-formed events at all):   **carded**. Every row counts `vocabulary_carded`. The group maps to the live note with   that `entry_seq` if one exists (`notes_existing`, once per seq), else `note.created`   (`notes_created`). The job then ensures a live card of kind `meaning` on it   (`card.created`, `cards_created`, only if none exists) - only that kind, never the   Vocab type\'s other default kinds (`reading`, `production`); existing cards of other   kinds are left alone and get no history. A `known` event of a carded group writes   nothing (FSRS schedules from the reviews; no override). So `notes_created + notes_existing` = distinct seqs of carded groups, and `cards_created <= notes_created + notes_existing` (about equal to `notes_created`).  **Kanji rows and groups.** A row whose `character` is exactly one Unicode code point is mapped; any other is listed under `kanji_unmapped`. Mapped rows are grouped by (`character`, card kind); a group\'s events are merged and de-duplicated exactly as for vocab. The character maps to the live note with that `kanji_literal` if one exists (counted `kanji_notes_existing`, once per character), else to a new note: `note.created` with `note_type_id` = the built-in Kanji type (`00000000-0000-7000-8000-000000000102`, base type `kanji`), `kanji_literal` = `character`, `fields` = `{\"literal\": character}` plus `\"keyword\": <text>` when a row of that character carries a `keyword` member that is a non-empty string after trimming (ADR-009(4); the first such row in array order `cards_kanji_char_keyword`, `cards_kanji_keyword_char`, then row order; the observed export has no `keyword` member, so it is normally absent and the renderer fills it from `ref.kanji`; user-editable either way). `readings` and `notes` are never written. An existing note\'s fields are never changed. `origin = jpdb_import`. Both arrays naming the same character share one note. The job then ensures a live card of the group\'s kind (`keyword` or `writing`) - only that kind, never the note type\'s other default kinds. A card is created **with `suspended = true` in its `card.created` payload** (no separate `card.suspended` op; counted `kanji_cards_suspended`) when its kind is `writing` (ADR-009(1): preserved with its history, never shown unless the user unsuspends it) or when the group is known-only (all kept well-formed events `known`, at least one; ADR-009(3)). A card that already exists keeps its `suspended` state (a re-run never re-suspends a card the user unsuspended). Every mapped kanji row counts `kanji_carded`, known-only ones included (kanji have no word status). A character missing from `ref.kanji` is still imported.  **Grades** (each kept, well-formed event of a carded vocab group or a kanji group; `timestamp` is Unix seconds UTC → `reviewed_at` = timestamp × 1000, `utc_offset_min = null`, `duration_ms = null`, `review_type = scheduled`, `import_source = jpdb`; grades match exactly, case-sensitive): | `grade` | Effect | |---|---| | `nothing` | `review.logged` rating 1, button `nothing` | | `something` | rating 1, button `something` | | `hard` | rating 2, button `hard` (FSRS Hard; ADR-008) | | `okay` | rating 3, button `okay` | | `easy` | rating 4, button `easy` | | `pass` | rating 3, button `null` | | `fail` | rating 1, button `null` | | `unknown` | no review; the card\'s creation is all it means. Counted `status_events_unknown` | | `known` | no review. Status-only vocab group: the word status above. Carded vocab group or any kanji group: nothing (a known-only kanji card is created suspended). Counted `status_events_known` | | anything else | nothing; counted `reviews_unknown_grade` |  **Review ids.** Each rating event gets `id` = a UUIDv7 whose 48-bit timestamp is `reviewed_at` and whose other bits come from `h = SHA-256(UTF-8(name))`, `name = \"jpdb-review/v1|\" + key + \"|\" + timestamp + \"|\" + grade + \"|\" + occ`: `key` is `\"v\" + vid` (decimal, the event\'s own row\'s vid; `\"v0\"` if absent) for vocab, `\"k:keyword:\" + character` or `\"k:writing:\" + character` for kanji; `timestamp` is the export\'s decimal seconds; `occ` is the 0-based index of the event among the group\'s kept events with the same `timestamp`, in group order. Bytes: `b[0..5]` = `reviewed_at` big-endian, `b[6] = 0x70 | (h[0] & 0x0F)`, `b[7] = h[1]`, `b[8] = 0x80 | (h[2] & 0x3F)`, `b[9..15] = h[3..9]`. A rating event whose id already exists in `review_log` (any state, undone included) is not emitted again (`reviews_already_imported`). Rating ops of one card are emitted in group order, after the card\'s `card.created` (if any).  **`import_reviews = false`**: nothing but word statuses is written. Rows are bucketed by the same rules (`*_carded` then means \"would get a note and card\"); `notes_created`, `notes_existing`, `cards_created`, `kanji_notes_*`, `kanji_cards_*` are 0; rating events count as `reviews_skipped`; status, duplicate and unknown-grade events as usual. **`import_word_status = false`**: no `word.status_set`; bucketing is unchanged.  **Counting.** Every input record - each row of the four arrays and each element of its `reviews[]` - is counted in exactly one bucket of `JobResultJpdbHistory` (its description gives the three sums that must hold and the order in which an event\'s bucket is decided). 
     * Import jpdb review history and word statuses from a jpdb export file.
     */
    async importJpdbHistory(requestParameters: ImportJpdbHistoryRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<Job> {
        const response = await this.importJpdbHistoryRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for importMedia without sending the request
     */
    async importMediaRequestOpts(requestParameters: ImportMediaRequest): Promise<runtime.RequestOpts> {
        if (requestParameters['mediaImportRequest'] == null) {
            throw new runtime.RequiredError(
                'mediaImportRequest',
                'Required parameter "mediaImportRequest" was null or undefined when calling importMedia().'
            );
        }

        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};

        headerParameters['Content-Type'] = 'application/json';

        if (this.configuration && this.configuration.accessToken) {
            const token = this.configuration.accessToken;
            const tokenString = await token("bearerAuth", ["import"]);

            if (tokenString) {
                headerParameters["Authorization"] = `Bearer ${tokenString}`;
            }
        }

        let urlPath = `/media/import`;

        return {
            path: urlPath,
            method: 'POST',
            headers: headerParameters,
            query: queryParameters,
            body: MediaImportRequestToJSON(requestParameters['mediaImportRequest']),
        };
    }

    /**
     * Enqueues a job of kind `media-import` and returns it with `202`. Files are uploaded first with `PUT /blobs/{hash}` (kind `upload`) and referenced by hash; there is no multipart variant.  - `source: file` - `files` required (1 to 500). Each file becomes one segment in array   order (an EPUB becomes one segment per chapter, in book order). Every `blob_hash` must   exist (`422` naming the element otherwise). - `source: url` - `url` required (http/https); fetched by the worker with readability   extraction; one segment. - `source: paste` - `text` required (1 to 1,000,000 characters); one segment. - New media: `title` and `kind` required. The server pre-allocates `media_id` (returned   in `job.payload.media_id`); the job creates the `media` row, its deck (`deck.id` =   `media_id`) and all content, emitting as server ops `media.created`, `deck.created`,   `media.content_published`, `deck.content_published`. `GET /media/{media_id}` is `404`   until the job has created the row. - Existing media: pass `media_id` (must be live, with `source` equal to this request\'s);   the new segments are appended after the existing ones and a new content version is   published. `title`/`kind`/`deck` must then be absent. - Dedupe key `media-import:<media_id>`: a second import into the same media while one is   queued or running is `409 already_exists` (`existing_id` = the job id). - **Idempotent retry: `import_id`.** Optional client-generated UUIDv7, minted once per   user action and reused on every retry; it becomes the job\'s `id`, and for new media   also the new `media_id` (and so the deck id). If a job with this id already exists   (any status), or - for new media - a `media` row with this id exists (live or deleted),   the server enqueues nothing and answers `200` with that job (or, if the job row is   gone, the most recent `media-import` job for that media; `404` if none), whatever the   rest of the body says. Without `import_id` a retry after a lost `202` imports twice   (a second media and deck); clients that may retry MUST send it. An `import_id` used by   a job of another kind is `409 already_exists` (`existing_id` = that job). 
     * Import your own file(s), a URL or pasted text as media (worker job).
     */
    async importMediaRaw(requestParameters: ImportMediaRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<Job>> {
        const requestOptions = await this.importMediaRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => JobFromJSON(jsonValue));
    }

    /**
     * Enqueues a job of kind `media-import` and returns it with `202`. Files are uploaded first with `PUT /blobs/{hash}` (kind `upload`) and referenced by hash; there is no multipart variant.  - `source: file` - `files` required (1 to 500). Each file becomes one segment in array   order (an EPUB becomes one segment per chapter, in book order). Every `blob_hash` must   exist (`422` naming the element otherwise). - `source: url` - `url` required (http/https); fetched by the worker with readability   extraction; one segment. - `source: paste` - `text` required (1 to 1,000,000 characters); one segment. - New media: `title` and `kind` required. The server pre-allocates `media_id` (returned   in `job.payload.media_id`); the job creates the `media` row, its deck (`deck.id` =   `media_id`) and all content, emitting as server ops `media.created`, `deck.created`,   `media.content_published`, `deck.content_published`. `GET /media/{media_id}` is `404`   until the job has created the row. - Existing media: pass `media_id` (must be live, with `source` equal to this request\'s);   the new segments are appended after the existing ones and a new content version is   published. `title`/`kind`/`deck` must then be absent. - Dedupe key `media-import:<media_id>`: a second import into the same media while one is   queued or running is `409 already_exists` (`existing_id` = the job id). - **Idempotent retry: `import_id`.** Optional client-generated UUIDv7, minted once per   user action and reused on every retry; it becomes the job\'s `id`, and for new media   also the new `media_id` (and so the deck id). If a job with this id already exists   (any status), or - for new media - a `media` row with this id exists (live or deleted),   the server enqueues nothing and answers `200` with that job (or, if the job row is   gone, the most recent `media-import` job for that media; `404` if none), whatever the   rest of the body says. Without `import_id` a retry after a lost `202` imports twice   (a second media and deck); clients that may retry MUST send it. An `import_id` used by   a job of another kind is `409 already_exists` (`existing_id` = that job). 
     * Import your own file(s), a URL or pasted text as media (worker job).
     */
    async importMedia(requestParameters: ImportMediaRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<Job> {
        const response = await this.importMediaRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for listMedia without sending the request
     */
    async listMediaRequestOpts(requestParameters: ListMediaRequest): Promise<runtime.RequestOpts> {
        const queryParameters: any = {};

        if (requestParameters['kind'] != null) {
            queryParameters['kind'] = requestParameters['kind'];
        }

        if (requestParameters['q'] != null) {
            queryParameters['q'] = requestParameters['q'];
        }

        if (requestParameters['includeDeleted'] != null) {
            queryParameters['include_deleted'] = requestParameters['includeDeleted'];
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

        let urlPath = `/media`;

        return {
            path: urlPath,
            method: 'GET',
            headers: headerParameters,
            query: queryParameters,
        };
    }

    /**
     * Ordered by `created_at` descending, then `id` descending.
     * List the media library.
     */
    async listMediaRaw(requestParameters: ListMediaRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<MediaPage>> {
        const requestOptions = await this.listMediaRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => MediaPageFromJSON(jsonValue));
    }

    /**
     * Ordered by `created_at` descending, then `id` descending.
     * List the media library.
     */
    async listMedia(requestParameters: ListMediaRequest = {}, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<MediaPage> {
        const response = await this.listMediaRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for listMediaSentences without sending the request
     */
    async listMediaSentencesRequestOpts(requestParameters: ListMediaSentencesRequest): Promise<runtime.RequestOpts> {
        if (requestParameters['mediaId'] == null) {
            throw new runtime.RequiredError(
                'mediaId',
                'Required parameter "mediaId" was null or undefined when calling listMediaSentences().'
            );
        }

        const queryParameters: any = {};

        if (requestParameters['filter'] != null) {
            queryParameters['filter'] = requestParameters['filter'];
        }

        if (requestParameters['segmentId'] != null) {
            queryParameters['segment_id'] = requestParameters['segmentId'];
        }

        if (requestParameters['q'] != null) {
            queryParameters['q'] = requestParameters['q'];
        }

        if (requestParameters['entrySeq'] != null) {
            queryParameters['entry_seq'] = requestParameters['entrySeq'];
        }

        if (requestParameters['grammarPointId'] != null) {
            queryParameters['grammar_point_id'] = requestParameters['grammarPointId'];
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

        let urlPath = `/media/{media_id}/sentences`;
        urlPath = urlPath.replace('{media_id}', encodeURIComponent(String(requestParameters['mediaId'])));

        return {
            path: urlPath,
            method: 'GET',
            headers: headerParameters,
            query: queryParameters,
        };
    }

    /**
     * Same as `GET /sentences` with `media_id` fixed. Ordered by segment `ordinal`, then sentence `ordinal`. jpdb-imported media have no sentences (empty page). 
     * Sentences of a media, optionally only i+1 sentences.
     */
    async listMediaSentencesRaw(requestParameters: ListMediaSentencesRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<SentencePage>> {
        const requestOptions = await this.listMediaSentencesRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => SentencePageFromJSON(jsonValue));
    }

    /**
     * Same as `GET /sentences` with `media_id` fixed. Ordered by segment `ordinal`, then sentence `ordinal`. jpdb-imported media have no sentences (empty page). 
     * Sentences of a media, optionally only i+1 sentences.
     */
    async listMediaSentences(requestParameters: ListMediaSentencesRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<SentencePage> {
        const response = await this.listMediaSentencesRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for listSentences without sending the request
     */
    async listSentencesRequestOpts(requestParameters: ListSentencesRequest): Promise<runtime.RequestOpts> {
        const queryParameters: any = {};

        if (requestParameters['mediaId'] != null) {
            queryParameters['media_id'] = requestParameters['mediaId'];
        }

        if (requestParameters['filter'] != null) {
            queryParameters['filter'] = requestParameters['filter'];
        }

        if (requestParameters['segmentId'] != null) {
            queryParameters['segment_id'] = requestParameters['segmentId'];
        }

        if (requestParameters['q'] != null) {
            queryParameters['q'] = requestParameters['q'];
        }

        if (requestParameters['entrySeq'] != null) {
            queryParameters['entry_seq'] = requestParameters['entrySeq'];
        }

        if (requestParameters['grammarPointId'] != null) {
            queryParameters['grammar_point_id'] = requestParameters['grammarPointId'];
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

        let urlPath = `/sentences`;

        return {
            path: urlPath,
            method: 'GET',
            headers: headerParameters,
            query: queryParameters,
        };
    }

    /**
     * All filters combine with AND. Ordered by media `created_at` descending, media id, segment `ordinal`, sentence `ordinal`. Sentences of deleted media do not exist any more and are never returned. LLM-generated sentences (`origin = generated`) are included and must be labelled by clients. Each sentence carries its live grammar spans (detected and manual). 
     * Search sentences across all media.
     */
    async listSentencesRaw(requestParameters: ListSentencesRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<SentencePage>> {
        const requestOptions = await this.listSentencesRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => SentencePageFromJSON(jsonValue));
    }

    /**
     * All filters combine with AND. Ordered by media `created_at` descending, media id, segment `ordinal`, sentence `ordinal`. Sentences of deleted media do not exist any more and are never returned. LLM-generated sentences (`origin = generated`) are included and must be labelled by clients. Each sentence carries its live grammar spans (detected and manual). 
     * Search sentences across all media.
     */
    async listSentences(requestParameters: ListSentencesRequest = {}, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<SentencePage> {
        const response = await this.listSentencesRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for reparseMedia without sending the request
     */
    async reparseMediaRequestOpts(requestParameters: ReparseMediaRequest): Promise<runtime.RequestOpts> {
        if (requestParameters['mediaId'] == null) {
            throw new runtime.RequiredError(
                'mediaId',
                'Required parameter "mediaId" was null or undefined when calling reparseMedia().'
            );
        }

        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};

        if (this.configuration && this.configuration.accessToken) {
            const token = this.configuration.accessToken;
            const tokenString = await token("bearerAuth", ["import"]);

            if (tokenString) {
                headerParameters["Authorization"] = `Bearer ${tokenString}`;
            }
        }

        let urlPath = `/media/{media_id}/reparse`;
        urlPath = urlPath.replace('{media_id}', encodeURIComponent(String(requestParameters['mediaId'])));

        return {
            path: urlPath,
            method: 'POST',
            headers: headerParameters,
            query: queryParameters,
        };
    }

    /**
     * Enqueues a job of kind `media-parse` (dedupe key `media-parse:<media_id>`); an equivalent queued or running job is returned with `200`. The job keeps segment and sentence ids as SCHEMA.md requires and makes the server emit `media.content_published` and `deck.content_published`. Media with `source = jpdb` has nothing to parse: `409 not_applicable`. 
     * Re-parse a media (after parse overrides or a dictionary refresh).
     */
    async reparseMediaRaw(requestParameters: ReparseMediaRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<Job>> {
        const requestOptions = await this.reparseMediaRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => JobFromJSON(jsonValue));
    }

    /**
     * Enqueues a job of kind `media-parse` (dedupe key `media-parse:<media_id>`); an equivalent queued or running job is returned with `200`. The job keeps segment and sentence ids as SCHEMA.md requires and makes the server emit `media.content_published` and `deck.content_published`. Media with `source = jpdb` has nothing to parse: `409 not_applicable`. 
     * Re-parse a media (after parse overrides or a dictionary refresh).
     */
    async reparseMedia(requestParameters: ReparseMediaRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<Job> {
        const response = await this.reparseMediaRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for updateMedia without sending the request
     */
    async updateMediaRequestOpts(requestParameters: UpdateMediaRequest): Promise<runtime.RequestOpts> {
        if (requestParameters['mediaId'] == null) {
            throw new runtime.RequiredError(
                'mediaId',
                'Required parameter "mediaId" was null or undefined when calling updateMedia().'
            );
        }

        if (requestParameters['mediaPatch'] == null) {
            throw new runtime.RequiredError(
                'mediaPatch',
                'Required parameter "mediaPatch" was null or undefined when calling updateMedia().'
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

        let urlPath = `/media/{media_id}`;
        urlPath = urlPath.replace('{media_id}', encodeURIComponent(String(requestParameters['mediaId'])));

        return {
            path: urlPath,
            method: 'PATCH',
            headers: headerParameters,
            query: queryParameters,
            body: MediaPatchToJSON(requestParameters['mediaPatch']),
        };
    }

    /**
     * Server emits one `media.updated` with the registers present (`title`, `kind`, `cover_blob_hash`). `cover_blob_hash` must be an existing blob of kind `image`, or `null` to remove the cover. The media deck\'s name is not changed (patch the deck). Empty body: `422`. 
     * Change a media\'s title, kind or cover.
     */
    async updateMediaRaw(requestParameters: UpdateMediaRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<Media>> {
        const requestOptions = await this.updateMediaRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => MediaFromJSON(jsonValue));
    }

    /**
     * Server emits one `media.updated` with the registers present (`title`, `kind`, `cover_blob_hash`). `cover_blob_hash` must be an existing blob of kind `image`, or `null` to remove the cover. The media deck\'s name is not changed (patch the deck). Empty body: `422`. 
     * Change a media\'s title, kind or cover.
     */
    async updateMedia(requestParameters: UpdateMediaRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<Media> {
        const response = await this.updateMediaRaw(requestParameters, initOverrides);
        return await response.value();
    }

}

/**
 * @export
 */
export const GetMediaCoverageHideEnum = {
    Names: 'names',
    Particles: 'particles',
    Auxiliary: 'auxiliary',
    Ignored: 'ignored',
    Unmapped: 'unmapped',
} as const;
export type GetMediaCoverageHideEnum = typeof GetMediaCoverageHideEnum[keyof typeof GetMediaCoverageHideEnum];
/**
 * @export
 */
export const ListMediaSentencesFilterEnum = {
    All: 'all',
    I0: 'i+0',
    I1: 'i+1',
} as const;
export type ListMediaSentencesFilterEnum = typeof ListMediaSentencesFilterEnum[keyof typeof ListMediaSentencesFilterEnum];
/**
 * @export
 */
export const ListSentencesFilterEnum = {
    All: 'all',
    I0: 'i+0',
    I1: 'i+1',
} as const;
export type ListSentencesFilterEnum = typeof ListSentencesFilterEnum[keyof typeof ListSentencesFilterEnum];
