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
    type Card,
    CardFromJSON,
    CardToJSON,
} from '../models/Card';
import {
    type CardBulkRequest,
    CardBulkRequestFromJSON,
    CardBulkRequestToJSON,
} from '../models/CardBulkRequest';
import {
    type CardBulkResult,
    CardBulkResultFromJSON,
    CardBulkResultToJSON,
} from '../models/CardBulkResult';
import {
    type CardBulkScheduleRequest,
    CardBulkScheduleRequestFromJSON,
    CardBulkScheduleRequestToJSON,
} from '../models/CardBulkScheduleRequest';
import {
    type CardCreate,
    CardCreateFromJSON,
    CardCreateToJSON,
} from '../models/CardCreate';
import {
    type CardPage,
    CardPageFromJSON,
    CardPageToJSON,
} from '../models/CardPage';
import {
    type CardPatch,
    CardPatchFromJSON,
    CardPatchToJSON,
} from '../models/CardPatch';
import {
    type Problem,
    ProblemFromJSON,
    ProblemToJSON,
} from '../models/Problem';
import {
    type ReviewPage,
    ReviewPageFromJSON,
    ReviewPageToJSON,
} from '../models/ReviewPage';

export interface BulkCardsRequest {
    /**
     * 
     */
    cardBulkRequest: CardBulkRequest;
}

export interface BulkScheduleCardsRequest {
    /**
     * 
     */
    cardBulkScheduleRequest: CardBulkScheduleRequest;
}

export interface CreateCardRequest {
    /**
     * 
     */
    cardCreate: CardCreate;
}

export interface DeleteCardRequest {
    /**
     * Card id.
     */
    cardId: string;
}

export interface GetCardRequest {
    /**
     * Card id.
     */
    cardId: string;
    /**
     * Also return tombstoned rows (`deleted_at` set).
     */
    includeDeleted?: boolean;
}

export interface ListCardReviewsRequest {
    /**
     * Card id.
     */
    cardId: string;
    /**
     * Page size.
     */
    limit?: number;
    /**
     * Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`.
     */
    cursor?: string;
}

export interface ListCardsRequest {
    /**
     * Card search string (grammar owned by the shared core). Maximum 2000 characters.
     */
    query?: string;
    /**
     * Sort key; a leading `-` means descending. Ties are broken by card `id` ascending. NULL values sort last in either direction.
     */
    sort?: ListCardsSortEnum;
    /**
     * Page size.
     */
    limit?: number;
    /**
     * Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`.
     */
    cursor?: string;
}

export interface UpdateCardRequest {
    /**
     * Card id.
     */
    cardId: string;
    /**
     * 
     */
    cardPatch: CardPatch;
}

/**
 * 
 */
export class CardsApi extends runtime.BaseAPI {

