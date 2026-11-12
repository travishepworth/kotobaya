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
    type Coverage,
    CoverageFromJSON,
    CoverageToJSON,
} from '../models/Coverage';
import {
    type Deck,
    DeckFromJSON,
    DeckToJSON,
} from '../models/Deck';
import {
    type DeckCreate,
    DeckCreateFromJSON,
    DeckCreateToJSON,
} from '../models/DeckCreate';
import {
    type DeckItem,
    DeckItemFromJSON,
    DeckItemToJSON,
} from '../models/DeckItem';
import {
    type DeckItemList,
    DeckItemListFromJSON,
    DeckItemListToJSON,
} from '../models/DeckItemList';
import {
    type DeckItemPage,
    DeckItemPageFromJSON,
    DeckItemPageToJSON,
} from '../models/DeckItemPage';
import {
    type DeckItemPut,
    DeckItemPutFromJSON,
    DeckItemPutToJSON,
} from '../models/DeckItemPut';
import {
    type DeckItemsAdd,
    DeckItemsAddFromJSON,
    DeckItemsAddToJSON,
} from '../models/DeckItemsAdd';
import {
    type DeckList,
    DeckListFromJSON,
    DeckListToJSON,
} from '../models/DeckList';
import {
    type DeckListCatalogue,
    DeckListCatalogueFromJSON,
    DeckListCatalogueToJSON,
} from '../models/DeckListCatalogue';
import {
    type DeckPatch,
    DeckPatchFromJSON,
    DeckPatchToJSON,
} from '../models/DeckPatch';
import {
    type DeckType,
    DeckTypeFromJSON,
    DeckTypeToJSON,
} from '../models/DeckType';
import {
    type Problem,
    ProblemFromJSON,
    ProblemToJSON,
} from '../models/Problem';
import {
    type WordStatusValue,
    WordStatusValueFromJSON,
    WordStatusValueToJSON,
} from '../models/WordStatusValue';

export interface AddDeckItemsRequest {
    /**
     * Deck id. For a media deck this equals the media id.
     */
    deckId: string;
    /**
     * 
     */
    deckItemsAdd: DeckItemsAdd;
}

export interface CreateDeckRequest {
    /**
     * 
     */
    deckCreate: DeckCreate;
}

export interface DeleteDeckRequest {
    /**
     * Deck id. For a media deck this equals the media id.
     */
    deckId: string;
}

export interface GetDeckRequest {
    /**
     * Deck id. For a media deck this equals the media id.
     */
    deckId: string;
    /**
     * Also return tombstoned rows (`deleted_at` set).
     */
    includeDeleted?: boolean;
}

export interface GetDeckCoverageRequest {
    /**
     * Deck id. For a media deck this equals the media id.
     */
    deckId: string;
    /**
     * Classes left out of every coverage number, comma-separated. `names`, `particles`,
     * `auxiliary` = entries whose `occurrence.pos_class` is `name`, `particle`, `auxiliary`;
     * `ignored` = entries whose word status is `blacklisted` or `ignored`; `unmapped` = tokens
     * that matched no dictionary entry. Default `ignored`. Pass an empty value (`hide=`) to hide
     * nothing.
     * 
     */
    hide?: Set<GetDeckCoverageHideEnum>;
}

export interface ListDeckItemsRequest {
    /**
     * Deck id. For a media deck this equals the media id.
     */
    deckId: string;
    /**
     * Only items whose effective word status is one of these (comma-separated). Items without an `entry_seq` have no status and match only when this parameter is absent.
     */
    status?: Set<WordStatusValue>;
    /**
     * `position` = the deck's new-card order (ties by `item_key`); `occurrence_count` = most frequent in this deck first; `freq_rank` = globally most frequent first (NULLs last). Ties by `item_key`.
     */
    sort?: ListDeckItemsSortEnum;
    /**
     * Page size.
     */
    limit?: number;
    /**
     * Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`.
     */
    cursor?: string;
}

export interface ListDecksRequest {
    /**
     * Only decks of this type.
     */
    type?: DeckType;
    /**
     * Also return tombstoned rows (`deleted_at` set).
     */
    includeDeleted?: boolean;
}

export interface PutDeckItemRequest {
    /**
     * Deck id. For a media deck this equals the media id.
     */
    deckId: string;
    /**
     * `e:<entry_seq>` or `n:<note uuid, lowercase>`. The colon need not be percent-encoded.
     */
    itemKey: string;
    /**
     * 
     */
    deckItemPut?: DeckItemPut;
}

