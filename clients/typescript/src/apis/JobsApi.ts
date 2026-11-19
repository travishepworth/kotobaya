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
    type Job,
    JobFromJSON,
    JobToJSON,
} from '../models/Job';
import {
    type JobKind,
    JobKindFromJSON,
    JobKindToJSON,
} from '../models/JobKind';
import {
    type JobPage,
    JobPageFromJSON,
    JobPageToJSON,
} from '../models/JobPage';
import {
    type JobStatus,
    JobStatusFromJSON,
    JobStatusToJSON,
} from '../models/JobStatus';
import {
    type Problem,
    ProblemFromJSON,
    ProblemToJSON,
} from '../models/Problem';
import {
    type SpeechResolveRequest,
    SpeechResolveRequestFromJSON,
    SpeechResolveRequestToJSON,
} from '../models/SpeechResolveRequest';
import {
    type SpeechResolveResult,
    SpeechResolveResultFromJSON,
    SpeechResolveResultToJSON,
} from '../models/SpeechResolveResult';
import {
    type TtsRequest,
    TtsRequestFromJSON,
    TtsRequestToJSON,
} from '../models/TtsRequest';

export interface CancelJobRequest {
    /**
     * Job id.
     */
    jobId: string;
}

export interface GetJobRequest {
    /**
     * Job id.
     */
    jobId: string;
}

export interface ListJobsRequest {
    /**
     * Only jobs of this kind.
     */
    kind?: JobKind;
    /**
     * Only jobs in one of these states (comma-separated).
     */
    status?: Set<JobStatus>;
    /**
     * Page size.
     */
    limit?: number;
    /**
     * Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`.
     */
    cursor?: string;
}

export interface ResolveSpeechRequest {
    /**
     * 
     */
    speechResolveRequest: SpeechResolveRequest;
}

export interface SynthesizeSpeechRequest {
    /**
     * 
     */
    ttsRequest: TtsRequest;
}

/**
 * 
 */
export class JobsApi extends runtime.BaseAPI {