    /**
     * Creates request options for bulkCards without sending the request
     */
    async bulkCardsRequestOpts(requestParameters: BulkCardsRequest): Promise<runtime.RequestOpts> {
        if (requestParameters['cardBulkRequest'] == null) {
            throw new runtime.RequiredError(
                'cardBulkRequest',
                'Required parameter "cardBulkRequest" was null or undefined when calling bulkCards().'
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

        let urlPath = `/cards/bulk`;

        return {
            path: urlPath,
            method: 'POST',
            headers: headerParameters,
            query: queryParameters,
            body: CardBulkRequestToJSON(requestParameters['cardBulkRequest']),
        };
    }

    /**
     * Select cards with exactly one of `card_ids` (1 to 5000 ids) or `query` (card search string). Unknown, deleted and merged-away ids in `card_ids` are skipped and counted in `skipped` (merge losers are **not** redirected in bulk). A selector matching more than 5000 cards is `422 bulk_too_large`; nothing is applied. Matching zero cards is a successful no-op. All ops of one call are applied in one transaction, ordered by card id.  | `action.type` | Required params | Server emits, per matched card unless noted | |---|---|---| | `suspend` | - | `card.suspended` | | `unsuspend` | - | `card.unsuspended` | | `bury` | `until` (optional; default = start of the next study day) | `card.buried` | | `unbury` | - | `card.unburied` | | `flag` | `flags` (0-7; 0 clears) | `card.flag_set` | | `set_preset` | `preset_id` (live preset) | `card.preset_set` | | `add_tags`, `remove_tags` | `tags` (non-empty) | one `note.updated` (register `tags`) per **distinct note** of the matched cards | | `move` | `to_deck_id` (live `user` deck), optional `from_deck_id` (live `user` deck) | per distinct note: `deck.item_put` into `to_deck_id` (key `e:<seq>` or `n:<note id>`, appended in card-id order at max position + 1024 each) and, with `from_deck_id`, `deck.item_removed` there if the item exists | | `delete` | - | `card.deleted` |  Cards already in the target state still get an op (it bumps the clock) except: `delete` on a deleted card, and tag actions that would not change a note\'s tag set - those are counted in `skipped`. An `add_tags` that would give any matched note more than 200 tags is `422 validation_failed` (error `out_of_range` at `/action/tags`); nothing is applied (C-2). Params not listed for the action type are `422`. A `to_deck_id`/`from_deck_id` that is not a live `user` deck is `409 deck_read_only`. With `dry_run: true` nothing is emitted and the counts say what would happen. Repeating a call is safe: register actions converge.  Reschedule and reset write review history (`review.logged`) and therefore need the `review` scope; they are the separate operation `POST /cards/bulk/schedule`. 
     * Apply one action to many cards (suspend, tag, move, delete, ...).
     */
    async bulkCardsRaw(requestParameters: BulkCardsRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<CardBulkResult>> {
        const requestOptions = await this.bulkCardsRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => CardBulkResultFromJSON(jsonValue));
    }

    /**
     * Select cards with exactly one of `card_ids` (1 to 5000 ids) or `query` (card search string). Unknown, deleted and merged-away ids in `card_ids` are skipped and counted in `skipped` (merge losers are **not** redirected in bulk). A selector matching more than 5000 cards is `422 bulk_too_large`; nothing is applied. Matching zero cards is a successful no-op. All ops of one call are applied in one transaction, ordered by card id.  | `action.type` | Required params | Server emits, per matched card unless noted | |---|---|---| | `suspend` | - | `card.suspended` | | `unsuspend` | - | `card.unsuspended` | | `bury` | `until` (optional; default = start of the next study day) | `card.buried` | | `unbury` | - | `card.unburied` | | `flag` | `flags` (0-7; 0 clears) | `card.flag_set` | | `set_preset` | `preset_id` (live preset) | `card.preset_set` | | `add_tags`, `remove_tags` | `tags` (non-empty) | one `note.updated` (register `tags`) per **distinct note** of the matched cards | | `move` | `to_deck_id` (live `user` deck), optional `from_deck_id` (live `user` deck) | per distinct note: `deck.item_put` into `to_deck_id` (key `e:<seq>` or `n:<note id>`, appended in card-id order at max position + 1024 each) and, with `from_deck_id`, `deck.item_removed` there if the item exists | | `delete` | - | `card.deleted` |  Cards already in the target state still get an op (it bumps the clock) except: `delete` on a deleted card, and tag actions that would not change a note\'s tag set - those are counted in `skipped`. An `add_tags` that would give any matched note more than 200 tags is `422 validation_failed` (error `out_of_range` at `/action/tags`); nothing is applied (C-2). Params not listed for the action type are `422`. A `to_deck_id`/`from_deck_id` that is not a live `user` deck is `409 deck_read_only`. With `dry_run: true` nothing is emitted and the counts say what would happen. Repeating a call is safe: register actions converge.  Reschedule and reset write review history (`review.logged`) and therefore need the `review` scope; they are the separate operation `POST /cards/bulk/schedule`. 
     * Apply one action to many cards (suspend, tag, move, delete, ...).
     */
    async bulkCards(requestParameters: BulkCardsRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<CardBulkResult> {
        const response = await this.bulkCardsRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for bulkScheduleCards without sending the request
     */
    async bulkScheduleCardsRequestOpts(requestParameters: BulkScheduleCardsRequest): Promise<runtime.RequestOpts> {
        if (requestParameters['cardBulkScheduleRequest'] == null) {
            throw new runtime.RequiredError(
                'cardBulkScheduleRequest',
                'Required parameter "cardBulkScheduleRequest" was null or undefined when calling bulkScheduleCards().'
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

        let urlPath = `/cards/bulk/schedule`;

        return {
            path: urlPath,
            method: 'POST',
            headers: headerParameters,
            query: queryParameters,
            body: CardBulkScheduleRequestToJSON(requestParameters['cardBulkScheduleRequest']),
        };
    }

    /**
     * Same selector, limits, ordering, transaction and `dry_run` rules as `POST /cards/bulk`. Needs the `review` scope, like every other way of writing `review_log` (`POST /sync/push` needs `review` for `review.*` ops; `POST /review/answer`).  | `action.type` | Required params | Server emits, per matched card | |---|---|---| | `reschedule` | `due_at` | `review.logged` with `review_type = manual`, `manual_action = set_due`, `manual_due_at = due_at` | | `reset` | - | `review.logged` with `review_type = manual`, `manual_action = reset` |  - `reset` of a card in state `new` is skipped (counted in `skipped`). - `reschedule` is `422` for the whole call if any matched card is `new` (a new card has no   memory state to keep); nothing is applied. - Suspended cards are processed (the manual row changes the schedule cache; the card   stays suspended). - Repeating a call adds another manual row per card with the same effect on the   schedule (manual rows are not deduplicated). Each manual row can be undone with   `POST /review/undo` by its id (`GET /cards/{card_id}/reviews`). 
     * Reschedule or reset many cards (manual review-log rows).
     */
    async bulkScheduleCardsRaw(requestParameters: BulkScheduleCardsRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<CardBulkResult>> {
        const requestOptions = await this.bulkScheduleCardsRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => CardBulkResultFromJSON(jsonValue));
    }

    /**
     * Same selector, limits, ordering, transaction and `dry_run` rules as `POST /cards/bulk`. Needs the `review` scope, like every other way of writing `review_log` (`POST /sync/push` needs `review` for `review.*` ops; `POST /review/answer`).  | `action.type` | Required params | Server emits, per matched card | |---|---|---| | `reschedule` | `due_at` | `review.logged` with `review_type = manual`, `manual_action = set_due`, `manual_due_at = due_at` | | `reset` | - | `review.logged` with `review_type = manual`, `manual_action = reset` |  - `reset` of a card in state `new` is skipped (counted in `skipped`). - `reschedule` is `422` for the whole call if any matched card is `new` (a new card has no   memory state to keep); nothing is applied. - Suspended cards are processed (the manual row changes the schedule cache; the card   stays suspended). - Repeating a call adds another manual row per card with the same effect on the   schedule (manual rows are not deduplicated). Each manual row can be undone with   `POST /review/undo` by its id (`GET /cards/{card_id}/reviews`). 
     * Reschedule or reset many cards (manual review-log rows).
     */
    async bulkScheduleCards(requestParameters: BulkScheduleCardsRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<CardBulkResult> {
        const response = await this.bulkScheduleCardsRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for createCard without sending the request
     */
    async createCardRequestOpts(requestParameters: CreateCardRequest): Promise<runtime.RequestOpts> {
        if (requestParameters['cardCreate'] == null) {
            throw new runtime.RequiredError(
                'cardCreate',
                'Required parameter "cardCreate" was null or undefined when calling createCard().'
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

        let urlPath = `/cards`;

        return {
            path: urlPath,
            method: 'POST',
            headers: headerParameters,
            query: queryParameters,
            body: CardCreateToJSON(requestParameters['cardCreate']),
        };
    }

    /**
     * Server emits `card.created`. `kind` must be a key of the note type\'s `templates`. If the note already has a live card of that kind it is returned with `200` and nothing is emitted (REST never creates a duplicate `(note, kind)`). A merge loser\'s `note_id` acts on the survivor. A deleted note is `410 gone`. 
     * Add an optional card kind to an existing note.
     */
    async createCardRaw(requestParameters: CreateCardRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<Card>> {
        const requestOptions = await this.createCardRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => CardFromJSON(jsonValue));
    }

    /**
     * Server emits `card.created`. `kind` must be a key of the note type\'s `templates`. If the note already has a live card of that kind it is returned with `200` and nothing is emitted (REST never creates a duplicate `(note, kind)`). A merge loser\'s `note_id` acts on the survivor. A deleted note is `410 gone`. 
     * Add an optional card kind to an existing note.
     */
    async createCard(requestParameters: CreateCardRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<Card> {
        const response = await this.createCardRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for deleteCard without sending the request
     */
    async deleteCardRequestOpts(requestParameters: DeleteCardRequest): Promise<runtime.RequestOpts> {
        if (requestParameters['cardId'] == null) {
            throw new runtime.RequiredError(
                'cardId',
                'Required parameter "cardId" was null or undefined when calling deleteCard().'
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

        let urlPath = `/cards/{card_id}`;
        urlPath = urlPath.replace('{card_id}', encodeURIComponent(String(requestParameters['cardId'])));

        return {
            path: urlPath,
            method: 'DELETE',
            headers: headerParameters,
            query: queryParameters,
        };
    }

    /**
     * Server emits `card.deleted`. Already deleted or a merge loser - `204`, no op.
     * Delete one card (permanent tombstone); the note and its other cards stay.
     */
    async deleteCardRaw(requestParameters: DeleteCardRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<void>> {
        const requestOptions = await this.deleteCardRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.VoidApiResponse(response);
    }

    /**
     * Server emits `card.deleted`. Already deleted or a merge loser - `204`, no op.
     * Delete one card (permanent tombstone); the note and its other cards stay.
     */
    async deleteCard(requestParameters: DeleteCardRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<void> {
        await this.deleteCardRaw(requestParameters, initOverrides);
    }

    /**
     * Creates request options for getCard without sending the request
     */
    async getCardRequestOpts(requestParameters: GetCardRequest): Promise<runtime.RequestOpts> {
        if (requestParameters['cardId'] == null) {
            throw new runtime.RequiredError(
                'cardId',
                'Required parameter "cardId" was null or undefined when calling getCard().'
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

        let urlPath = `/cards/{card_id}`;
        urlPath = urlPath.replace('{card_id}', encodeURIComponent(String(requestParameters['cardId'])));

        return {
            path: urlPath,
            method: 'GET',
            headers: headerParameters,
            query: queryParameters,
        };
    }

    /**
     * A merge loser\'s id returns the survivor (`Content-Location` set). Deleted card, or card of a deleted note - `410 gone` unless `include_deleted=true`.
     * One card with its note summary.
     */
    async getCardRaw(requestParameters: GetCardRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<Card>> {
        const requestOptions = await this.getCardRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => CardFromJSON(jsonValue));
    }

    /**
     * A merge loser\'s id returns the survivor (`Content-Location` set). Deleted card, or card of a deleted note - `410 gone` unless `include_deleted=true`.
     * One card with its note summary.
     */
    async getCard(requestParameters: GetCardRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<Card> {
        const response = await this.getCardRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for listCardReviews without sending the request
     */
    async listCardReviewsRequestOpts(requestParameters: ListCardReviewsRequest): Promise<runtime.RequestOpts> {
        if (requestParameters['cardId'] == null) {
            throw new runtime.RequiredError(
                'cardId',
                'Required parameter "cardId" was null or undefined when calling listCardReviews().'
            );
        }

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

        let urlPath = `/cards/{card_id}/reviews`;
        urlPath = urlPath.replace('{card_id}', encodeURIComponent(String(requestParameters['cardId'])));

        return {
            path: urlPath,
            method: 'GET',
            headers: headerParameters,
            query: queryParameters,
        };
    }

    /**
     * Rows of `review_log` whose `card_id` is this card or any card merged into it, every `review_type`, newest first by `(reviewed_at, hlc, id)` descending - the reverse of the replay order (SYNC.md 9.1), so a late or re-minted review (SYNC.md 10.4) sits where it was answered, not where it was logged. Undone rows are included (`undone_at` set) so the history is complete. A merge loser\'s id resolves to the survivor. Works for deleted cards (history is never deleted). 
     * Review history of a card (its whole replay set plus drills and crams).
     */
    async listCardReviewsRaw(requestParameters: ListCardReviewsRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<ReviewPage>> {
        const requestOptions = await this.listCardReviewsRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => ReviewPageFromJSON(jsonValue));
    }

    /**
     * Rows of `review_log` whose `card_id` is this card or any card merged into it, every `review_type`, newest first by `(reviewed_at, hlc, id)` descending - the reverse of the replay order (SYNC.md 9.1), so a late or re-minted review (SYNC.md 10.4) sits where it was answered, not where it was logged. Undone rows are included (`undone_at` set) so the history is complete. A merge loser\'s id resolves to the survivor. Works for deleted cards (history is never deleted). 
     * Review history of a card (its whole replay set plus drills and crams).
     */
    async listCardReviews(requestParameters: ListCardReviewsRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<ReviewPage> {
        const response = await this.listCardReviewsRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for listCards without sending the request
     */
    async listCardsRequestOpts(requestParameters: ListCardsRequest): Promise<runtime.RequestOpts> {
        const queryParameters: any = {};

        if (requestParameters['query'] != null) {
            queryParameters['query'] = requestParameters['query'];
        }

        if (requestParameters['sort'] != null) {
            queryParameters['sort'] = requestParameters['sort'];
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

        let urlPath = `/cards`;

        return {
            path: urlPath,
            method: 'GET',
            headers: headerParameters,
            query: queryParameters,
        };
    }

    /**
     * `query` is a string in the card search language, parsed by the shared core (e.g. `deck:\"Frieren\" is:due tag:leech lapses>3 kanji:績 rated:7:1 kind:reading`). Absent or empty = all live cards. A string that does not parse is `422 invalid_query` with the UTF-16 offset of the error in `errors[0].position`. Cards of deleted notes, deleted cards and merge losers never match. Each card embeds a `note` summary. `is:due` and similar relative terms are evaluated at request time for the current study day. 
     * Find cards with the card search language.
     */
    async listCardsRaw(requestParameters: ListCardsRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<CardPage>> {
        const requestOptions = await this.listCardsRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => CardPageFromJSON(jsonValue));
    }

    /**
     * `query` is a string in the card search language, parsed by the shared core (e.g. `deck:\"Frieren\" is:due tag:leech lapses>3 kanji:績 rated:7:1 kind:reading`). Absent or empty = all live cards. A string that does not parse is `422 invalid_query` with the UTF-16 offset of the error in `errors[0].position`. Cards of deleted notes, deleted cards and merge losers never match. Each card embeds a `note` summary. `is:due` and similar relative terms are evaluated at request time for the current study day. 
     * Find cards with the card search language.
     */
    async listCards(requestParameters: ListCardsRequest = {}, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<CardPage> {
        const response = await this.listCardsRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for updateCard without sending the request
     */
    async updateCardRequestOpts(requestParameters: UpdateCardRequest): Promise<runtime.RequestOpts> {
        if (requestParameters['cardId'] == null) {
            throw new runtime.RequiredError(
                'cardId',
                'Required parameter "cardId" was null or undefined when calling updateCard().'
            );
        }

        if (requestParameters['cardPatch'] == null) {
            throw new runtime.RequiredError(
                'cardPatch',
                'Required parameter "cardPatch" was null or undefined when calling updateCard().'
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

        let urlPath = `/cards/{card_id}`;
        urlPath = urlPath.replace('{card_id}', encodeURIComponent(String(requestParameters['cardId'])));

        return {
            path: urlPath,
            method: 'PATCH',
            headers: headerParameters,
            query: queryParameters,
            body: CardPatchToJSON(requestParameters['cardPatch']),
        };
    }

    /**
     * Server emits one op per property present, in this order: `suspended` → `card.suspended` (true) or `card.unsuspended` (false); `buried_until` → `card.buried` (an instant) or `card.unburied` (`null`); `flags` → `card.flag_set`; `preset_id` → `card.preset_set` (must be a live preset). An empty body is `422`. A merge loser\'s id acts on the survivor. Schedule properties cannot be patched (use `POST /cards/bulk/schedule` `reschedule`/`reset` or review the card). 
     * Suspend, bury, flag or re-preset one card.
     */
    async updateCardRaw(requestParameters: UpdateCardRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<Card>> {
        const requestOptions = await this.updateCardRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => CardFromJSON(jsonValue));
    }

    /**
     * Server emits one op per property present, in this order: `suspended` → `card.suspended` (true) or `card.unsuspended` (false); `buried_until` → `card.buried` (an instant) or `card.unburied` (`null`); `flags` → `card.flag_set`; `preset_id` → `card.preset_set` (must be a live preset). An empty body is `422`. A merge loser\'s id acts on the survivor. Schedule properties cannot be patched (use `POST /cards/bulk/schedule` `reschedule`/`reset` or review the card). 
     * Suspend, bury, flag or re-preset one card.
     */
    async updateCard(requestParameters: UpdateCardRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<Card> {
        const response = await this.updateCardRaw(requestParameters, initOverrides);
        return await response.value();
    }

}

/**
 * @export
 */
export const ListCardsSortEnum = {
    CreatedAt: 'created_at',
    CreatedAt2: '-created_at',
    DueAt: 'due_at',
    DueAt2: '-due_at',
    UpdatedAt: 'updated_at',
    UpdatedAt2: '-updated_at',
    Lapses: 'lapses',
    Lapses2: '-lapses',
    Difficulty: 'difficulty',
    Difficulty2: '-difficulty',
    Stability: 'stability',
    Stability2: '-stability',
    LastReviewAt: 'last_review_at',
    LastReviewAt2: '-last_review_at',
} as const;
export type ListCardsSortEnum = typeof ListCardsSortEnum[keyof typeof ListCardsSortEnum];