export interface RemoveDeckItemRequest {
    /**
     * Deck id. For a media deck this equals the media id.
     */
    deckId: string;
    /**
     * `e:<entry_seq>` or `n:<note uuid, lowercase>`. The colon need not be percent-encoded.
     */
    itemKey: string;
}

export interface UpdateDeckRequest {
    /**
     * Deck id. For a media deck this equals the media id.
     */
    deckId: string;
    /**
     * 
     */
    deckPatch: DeckPatch;
}

/**
 * 
 */
export class DecksApi extends runtime.BaseAPI {

    /**
     * Creates request options for addDeckItems without sending the request
     */
    async addDeckItemsRequestOpts(requestParameters: AddDeckItemsRequest): Promise<runtime.RequestOpts> {
        if (requestParameters['deckId'] == null) {
            throw new runtime.RequiredError(
                'deckId',
                'Required parameter "deckId" was null or undefined when calling addDeckItems().'
            );
        }

        if (requestParameters['deckItemsAdd'] == null) {
            throw new runtime.RequiredError(
                'deckItemsAdd',
                'Required parameter "deckItemsAdd" was null or undefined when calling addDeckItems().'
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

        let urlPath = `/decks/{deck_id}/items`;
        urlPath = urlPath.replace('{deck_id}', encodeURIComponent(String(requestParameters['deckId'])));

        return {
            path: urlPath,
            method: 'POST',
            headers: headerParameters,
            query: queryParameters,
            body: DeckItemsAddToJSON(requestParameters['deckItemsAdd']),
        };
    }

    /**
     * Server emits one `deck.item_put` per element, in array order, in one transaction. Each element names exactly one of `entry_seq` or `note_id`. A `note_id` whose note has an `entry_seq` is stored in entry form (`e:<seq>`); a merge loser\'s id is resolved to the survivor first. Elements without `position` are appended after the current maximum, 1024 apart, in array order. An element already present (live or removed) is put again (the put clears a removal and moves it). Duplicates inside the array: the last wins. 1 to 1000 elements. Not a `user` deck: `409 deck_read_only`. Unknown entry or note: `422` naming the element; nothing is applied. 
     * Add several items to a user deck.
     */
    async addDeckItemsRaw(requestParameters: AddDeckItemsRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<DeckItemList>> {
        const requestOptions = await this.addDeckItemsRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => DeckItemListFromJSON(jsonValue));
    }

    /**
     * Server emits one `deck.item_put` per element, in array order, in one transaction. Each element names exactly one of `entry_seq` or `note_id`. A `note_id` whose note has an `entry_seq` is stored in entry form (`e:<seq>`); a merge loser\'s id is resolved to the survivor first. Elements without `position` are appended after the current maximum, 1024 apart, in array order. An element already present (live or removed) is put again (the put clears a removal and moves it). Duplicates inside the array: the last wins. 1 to 1000 elements. Not a `user` deck: `409 deck_read_only`. Unknown entry or note: `422` naming the element; nothing is applied. 
     * Add several items to a user deck.
     */
    async addDeckItems(requestParameters: AddDeckItemsRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<DeckItemList> {
        const response = await this.addDeckItemsRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for createDeck without sending the request
     */
    async createDeckRequestOpts(requestParameters: CreateDeckRequest): Promise<runtime.RequestOpts> {
        if (requestParameters['deckCreate'] == null) {
            throw new runtime.RequiredError(
                'deckCreate',
                'Required parameter "deckCreate" was null or undefined when calling createDeck().'
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

        let urlPath = `/decks`;

        return {
            path: urlPath,
            method: 'POST',
            headers: headerParameters,
            query: queryParameters,
            body: DeckCreateToJSON(requestParameters['deckCreate']),
        };
    }

    /**
     * Server emits `deck.created` (origin: server device, which is what makes `list` decks legal here).  - `type: user` - `name` required. Starts empty. - `type: smart` - `name` and `query` required; `query` must parse (`422 invalid_query`). - `type: list` - `list_key` required and must be in `GET /deck-lists` (`422` otherwise);   `name` defaults to the catalogue title. Under the op-apply lock: if a live deck with   that `list_key` exists it is returned with `200` and nothing is emitted (a sent `id`   is then ignored). Otherwise the deck is created and a job of kind `deck-build` is   queued that builds the item snapshot and makes the server emit   `deck.content_published`; until then the deck has `content_version = 0` and no items. - `type: media` is `422`: media decks are created only by import jobs.  Properties that do not belong to the type (`query` on a user deck, ...) are `422`. 
     * Create a user or smart deck, or enable a generated list.
     */
    async createDeckRaw(requestParameters: CreateDeckRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<Deck>> {
        const requestOptions = await this.createDeckRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => DeckFromJSON(jsonValue));
    }

    /**
     * Server emits `deck.created` (origin: server device, which is what makes `list` decks legal here).  - `type: user` - `name` required. Starts empty. - `type: smart` - `name` and `query` required; `query` must parse (`422 invalid_query`). - `type: list` - `list_key` required and must be in `GET /deck-lists` (`422` otherwise);   `name` defaults to the catalogue title. Under the op-apply lock: if a live deck with   that `list_key` exists it is returned with `200` and nothing is emitted (a sent `id`   is then ignored). Otherwise the deck is created and a job of kind `deck-build` is   queued that builds the item snapshot and makes the server emit   `deck.content_published`; until then the deck has `content_version = 0` and no items. - `type: media` is `422`: media decks are created only by import jobs.  Properties that do not belong to the type (`query` on a user deck, ...) are `422`. 
     * Create a user or smart deck, or enable a generated list.
     */
    async createDeck(requestParameters: CreateDeckRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<Deck> {
        const response = await this.createDeckRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for deleteDeck without sending the request
     */
    async deleteDeckRequestOpts(requestParameters: DeleteDeckRequest): Promise<runtime.RequestOpts> {
        if (requestParameters['deckId'] == null) {
            throw new runtime.RequiredError(
                'deckId',
                'Required parameter "deckId" was null or undefined when calling deleteDeck().'
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

        let urlPath = `/decks/{deck_id}`;
        urlPath = urlPath.replace('{deck_id}', encodeURIComponent(String(requestParameters['deckId'])));

        return {
            path: urlPath,
            method: 'DELETE',
            headers: headerParameters,
            query: queryParameters,
        };
    }

    /**
     * Server emits `deck.deleted`. A `media` deck is `409 deck_read_only`: delete the media (`DELETE /media/{media_id}`), which tombstones its deck. A deleted `list` deck can be enabled again later (a new deck with a new id). Already deleted: `204`, no op. 
     * Delete a user, smart or list deck. Notes and cards are untouched.
     */
    async deleteDeckRaw(requestParameters: DeleteDeckRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<void>> {
        const requestOptions = await this.deleteDeckRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.VoidApiResponse(response);
    }

    /**
     * Server emits `deck.deleted`. A `media` deck is `409 deck_read_only`: delete the media (`DELETE /media/{media_id}`), which tombstones its deck. A deleted `list` deck can be enabled again later (a new deck with a new id). Already deleted: `204`, no op. 
     * Delete a user, smart or list deck. Notes and cards are untouched.
     */
    async deleteDeck(requestParameters: DeleteDeckRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<void> {
        await this.deleteDeckRaw(requestParameters, initOverrides);
    }

    /**
     * Creates request options for getDeck without sending the request
     */
    async getDeckRequestOpts(requestParameters: GetDeckRequest): Promise<runtime.RequestOpts> {
        if (requestParameters['deckId'] == null) {
            throw new runtime.RequiredError(
                'deckId',
                'Required parameter "deckId" was null or undefined when calling getDeck().'
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

        let urlPath = `/decks/{deck_id}`;
        urlPath = urlPath.replace('{deck_id}', encodeURIComponent(String(requestParameters['deckId'])));

        return {
            path: urlPath,
            method: 'GET',
            headers: headerParameters,
            query: queryParameters,
        };
    }

    /**
     * One deck.
     */
    async getDeckRaw(requestParameters: GetDeckRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<Deck>> {
        const requestOptions = await this.getDeckRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => DeckFromJSON(jsonValue));
    }

    /**
     * One deck.
     */
    async getDeck(requestParameters: GetDeckRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<Deck> {
        const response = await this.getDeckRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for getDeckCoverage without sending the request
     */
    async getDeckCoverageRequestOpts(requestParameters: GetDeckCoverageRequest): Promise<runtime.RequestOpts> {
        if (requestParameters['deckId'] == null) {
            throw new runtime.RequiredError(
                'deckId',
                'Required parameter "deckId" was null or undefined when calling getDeckCoverage().'
            );
        }

        const queryParameters: any = {};

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

        let urlPath = `/decks/{deck_id}/coverage`;
        urlPath = urlPath.replace('{deck_id}', encodeURIComponent(String(requestParameters['deckId'])));

        return {
            path: urlPath,
            method: 'GET',
            headers: headerParameters,
            query: queryParameters,
        };
    }

    /**
     * Computed at request time from live `deck_item` rows that have an `entry_seq` and `word_status`. For `media` decks the result equals `GET /media/{id}/coverage` without `segment_id` (including `segments`, `curve` and token numbers). For `user` and `list` decks token numbers count each item once (`occurrence_count` is 0, treated as 1), `segments` is empty and `difficulty` is null. Smart decks: `409 not_applicable`. 
     * Coverage of a deck\'s items by your word statuses.
     */
    async getDeckCoverageRaw(requestParameters: GetDeckCoverageRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<Coverage>> {
        const requestOptions = await this.getDeckCoverageRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => CoverageFromJSON(jsonValue));
    }

    /**
     * Computed at request time from live `deck_item` rows that have an `entry_seq` and `word_status`. For `media` decks the result equals `GET /media/{id}/coverage` without `segment_id` (including `segments`, `curve` and token numbers). For `user` and `list` decks token numbers count each item once (`occurrence_count` is 0, treated as 1), `segments` is empty and `difficulty` is null. Smart decks: `409 not_applicable`. 
     * Coverage of a deck\'s items by your word statuses.
     */
    async getDeckCoverage(requestParameters: GetDeckCoverageRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<Coverage> {
        const response = await this.getDeckCoverageRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for listDeckItems without sending the request
     */
    async listDeckItemsRequestOpts(requestParameters: ListDeckItemsRequest): Promise<runtime.RequestOpts> {
        if (requestParameters['deckId'] == null) {
            throw new runtime.RequiredError(
                'deckId',
                'Required parameter "deckId" was null or undefined when calling listDeckItems().'
            );
        }

        const queryParameters: any = {};

        if (requestParameters['status'] != null) {
            queryParameters['status'] = Array.from(requestParameters['status'])!.join(runtime.COLLECTION_FORMATS["csv"]);
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

        let urlPath = `/decks/{deck_id}/items`;
        urlPath = urlPath.replace('{deck_id}', encodeURIComponent(String(requestParameters['deckId'])));

        return {
            path: urlPath,
            method: 'GET',
            headers: headerParameters,
            query: queryParameters,
        };
    }

    /**
     * Live items only. For a media deck this is the media\'s vocabulary list: `status=new` with `sort=occurrence_count` gives \"unknown words by in-deck frequency, global rank beside them\". An item whose `note_id` is a merge loser is reported with the survivor\'s id. Items whose entry is missing from the dictionary have `entry: null`. Smart decks have no items: `409 not_applicable` (use `GET /cards?query=`). Deleted deck: `410 gone`. 
     * Items of a deck with dictionary summary and word status.
     */
    async listDeckItemsRaw(requestParameters: ListDeckItemsRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<DeckItemPage>> {
        const requestOptions = await this.listDeckItemsRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => DeckItemPageFromJSON(jsonValue));
    }

    /**
     * Live items only. For a media deck this is the media\'s vocabulary list: `status=new` with `sort=occurrence_count` gives \"unknown words by in-deck frequency, global rank beside them\". An item whose `note_id` is a merge loser is reported with the survivor\'s id. Items whose entry is missing from the dictionary have `entry: null`. Smart decks have no items: `409 not_applicable` (use `GET /cards?query=`). Deleted deck: `410 gone`. 
     * Items of a deck with dictionary summary and word status.
     */
    async listDeckItems(requestParameters: ListDeckItemsRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<DeckItemPage> {
        const response = await this.listDeckItemsRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for listDeckLists without sending the request
     */
    async listDeckListsRequestOpts(): Promise<runtime.RequestOpts> {
        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};

        if (this.configuration && this.configuration.accessToken) {
            const token = this.configuration.accessToken;
            const tokenString = await token("bearerAuth", ["read"]);

            if (tokenString) {
                headerParameters["Authorization"] = `Bearer ${tokenString}`;
            }
        }

        let urlPath = `/deck-lists`;

        return {
            path: urlPath,
            method: 'GET',
            headers: headerParameters,
            query: queryParameters,
        };
    }

    /**
     * The catalogue is computed by the server from the current dictionary release: `jlpt-n5` ... `jlpt-n1` and, per loaded frequency list, `freq-<list id>-<N>` for N in 1000, 2000, 5000, 10000, 20000. `deck_id` is set when a live deck for the key exists. Not paginated; ordered by `list_key`. Empty when no dictionary release is loaded. 
     * Generated lists (JLPT, frequency bands) that can be enabled as `list` decks.
     */
    async listDeckListsRaw(initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<DeckListCatalogue>> {
        const requestOptions = await this.listDeckListsRequestOpts();
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => DeckListCatalogueFromJSON(jsonValue));
    }

    /**
     * The catalogue is computed by the server from the current dictionary release: `jlpt-n5` ... `jlpt-n1` and, per loaded frequency list, `freq-<list id>-<N>` for N in 1000, 2000, 5000, 10000, 20000. `deck_id` is set when a live deck for the key exists. Not paginated; ordered by `list_key`. Empty when no dictionary release is loaded. 
     * Generated lists (JLPT, frequency bands) that can be enabled as `list` decks.
     */
    async listDeckLists(initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<DeckListCatalogue> {
        const response = await this.listDeckListsRaw(initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for listDecks without sending the request
     */
    async listDecksRequestOpts(requestParameters: ListDecksRequest): Promise<runtime.RequestOpts> {
        const queryParameters: any = {};

        if (requestParameters['type'] != null) {
            queryParameters['type'] = requestParameters['type'];
        }

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

        let urlPath = `/decks`;

        return {
            path: urlPath,
            method: 'GET',
            headers: headerParameters,
            query: queryParameters,
        };
    }

    /**
     * Not paginated. Ordered by `(priority, id)` ascending - the order new cards are drawn in.
     * List decks.
     */
    async listDecksRaw(requestParameters: ListDecksRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<DeckList>> {
        const requestOptions = await this.listDecksRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => DeckListFromJSON(jsonValue));
    }

    /**
     * Not paginated. Ordered by `(priority, id)` ascending - the order new cards are drawn in.
     * List decks.
     */
    async listDecks(requestParameters: ListDecksRequest = {}, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<DeckList> {
        const response = await this.listDecksRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for putDeckItem without sending the request
     */
    async putDeckItemRequestOpts(requestParameters: PutDeckItemRequest): Promise<runtime.RequestOpts> {
        if (requestParameters['deckId'] == null) {
            throw new runtime.RequiredError(
                'deckId',
                'Required parameter "deckId" was null or undefined when calling putDeckItem().'
            );
        }

        if (requestParameters['itemKey'] == null) {
            throw new runtime.RequiredError(
                'itemKey',
                'Required parameter "itemKey" was null or undefined when calling putDeckItem().'
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

        let urlPath = `/decks/{deck_id}/items/{item_key}`;
        urlPath = urlPath.replace('{deck_id}', encodeURIComponent(String(requestParameters['deckId'])));
        urlPath = urlPath.replace('{item_key}', encodeURIComponent(String(requestParameters['itemKey'])));

        return {
            path: urlPath,
            method: 'PUT',
            headers: headerParameters,
            query: queryParameters,
            body: DeckItemPutToJSON(requestParameters['deckItemPut']),
        };
    }

    /**
     * Server emits `deck.item_put`. Without a body, or without `position`: a new item is appended (maximum position + 1024); an existing live item keeps its position. An `n:<id>` key whose note has an `entry_seq` is `422` (use the `e:` form); the note or entry must exist. Not a `user` deck: `409 deck_read_only`. 
     * Add one item to a user deck, or move it.
     */
    async putDeckItemRaw(requestParameters: PutDeckItemRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<DeckItem>> {
        const requestOptions = await this.putDeckItemRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => DeckItemFromJSON(jsonValue));
    }

    /**
     * Server emits `deck.item_put`. Without a body, or without `position`: a new item is appended (maximum position + 1024); an existing live item keeps its position. An `n:<id>` key whose note has an `entry_seq` is `422` (use the `e:` form); the note or entry must exist. Not a `user` deck: `409 deck_read_only`. 
     * Add one item to a user deck, or move it.
     */
    async putDeckItem(requestParameters: PutDeckItemRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<DeckItem> {
        const response = await this.putDeckItemRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for removeDeckItem without sending the request
     */
    async removeDeckItemRequestOpts(requestParameters: RemoveDeckItemRequest): Promise<runtime.RequestOpts> {
        if (requestParameters['deckId'] == null) {
            throw new runtime.RequiredError(
                'deckId',
                'Required parameter "deckId" was null or undefined when calling removeDeckItem().'
            );
        }

        if (requestParameters['itemKey'] == null) {
            throw new runtime.RequiredError(
                'itemKey',
                'Required parameter "itemKey" was null or undefined when calling removeDeckItem().'
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

        let urlPath = `/decks/{deck_id}/items/{item_key}`;
        urlPath = urlPath.replace('{deck_id}', encodeURIComponent(String(requestParameters['deckId'])));
        urlPath = urlPath.replace('{item_key}', encodeURIComponent(String(requestParameters['itemKey'])));

        return {
            path: urlPath,
            method: 'DELETE',
            headers: headerParameters,
            query: queryParameters,
        };
    }

    /**
     * Server emits `deck.item_removed`. Item absent or already removed: `204`, no op. Not a `user` deck: `409 deck_read_only`. 
     * Remove one item from a user deck (reversible by putting it again).
     */
    async removeDeckItemRaw(requestParameters: RemoveDeckItemRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<void>> {
        const requestOptions = await this.removeDeckItemRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.VoidApiResponse(response);
    }

    /**
     * Server emits `deck.item_removed`. Item absent or already removed: `204`, no op. Not a `user` deck: `409 deck_read_only`. 
     * Remove one item from a user deck (reversible by putting it again).
     */
    async removeDeckItem(requestParameters: RemoveDeckItemRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<void> {
        await this.removeDeckItemRaw(requestParameters, initOverrides);
    }

    /**
     * Creates request options for updateDeck without sending the request
     */
    async updateDeckRequestOpts(requestParameters: UpdateDeckRequest): Promise<runtime.RequestOpts> {
        if (requestParameters['deckId'] == null) {
            throw new runtime.RequiredError(
                'deckId',
                'Required parameter "deckId" was null or undefined when calling updateDeck().'
            );
        }

        if (requestParameters['deckPatch'] == null) {
            throw new runtime.RequiredError(
                'deckPatch',
                'Required parameter "deckPatch" was null or undefined when calling updateDeck().'
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

        let urlPath = `/decks/{deck_id}`;
        urlPath = urlPath.replace('{deck_id}', encodeURIComponent(String(requestParameters['deckId'])));

        return {
            path: urlPath,
            method: 'PATCH',
            headers: headerParameters,
            query: queryParameters,
            body: DeckPatchToJSON(requestParameters['deckPatch']),
        };
    }

    /**
     * Server emits one `deck.updated` with the registers present: `name`, `priority`, `enabled`, `new_cards_per_day`, `reviews_per_day`, `query` (smart decks only, must parse), and one `settings.<key>` per key of `settings` (partial map; a `null` value removes the key). Works for every deck type. `type`, `media_id`, `list_key` are immutable (`422`). An empty body is `422`. 
     * Rename, reprioritise, enable/disable, set limits or settings.
     */
    async updateDeckRaw(requestParameters: UpdateDeckRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<Deck>> {
        const requestOptions = await this.updateDeckRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => DeckFromJSON(jsonValue));
    }

    /**
     * Server emits one `deck.updated` with the registers present: `name`, `priority`, `enabled`, `new_cards_per_day`, `reviews_per_day`, `query` (smart decks only, must parse), and one `settings.<key>` per key of `settings` (partial map; a `null` value removes the key). Works for every deck type. `type`, `media_id`, `list_key` are immutable (`422`). An empty body is `422`. 
     * Rename, reprioritise, enable/disable, set limits or settings.
     */
    async updateDeck(requestParameters: UpdateDeckRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<Deck> {
        const response = await this.updateDeckRaw(requestParameters, initOverrides);
        return await response.value();
    }

}

/**
 * @export
 */
export const GetDeckCoverageHideEnum = {
    Names: 'names',
    Particles: 'particles',
    Auxiliary: 'auxiliary',
    Ignored: 'ignored',
    Unmapped: 'unmapped',
} as const;
export type GetDeckCoverageHideEnum = typeof GetDeckCoverageHideEnum[keyof typeof GetDeckCoverageHideEnum];
/**
 * @export
 */
export const ListDeckItemsSortEnum = {
    Position: 'position',
    OccurrenceCount: 'occurrence_count',
    FreqRank: 'freq_rank',
} as const;
export type ListDeckItemsSortEnum = typeof ListDeckItemsSortEnum[keyof typeof ListDeckItemsSortEnum];
