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
    type DictEntry,
    DictEntryFromJSON,
    DictEntryToJSON,
} from '../models/DictEntry';
import {
    type DictEntrySummaryPage,
    DictEntrySummaryPageFromJSON,
    DictEntrySummaryPageToJSON,
} from '../models/DictEntrySummaryPage';
import {
    type DictHeadwordList,
    DictHeadwordListFromJSON,
    DictHeadwordListToJSON,
} from '../models/DictHeadwordList';
import {
    type DictRelease,
    DictReleaseFromJSON,
    DictReleaseToJSON,
} from '../models/DictRelease';
import {
    type EntryExamples,
    EntryExamplesFromJSON,
    EntryExamplesToJSON,
} from '../models/EntryExamples';
import {
    type Job,
    JobFromJSON,
    JobToJSON,
} from '../models/Job';
import {
    type JobPayloadDictRefresh,
    JobPayloadDictRefreshFromJSON,
    JobPayloadDictRefreshToJSON,
} from '../models/JobPayloadDictRefresh';
import {
    type Kanji,
    KanjiFromJSON,
    KanjiToJSON,
} from '../models/Kanji';
import {
    type KanjiWordPage,
    KanjiWordPageFromJSON,
    KanjiWordPageToJSON,
} from '../models/KanjiWordPage';
import {
    type Problem,
    ProblemFromJSON,
    ProblemToJSON,
} from '../models/Problem';

export interface GetDictEntryRequest {
    /**
     * JMdict sequence number of the entry.
     */
    seq: number;
}

export interface GetKanjiRequest {
    /**
     * Exactly one kanji (one Unicode code point), percent-encoded UTF-8. The literal path segment `hot` is the separate operation `listHotKanji`.
     */
    literal: string;
}

export interface ListDictHeadwordsRequest {
    /**
     * 1 to 500 JMdict sequence numbers, comma-separated. Duplicates collapse; unknown values are omitted from the result.
     */
    seq: Array<number>;
}

export interface ListEntryExamplesRequest {
    /**
     * JMdict sequence number of the entry.
     */
    seq: number;
    /**
     * Maximum number of sentences per list.
     */
    limit?: number;
}

export interface ListKanjiWordsRequest {
    /**
     * Exactly one Unicode code point, percent-encoded UTF-8, matched exactly as given against `ref.entry_kanji.literal` (no normalisation, same rules as `getKanji`).
     */
    literal: string;
    /**
     * Only entries that are live items of this deck, ordered by in-deck frequency. Must be a live deck (`404` otherwise).
     */
    deckId?: string;
    /**
     * Only entries that are a live item of at least one of your live decks or have a live vocab note.
     */
    mine?: boolean;
    /**
     * Page size.
     */
    limit?: number;
    /**
     * Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`.
     */
    cursor?: string;
}

export interface RefreshDictionaryRequest {
    /**
     * 
     */
    jobPayloadDictRefresh?: JobPayloadDictRefresh;
}

export interface SearchDictionaryRequest {
    /**
     * Search text, 1 to 100 characters after trimming.
     */
    q: string;
    /**
     * Only entries with `is_common = true`.
     */
    commonOnly?: boolean;
    /**
     * Page size.
     */
    limit?: number;
    /**
     * Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`.
     */
    cursor?: string;
}

/**
 * 
 */
export class DictionaryApi extends runtime.BaseAPI {

