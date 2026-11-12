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
     * For online clients (web, scripts). The server runs the same queue builder the phone runs locally, at request time, over the server\'s replica: daily limits (global and per deck), deck priority order, sibling burying, interleaving and the per-session new-card cap all come from the core. The response is a prefix of today\'s queue: the first `limit` cards in the order they should be shown. It is **not** a reservation: fetch again after answering (each answer can change what comes next - learning steps, sibling burying). Suspended, buried, deleted and merged-away cards never appear. Empty `items` with all counts zero means done for the study day.  Each item carries everything needed to render and grade the card: the card with its note summary, the dictionary entry (when the note references one that exists), the example sentence chosen for rotating-example cards, the `review_type` an answer will be logged with, and a preview of the next due time per rating. 
     * The next cards to review, built by the shared core\'s queue builder.
     */
    async getReviewQueueRaw(requestParameters: GetReviewQueueRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<ReviewQueue>> {
        const requestOptions = await this.getReviewQueueRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => ReviewQueueFromJSON(jsonValue));
    }

    /**
     * For online clients (web, scripts). The server runs the same queue builder the phone runs locally, at request time, over the server\'s replica: daily limits (global and per deck), deck priority order, sibling burying, interleaving and the per-session new-card cap all come from the core. The response is a prefix of today\'s queue: the first `limit` cards in the order they should be shown. It is **not** a reservation: fetch again after answering (each answer can change what comes next - learning steps, sibling burying). Suspended, buried, deleted and merged-away cards never appear. Empty `items` with all counts zero means done for the study day.  Each item carries everything needed to render and grade the card: the card with its note summary, the dictionary entry (when the note references one that exists), the example sentence chosen for rotating-example cards, the `review_type` an answer will be logged with, and a preview of the next due time per rating. 
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
