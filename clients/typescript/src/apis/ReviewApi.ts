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
    type DrillSession,
    DrillSessionFromJSON,
    DrillSessionToJSON,
} from '../models/DrillSession';
import {
    type DrillSessionRequest,
    DrillSessionRequestFromJSON,
    DrillSessionRequestToJSON,
} from '../models/DrillSessionRequest';
import {
    type Problem,
    ProblemFromJSON,
    ProblemToJSON,
} from '../models/Problem';
import {
    type ReviewAnswerRequest,
    ReviewAnswerRequestFromJSON,
    ReviewAnswerRequestToJSON,
} from '../models/ReviewAnswerRequest';
import {
    type ReviewQueue,
    ReviewQueueFromJSON,
    ReviewQueueToJSON,
} from '../models/ReviewQueue';
import {
    type ReviewResult,
    ReviewResultFromJSON,
    ReviewResultToJSON,
} from '../models/ReviewResult';
import {
    type ReviewUndoRequest,
    ReviewUndoRequestFromJSON,
    ReviewUndoRequestToJSON,
} from '../models/ReviewUndoRequest';

export interface AnswerReviewRequest {
    /**
     * 
     */
    reviewAnswerRequest: ReviewAnswerRequest;
}

export interface CreateDrillSessionRequest {
    /**
     * 
     */
    drillSessionRequest: DrillSessionRequest;
}

export interface GetReviewQueueRequest {
    /**
     * Maximum number of cards to return.
     */
    limit?: number;
    /**
     * Restrict new cards to this deck and reviews to cards whose note or entry is an item of it. Must be a live deck (`404` otherwise).
     */
    deckId?: string;
    /**
     * Which queue sections to include, comma-separated. Default all three.
     */
    kinds?: Set<GetReviewQueueKindsEnum>;
    /**
     * C-26 (additive). `true` = keep going past today's limits (see the description; core
     * `QueueBuilder` rule 6, with `keepGoingNewBatch = limit`). Default `false` = the
     * normal queue. Scope (ADR-039, rule C2 in the description): one review visit of one
     * client, held in memory; every new visit, home screen and status fetch sends `false`.
     * Per client, never synced. Only the literal
     * values `true` and `false` are accepted; anything else is `422 validation_failed`
     * with `errors[0].pointer = /keep_going` and `code = invalid_value`. Echoed as
     * `loop.keep_going`. Older servers ignore the parameter (they return the normal
     * queue and no `more_available`).
     * 
     */
    keepGoing?: boolean;
}

export interface UndoReviewRequest {
    /**
     * 
     */
    reviewUndoRequest: ReviewUndoRequest;
}

/**
 * 
 */
export class ReviewApi extends runtime.BaseAPI {

    /**
     * Creates request options for answerReview without sending the request
     */
    async answerReviewRequestOpts(requestParameters: AnswerReviewRequest): Promise<runtime.RequestOpts> {
        if (requestParameters['reviewAnswerRequest'] == null) {
            throw new runtime.RequiredError(
                'reviewAnswerRequest',
                'Required parameter "reviewAnswerRequest" was null or undefined when calling answerReview().'
            );
        }

        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};

        headerParameters['Content-Type'] = 'application/json';

        if (this.configuration && this.configuration.accessToken) {
            const token = this.configuration.accessToken;
            const tokenString = await token("bearerAuth", ["review"]);

            if (tokenString) {
                headerParameters["Authorization"] = `Bearer ${tokenString}`;
            }
        }

        let urlPath = `/review/answer`;