    /**
     * Creates request options for cancelJob without sending the request
     */
    async cancelJobRequestOpts(requestParameters: CancelJobRequest): Promise<runtime.RequestOpts> {
        if (requestParameters['jobId'] == null) {
            throw new runtime.RequiredError(
                'jobId',
                'Required parameter "jobId" was null or undefined when calling cancelJob().'
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

        let urlPath = `/jobs/{job_id}/cancel`;
        urlPath = urlPath.replace('{job_id}', encodeURIComponent(String(requestParameters['jobId'])));

        return {
            path: urlPath,
            method: 'POST',
            headers: headerParameters,
            query: queryParameters,
        };
    }

    /**
     * Sets `status = cancelled`, `finished_at = now`. A running job\'s worker notices at its next heartbeat (within 30 s) and stops; content it already published stays published. Already cancelled: `200`, unchanged. Succeeded or failed: `409 job_not_cancellable`. A server-internal job\'s id (C-9, see `Job`) answers `404 not_found`; cancel its parent instead, which also stops the child. 
     * Cancel a queued or running job.
     */
    async cancelJobRaw(requestParameters: CancelJobRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<Job>> {
        const requestOptions = await this.cancelJobRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => JobFromJSON(jsonValue));
    }

    /**
     * Sets `status = cancelled`, `finished_at = now`. A running job\'s worker notices at its next heartbeat (within 30 s) and stops; content it already published stays published. Already cancelled: `200`, unchanged. Succeeded or failed: `409 job_not_cancellable`. A server-internal job\'s id (C-9, see `Job`) answers `404 not_found`; cancel its parent instead, which also stops the child. 
     * Cancel a queued or running job.
     */
    async cancelJob(requestParameters: CancelJobRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<Job> {
        const response = await this.cancelJobRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for getJob without sending the request
     */
    async getJobRequestOpts(requestParameters: GetJobRequest): Promise<runtime.RequestOpts> {
        if (requestParameters['jobId'] == null) {
            throw new runtime.RequiredError(
                'jobId',
                'Required parameter "jobId" was null or undefined when calling getJob().'
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

        let urlPath = `/jobs/{job_id}`;
        urlPath = urlPath.replace('{job_id}', encodeURIComponent(String(requestParameters['jobId'])));

        return {
            path: urlPath,
            method: 'GET',
            headers: headerParameters,
            query: queryParameters,
        };
    }

    /**
     * The id of a server-internal job (kind not in `JobKind`, C-9, see `Job`) answers `404 not_found`, as if it did not exist.
     * One job with progress, result or error.
     */
    async getJobRaw(requestParameters: GetJobRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<Job>> {
        const requestOptions = await this.getJobRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => JobFromJSON(jsonValue));
    }

    /**
     * The id of a server-internal job (kind not in `JobKind`, C-9, see `Job`) answers `404 not_found`, as if it did not exist.
     * One job with progress, result or error.
     */
    async getJob(requestParameters: GetJobRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<Job> {
        const response = await this.getJobRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for listJobs without sending the request
     */
    async listJobsRequestOpts(requestParameters: ListJobsRequest): Promise<runtime.RequestOpts> {
        const queryParameters: any = {};

        if (requestParameters['kind'] != null) {
            queryParameters['kind'] = requestParameters['kind'];
        }

        if (requestParameters['status'] != null) {
            queryParameters['status'] = Array.from(requestParameters['status'])!.join(runtime.COLLECTION_FORMATS["csv"]);
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

        let urlPath = `/jobs`;

        return {
            path: urlPath,
            method: 'GET',
            headers: headerParameters,
            query: queryParameters,
        };
    }

    /**
     * Ordered by `created_at` descending, then `id` descending. Jobs are kept for at least 30 days after finishing. Server-internal jobs (kind not in `JobKind`, C-9, see `Job`) are never listed.
     * List background jobs (imports, parses, optimizer runs, ...).
     */
    async listJobsRaw(requestParameters: ListJobsRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<JobPage>> {
        const requestOptions = await this.listJobsRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => JobPageFromJSON(jsonValue));
    }

    /**
     * Ordered by `created_at` descending, then `id` descending. Jobs are kept for at least 30 days after finishing. Server-internal jobs (kind not in `JobKind`, C-9, see `Job`) are never listed.
     * List background jobs (imports, parses, optimizer runs, ...).
     */
    async listJobs(requestParameters: ListJobsRequest = {}, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<JobPage> {
        const response = await this.listJobsRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for resolveSpeech without sending the request
     */
    async resolveSpeechRequestOpts(requestParameters: ResolveSpeechRequest): Promise<runtime.RequestOpts> {
        if (requestParameters['speechResolveRequest'] == null) {
            throw new runtime.RequiredError(
                'speechResolveRequest',
                'Required parameter "speechResolveRequest" was null or undefined when calling resolveSpeech().'
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

        let urlPath = `/tts/resolve`;

        return {
            path: urlPath,
            method: 'POST',
            headers: headerParameters,
            query: queryParameters,
            body: SpeechResolveRequestToJSON(requestParameters['speechResolveRequest']),
        };
    }

    /**
     * ADR-047 (C-29). The one way a client turns \"this word should have audio\" into a blob hash. Answers at once from the system table `tts_audio`; whatever is not generated yet is queued as a `tts` job and reported `pending`, and the caller asks again. It changes no learning data and emits no op: generated audio is derived data (SCHEMA.md 6.6).  **Items.** 1 to 200, each **exactly one** of: - `text` (with optional `voice`): a pair. `voice` absent or null = the effective voice   (setting `tts.voice` when valid, else `\"3\"`). - `note_id`: the word audio of that note as the core `WordAudioResolver` decides it (the   note\'s own `[sound:]` first, else the pair of its speech text and the effective voice).   For clients that show a card outside a review queue.  An item with both, or neither, or `voice` together with `note_id`, is `422` (pointer `/items/<i>`); nothing is enqueued for any item of a rejected request. Duplicate items are allowed and answered alike.  **Result.** `items` has one `SpeechResolution` per request item, in request order:  | `status` | Meaning | `blob_hash` | Enqueues | |---|---|---|---| | `ready` | Generated, or the note\'s own audio and `app.blob` has that hash: play `blob_hash` through `GET /blobs/{hash}`. | hash | no | | `pending` | Not generated yet. A `tts` job for the pair is queued or running, or will be queued by a later call (see \"Backlog\"). Ask again after `retry_after_ms`. | null | yes, unless one exists | | `failed` | The last attempt failed less than 600 s ago (VOICEVOX down, unknown voice). No new job until then; ask again after `retry_after_ms`. Also - a `note_id` whose own `[sound:<hash>]` names a blob the server does not have (never uploaded, or lost) - `source` `note`, `blob_hash` null, `retry_after_ms` 600000; the note\'s audio still wins, so no speech is generated in its place. | null | no | | `none` | Only for `note_id`: no such live note (unknown, deleted, merged away), not a `vocab` note, or no speakable text. Final for this note content. | null | no |  **Urgent.** With `urgent: true` (at most 20 items, else `422` pointer `/urgent`) the jobs this call needs run before every non-urgent job (SCHEMA.md 6.6 \"Urgent\"). The review screen uses it for the card on screen; prefetch never does.  **Backlog.** The number of `tts` jobs that are queued or running is bounded. A non-urgent call enqueues only while it is below 300; an urgent call only while it is below 340 (a reserve of 40 for the cards on screen; urgent is not an exemption). Beyond its limit a call enqueues nothing more: the remaining missing pairs are still `pending`, with `retry_after_ms` 60000, and are enqueued by a later call. (The server\'s sweep also enqueues, but only pairs of cards due within the prefetch window; an arbitrary `text` is enqueued by `resolveSpeech` or `synthesizeSpeech` alone.) Items are considered in request order, so a call that hits the limit has enqueued a prefix of its missing pairs.  **Idempotent and order-free.** Repeating a call, or two callers asking for the same pair at once, never produces two jobs for one pair (job dedupe key `tts:<sha256 of voice + NUL + text>`) and never two different answers once it is `ready`, except after a re-synthesis, which answers the newer blob; the older blob stays valid.  **Client rules.** - Review screen: for the item on screen whose `word_audio` has `source: tts` and   `blob_hash: null`, call with that `text` and `voice`, `urgent: true`, when the card is   first shown (not at reveal). On `pending` ask again after `retry_after_ms`, then double   the wait, at most 5 calls per showing; on `failed`, `none`, a network error, `403`   (a credential without `write`) or `5xx` stop for this showing. A card never waits   for audio. - Prefetch (Android): SCHEMA.md 6.6 \"Device\". - Never write the answer into a note.  **Scope.** `write`, for every request shape, as `synthesizeSpeech`: a `text` item makes the server run VOICEVOX and store a blob for a text the caller chose. A credential without `write` gets `403 insufficient_scope` and nothing is enqueued, also for a request of `note_id` items only (one rule, no per-item scope). A read-only client still gets audio: `getReviewQueue` queues the speech of the cards it serves (texts that come from the user\'s own notes) and reports the hash on a later fetch. 
     * Look up generated speech for texts or notes, generating what is missing.
     */
    async resolveSpeechRaw(requestParameters: ResolveSpeechRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<SpeechResolveResult>> {
        const requestOptions = await this.resolveSpeechRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => SpeechResolveResultFromJSON(jsonValue));
    }

    /**
     * ADR-047 (C-29). The one way a client turns \"this word should have audio\" into a blob hash. Answers at once from the system table `tts_audio`; whatever is not generated yet is queued as a `tts` job and reported `pending`, and the caller asks again. It changes no learning data and emits no op: generated audio is derived data (SCHEMA.md 6.6).  **Items.** 1 to 200, each **exactly one** of: - `text` (with optional `voice`): a pair. `voice` absent or null = the effective voice   (setting `tts.voice` when valid, else `\"3\"`). - `note_id`: the word audio of that note as the core `WordAudioResolver` decides it (the   note\'s own `[sound:]` first, else the pair of its speech text and the effective voice).   For clients that show a card outside a review queue.  An item with both, or neither, or `voice` together with `note_id`, is `422` (pointer `/items/<i>`); nothing is enqueued for any item of a rejected request. Duplicate items are allowed and answered alike.  **Result.** `items` has one `SpeechResolution` per request item, in request order:  | `status` | Meaning | `blob_hash` | Enqueues | |---|---|---|---| | `ready` | Generated, or the note\'s own audio and `app.blob` has that hash: play `blob_hash` through `GET /blobs/{hash}`. | hash | no | | `pending` | Not generated yet. A `tts` job for the pair is queued or running, or will be queued by a later call (see \"Backlog\"). Ask again after `retry_after_ms`. | null | yes, unless one exists | | `failed` | The last attempt failed less than 600 s ago (VOICEVOX down, unknown voice). No new job until then; ask again after `retry_after_ms`. Also - a `note_id` whose own `[sound:<hash>]` names a blob the server does not have (never uploaded, or lost) - `source` `note`, `blob_hash` null, `retry_after_ms` 600000; the note\'s audio still wins, so no speech is generated in its place. | null | no | | `none` | Only for `note_id`: no such live note (unknown, deleted, merged away), not a `vocab` note, or no speakable text. Final for this note content. | null | no |  **Urgent.** With `urgent: true` (at most 20 items, else `422` pointer `/urgent`) the jobs this call needs run before every non-urgent job (SCHEMA.md 6.6 \"Urgent\"). The review screen uses it for the card on screen; prefetch never does.  **Backlog.** The number of `tts` jobs that are queued or running is bounded. A non-urgent call enqueues only while it is below 300; an urgent call only while it is below 340 (a reserve of 40 for the cards on screen; urgent is not an exemption). Beyond its limit a call enqueues nothing more: the remaining missing pairs are still `pending`, with `retry_after_ms` 60000, and are enqueued by a later call. (The server\'s sweep also enqueues, but only pairs of cards due within the prefetch window; an arbitrary `text` is enqueued by `resolveSpeech` or `synthesizeSpeech` alone.) Items are considered in request order, so a call that hits the limit has enqueued a prefix of its missing pairs.  **Idempotent and order-free.** Repeating a call, or two callers asking for the same pair at once, never produces two jobs for one pair (job dedupe key `tts:<sha256 of voice + NUL + text>`) and never two different answers once it is `ready`, except after a re-synthesis, which answers the newer blob; the older blob stays valid.  **Client rules.** - Review screen: for the item on screen whose `word_audio` has `source: tts` and   `blob_hash: null`, call with that `text` and `voice`, `urgent: true`, when the card is   first shown (not at reveal). On `pending` ask again after `retry_after_ms`, then double   the wait, at most 5 calls per showing; on `failed`, `none`, a network error, `403`   (a credential without `write`) or `5xx` stop for this showing. A card never waits   for audio. - Prefetch (Android): SCHEMA.md 6.6 \"Device\". - Never write the answer into a note.  **Scope.** `write`, for every request shape, as `synthesizeSpeech`: a `text` item makes the server run VOICEVOX and store a blob for a text the caller chose. A credential without `write` gets `403 insufficient_scope` and nothing is enqueued, also for a request of `note_id` items only (one rule, no per-item scope). A read-only client still gets audio: `getReviewQueue` queues the speech of the cards it serves (texts that come from the user\'s own notes) and reports the hash on a later fetch. 
     * Look up generated speech for texts or notes, generating what is missing.
     */
    async resolveSpeech(requestParameters: ResolveSpeechRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<SpeechResolveResult> {
        const response = await this.resolveSpeechRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for synthesizeSpeech without sending the request
     */
    async synthesizeSpeechRequestOpts(requestParameters: SynthesizeSpeechRequest): Promise<runtime.RequestOpts> {
        if (requestParameters['ttsRequest'] == null) {
            throw new runtime.RequiredError(
                'ttsRequest',
                'Required parameter "ttsRequest" was null or undefined when calling synthesizeSpeech().'
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

        let urlPath = `/tts`;

        return {
            path: urlPath,
            method: 'POST',
            headers: headerParameters,
            query: queryParameters,
            body: TtsRequestToJSON(requestParameters['ttsRequest']),
        };
    }

    /**
     * Enqueues a job of kind `tts` (dedupe key `tts:<sha256 of voice + NUL + text>`); an equivalent queued or running job is returned with `200`. On success `job.result.blob_hash` names an audio blob (kind `audio`, origin `tts`). This call changes no note. A caller that wants the sound stored **in** a note puts `[sound:<hash>]` into a field with `PATCH /notes/{id}`; word audio of vocab cards needs none of that (ADR-047: see `QueueItem.word_audio` and `resolveSpeech`).  `voice` defaults to the effective voice (setting `tts.voice` when it is a string of 1-9 decimal digits, else `\"3\"`; SCHEMA.md 6.6); the server writes the resolved voice into the job payload. The worker records every success in the system table `tts_audio` (`(voice, text)` to blob), so a later request for the same pair, by this operation or by `resolveSpeech`, does not call VOICEVOX again. A `voice` that is not decimal digits is accepted here for compatibility and fails the job. 
     * Synthesize Japanese speech (VOICEVOX) into an audio blob.
     */
    async synthesizeSpeechRaw(requestParameters: SynthesizeSpeechRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<Job>> {
        const requestOptions = await this.synthesizeSpeechRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => JobFromJSON(jsonValue));
    }

    /**
     * Enqueues a job of kind `tts` (dedupe key `tts:<sha256 of voice + NUL + text>`); an equivalent queued or running job is returned with `200`. On success `job.result.blob_hash` names an audio blob (kind `audio`, origin `tts`). This call changes no note. A caller that wants the sound stored **in** a note puts `[sound:<hash>]` into a field with `PATCH /notes/{id}`; word audio of vocab cards needs none of that (ADR-047: see `QueueItem.word_audio` and `resolveSpeech`).  `voice` defaults to the effective voice (setting `tts.voice` when it is a string of 1-9 decimal digits, else `\"3\"`; SCHEMA.md 6.6); the server writes the resolved voice into the job payload. The worker records every success in the system table `tts_audio` (`(voice, text)` to blob), so a later request for the same pair, by this operation or by `resolveSpeech`, does not call VOICEVOX again. A `voice` that is not decimal digits is accepted here for compatibility and fails the job. 
     * Synthesize Japanese speech (VOICEVOX) into an audio blob.
     */
    async synthesizeSpeech(requestParameters: SynthesizeSpeechRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<Job> {
        const response = await this.synthesizeSpeechRaw(requestParameters, initOverrides);
        return await response.value();
    }

}