    /**
     * Creates request options for getDictEntry without sending the request
     */
    async getDictEntryRequestOpts(requestParameters: GetDictEntryRequest): Promise<runtime.RequestOpts> {
        if (requestParameters['seq'] == null) {
            throw new runtime.RequiredError(
                'seq',
                'Required parameter "seq" was null or undefined when calling getDictEntry().'
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

        let urlPath = `/dict/entries/{seq}`;
        urlPath = urlPath.replace('{seq}', encodeURIComponent(String(requestParameters['seq'])));

        return {
            path: urlPath,
            method: 'GET',
            headers: headerParameters,
            query: queryParameters,
        };
    }

    /**
     * Retired entries are still returned (`retired` is true). Unknown `seq` is `404`.
     * One JMdict entry with frequency, pitch, kanji and your status.
     */
    async getDictEntryRaw(requestParameters: GetDictEntryRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<DictEntry>> {
        const requestOptions = await this.getDictEntryRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => DictEntryFromJSON(jsonValue));
    }

    /**
     * Retired entries are still returned (`retired` is true). Unknown `seq` is `404`.
     * One JMdict entry with frequency, pitch, kanji and your status.
     */
    async getDictEntry(requestParameters: GetDictEntryRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<DictEntry> {
        const response = await this.getDictEntryRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for getDictRelease without sending the request
     */
    async getDictReleaseRequestOpts(): Promise<runtime.RequestOpts> {
        const queryParameters: any = {};

        const headerParameters: runtime.HTTPHeaders = {};

        if (this.configuration && this.configuration.accessToken) {
            const token = this.configuration.accessToken;
            const tokenString = await token("bearerAuth", ["read"]);

            if (tokenString) {
                headerParameters["Authorization"] = `Bearer ${tokenString}`;
            }
        }

        let urlPath = `/dict/release`;

        return {
            path: urlPath,
            method: 'GET',
            headers: headerParameters,
            query: queryParameters,
        };
    }

    /**
     * The phone compares `id` with the release of its dictionary file and, when behind, downloads an artifact through `GET /blobs/{hash}`: a `diff` whose `from_release` equals its release and whose `scope` matches, else the `full` build. `404` when no release has been loaded yet. Package and diff formats, hashes and the full install/update algorithm: `contracts/db/DICT_PACKAGE.md`. 
     * The current dictionary release and its downloadable device builds.
     */
    async getDictReleaseRaw(initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<DictRelease>> {
        const requestOptions = await this.getDictReleaseRequestOpts();
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => DictReleaseFromJSON(jsonValue));
    }

    /**
     * The phone compares `id` with the release of its dictionary file and, when behind, downloads an artifact through `GET /blobs/{hash}`: a `diff` whose `from_release` equals its release and whose `scope` matches, else the `full` build. `404` when no release has been loaded yet. Package and diff formats, hashes and the full install/update algorithm: `contracts/db/DICT_PACKAGE.md`. 
     * The current dictionary release and its downloadable device builds.
     */
    async getDictRelease(initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<DictRelease> {
        const response = await this.getDictReleaseRaw(initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for getKanji without sending the request
     */
    async getKanjiRequestOpts(requestParameters: GetKanjiRequest): Promise<runtime.RequestOpts> {
        if (requestParameters['literal'] == null) {
            throw new runtime.RequiredError(
                'literal',
                'Required parameter "literal" was null or undefined when calling getKanji().'
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

        let urlPath = `/kanji/{literal}`;
        urlPath = urlPath.replace('{literal}', encodeURIComponent(String(requestParameters['literal'])));

        return {
            path: urlPath,
            method: 'GET',
            headers: headerParameters,
            query: queryParameters,
        };
    }

    /**
     * More than one code point is `422`; a character not in `ref.kanji` is `404`. (`maxLength: 2` because a code point outside the BMP is two UTF-16 units.) 
     * One kanji from KANJIDIC2 with components and your kanji note, if any.
     */
    async getKanjiRaw(requestParameters: GetKanjiRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<Kanji>> {
        const requestOptions = await this.getKanjiRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => KanjiFromJSON(jsonValue));
    }

    /**
     * More than one code point is `422`; a character not in `ref.kanji` is `404`. (`maxLength: 2` because a code point outside the BMP is two UTF-16 units.) 
     * One kanji from KANJIDIC2 with components and your kanji note, if any.
     */
    async getKanji(requestParameters: GetKanjiRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<Kanji> {
        const response = await this.getKanjiRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for listDictHeadwords without sending the request
     */
    async listDictHeadwordsRequestOpts(requestParameters: ListDictHeadwordsRequest): Promise<runtime.RequestOpts> {
        if (requestParameters['seq'] == null) {
            throw new runtime.RequiredError(
                'seq',
                'Required parameter "seq" was null or undefined when calling listDictHeadwords().'
            );
        }

        const queryParameters: any = {};

        if (requestParameters['seq'] != null) {
            queryParameters['seq'] = requestParameters['seq']!.join(runtime.COLLECTION_FORMATS["csv"]);
        }

        const headerParameters: runtime.HTTPHeaders = {};

        if (this.configuration && this.configuration.accessToken) {
            const token = this.configuration.accessToken;
            const tokenString = await token("bearerAuth", ["read"]);

            if (tokenString) {
                headerParameters["Authorization"] = `Bearer ${tokenString}`;
            }
        }

        let urlPath = `/dict/headwords`;

        return {
            path: urlPath,
            method: 'GET',
            headers: headerParameters,
            query: queryParameters,
        };
    }

    /**
     * Batch label lookup for clients that hold only `entry_seq` values (e.g. a page of referenced notes whose `fields` are `{}`). Read-only; no overlay (`status`, `note_id`), no paging. Use `getDictEntry` for the full entry.  **`seq`.** One query parameter holding 1 to 500 JMdict sequence numbers separated by commas (`seq=1358280,1358300`; a percent-encoded comma `%2C` is the same thing). Each value is a base-10 integer >= 1 with no sign, spaces or decimals. Any of these is `422` (`validation_failed`, pointer `/seq`) and nothing is looked up: the parameter is missing, empty (`seq=`) or given more than once (`seq=1&seq=2`); an element is empty (`1,,2`, trailing comma), not an integer, below 1 or above the int64 range; more than 500 elements. The 500 limit counts elements as sent, before de-duplication. Duplicates are allowed and collapse: `seq=5,5` behaves as `seq=5`.  **Result.** One item per distinct requested `seq` that exists in `ref.dict_entry`, ordered by `seq` ascending (not in request order). A `seq` with no row is omitted - not an error, and not `404` even when no requested `seq` exists (`items: []`). Retired entries are returned, as in `getDictEntry` (a note may still reference one). Clients match items to their rows by `seq` and keep their fallback label for omitted ones.  **Item fields** have exactly the values `getDictEntry` and `searchDictionary` report for the same entry: `headword` = first kanji form in JMdict order, or the first reading when the entry has no kanji form; `headword_reading` = first reading (equal to `headword` for kana-only entries); `glosses` = English glosses of the first sense, in order (the first element is the short meaning; empty array if the first sense has no English gloss).  **Request size.** 500 seven-digit values with percent-encoded commas make a request line of about 5 kB. The server and every proxy in front of it MUST accept request lines of at least 8192 bytes for this operation (Netty\'s default of 4096 is too small).  The response depends only on the dictionary release, not on the caller; it carries no cache headers in v1. 
     * Headword, reading and first-sense glosses for up to 500 entries in one call (C-18).
     */
    async listDictHeadwordsRaw(requestParameters: ListDictHeadwordsRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<DictHeadwordList>> {
        const requestOptions = await this.listDictHeadwordsRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => DictHeadwordListFromJSON(jsonValue));
    }

    /**
     * Batch label lookup for clients that hold only `entry_seq` values (e.g. a page of referenced notes whose `fields` are `{}`). Read-only; no overlay (`status`, `note_id`), no paging. Use `getDictEntry` for the full entry.  **`seq`.** One query parameter holding 1 to 500 JMdict sequence numbers separated by commas (`seq=1358280,1358300`; a percent-encoded comma `%2C` is the same thing). Each value is a base-10 integer >= 1 with no sign, spaces or decimals. Any of these is `422` (`validation_failed`, pointer `/seq`) and nothing is looked up: the parameter is missing, empty (`seq=`) or given more than once (`seq=1&seq=2`); an element is empty (`1,,2`, trailing comma), not an integer, below 1 or above the int64 range; more than 500 elements. The 500 limit counts elements as sent, before de-duplication. Duplicates are allowed and collapse: `seq=5,5` behaves as `seq=5`.  **Result.** One item per distinct requested `seq` that exists in `ref.dict_entry`, ordered by `seq` ascending (not in request order). A `seq` with no row is omitted - not an error, and not `404` even when no requested `seq` exists (`items: []`). Retired entries are returned, as in `getDictEntry` (a note may still reference one). Clients match items to their rows by `seq` and keep their fallback label for omitted ones.  **Item fields** have exactly the values `getDictEntry` and `searchDictionary` report for the same entry: `headword` = first kanji form in JMdict order, or the first reading when the entry has no kanji form; `headword_reading` = first reading (equal to `headword` for kana-only entries); `glosses` = English glosses of the first sense, in order (the first element is the short meaning; empty array if the first sense has no English gloss).  **Request size.** 500 seven-digit values with percent-encoded commas make a request line of about 5 kB. The server and every proxy in front of it MUST accept request lines of at least 8192 bytes for this operation (Netty\'s default of 4096 is too small).  The response depends only on the dictionary release, not on the caller; it carries no cache headers in v1. 
     * Headword, reading and first-sense glosses for up to 500 entries in one call (C-18).
     */
    async listDictHeadwords(requestParameters: ListDictHeadwordsRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<DictHeadwordList> {
        const response = await this.listDictHeadwordsRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for listEntryExamples without sending the request
     */
    async listEntryExamplesRequestOpts(requestParameters: ListEntryExamplesRequest): Promise<runtime.RequestOpts> {
        if (requestParameters['seq'] == null) {
            throw new runtime.RequiredError(
                'seq',
                'Required parameter "seq" was null or undefined when calling listEntryExamples().'
            );
        }

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

        let urlPath = `/dict/entries/{seq}/examples`;
        urlPath = urlPath.replace('{seq}', encodeURIComponent(String(requestParameters['seq'])));

        return {
            path: urlPath,
            method: 'GET',
            headers: headerParameters,
            query: queryParameters,
        };
    }

    /**
     * `media`: up to `limit` sentences from imported media that contain the entry (resolved through `occurrence.sentence_ids`), sentences with the fewest unknown words first, then by media `created_at` descending, then sentence id. `tatoeba`: up to `limit` rows of `ref.example_sentence` linked to the entry, shortest text first, then id. Either list may be empty. Not paginated. Unknown `seq` is `404`. 
     * Example sentences for an entry, from your media and from Tatoeba.
     */
    async listEntryExamplesRaw(requestParameters: ListEntryExamplesRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<EntryExamples>> {
        const requestOptions = await this.listEntryExamplesRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => EntryExamplesFromJSON(jsonValue));
    }

    /**
     * `media`: up to `limit` sentences from imported media that contain the entry (resolved through `occurrence.sentence_ids`), sentences with the fewest unknown words first, then by media `created_at` descending, then sentence id. `tatoeba`: up to `limit` rows of `ref.example_sentence` linked to the entry, shortest text first, then id. Either list may be empty. Not paginated. Unknown `seq` is `404`. 
     * Example sentences for an entry, from your media and from Tatoeba.
     */
    async listEntryExamples(requestParameters: ListEntryExamplesRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<EntryExamples> {
        const response = await this.listEntryExamplesRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for listKanjiWords without sending the request
     */
    async listKanjiWordsRequestOpts(requestParameters: ListKanjiWordsRequest): Promise<runtime.RequestOpts> {
        if (requestParameters['literal'] == null) {
            throw new runtime.RequiredError(
                'literal',
                'Required parameter "literal" was null or undefined when calling listKanjiWords().'
            );
        }

        const queryParameters: any = {};

        if (requestParameters['deckId'] != null) {
            queryParameters['deck_id'] = requestParameters['deckId'];
        }

        if (requestParameters['mine'] != null) {
            queryParameters['mine'] = requestParameters['mine'];
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

        let urlPath = `/kanji/{literal}/words`;
        urlPath = urlPath.replace('{literal}', encodeURIComponent(String(requestParameters['literal'])));

        return {
            path: urlPath,
            method: 'GET',
            headers: headerParameters,
            query: queryParameters,
        };
    }

    /**
     * Rows: the live (`retired_release_id IS NULL`) `ref.dict_entry` rows `e` with a `ref.entry_kanji` row `(e.seq, literal)`. Retired entries are never returned.  **Literal.** More than one code point (or an empty string) is `422`. Unlike `getKanji`, a character absent from `ref.kanji` is **not** `404`: `ref.entry_kanji.literal` need not exist in `ref.kanji`, so such a character, or any code point no entry contains (kana, Latin...), is simply an empty page (`items: []`, `next_cursor: null`).  **Filters** (combine with AND): - `deck_id`: only entries `e` with a live item of that deck: an `app.deck_item` row with   `deck_id` = the parameter, `entry_seq = e.seq` and `deleted_at IS NULL`. The deck must   be a live deck of the caller (`app.deck.deleted_at IS NULL`), else `404` - a deleted   deck is `404` even though its rows remain. A live deck with no matching item (including   every `smart` deck, which has no items) gives an empty page. - `mine=true`: only entries that are a live item (as above) of at least one live deck of   the caller, **or** that have a live vocab note (`app.note` with `entry_seq = e.seq`,   `deleted_at IS NULL`, base type `vocab` - the same note the `note_id` overlay reports).   Word status alone (e.g. `known` set by hand) does not count. With `deck_id` also given,   `mine=true` adds nothing (every result is already in one of your decks). - Note-keyed deck items (`deck_item.note_id`, `entry_seq IS NULL`) never match: only   `entry_seq` items count, here and in `decks`.  **Order** (total; `seq` is unique): - `deck_id` given: that deck\'s `deck_item.occurrence_count` descending (in-deck frequency;   all `0` for non-media decks, so this degrades to the global order), then   `freq_rank` ascending with NULLs last, then `seq` ascending. - Otherwise: `freq_rank` ascending with NULLs last, then `seq` ascending.  **Paging.** Keyset pagination: the cursor encodes the sort key of the last returned row and the `literal`, `deck_id` and `mine` it was issued for; reusing it with a different `literal`, `deck_id` or `mine` is `422`. Pages are not a snapshot: if `occurrence_count` or `freq_rank` changes between pages (media reparse, dictionary refresh), a row may be skipped or repeated; clients that page through MUST de-duplicate by `seq`. `next_cursor` is null on the last page (it MAY be non-null on a page that happens to end exactly at the last row; the following page is then empty).  **Item.** Every `DictEntrySummary` field with the same meaning as in `searchDictionary` (caller\'s `status` and `note_id` overlay), plus `decks`: the caller\'s live decks (`app.deck.deleted_at IS NULL`) that hold the entry as a live item (`deck_item.deleted_at IS NULL`), ordered by `name` in code-point order (`COLLATE \"C\"`), then `id`. Empty array when none. Independent of `deck_id`/`mine`: with `deck_id`, that deck is always among them. `decks[].occurrence_count` is that deck\'s `deck_item.occurrence_count`. 
     * Dictionary entries whose kanji forms contain this kanji, most frequent first, with your deck membership (C-17).
     */
    async listKanjiWordsRaw(requestParameters: ListKanjiWordsRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<KanjiWordPage>> {
        const requestOptions = await this.listKanjiWordsRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => KanjiWordPageFromJSON(jsonValue));
    }

    /**
     * Rows: the live (`retired_release_id IS NULL`) `ref.dict_entry` rows `e` with a `ref.entry_kanji` row `(e.seq, literal)`. Retired entries are never returned.  **Literal.** More than one code point (or an empty string) is `422`. Unlike `getKanji`, a character absent from `ref.kanji` is **not** `404`: `ref.entry_kanji.literal` need not exist in `ref.kanji`, so such a character, or any code point no entry contains (kana, Latin...), is simply an empty page (`items: []`, `next_cursor: null`).  **Filters** (combine with AND): - `deck_id`: only entries `e` with a live item of that deck: an `app.deck_item` row with   `deck_id` = the parameter, `entry_seq = e.seq` and `deleted_at IS NULL`. The deck must   be a live deck of the caller (`app.deck.deleted_at IS NULL`), else `404` - a deleted   deck is `404` even though its rows remain. A live deck with no matching item (including   every `smart` deck, which has no items) gives an empty page. - `mine=true`: only entries that are a live item (as above) of at least one live deck of   the caller, **or** that have a live vocab note (`app.note` with `entry_seq = e.seq`,   `deleted_at IS NULL`, base type `vocab` - the same note the `note_id` overlay reports).   Word status alone (e.g. `known` set by hand) does not count. With `deck_id` also given,   `mine=true` adds nothing (every result is already in one of your decks). - Note-keyed deck items (`deck_item.note_id`, `entry_seq IS NULL`) never match: only   `entry_seq` items count, here and in `decks`.  **Order** (total; `seq` is unique): - `deck_id` given: that deck\'s `deck_item.occurrence_count` descending (in-deck frequency;   all `0` for non-media decks, so this degrades to the global order), then   `freq_rank` ascending with NULLs last, then `seq` ascending. - Otherwise: `freq_rank` ascending with NULLs last, then `seq` ascending.  **Paging.** Keyset pagination: the cursor encodes the sort key of the last returned row and the `literal`, `deck_id` and `mine` it was issued for; reusing it with a different `literal`, `deck_id` or `mine` is `422`. Pages are not a snapshot: if `occurrence_count` or `freq_rank` changes between pages (media reparse, dictionary refresh), a row may be skipped or repeated; clients that page through MUST de-duplicate by `seq`. `next_cursor` is null on the last page (it MAY be non-null on a page that happens to end exactly at the last row; the following page is then empty).  **Item.** Every `DictEntrySummary` field with the same meaning as in `searchDictionary` (caller\'s `status` and `note_id` overlay), plus `decks`: the caller\'s live decks (`app.deck.deleted_at IS NULL`) that hold the entry as a live item (`deck_item.deleted_at IS NULL`), ordered by `name` in code-point order (`COLLATE \"C\"`), then `id`. Empty array when none. Independent of `deck_id`/`mine`: with `deck_id`, that deck is always among them. `decks[].occurrence_count` is that deck\'s `deck_item.occurrence_count`. 
     * Dictionary entries whose kanji forms contain this kanji, most frequent first, with your deck membership (C-17).
     */
    async listKanjiWords(requestParameters: ListKanjiWordsRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<KanjiWordPage> {
        const response = await this.listKanjiWordsRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for refreshDictionary without sending the request
     */
    async refreshDictionaryRequestOpts(requestParameters: RefreshDictionaryRequest): Promise<runtime.RequestOpts> {
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

        let urlPath = `/dict/refresh`;

        return {
            path: urlPath,
            method: 'POST',
            headers: headerParameters,
            query: queryParameters,
            body: JobPayloadDictRefreshToJSON(requestParameters['jobPayloadDictRefresh']),
        };
    }

    /**
     * Enqueues a job of kind `dict-refresh` (payload `JobPayloadDictRefresh`, dedupe key `dict-refresh`). An already queued or running refresh is returned with `200`. An absent body equals `{}`. 
     * Queue a dictionary refresh (download sources, build and load a new release).
     */
    async refreshDictionaryRaw(requestParameters: RefreshDictionaryRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<Job>> {
        const requestOptions = await this.refreshDictionaryRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => JobFromJSON(jsonValue));
    }

    /**
     * Enqueues a job of kind `dict-refresh` (payload `JobPayloadDictRefresh`, dedupe key `dict-refresh`). An already queued or running refresh is returned with `200`. An absent body equals `{}`. 
     * Queue a dictionary refresh (download sources, build and load a new release).
     */
    async refreshDictionary(requestParameters: RefreshDictionaryRequest = {}, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<Job> {
        const response = await this.refreshDictionaryRaw(requestParameters, initOverrides);
        return await response.value();
    }

    /**
     * Creates request options for searchDictionary without sending the request
     */
    async searchDictionaryRequestOpts(requestParameters: SearchDictionaryRequest): Promise<runtime.RequestOpts> {
        if (requestParameters['q'] == null) {
            throw new runtime.RequiredError(
                'q',
                'Required parameter "q" was null or undefined when calling searchDictionary().'
            );
        }

        const queryParameters: any = {};

        if (requestParameters['q'] != null) {
            queryParameters['q'] = requestParameters['q'];
        }

        if (requestParameters['commonOnly'] != null) {
            queryParameters['common_only'] = requestParameters['commonOnly'];
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

        let urlPath = `/dict/search`;

        return {
            path: urlPath,
            method: 'GET',
            headers: headerParameters,
            query: queryParameters,
        };
    }

    /**
     * `q` is trimmed and NFKC-normalised. Interpretation, in order: 1. Contains kanji or kana: katakana is folded to hiragana and matched against    `dict_entry_form.norm` - 1 or 2 characters: exact or prefix; 3 or more: substring. 2. Otherwise (Latin letters): the server\'s romaji-to-kana conversion (wanakana-equivalent:    Hepburn and common IME spellings, `nn`/`n\'` for ん, doubled consonant for っ) is tried    and, if the whole string converts, matched as in 1; the string is also matched    case-insensitively as a substring of English glosses. Both result sets are merged. No deinflection: the input is matched as given (`たべた` does not find `食べる` unless a form of that entry contains `たべた`). Deinflection is the parser\'s job (`POST /parse`), not this search\'s (C-2). Retired entries are never returned. Order: exact form match first, then prefix, then the rest; inside each group `freq_rank` ascending with NULLs last, then `seq`. An empty or whitespace-only `q` is `422`. No match is an empty page. Each item carries the caller\'s `status` and `note_id` overlay. 
     * Search JMdict entries by kanji, kana, romaji or English.
     */
    async searchDictionaryRaw(requestParameters: SearchDictionaryRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<runtime.ApiResponse<DictEntrySummaryPage>> {
        const requestOptions = await this.searchDictionaryRequestOpts(requestParameters);
        const response = await this.request(requestOptions, initOverrides);

        return new runtime.JSONApiResponse(response, (jsonValue) => DictEntrySummaryPageFromJSON(jsonValue));
    }

    /**
     * `q` is trimmed and NFKC-normalised. Interpretation, in order: 1. Contains kanji or kana: katakana is folded to hiragana and matched against    `dict_entry_form.norm` - 1 or 2 characters: exact or prefix; 3 or more: substring. 2. Otherwise (Latin letters): the server\'s romaji-to-kana conversion (wanakana-equivalent:    Hepburn and common IME spellings, `nn`/`n\'` for ん, doubled consonant for っ) is tried    and, if the whole string converts, matched as in 1; the string is also matched    case-insensitively as a substring of English glosses. Both result sets are merged. No deinflection: the input is matched as given (`たべた` does not find `食べる` unless a form of that entry contains `たべた`). Deinflection is the parser\'s job (`POST /parse`), not this search\'s (C-2). Retired entries are never returned. Order: exact form match first, then prefix, then the rest; inside each group `freq_rank` ascending with NULLs last, then `seq`. An empty or whitespace-only `q` is `422`. No match is an empty page. Each item carries the caller\'s `status` and `note_id` overlay. 
     * Search JMdict entries by kanji, kana, romaji or English.
     */
    async searchDictionary(requestParameters: SearchDictionaryRequest, initOverrides?: RequestInit | runtime.InitOverrideFunction): Promise<DictEntrySummaryPage> {
        const response = await this.searchDictionaryRaw(requestParameters, initOverrides);
        return await response.value();
    }

}