        return {
            path: urlPath,
            method: 'POST',
            headers: headerParameters,
            query: queryParameters,
            body: ReviewAnswerRequestToJSON(requestParameters['reviewAnswerRequest']),
        };
    }

    /**
     * Server emits `review.logged` and recomputes the card\'s schedule cache with the shared core. `mode` decides the review type:  - `schedule` (default): `review_type` = `learning` if the card\'s state is `new`,   `learning` or `relearning` at apply time, else `scheduled`. Feeds FSRS. Reviewing a   card that is not due yet is allowed (FSRS accounts for the short interval). - `drill`, `cram`: logged with that `review_type`; the schedule does not change.  Rules: - `rating` is the FSRS rating 1-4. `button`, when sent, must map to `rating`   (`again`, `nothing`, `something` → 1; `hard` → 2; `good`, `okay` → 3; `easy` → 4);   otherwise `422`. Clients in five-button mode MUST send `button`. - `reviewed_at` defaults to the server\'s now. Past values are accepted (late   submission) within these bounds, each `422 validation_failed` with   `errors[0].pointer = /reviewed_at`:   - more than 5 minutes after the server\'s now (`code = in_future`);   - before `FLOOR` = 2020-01-01T00:00:00Z (`code = before_floor`);   - before `min(card.created_at, now) − 24 h` (`code = before_card`), where `card` is the     card the review is logged on (the survivor, for a merge loser\'s id) and `created_at`     is `wall(hlc)` of the `card.created` op that inserted the row (SYNC.md 7.2, 7.5).   The op the server mints is exempt from the push-time clock checks (SYNC.md 3.4), so   these are the only bounds a REST review gets; they reproduce the device guards   (G1, and G2\'s implied `reviewed_at >= card.created_at − 24 h`) and the lower bound   Re-mint uses (SYNC.md 10.4 step 4). The `min` with now keeps a card created by a   device up to 24 h ahead reviewable at once. The bound uses the replicated   `created_at`, which comes from the creating device\'s clock; SYNC.md 3.4 records   that a pushed review is not checked against it (known issue) - this REST check does   not change that. - `utc_offset_min` SHOULD be sent by browsers (`-new Date().getTimezoneOffset()`); when   absent it is stored as NULL. - `duration_ms` above 600000 is clamped to 600000. - `id` is **required**: the client-generated UUIDv7 `review_log.id`, minted once when the   answer is given and reused unchanged on every retry. Re-sending an existing `id` returns   the stored review and the card\'s current state with `200` and emits nothing - whatever   the rest of the body. This is what makes a retry after a lost response or a `5xx` safe   (no second `review.logged`, no double FSRS update). An `id` that exists as a review of a   different card is still answered with the stored review (the body is ignored). - Card suspended and `mode = schedule`: `409 card_not_reviewable`. Buried cards may be   answered. Deleted card or note: `410 gone`. A merge loser\'s id is answered against the   survivor (`review.card_id` in the response is the survivor\'s id). 
     * Grade a card.
     */
    async answerReviewRaw(requestParameters: AnswerReviewRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<ReviewResult>> {
        const requestOptions = await this.answerReviewRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => ReviewResultFromJSON(jsonValue));
    }

    /**
     * Server emits `review.logged` and recomputes the card\'s schedule cache with the shared core. `mode` decides the review type:  - `schedule` (default): `review_type` = `learning` if the card\'s state is `new`,   `learning` or `relearning` at apply time, else `scheduled`. Feeds FSRS. Reviewing a   card that is not due yet is allowed (FSRS accounts for the short interval). - `drill`, `cram`: logged with that `review_type`; the schedule does not change.  Rules: - `rating` is the FSRS rating 1-4. `button`, when sent, must map to `rating`   (`again`, `nothing`, `something` → 1; `hard` → 2; `good`, `okay` → 3; `easy` → 4);   otherwise `422`. Clients in five-button mode MUST send `button`. - `reviewed_at` defaults to the server\'s now. Past values are accepted (late   submission) within these bounds, each `422 validation_failed` with   `errors[0].pointer = /reviewed_at`:   - more than 5 minutes after the server\'s now (`code = in_future`);   - before `FLOOR` = 2020-01-01T00:00:00Z (`code = before_floor`);   - before `min(card.created_at, now) − 24 h` (`code = before_card`), where `card` is the     card the review is logged on (the survivor, for a merge loser\'s id) and `created_at`     is `wall(hlc)` of the `card.created` op that inserted the row (SYNC.md 7.2, 7.5).   The op the server mints is exempt from the push-time clock checks (SYNC.md 3.4), so   these are the only bounds a REST review gets; they reproduce the device guards   (G1, and G2\'s implied `reviewed_at >= card.created_at − 24 h`) and the lower bound   Re-mint uses (SYNC.md 10.4 step 4). The `min` with now keeps a card created by a   device up to 24 h ahead reviewable at once. The bound uses the replicated   `created_at`, which comes from the creating device\'s clock; SYNC.md 3.4 records   that a pushed review is not checked against it (known issue) - this REST check does   not change that. - `utc_offset_min` SHOULD be sent by browsers (`-new Date().getTimezoneOffset()`); when   absent it is stored as NULL. - `duration_ms` above 600000 is clamped to 600000. - `id` is **required**: the client-generated UUIDv7 `review_log.id`, minted once when the   answer is given and reused unchanged on every retry. Re-sending an existing `id` returns   the stored review and the card\'s current state with `200` and emits nothing - whatever   the rest of the body. This is what makes a retry after a lost response or a `5xx` safe   (no second `review.logged`, no double FSRS update). An `id` that exists as a review of a   different card is still answered with the stored review (the body is ignored). - Card suspended and `mode = schedule`: `409 card_not_reviewable`. Buried cards may be   answered. Deleted card or note: `410 gone`. A merge loser\'s id is answered against the   survivor (`review.card_id` in the response is the survivor\'s id). 
     * Grade a card.
     */
    async answerReview(requestParameters: AnswerReviewRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<ReviewResult> {
        const response = await this.answerReviewRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for createDrillSession without sending the request
     */
    async createDrillSessionRequestOpts(requestParameters: CreateDrillSessionRequest): Promise<runtime.RequestOpts> {
        if (requestParameters['drillSessionRequest'] == null) {
            throw new runtime.RequiredError(
                'drillSessionRequest',
                'Required parameter "drillSessionRequest" was null or undefined when calling createDrillSession().'
            );
        }

        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};

        headerParameters['Content-Type'] = 'application/json';

        if (this.configuration && this.configuration.accessToken) {
            const token = this.configuration.accessToken;
            const tokenString = await token("bearerAuth", ["review"]);

            if (tokenString) {
                headerParameters["Authorization"] = `Bearer ${tokenString}`;
            }
        }

        let urlPath = `/drill/session`;

        return {
            path: urlPath,
            method: 'POST',
            headers: headerParameters,
            query: queryParameters,
            body: DrillSessionRequestToJSON(requestParameters['drillSessionRequest']),
        };
    }

    /**
     * Returns an ordered list of cards to drill. Nothing is stored and no op is emitted: the session exists only in the response (`id` is a fresh UUIDv7 for client-side bookkeeping). The client shows the items and posts each answer to `POST /review/answer` with `mode` = the session\'s `mode` (`drill` or `cram`).  `source` selects the cards: - `hot_words`: live cards of the current hot words, hottest first. - `leeches`: live cards with `lapses >= scheduler.leech_threshold`, most lapses first. - `confusables`: cards of confirmed confusable pairs, partners adjacent. With   `entry_seq`, only pairs involving that entry\'s note. - `query`: cards matching `query` (card search string; required), in `-lapses` order. - `cards`: exactly `card_ids` (required, 1 to 500), in the given order; unknown, deleted   and merged-away ids are dropped silently. Suspended cards are included (drilling a suspended leech is the point); deleted ones never. An empty `items` list is a normal `200`. 
     * Build an extra-practice session that does not touch scheduling.
     */
    async createDrillSessionRaw(requestParameters: CreateDrillSessionRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<DrillSession>> {
        const requestOptions = await this.createDrillSessionRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => DrillSessionFromJSON(jsonValue));
    }

    /**
     * Returns an ordered list of cards to drill. Nothing is stored and no op is emitted: the session exists only in the response (`id` is a fresh UUIDv7 for client-side bookkeeping). The client shows the items and posts each answer to `POST /review/answer` with `mode` = the session\'s `mode` (`drill` or `cram`).  `source` selects the cards: - `hot_words`: live cards of the current hot words, hottest first. - `leeches`: live cards with `lapses >= scheduler.leech_threshold`, most lapses first. - `confusables`: cards of confirmed confusable pairs, partners adjacent. With   `entry_seq`, only pairs involving that entry\'s note. - `query`: cards matching `query` (card search string; required), in `-lapses` order. - `cards`: exactly `card_ids` (required, 1 to 500), in the given order; unknown, deleted   and merged-away ids are dropped silently. Suspended cards are included (drilling a suspended leech is the point); deleted ones never. An empty `items` list is a normal `200`. 
     * Build an extra-practice session that does not touch scheduling.
     */
    async createDrillSession(requestParameters: CreateDrillSessionRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<DrillSession> {
        const response = await this.createDrillSessionRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for getReviewQueue without sending the request
     */
    async getReviewQueueRequestOpts(requestParameters: GetReviewQueueRequest): Promise<runtime.RequestOpts> {
        const queryParameters: any = {};

        if (requestParameters['limit'] != null) {
            queryParameters['limit'] = requestParameters['limit'];
        }

        if (requestParameters['deckId'] != null) {
            queryParameters['deck_id'] = requestParameters['deckId'];
        }

        if (requestParameters['kinds'] != null) {
            queryParameters['kinds'] = Array.from(requestParameters['kinds'])!.join(runtime.COLLECTION_FORMATS["csv"]);
        }

        if (requestParameters['keepGoing'] != null) {
            queryParameters['keep_going'] = requestParameters['keepGoing'];
        }

        const headerParameters: runtime.HTTPHeaders = {};

        if (this.configuration && this.configuration.accessToken) {
            const token = this.configuration.accessToken;
            const tokenString = await token("bearerAuth", ["read"]);

            if (tokenString) {
                headerParameters["Authorization"] = `Bearer ${tokenString}`;
            }
        }

        let urlPath = `/review/queue`;

        return {
            path: urlPath,
            method: 'GET',
            headers: headerParameters,
            query: queryParameters,
        };
    }

    /**
     * For online clients (web, scripts). The server runs the same queue builder the phone runs locally, at request time, over the server\'s replica: daily limits (global and per deck), deck priority order, sibling burying and the proportional mix of reviews and new cards all come from the core (`ReviewLoop.evaluate`, ADR-037). The response is a prefix of today\'s queue: the first `limit` cards in the order they should be shown. It is **not** a reservation: fetch again after answering (each answer can change what comes next - learning steps, sibling burying, the mix). Suspended, buried, deleted and merged-away cards never appear. `loop` (ADR-037) carries the phase and every number a client shows; with an empty `items`, `loop.phase` alone says which end state this is (see \"Review loop\" below). `counts`, `more_available` and `next_learning_due_at` are deprecated and kept with their old meaning for old clients.  **Review loop (ADR-037).** One rule, in the core (`ReviewLoop`), the same on the server and in the phone\'s local API. Vocabulary (the same numbers on every client at the same instant): - *ready learning*: learning/relearning cards whose step has elapsed (`due_at` <= now).   Never capped, never served early. - *ready reviews*: review cards due before `day_ends_at` (overdue included) within the   remaining review budget (`scheduler.reviews_per_day`, deck `reviews_per_day`; no   budget when `scheduler.reviews_unlimited`). - *ready now* = ready learning + ready reviews. New cards are not part of it. - *later today*: learning/relearning cards due later, before `day_ends_at`. A hint only. - *backlog*: every due review an uncapped build would take; *beyond limit* = backlog -   ready reviews. - *new today / new goal*: cards introduced today / `scheduler.new_per_day`.  Order of a normal request (`keep_going=false`), always: ready learning first (a card that came due is the next card); then ready reviews (most overdue first) and new cards in the **proportional mix** (core `QueueBuilder` rule 5): new cards are spread evenly over the ready reviews that remain, so both run out at about the same time. The next card is a new card iff `s * gRem >= rRem`, with `rRem` = ready reviews left, `gRem` = new cards that can still be introduced today (the smaller of the goal\'s remainder and what the decks can supply under their limits) and `s` = reviews answered since the last new card was introduced today; otherwise it is the most overdue review. So a fresh day starts with a review, there are never two new cards in a row while a review is left, and the order of one response equals what fetching after every answer gives. Both modes mix: with `scheduler.reviews_unlimited` `rRem` is every ready review, otherwise the reviews left under the cap. New cards per response are bounded by the day\'s remaining goal and by `limit` (the batch; a kanji group may overshoot `limit`, never the goal); `limit` does not change the order. A kanji group (below) takes one new-card slot of the mix and is never split by it. Example: 4 reviews ready, 2 new cards left in the goal, nothing done today: `R1 R2 N1 R3 N2 R4`. Example: 200 ready, 40 left: a new card after every 5th review.  Phases (`loop.phase`; first match wins): 1. `reviewing`: the served queue has a card (or a deck entry to materialize, C-27). 2. `goal_reached` (only with `scheduler.reviews_unlimited` = true) / `done_for_today`    (only with it false): only for `keep_going=false`; nothing is ready under the limits    and `keep_going=true` would return at least one card. Offer Keep going; it never    leads to a dead end. Which of the two is chosen by the setting, not by which limit    was hit. 3. `waiting`: nothing ready, keep going would return nothing (or is on and returned    nothing), and at least one card falls due later today:    `loop.later_today.next_due_at`. Happens only when no new card is left anywhere. 4. `empty`: nothing now, nothing with keep going, nothing later today. A learning card that falls due during 2-4 makes the next response `reviewing` again.  Client rules (normative for web, the shell SPA and the CLI): - **C1 Due first.** A client may hold a page of `items`, but before showing each next   card it MUST fetch again if `now >= T`, T = the smallest of   `loop.later_today.next_due_at` of the last response (when not null) and `card.due_at`   of every `answerReview` response since then whose `card.state` is `learning` or   `relearning`. So a card that came due is the very next card. An undo   (`undoReview`) is followed by a fetch. - **C2 Keep going lasts one review visit (ADR-039).** A *visit* starts when the user   enters the review screen (CLI: starts a review session) and ends when they leave it,   when the app, tab or daemon restarts, when the response\'s `study_day` differs from   the one the visit started in, or after 10 minutes without an answer. The client keeps   the flag **in memory only**: after the user presses Keep going it sends   `keep_going=true` on the fetches of that visit; every new visit starts with   `keep_going=false`, and home screens, status lines, widgets and notifications always   send `false`. Nothing is persisted (`localStorage[\"kotobaya.keepGoingDay\"]` of   ADR-037 is no longer read or written; a client that finds it deletes it). So after the   goal is met and the user comes back later to newly due reviews, the loop ends at   `goal_reached` / `done_for_today` again before any card over the quota is served.   Per client, never synced, not an op, not a setting. (Replaces ADR-037\'s \"persisted for   the study day\".) - **C3 Display.** The headline \"ready now\" number is **always** `loop.ready.total`,   with or without keep going, so every client and surface (web, phone, widget, CLI)   shows the same number at the same instant. The backlog is shown only in a separate   backlog line, `loop.backlog.reviews`, displayed iff `loop.backlog.beyond_limit > 0`,   in both modes (while keeping going past the cap the headline is therefore   `ready.learning` and the backlog line carries the reviews being cleared). A \"later   today\" hint only when `loop.later_today.count > 0`; the new bar as `loop.new.today` /   `loop.new.goal` (it may exceed the goal after keep going: clamp the bar, show the   number). The **review progress bar** (ADR-038) is `done / (done + loop.until_stop)`   with `done` = the answers this client sent in the current visit (an undo takes one   off); the total may grow (Again, a new card entering its learning steps). When   `until_stop` is absent (older server) the client uses `loop.ready.total` instead. - **C4 Empty `items`** is rendered from `loop.phase` only. `waiting` schedules **one**   fetch at `next_due_at` + 1 s, and never sooner than 3 s after the previous   timer-driven fetch (the client\'s clock may differ from the server\'s; if the card is   still not served, the new response reschedules). New clients never read `counts`,   `more_available` or `next_learning_due_at`. An unknown `phase` value is treated as   `reviewing` when `items` is non-empty and as `empty` otherwise. - **C5 Materialization retries** (C-27): `reviewing` with empty `items` and   `to_materialize > 0` (the three rounds ran out, or the caller is read-only): a caller   with `review` or `write` fetches again (at most 3 times in a row); a read-only caller   shows that new cards exist but cannot be created by it. - A client that sends `kinds` may get `reviewing` with empty `items` (the ready cards   are of a kind it excluded): for that filter there is nothing to show; `loop` still   describes the whole queue.  **Keep going (C-26, ADR-031).** With `keep_going=true` the builder runs with `QueueLimits.keepGoing = true` and `keepGoingNewBatch = limit` (core `QueueBuilder` rule 6): the daily limits (`scheduler.new_per_day`, `scheduler.reviews_per_day`, deck `new_cards_per_day` / `reviews_per_day`) are lifted, at most about `limit` new cards are drawn per request (a kanji group may overshoot), and the order is ready learning, then **all** due reviews (most overdue first), then new cards - no mix: with a review cap the backlog is cleared first, then new cards follow without end; with `scheduler.reviews_unlimited` new cards follow without end and a review that becomes due is slotted in next. Everything else (eligibility, sibling burying, deck priority and order, disabled decks, learning) is unchanged. Cards not due before `day_ends_at` are never returned (no study-ahead). It is a stateless per-request flag: nothing is stored, no op is emitted, and the client keeps sending it on every fetch while the user keeps going. Cards answered this way are ordinary reviews and count against today\'s limits as usual. Everything in `loop` except `phase`, `keep_going` and `until_stop` is the same with or without keep going (ADR-033 principle), so every client shows the same numbers at the same moment whatever mode it is in; so are the deprecated `counts`.  **No review limit (ADR-034).** If the setting `scheduler.reviews_unlimited` is `true` (SCHEMA.md 6.3; default `false`), every build (keep going or not) ignores `scheduler.reviews_per_day` and deck `reviews_per_day`: every review due before `day_ends_at` is returned and counted in `loop.ready.review` (`loop.reviews.limit` is null, `loop.backlog.beyond_limit` 0). The new goal and the proportional mix apply as usual; at the goal the phase is `goal_reached`. It is an ordinary setting written with `updateSettings`.  **Reading cards retired (ADR-035).** While the setting `cards.reading_enabled` is not `true` (default `false`; SCHEMA.md 6.4), vocab `reading` cards are never returned, never counted in `counts`, never bury their siblings, and today\'s reviews of them use no limit (core `QueueInput.readingEnabled`, QueueBuilder rule 0). Materialized deck entries get no `reading` card. Setting it `true` returns the same cards with their history.  **No learn-ahead (ADR-033).** A learning or relearning card is returned only once its `due_at` has passed (core `QueueLimits.learnAheadMs` = 0 on the server and on devices). Cards due later today are counted in `loop.later_today` and are only a hint while anything else can be shown (reviews, new cards, Keep going). Only in phase `waiting` does the client show a waiting state (\"next card in m:ss\") and fetch again at `loop.later_today.next_due_at` (rule C4). A card is never shown again before its learning step has passed.  **Keep-going scope (normative).** Rule C2 above: one review visit of one client, held in memory, never persisted.  **Kanji first (ADR-040).** A new vocab card is never served before the new kanji cards of the kanji it requires. Those kanji cards come immediately before it, in the order of the word\'s kanji, as one **group**: the proportional mix never puts a review between them (only a learning card that falls due can come between; the group then continues right after it) and the whole group counts against the new-card goal. A group that does not fit today\'s remainder is not skipped and never overshoots the goal (ADR-043): as many of its kanji as fit are served today, and the word follows, behind its last kanji, on the first day the rest fits; nothing behind that word in its deck is introduced before it (a group may overshoot `limit`, never the goal). This holds for every word, whatever deck it comes from and for words in no deck; the deck setting `add_kanji` and the setting `scheduler.kanji_before_vocab` are no longer read. A kanji that already has a card in learning or review does not block and is not shown again; a suspended kanji card and a kanji note the user deleted are respected (the word comes without it); a kanji card the user **buried** holds its word back until the bury ends (ADR-043). A kanji card joins its word\'s group even when its own decks are all disabled. **Missing kanji notes are created here**, like deck entries (C-27): when the served build draws a word whose required kanji has no kanji note, the server creates the kanji note (`note.created` `origin` `auto_kanji`, built-in kanji type, and its default `card.created`s) in the same op transaction, before any vocab note of that fetch, and rebuilds; the word is withheld from `items` until its kanji exist. **Backfill:** the required kanji of words that are already being studied come first among the new cards, the oldest word\'s first: kanji cards that are still new, and (ADR-043) kanji that have no note yet, which are created in the same way. These kanji use at most half of the day\'s new goal (`floor(scheduler.new_per_day / 2)`, at least 1; ADR-044); the rest goes to new words with their own kanji, and when no new word can be drawn the kanji may use the remainder. Such a kanji card that is in no deck (every automatically created one) is drawn only through this share, never as an ordinary deckless new card; one that belongs to a deck is drawn through this share too (whatever the deck\'s priority or limit) and may also come up earlier as an ordinary card of its enabled deck. With `keep_going=true` they are at most half of the new cards introduced in the study day. Example: goal 4, three such kanji and two new words → `日 本 うっかり Ｔシャツ` today, `語` on the next study day. Core `QueueBuilder` rule 4 \"Kanji first\" is normative. Example: new word 学校, no kanji notes, 3 reviews ready, 3 new left today → `R1 学 校 学校 R2 R3`.  **Which kanji a word requires (ADR-042).** Decided by the core predicate `KanjiRequirement` from the setting `cards.kanji_exemptions` (bool, default `true`; SCHEMA.md 6.5). While it is on: a word whose displayed form JMdict tags as usually written in kana (`uk` on the sense used) or whose kanji form is tagged `ateji`, `rK`, `oK`, `iK` or `sK` requires **no** kanji; otherwise it requires its **learnable** kanji only (jōyō, i.e. KANJIDIC grade 1-6 or 8, or KANJIDIC frequency rank 1-2500). A word that requires no kanji although it is written with some shows its reading as furigana over the whole word on the front: `QueueItem.front_furigana`. With the setting `false` every kanji is required and no front has furigana. Examples: 寿司 (ateji) → no kanji cards, furigana front; 昨日 → 昨, 日 first, no furigana; 鱈 → no kanji card, furigana.  **Old clients.** A client written before ADR-037 keeps working: `counts`, `more_available` and `next_learning_due_at` are still sent with their old meaning (see the schema). What it sees differently: the order (the proportional mix instead of the old interleave) and no per-session \"done for now\" (`scheduler.new_per_session` is ignored; `counts.new` is the day\'s remainder, never a session\'s).  **Deck entries become cards here (C-27, ADR-032).** Deck items `e:<seq>` with no note are new cards too (core `PendingEntry`; which entries qualify: contracts/core README \"C-27\"). The server passes them to the core builder, and when its build draws some (`toMaterialize` of the build that is **served** - `LoopResult.queue`; never the other build\'s - bounded by the same new-card budget as the cards: the day\'s remaining goal and the batch `limit`, or with keep going the batch alone; each entry counted as the whole kanji group its card will be, so an entry that would not fit is not drawn) and the caller holds `review` or `write`, the server creates them before answering, in one op transaction as the **server device**, exactly as contracts/core README \"C-27\" defines: first the kanji notes of the build\'s `kanjiToMaterialize` (ADR-040: the missing required kanji of every drawn word, whatever the deck\'s `add_kanji`; `note.created` `origin` `auto_kanji` + their `card.created`s; never a kanji the user deleted), then per drawn entry, in the builder\'s order, `note.created` {built-in vocab type, `entry_seq`, `fields` `{}`, `origin` `deck`} and one `card.created` per kind of the drawing deck\'s `card_kinds` (the vocab type\'s default kinds when that is absent, empty or invalid), minus kinds whose front would render empty. It then rebuilds and returns that queue; it materializes again (at most 3 rounds) only while the previous round\'s notes appear in `items`. This is the only place an API client\'s deck entries become notes: clients never mint these ops or call anything else for it; a device builds offline from the same core output and mints the same ops itself. - **Idempotent.** The transaction holds the sync lock and skips an entry a live note   already holds, so repeated or concurrent fetches never create a second note; a   fetch that draws nothing emits nothing (and takes no lock). - **Devices.** A device that created a note for the same entry offline converges by   SYNC.md 8.1 (the note that held the entry first survives; the other\'s cards and   reviews are merged into it). - **Read-only callers** (`read` only) get the queue without materializing: drawn   entries are counted in `to_materialize` (and the deprecated `counts.new`), not in   `items`; `loop.phase` is `reviewing` (rule C5). - `Kotobaya-Seq` is set when ops were emitted.  Each item carries everything needed to render and grade the card: the card with its note summary, the dictionary entry (when the note references one that exists), the example sentence chosen for rotating-example cards, the `review_type` an answer will be logged with, and a preview of the next due time per rating. Grammar cards also carry `grammar_sentence` (C-15): the rotated example sentence and span, chosen by the core picker with the rules on `GrammarCardSentence` (drill sessions use the same rules). 
     * The next cards to review, built by the shared core\'s queue builder.
     */
    async getReviewQueueRaw(requestParameters: GetReviewQueueRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<ReviewQueue>> {
        const requestOptions = await this.getReviewQueueRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => ReviewQueueFromJSON(jsonValue));
    }

    /**
     * For online clients (web, scripts). The server runs the same queue builder the phone runs locally, at request time, over the server\'s replica: daily limits (global and per deck), deck priority order, sibling burying and the proportional mix of reviews and new cards all come from the core (`ReviewLoop.evaluate`, ADR-037). The response is a prefix of today\'s queue: the first `limit` cards in the order they should be shown. It is **not** a reservation: fetch again after answering (each answer can change what comes next - learning steps, sibling burying, the mix). Suspended, buried, deleted and merged-away cards never appear. `loop` (ADR-037) carries the phase and every number a client shows; with an empty `items`, `loop.phase` alone says which end state this is (see \"Review loop\" below). `counts`, `more_available` and `next_learning_due_at` are deprecated and kept with their old meaning for old clients.  **Review loop (ADR-037).** One rule, in the core (`ReviewLoop`), the same on the server and in the phone\'s local API. Vocabulary (the same numbers on every client at the same instant): - *ready learning*: learning/relearning cards whose step has elapsed (`due_at` <= now).   Never capped, never served early. - *ready reviews*: review cards due before `day_ends_at` (overdue included) within the   remaining review budget (`scheduler.reviews_per_day`, deck `reviews_per_day`; no   budget when `scheduler.reviews_unlimited`). - *ready now* = ready learning + ready reviews. New cards are not part of it. - *later today*: learning/relearning cards due later, before `day_ends_at`. A hint only. - *backlog*: every due review an uncapped build would take; *beyond limit* = backlog -   ready reviews. - *new today / new goal*: cards introduced today / `scheduler.new_per_day`.  Order of a normal request (`keep_going=false`), always: ready learning first (a card that came due is the next card); then ready reviews (most overdue first) and new cards in the **proportional mix** (core `QueueBuilder` rule 5): new cards are spread evenly over the ready reviews that remain, so both run out at about the same time. The next card is a new card iff `s * gRem >= rRem`, with `rRem` = ready reviews left, `gRem` = new cards that can still be introduced today (the smaller of the goal\'s remainder and what the decks can supply under their limits) and `s` = reviews answered since the last new card was introduced today; otherwise it is the most overdue review. So a fresh day starts with a review, there are never two new cards in a row while a review is left, and the order of one response equals what fetching after every answer gives. Both modes mix: with `scheduler.reviews_unlimited` `rRem` is every ready review, otherwise the reviews left under the cap. New cards per response are bounded by the day\'s remaining goal and by `limit` (the batch; a kanji group may overshoot `limit`, never the goal); `limit` does not change the order. A kanji group (below) takes one new-card slot of the mix and is never split by it. Example: 4 reviews ready, 2 new cards left in the goal, nothing done today: `R1 R2 N1 R3 N2 R4`. Example: 200 ready, 40 left: a new card after every 5th review.  Phases (`loop.phase`; first match wins): 1. `reviewing`: the served queue has a card (or a deck entry to materialize, C-27). 2. `goal_reached` (only with `scheduler.reviews_unlimited` = true) / `done_for_today`    (only with it false): only for `keep_going=false`; nothing is ready under the limits    and `keep_going=true` would return at least one card. Offer Keep going; it never    leads to a dead end. Which of the two is chosen by the setting, not by which limit    was hit. 3. `waiting`: nothing ready, keep going would return nothing (or is on and returned    nothing), and at least one card falls due later today:    `loop.later_today.next_due_at`. Happens only when no new card is left anywhere. 4. `empty`: nothing now, nothing with keep going, nothing later today. A learning card that falls due during 2-4 makes the next response `reviewing` again.  Client rules (normative for web, the shell SPA and the CLI): - **C1 Due first.** A client may hold a page of `items`, but before showing each next   card it MUST fetch again if `now >= T`, T = the smallest of   `loop.later_today.next_due_at` of the last response (when not null) and `card.due_at`   of every `answerReview` response since then whose `card.state` is `learning` or   `relearning`. So a card that came due is the very next card. An undo   (`undoReview`) is followed by a fetch. - **C2 Keep going lasts one review visit (ADR-039).** A *visit* starts when the user   enters the review screen (CLI: starts a review session) and ends when they leave it,   when the app, tab or daemon restarts, when the response\'s `study_day` differs from   the one the visit started in, or after 10 minutes without an answer. The client keeps   the flag **in memory only**: after the user presses Keep going it sends   `keep_going=true` on the fetches of that visit; every new visit starts with   `keep_going=false`, and home screens, status lines, widgets and notifications always   send `false`. Nothing is persisted (`localStorage[\"kotobaya.keepGoingDay\"]` of   ADR-037 is no longer read or written; a client that finds it deletes it). So after the   goal is met and the user comes back later to newly due reviews, the loop ends at   `goal_reached` / `done_for_today` again before any card over the quota is served.   Per client, never synced, not an op, not a setting. (Replaces ADR-037\'s \"persisted for   the study day\".) - **C3 Display.** The headline \"ready now\" number is **always** `loop.ready.total`,   with or without keep going, so every client and surface (web, phone, widget, CLI)   shows the same number at the same instant. The backlog is shown only in a separate   backlog line, `loop.backlog.reviews`, displayed iff `loop.backlog.beyond_limit > 0`,   in both modes (while keeping going past the cap the headline is therefore   `ready.learning` and the backlog line carries the reviews being cleared). A \"later   today\" hint only when `loop.later_today.count > 0`; the new bar as `loop.new.today` /   `loop.new.goal` (it may exceed the goal after keep going: clamp the bar, show the   number). The **review progress bar** (ADR-038) is `done / (done + loop.until_stop)`   with `done` = the answers this client sent in the current visit (an undo takes one   off); the total may grow (Again, a new card entering its learning steps). When   `until_stop` is absent (older server) the client uses `loop.ready.total` instead. - **C4 Empty `items`** is rendered from `loop.phase` only. `waiting` schedules **one**   fetch at `next_due_at` + 1 s, and never sooner than 3 s after the previous   timer-driven fetch (the client\'s clock may differ from the server\'s; if the card is   still not served, the new response reschedules). New clients never read `counts`,   `more_available` or `next_learning_due_at`. An unknown `phase` value is treated as   `reviewing` when `items` is non-empty and as `empty` otherwise. - **C5 Materialization retries** (C-27): `reviewing` with empty `items` and   `to_materialize > 0` (the three rounds ran out, or the caller is read-only): a caller   with `review` or `write` fetches again (at most 3 times in a row); a read-only caller   shows that new cards exist but cannot be created by it. - A client that sends `kinds` may get `reviewing` with empty `items` (the ready cards   are of a kind it excluded): for that filter there is nothing to show; `loop` still   describes the whole queue.  **Keep going (C-26, ADR-031).** With `keep_going=true` the builder runs with `QueueLimits.keepGoing = true` and `keepGoingNewBatch = limit` (core `QueueBuilder` rule 6): the daily limits (`scheduler.new_per_day`, `scheduler.reviews_per_day`, deck `new_cards_per_day` / `reviews_per_day`) are lifted, at most about `limit` new cards are drawn per request (a kanji group may overshoot), and the order is ready learning, then **all** due reviews (most overdue first), then new cards - no mix: with a review cap the backlog is cleared first, then new cards follow without end; with `scheduler.reviews_unlimited` new cards follow without end and a review that becomes due is slotted in next. Everything else (eligibility, sibling burying, deck priority and order, disabled decks, learning) is unchanged. Cards not due before `day_ends_at` are never returned (no study-ahead). It is a stateless per-request flag: nothing is stored, no op is emitted, and the client keeps sending it on every fetch while the user keeps going. Cards answered this way are ordinary reviews and count against today\'s limits as usual. Everything in `loop` except `phase`, `keep_going` and `until_stop` is the same with or without keep going (ADR-033 principle), so every client shows the same numbers at the same moment whatever mode it is in; so are the deprecated `counts`.  **No review limit (ADR-034).** If the setting `scheduler.reviews_unlimited` is `true` (SCHEMA.md 6.3; default `false`), every build (keep going or not) ignores `scheduler.reviews_per_day` and deck `reviews_per_day`: every review due before `day_ends_at` is returned and counted in `loop.ready.review` (`loop.reviews.limit` is null, `loop.backlog.beyond_limit` 0). The new goal and the proportional mix apply as usual; at the goal the phase is `goal_reached`. It is an ordinary setting written with `updateSettings`.  **Reading cards retired (ADR-035).** While the setting `cards.reading_enabled` is not `true` (default `false`; SCHEMA.md 6.4), vocab `reading` cards are never returned, never counted in `counts`, never bury their siblings, and today\'s reviews of them use no limit (core `QueueInput.readingEnabled`, QueueBuilder rule 0). Materialized deck entries get no `reading` card. Setting it `true` returns the same cards with their history.  **No learn-ahead (ADR-033).** A learning or relearning card is returned only once its `due_at` has passed (core `QueueLimits.learnAheadMs` = 0 on the server and on devices). Cards due later today are counted in `loop.later_today` and are only a hint while anything else can be shown (reviews, new cards, Keep going). Only in phase `waiting` does the client show a waiting state (\"next card in m:ss\") and fetch again at `loop.later_today.next_due_at` (rule C4). A card is never shown again before its learning step has passed.  **Keep-going scope (normative).** Rule C2 above: one review visit of one client, held in memory, never persisted.  **Kanji first (ADR-040).** A new vocab card is never served before the new kanji cards of the kanji it requires. Those kanji cards come immediately before it, in the order of the word\'s kanji, as one **group**: the proportional mix never puts a review between them (only a learning card that falls due can come between; the group then continues right after it) and the whole group counts against the new-card goal. A group that does not fit today\'s remainder is not skipped and never overshoots the goal (ADR-043): as many of its kanji as fit are served today, and the word follows, behind its last kanji, on the first day the rest fits; nothing behind that word in its deck is introduced before it (a group may overshoot `limit`, never the goal). This holds for every word, whatever deck it comes from and for words in no deck; the deck setting `add_kanji` and the setting `scheduler.kanji_before_vocab` are no longer read. A kanji that already has a card in learning or review does not block and is not shown again; a suspended kanji card and a kanji note the user deleted are respected (the word comes without it); a kanji card the user **buried** holds its word back until the bury ends (ADR-043). A kanji card joins its word\'s group even when its own decks are all disabled. **Missing kanji notes are created here**, like deck entries (C-27): when the served build draws a word whose required kanji has no kanji note, the server creates the kanji note (`note.created` `origin` `auto_kanji`, built-in kanji type, and its default `card.created`s) in the same op transaction, before any vocab note of that fetch, and rebuilds; the word is withheld from `items` until its kanji exist. **Backfill:** the required kanji of words that are already being studied come first among the new cards, the oldest word\'s first: kanji cards that are still new, and (ADR-043) kanji that have no note yet, which are created in the same way. These kanji use at most half of the day\'s new goal (`floor(scheduler.new_per_day / 2)`, at least 1; ADR-044); the rest goes to new words with their own kanji, and when no new word can be drawn the kanji may use the remainder. Such a kanji card that is in no deck (every automatically created one) is drawn only through this share, never as an ordinary deckless new card; one that belongs to a deck is drawn through this share too (whatever the deck\'s priority or limit) and may also come up earlier as an ordinary card of its enabled deck. With `keep_going=true` they are at most half of the new cards introduced in the study day. Example: goal 4, three such kanji and two new words → `日 本 うっかり Ｔシャツ` today, `語` on the next study day. Core `QueueBuilder` rule 4 \"Kanji first\" is normative. Example: new word 学校, no kanji notes, 3 reviews ready, 3 new left today → `R1 学 校 学校 R2 R3`.  **Which kanji a word requires (ADR-042).** Decided by the core predicate `KanjiRequirement` from the setting `cards.kanji_exemptions` (bool, default `true`; SCHEMA.md 6.5). While it is on: a word whose displayed form JMdict tags as usually written in kana (`uk` on the sense used) or whose kanji form is tagged `ateji`, `rK`, `oK`, `iK` or `sK` requires **no** kanji; otherwise it requires its **learnable** kanji only (jōyō, i.e. KANJIDIC grade 1-6 or 8, or KANJIDIC frequency rank 1-2500). A word that requires no kanji although it is written with some shows its reading as furigana over the whole word on the front: `QueueItem.front_furigana`. With the setting `false` every kanji is required and no front has furigana. Examples: 寿司 (ateji) → no kanji cards, furigana front; 昨日 → 昨, 日 first, no furigana; 鱈 → no kanji card, furigana.  **Old clients.** A client written before ADR-037 keeps working: `counts`, `more_available` and `next_learning_due_at` are still sent with their old meaning (see the schema). What it sees differently: the order (the proportional mix instead of the old interleave) and no per-session \"done for now\" (`scheduler.new_per_session` is ignored; `counts.new` is the day\'s remainder, never a session\'s).  **Deck entries become cards here (C-27, ADR-032).** Deck items `e:<seq>` with no note are new cards too (core `PendingEntry`; which entries qualify: contracts/core README \"C-27\"). The server passes them to the core builder, and when its build draws some (`toMaterialize` of the build that is **served** - `LoopResult.queue`; never the other build\'s - bounded by the same new-card budget as the cards: the day\'s remaining goal and the batch `limit`, or with keep going the batch alone; each entry counted as the whole kanji group its card will be, so an entry that would not fit is not drawn) and the caller holds `review` or `write`, the server creates them before answering, in one op transaction as the **server device**, exactly as contracts/core README \"C-27\" defines: first the kanji notes of the build\'s `kanjiToMaterialize` (ADR-040: the missing required kanji of every drawn word, whatever the deck\'s `add_kanji`; `note.created` `origin` `auto_kanji` + their `card.created`s; never a kanji the user deleted), then per drawn entry, in the builder\'s order, `note.created` {built-in vocab type, `entry_seq`, `fields` `{}`, `origin` `deck`} and one `card.created` per kind of the drawing deck\'s `card_kinds` (the vocab type\'s default kinds when that is absent, empty or invalid), minus kinds whose front would render empty. It then rebuilds and returns that queue; it materializes again (at most 3 rounds) only while the previous round\'s notes appear in `items`. This is the only place an API client\'s deck entries become notes: clients never mint these ops or call anything else for it; a device builds offline from the same core output and mints the same ops itself. - **Idempotent.** The transaction holds the sync lock and skips an entry a live note   already holds, so repeated or concurrent fetches never create a second note; a   fetch that draws nothing emits nothing (and takes no lock). - **Devices.** A device that created a note for the same entry offline converges by   SYNC.md 8.1 (the note that held the entry first survives; the other\'s cards and   reviews are merged into it). - **Read-only callers** (`read` only) get the queue without materializing: drawn   entries are counted in `to_materialize` (and the deprecated `counts.new`), not in   `items`; `loop.phase` is `reviewing` (rule C5). - `Kotobaya-Seq` is set when ops were emitted.  Each item carries everything needed to render and grade the card: the card with its note summary, the dictionary entry (when the note references one that exists), the example sentence chosen for rotating-example cards, the `review_type` an answer will be logged with, and a preview of the next due time per rating. Grammar cards also carry `grammar_sentence` (C-15): the rotated example sentence and span, chosen by the core picker with the rules on `GrammarCardSentence` (drill sessions use the same rules). 
     * The next cards to review, built by the shared core\'s queue builder.
     */
    async getReviewQueue(requestParameters: GetReviewQueueRequest = {}, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<ReviewQueue> {
        const response = await this.getReviewQueueRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for undoReview without sending the request
     */
    async undoReviewRequestOpts(requestParameters: UndoReviewRequest): Promise<runtime.RequestOpts> {
        if (requestParameters['reviewUndoRequest'] == null) {
            throw new runtime.RequiredError(
                'reviewUndoRequest',
                'Required parameter "reviewUndoRequest" was null or undefined when calling undoReview().'
            );
        }

        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};

        headerParameters['Content-Type'] = 'application/json';

        if (this.configuration && this.configuration.accessToken) {
            const token = this.configuration.accessToken;
            const tokenString = await token("bearerAuth", ["review"]);

            if (tokenString) {
                headerParameters["Authorization"] = `Bearer ${tokenString}`;
            }
        }

        let urlPath = `/review/undo`;

        return {
            path: urlPath,
            method: 'POST',
            headers: headerParameters,
            query: queryParameters,
            body: ReviewUndoRequestToJSON(requestParameters['reviewUndoRequest']),
        };
    }

    /**
     * Server emits `review.undone` for the review named by `review_id` and recomputes the card.  - `review_id` is **required** (any review, whichever device logged it, `manual` rows   included). There is no implicit \"undo my last review\": that would not be idempotent   (a retry after a lost response would undo the review before it). The web app keeps the   `id` it sent to `POST /review/answer` and passes it here. - Target already undone: `200` with the current state, no op (an undo cannot be undone).   Hence retries are safe. - Unknown `review_id`: `404 not_found`. 
     * Undo a review.
     */
    async undoReviewRaw(requestParameters: UndoReviewRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<ReviewResult>> {
        const requestOptions = await this.undoReviewRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => ReviewResultFromJSON(jsonValue));
    }

    /**
     * Server emits `review.undone` for the review named by `review_id` and recomputes the card.  - `review_id` is **required** (any review, whichever device logged it, `manual` rows   included). There is no implicit \"undo my last review\": that would not be idempotent   (a retry after a lost response would undo the review before it). The web app keeps the   `id` it sent to `POST /review/answer` and passes it here. - Target already undone: `200` with the current state, no op (an undo cannot be undone).   Hence retries are safe. - Unknown `review_id`: `404 not_found`. 
     * Undo a review.
     */
    async undoReview(requestParameters: UndoReviewRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<ReviewResult> {
        const response = await this.undoReviewRaw(requestParameters, initOverrides);
        return await response.value();
    }

}

/**
 * @export
 */
export const GetReviewQueueKindsEnum = {
    New: 'new',
    Learning: 'learning',
    Review: 'review',
} as const;
export type GetReviewQueueKindsEnum = typeof GetReviewQueueKindsEnum[keyof typeof GetReviewQueueKindsEnum];
