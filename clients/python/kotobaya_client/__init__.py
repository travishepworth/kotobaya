# coding: utf-8

# flake8: noqa

"""
    Kotobaya API

    Versioned REST/JSON interface at `/api/v1`. The web app uses nothing else; the Android app uses `sync`, `blobs`, `dict/release`, `devices/pair`, `devices/self` and whatever conveniences it likes.  Implementers: this file is the contract. If something you need is missing or wrong, do not work around it; return `CONTRACT_CHANGE_REQUEST`. Additive changes stay in v1; breaking changes go to `/api/v2`.  ## 1. Conventions  - **JSON.** Request and response bodies are `application/json; charset=utf-8` unless stated.   Property names are `snake_case` and equal the column names of `contracts/db/V1__init.sql`   wherever a property mirrors a column; the column `COMMENT` is then the definition. - **Unknown properties.** Servers reject unknown request properties and unknown query   parameters with `422 validation_failed`. Clients MUST ignore unknown response properties and   MUST treat unknown enum values in responses as \"other\" (v1 may add both). - **Absent vs null.** In `PATCH` bodies an absent property means \"leave unchanged\"; `null` means   \"set to NULL\" where the property is nullable and is a validation error otherwise.   Responses always include every documented property; nullable ones are `null`, never absent,   unless the property is documented as \"omitted when ...\". - **Ids.** Row ids are UUIDv7 in canonical lowercase `8-4-4-4-12` text. Uppercase ids in   requests are rejected with `422`. \"Entry\" always means a JMdict sequence number (`seq`,   JSON integer). - **Time.** Instants are RFC 3339 UTC strings with millisecond precision and a `Z` suffix,   e.g. `2026-11-12T09:30:00.000Z`. Servers accept any RFC 3339 offset on input and normalise   to UTC. A \"study day\" is a local date `YYYY-MM-DD` computed from the settings   `scheduler.timezone` and `scheduler.day_rollover_hour`: study day D covers local time   `D rollover:00` (inclusive) to `D+1 rollover:00` (exclusive). - **Text offsets** into sentence text are UTF-16 code units, half-open `[start, end)`. - **Blobs** (audio, images, uploads, snapshots) are addressed by the lowercase hex SHA-256 of   their bytes and transferred through `/blobs/{hash}`, never inline in JSON.  ## 2. Authentication and scopes  Three credential kinds, all rows of `app.api_token` (stored hashed):  | Kind | How obtained | Sent as | Scopes | |---|---|---|---| | `session` | `POST /auth/login` | cookie `kotobaya_session` (HTTP-only) | all five | | `device` | `POST /devices/pair` | `Authorization: Bearer kbd_...` | `read`, `write`, `review` | | `personal` | `POST /tokens` | `Authorization: Bearer kbp_...` | chosen at creation |  Token strings are `<3-letter kind prefix>_<40 characters of [A-Za-z0-9]>` (`kbs_`, `kbd_`, `kbp_`), generated from a CSPRNG. `token_hash` = lowercase hex SHA-256 of the whole string; `token_prefix` = its first 8 characters. A token is valid iff `revoked_at IS NULL` and (`expires_at IS NULL` or `expires_at > now()`) and, when it has a `device_id`, that device is not revoked. Validity is checked on every request (no caching longer than 5 seconds), so revocation takes effect at once.  Scopes are independent (no scope implies another):  | Scope | Grants | |---|---| | `read` | Every `GET` of learning, media and reference data; `POST /parse`; `GET /events`; `GET /sync/pull`; exports. | | `write` | Create, change, delete notes, cards, decks, grammar, note types, presets, settings, word status, parse overrides, media metadata; upload blobs; `POST /sync/push` (non-review ops). | | `review` | `POST /review/answer`, `POST /review/undo`, `POST /drill/session`, `POST /cards/bulk/schedule`; `review.*` ops inside `POST /sync/push` (per op; without it the op is rejected with `insufficient_scope`). | | `import` | Start and cancel import, parse, dictionary and optimizer jobs; upload blobs. | | `admin` | Devices, pairing, tokens (a device's own row: `/devices/self` with `read`/`write`). |  Each operation lists the scope it needs in its `security` requirement: the array after the scheme name. When two alternatives are listed for one scheme the caller needs either. A request with no or an invalid credential gets `401 unauthenticated`; a valid credential lacking the scope gets `403 insufficient_scope`. Operations with `security: []` are public.  There are exactly two places where who may call is not just \"scheme + scope\", and both are declared in the operation, not only in prose: - `GET/PATCH /devices/self` (`read` / `write`, bearer only): the token must be bound to a   device, else `403 device_required`. A device never needs `admin` to manage its own row;   `/devices/{device_id}` is admin only. - `POST /ankiconnect` (`x-auth: body-key`): the token may instead be sent in the JSON body   property `key`; the empty `{}` alternative in its `security` only signals that no header is   needed. The scope for `key` is `x-body-key-scope`.  **CSRF.** A request authenticated by the session cookie whose method is not `GET` or `HEAD` MUST carry the header `X-Kotobaya-Csrf: 1`; otherwise `403 csrf_required`. The cookie is set with `HttpOnly; Secure; SameSite=Strict; Path=/api`. The server sends no CORS headers (the web app is served from the same origin). If both a cookie and an `Authorization` header are present, the header wins and the cookie is ignored.  **Audit.** Every request is logged with `token.id` and `token.name` (structured server log; there is no audit table or endpoint in v1). Ops record the token in `change.token_id`.  ## 3. Writes are sync ops  Learning data is only ever changed by applying sync ops (`contracts/sync`). Every REST operation that changes learning data is a convenience wrapper: the server builds the op(s) named in the operation's `x-emits-ops`, applies them through the same op-apply path as `POST /sync/push` in one transaction, and they then appear in `GET /sync/pull` for every device. Op names are those of `contracts/db/SCHEMA.md` section 4; `contracts/sync` is authoritative for names and payloads.  For an op the server builds on behalf of a REST call:  - `op_id` is a fresh UUIDv7; origin `device_id` is the **server device**   `00000000-0000-7000-8000-000000000001` (also for session and device tokens: only one party   ever generates HLCs for a given device id); `hlc` comes from the server's HLC clock;   `change.token_id` is the calling token. - A property absent from a `PATCH` body produces no register write. A property present with   the value the row already has still produces a write (it bumps the register's clock). - If a call changes nothing (documented per operation, e.g. deleting an already deleted   note), no op is emitted and the response is still successful. - **Never an invalid op (C-6).** If a request would make the server build an op that fails   its schema or an op-level rule in `contracts/sync` - a tag containing ECMA-262 whitespace   (e.g. U+3000 or U+00A0), U+0000 in any string (field values and open JSON objects   included), an integer above its op maximum (`2147483647` for int4 columns) or an instant   after `9999-12-31T23:59:59.999Z` - the call is `422 validation_failed` with a field error   at that request property, code `invalid_value` (or `out_of_range` / `too_long` where the   operation's own request bound fails first). Nothing is emitted; never a `500`.   SYNC.md 2.1 \"Storable values\" and \"Input that would mint an invalid op\". - Responses to mutating calls carry the header `Kotobaya-Seq`: the `change.seq` of the last op   the call emitted. A client holding a local replica knows it has seen the effect once its   pull cursor is at or past that value. The header is absent when no op was emitted.  **Idempotent creation.** Create bodies accept an optional client-generated `id` (UUIDv7). Re-sending a create with an `id` that already exists returns the existing resource with status `200` instead of `201` and emits nothing, whatever the rest of the body says. Clients that may retry MUST send `id`. When `id` is omitted the server generates one. `POST /review/answer` requires `id` (a duplicate review would corrupt scheduling); `POST /media/import` takes the same optional idempotency id as `media_id`.  The existence check and the emit are **one transaction that takes advisory lock `7033001` before the check** and holds it until commit (the lock `POST /sync/push` takes per op, SYNC.md 5.2): check whether `id` exists; if not, mint, apply and log the op(s) - for `createNote` the `note.created` and every `card.created` - then commit. Without the lock two concurrent retries of the same create both pass the check; the second `note.created` is a no-op on the row (SYNC.md 7.2) but its `card.created` ops carry fresh card ids, so the note would get duplicate cards. The same holds for every other id-idempotent call (`POST /review/answer`, `POST /review/undo`, `POST /media/import`), and a pushed op that creates the same id races safely because it takes the same lock.  **Tombstones and merges.** Deleted learning rows are tombstones (`deleted_at` set). `GET` by id of a deleted row returns `410 gone` unless `include_deleted=true`; lists omit deleted rows unless `include_deleted=true`. A note or card that lost a merge (`merged_into_id` set) is answered with its survivor: `GET`, `PATCH` and review calls addressed to the loser's id act on the survivor and the response body is the survivor (its `id` differs from the requested id; the response has `Content-Location` with the survivor's URL).  ## 4. Pagination  List operations that take `limit` and `cursor` return `{\"items\": [...], \"next_cursor\": ...}`. `next_cursor` is an opaque string, or `null` on the last page. Pass it back unchanged as `cursor` together with the same other parameters; changing any other parameter while passing a cursor is `422 validation_failed` (`pointer` `/cursor`). `limit` defaults to 50, maximum 500. Order is stable and documented per operation; rows created while paging may or may not appear.  **Cursor semantics (keyset, no snapshot).** A cursor encodes the sort-key values and the `id` of the last row returned; the next page is the rows strictly after that tuple in the current order, evaluated against the data at the time of that request. The order is not snapshotted. Consequently, while a client pages through a list sorted by a mutable key (`due_at`, `updated_at`, `lapses`, ...): - a row whose key changes so that it moves from after the cursor to before it is **skipped**; - a row whose key changes so that it moves from before the cursor to after it is **returned   again** (with its new values); - a row deleted while paging disappears; one created may or may not appear. Rows that do not change are returned exactly once. Clients MUST de-duplicate by `id` (last occurrence wins). A client that needs an exact set (bulk actions, exports) uses an `id`-sorted list where offered, `POST /cards/bulk` with `query`, or `GET /export`. Lists sorted by an immutable key (`id`, `created_at`) have no skips or repeats. An empty result is `{\"items\": [], \"next_cursor\": null}` with status `200`.  ## 5. Errors  Every non-2xx response has the body `application/problem+json` (RFC 9457), schema `Problem`. `code` is the stable machine-readable identifier; `type` is `urn:kotobaya:problem:<code>`.  | Status | `code` | When | |---|---|---| | 400 | `invalid_request` | Body is not valid JSON, wrong content type framing, malformed header; `POST /sync/push` body not valid against `PushRequestLenient`. | | 401 | `unauthenticated` | No credential, or it is unknown, expired or revoked. | | 401 | `invalid_credentials` | Wrong password or TOTP code (`POST /auth/login`). | | 401 | `totp_required` | TOTP is enabled and `totp_code` was not sent. | | 401 | `invalid_pairing_code` | Pairing code unknown, expired or already used. | | 403 | `insufficient_scope` | Credential lacks the scope; `required_scope` names it. | | 403 | `csrf_required` | Cookie-authenticated write without `X-Kotobaya-Csrf`. | | 403 | `device_required` | `POST /sync/push` or `/devices/self` with a credential that has no `device_id`. | | 404 | `not_found` | No such resource (also: id of another resource kind). | | 409 | `already_exists` | Natural-key collision; `existing_id` names the holder. | | 409 | `builtin_immutable` | Deleting a built-in note type or the Default preset, or removing a built-in field. | | 409 | `in_use` | Deleting a note type that live notes use. | | 409 | `deck_read_only` | Changing items or deleting a `media`/`list` deck through deck endpoints. | | 409 | `not_applicable` | The resource kind does not support the operation (e.g. items of a smart deck). | | 409 | `card_not_reviewable` | Card is suspended. | | 409 | `job_not_cancellable` | Job already finished. | | 410 | `gone` | Tombstoned row requested without `include_deleted=true`. | | 413 | `payload_too_large` | Body over the limit in `GET /meta`. On `POST /sync/push` only for the whole batch (the client halves it); one op over `max_op_bytes` is a per-op `op_too_large` result instead. | | 415 | `unsupported_media_type` | Wrong `Content-Type`. | | 422 | `validation_failed` | Well-formed but invalid; `errors[]` lists each problem. | | 422 | `invalid_query` | Card search string does not parse; `errors[0].position` is the UTF-16 offset. | | 422 | `bulk_too_large` | Bulk selector matched more than 5000 cards. | | 422 | `hash_mismatch` | Uploaded bytes do not hash to the `{hash}` in the path. | | 429 | `rate_limited` | Too many login or pairing attempts; see `Retry-After`. | | 500 | `internal` | Bug. Safe to retry idempotent calls. | | 503 | `unavailable` | Database or object store unreachable. Retry with backoff. |  Check order: 401, then 403, then 404/410, then 415/400, then 422, then 409.  ## 6. Events  `GET /events` is a server-sent events stream; see that operation. Event payload schemas are `Event*` in components. 

    The version of the OpenAPI document: 1.0.0
    Generated by OpenAPI Generator (https://openapi-generator.tech)

    Do not edit the class manually.
"""  # noqa: E501


__version__ = "1.0.0"

# Define package exports
__all__ = [
    "AuthApi",
    "BlobsApi",
    "CardsApi",
    "CompatApi",
    "DecksApi",
    "DevicesApi",
    "DictionaryApi",
    "EventsApi",
    "ExportApi",
    "GrammarApi",
    "JobsApi",
    "MediaApi",
    "MetaApi",
    "NoteTypesApi",
    "NotesApi",
    "ParsingApi",
    "PresetsApi",
    "ReviewApi",
    "SettingsApi",
    "StatsApi",
    "SyncApi",
    "TokensApi",
    "WordsApi",
    "ApiResponse",
    "ApiClient",
    "Configuration",
    "OpenApiException",
    "ApiTypeError",
    "ApiValueError",
    "ApiKeyError",
    "ApiAttributeError",
    "ApiException",
    "AnkiConnectRequest",
    "AnkiConnectResponse",
    "Blob",
    "BlobQuery",
    "BlobQueryResult",
    "Card",
    "CardBulkAction",
    "CardBulkRequest",
    "CardBulkResult",
    "CardBulkScheduleRequest",
    "CardBulkScheduleRequestAction",
    "CardBuriedSchema",
    "CardCreate",
    "CardCreatedSchema",
    "CardDeletedSchema",
    "CardFlagSetSchema",
    "CardMergedSchema",
    "CardPage",
    "CardPatch",
    "CardPresetSetSchema",
    "CardState",
    "CardSuspendedSchema",
    "CardTemplate",
    "CardUnburiedSchema",
    "CardUnsuspendedSchema",
    "Confusable",
    "ConfusableConfirmedSchema",
    "ConfusableList",
    "ConfusablePage",
    "ConfusableReason",
    "ConfusableRejectedSchema",
    "ConfusableSet",
    "ConfusableStatus",
    "ConfusableSuggestedSchema",
    "Coverage",
    "CoverageCounts",
    "CoverageCurveInner",
    "CoverageDifficulty",
    "CoverageSegmentsInner",
    "CoverageTokens",
    "Deck",
    "DeckContentPublishedSchema",
    "DeckCreate",
    "DeckCreatedSchema",
    "DeckDeletedSchema",
    "DeckItem",
    "DeckItemList",
    "DeckItemPage",
    "DeckItemPut",
    "DeckItemPutSchema",
    "DeckItemRemovedSchema",
    "DeckItemsAdd",
    "DeckItemsAddItemsInner",
    "DeckList",
    "DeckListCatalogue",
    "DeckListCatalogueEntry",
    "DeckPatch",
    "DeckSettings",
    "DeckType",
    "DeckUpdatedSchema",
    "DeckUpdatedSchemaSet",
    "DetectionRule",
    "Device",
    "DeviceList",
    "DevicePatch",
    "DictEntry",
    "DictEntryFreqsInner",
    "DictEntryKanjiFormsInner",
    "DictEntryPitchInner",
    "DictEntryReadingsInner",
    "DictEntrySensesInner",
    "DictEntrySensesInnerGlossInner",
    "DictEntrySummary",
    "DictEntrySummaryPage",
    "DictRelease",
    "DictReleaseArtifactsInner",
    "DictReleaseFreqListsInner",
    "DrillSession",
    "DrillSessionRequest",
    "EntryExamples",
    "Event",
    "EventDevice",
    "EventHello",
    "EventNoteCreated",
    "EventSyncChanged",
    "EventType",
    "ExportDump",
    "FieldError",
    "FsrsParamsCreatedSchema",
    "FsrsParamsDeletedSchema",
    "FsrsParamsPublishedSchema",
    "FsrsParamsPublishedSchemaOptimizerStats",
    "FsrsParamsUpdatedSchema",
    "FsrsParamsUpdatedSchemaSet",
    "GrammarCreatedSchema",
    "GrammarCreatedSchemaDetectionRulesInner",
    "GrammarCreatedSchemaLinksInner",
    "GrammarDeletedSchema",
    "GrammarLink",
    "GrammarPoint",
    "GrammarPointCreate",
    "GrammarPointPage",
    "GrammarPointPatch",
    "GrammarRuleTestRequest",
    "GrammarRuleTestResult",
    "GrammarRuleTestResultMatchesInner",
    "GrammarRuleTestResultMatchesInnerSpansInner",
    "GrammarSpan",
    "GrammarSpanPut",
    "GrammarSpanPutSchema",
    "GrammarSpanRef",
    "GrammarSpanRemovedSchema",
    "GrammarUpdatedSchema",
    "GrammarUpdatedSchemaSet",
    "Health",
    "HotKanji",
    "HotKanjiList",
    "HotWord",
    "HotWordList",
    "Job",
    "JobKind",
    "JobPage",
    "JobPayloadDeckBuild",
    "JobPayloadDictRefresh",
    "JobPayloadFsrsOptimize",
    "JobPayloadMediaParse",
    "JobResultDeckBuild",
    "JobResultDictRefresh",
    "JobResultFsrsOptimize",
    "JobResultJpdbHistory",
    "JobResultJpdbHistoryKanjiUnmappedInner",
    "JobResultJpdbHistoryUnmappedInner",
    "JobResultMedia",
    "JobResultMediaUnmappedInner",
    "JobResultTts",
    "JobStatus",
    "JpdbDeckImportRequest",
    "JpdbHistoryImportRequest",
    "Kanji",
    "LoginRequest",
    "Media",
    "MediaContentPublishedSchema",
    "MediaCreatedSchema",
    "MediaDeckOptions",
    "MediaDeletedSchema",
    "MediaImportFile",
    "MediaImportRequest",
    "MediaImportRequestOptions",
    "MediaKind",
    "MediaPage",
    "MediaPatch",
    "MediaSegment",
    "MediaUpdatedSchema",
    "MediaUpdatedSchemaSet",
    "Meta",
    "MetaDictRelease",
    "MetaLimits",
    "Note",
    "NoteBaseType",
    "NoteCreate",
    "NoteCreatedSchema",
    "NoteDeletedSchema",
    "NoteField",
    "NoteMergedSchema",
    "NotePage",
    "NotePatch",
    "NoteSummary",
    "NoteType",
    "NoteTypeCreate",
    "NoteTypeCreatedSchema",
    "NoteTypeCreatedSchemaFieldsInner",
    "NoteTypeDeletedSchema",
    "NoteTypeList",
    "NoteTypePatch",
    "NoteTypeUpdatedSchema",
    "NoteTypeUpdatedSchemaSet",
    "NoteUpdatedSchema",
    "NoteUpdatedSchemaSet",
    "OpSchema",
    "OverrideToken",
    "PairRequest",
    "PairResponse",
    "Pairing",
    "ParseOverride",
    "ParseOverrideDeletedSchema",
    "ParseOverridePage",
    "ParseOverridePut",
    "ParseOverridePutSchema",
    "ParseOverridePutSchemaTokensInner",
    "ParseRequest",
    "ParseResult",
    "ParsedSentence",
    "Preset",
    "PresetCreate",
    "PresetList",
    "PresetPatch",
    "Principal",
    "Problem",
    "PullResponse",
    "PushRequest",
    "PushRequestOpsInner",
    "PushResponse",
    "PushResult",
    "PushResultError",
    "QueueItem",
    "RetentionGroup",
    "ReviewAnswerRequest",
    "ReviewButton",
    "ReviewLog",
    "ReviewLoggedSchema",
    "ReviewPage",
    "ReviewQueue",
    "ReviewQueueCounts",
    "ReviewResult",
    "ReviewType",
    "ReviewUndoRequest",
    "ReviewUndoneSchema",
    "SchedulePreview",
    "SchedulePreviewOption",
    "Scope",
    "Sentence",
    "SentencePage",
    "SentenceToken",
    "Setting",
    "SettingList",
    "SettingsPatch",
    "SettingsSetSchema",
    "SpanOrigin",
    "StatsCalibration",
    "StatsCalibrationBinsInner",
    "StatsForecast",
    "StatsForecastDaysInner",
    "StatsForecastHoursInner",
    "StatsHeatmap",
    "StatsHeatmapDaysInner",
    "StatsKnownWords",
    "StatsKnownWordsCurrent",
    "StatsKnownWordsSeriesInner",
    "StatsRetention",
    "StatsSummary",
    "StatsSummaryDoneToday",
    "StatsSummaryDue",
    "StatsSummaryTotals",
    "TatoebaSentence",
    "Template",
    "Token",
    "TokenCreate",
    "TokenCreated",
    "TokenKind",
    "TokenList",
    "TtsRequest",
    "WordStatus",
    "WordStatusClearedSchema",
    "WordStatusSet",
    "WordStatusSetSchema",
    "WordStatusValue",
]

# import apis into sdk package
from kotobaya_client.api.auth_api import AuthApi as AuthApi
from kotobaya_client.api.blobs_api import BlobsApi as BlobsApi
from kotobaya_client.api.cards_api import CardsApi as CardsApi
from kotobaya_client.api.compat_api import CompatApi as CompatApi
from kotobaya_client.api.decks_api import DecksApi as DecksApi
from kotobaya_client.api.devices_api import DevicesApi as DevicesApi
from kotobaya_client.api.dictionary_api import DictionaryApi as DictionaryApi
from kotobaya_client.api.events_api import EventsApi as EventsApi
from kotobaya_client.api.export_api import ExportApi as ExportApi
from kotobaya_client.api.grammar_api import GrammarApi as GrammarApi
from kotobaya_client.api.jobs_api import JobsApi as JobsApi
from kotobaya_client.api.media_api import MediaApi as MediaApi
from kotobaya_client.api.meta_api import MetaApi as MetaApi
from kotobaya_client.api.note_types_api import NoteTypesApi as NoteTypesApi
from kotobaya_client.api.notes_api import NotesApi as NotesApi
from kotobaya_client.api.parsing_api import ParsingApi as ParsingApi
from kotobaya_client.api.presets_api import PresetsApi as PresetsApi
from kotobaya_client.api.review_api import ReviewApi as ReviewApi
from kotobaya_client.api.settings_api import SettingsApi as SettingsApi
from kotobaya_client.api.stats_api import StatsApi as StatsApi
from kotobaya_client.api.sync_api import SyncApi as SyncApi
from kotobaya_client.api.tokens_api import TokensApi as TokensApi
from kotobaya_client.api.words_api import WordsApi as WordsApi

# import ApiClient
from kotobaya_client.api_response import ApiResponse as ApiResponse
from kotobaya_client.api_client import ApiClient as ApiClient
from kotobaya_client.configuration import Configuration as Configuration
from kotobaya_client.exceptions import OpenApiException as OpenApiException
from kotobaya_client.exceptions import ApiTypeError as ApiTypeError
from kotobaya_client.exceptions import ApiValueError as ApiValueError
from kotobaya_client.exceptions import ApiKeyError as ApiKeyError
from kotobaya_client.exceptions import ApiAttributeError as ApiAttributeError
from kotobaya_client.exceptions import ApiException as ApiException

# import models into sdk package
from kotobaya_client.models.anki_connect_request import AnkiConnectRequest as AnkiConnectRequest
from kotobaya_client.models.anki_connect_response import AnkiConnectResponse as AnkiConnectResponse
from kotobaya_client.models.blob import Blob as Blob
from kotobaya_client.models.blob_query import BlobQuery as BlobQuery
from kotobaya_client.models.blob_query_result import BlobQueryResult as BlobQueryResult
from kotobaya_client.models.card import Card as Card
from kotobaya_client.models.card_bulk_action import CardBulkAction as CardBulkAction
from kotobaya_client.models.card_bulk_request import CardBulkRequest as CardBulkRequest
from kotobaya_client.models.card_bulk_result import CardBulkResult as CardBulkResult
from kotobaya_client.models.card_bulk_schedule_request import CardBulkScheduleRequest as CardBulkScheduleRequest
from kotobaya_client.models.card_bulk_schedule_request_action import CardBulkScheduleRequestAction as CardBulkScheduleRequestAction
from kotobaya_client.models.card_buried_schema import CardBuriedSchema as CardBuriedSchema
from kotobaya_client.models.card_create import CardCreate as CardCreate
from kotobaya_client.models.card_created_schema import CardCreatedSchema as CardCreatedSchema
from kotobaya_client.models.card_deleted_schema import CardDeletedSchema as CardDeletedSchema
from kotobaya_client.models.card_flag_set_schema import CardFlagSetSchema as CardFlagSetSchema
from kotobaya_client.models.card_merged_schema import CardMergedSchema as CardMergedSchema
from kotobaya_client.models.card_page import CardPage as CardPage
from kotobaya_client.models.card_patch import CardPatch as CardPatch
from kotobaya_client.models.card_preset_set_schema import CardPresetSetSchema as CardPresetSetSchema
from kotobaya_client.models.card_state import CardState as CardState
from kotobaya_client.models.card_suspended_schema import CardSuspendedSchema as CardSuspendedSchema
from kotobaya_client.models.card_template import CardTemplate as CardTemplate
from kotobaya_client.models.card_unburied_schema import CardUnburiedSchema as CardUnburiedSchema
from kotobaya_client.models.card_unsuspended_schema import CardUnsuspendedSchema as CardUnsuspendedSchema
from kotobaya_client.models.confusable import Confusable as Confusable
from kotobaya_client.models.confusable_confirmed_schema import ConfusableConfirmedSchema as ConfusableConfirmedSchema
from kotobaya_client.models.confusable_list import ConfusableList as ConfusableList
from kotobaya_client.models.confusable_page import ConfusablePage as ConfusablePage
from kotobaya_client.models.confusable_reason import ConfusableReason as ConfusableReason
from kotobaya_client.models.confusable_rejected_schema import ConfusableRejectedSchema as ConfusableRejectedSchema
from kotobaya_client.models.confusable_set import ConfusableSet as ConfusableSet
from kotobaya_client.models.confusable_status import ConfusableStatus as ConfusableStatus
from kotobaya_client.models.confusable_suggested_schema import ConfusableSuggestedSchema as ConfusableSuggestedSchema
from kotobaya_client.models.coverage import Coverage as Coverage
from kotobaya_client.models.coverage_counts import CoverageCounts as CoverageCounts
from kotobaya_client.models.coverage_curve_inner import CoverageCurveInner as CoverageCurveInner
from kotobaya_client.models.coverage_difficulty import CoverageDifficulty as CoverageDifficulty
from kotobaya_client.models.coverage_segments_inner import CoverageSegmentsInner as CoverageSegmentsInner
from kotobaya_client.models.coverage_tokens import CoverageTokens as CoverageTokens
from kotobaya_client.models.deck import Deck as Deck
from kotobaya_client.models.deck_content_published_schema import DeckContentPublishedSchema as DeckContentPublishedSchema
from kotobaya_client.models.deck_create import DeckCreate as DeckCreate
from kotobaya_client.models.deck_created_schema import DeckCreatedSchema as DeckCreatedSchema
from kotobaya_client.models.deck_deleted_schema import DeckDeletedSchema as DeckDeletedSchema
from kotobaya_client.models.deck_item import DeckItem as DeckItem
from kotobaya_client.models.deck_item_list import DeckItemList as DeckItemList
from kotobaya_client.models.deck_item_page import DeckItemPage as DeckItemPage
from kotobaya_client.models.deck_item_put import DeckItemPut as DeckItemPut
from kotobaya_client.models.deck_item_put_schema import DeckItemPutSchema as DeckItemPutSchema
from kotobaya_client.models.deck_item_removed_schema import DeckItemRemovedSchema as DeckItemRemovedSchema
from kotobaya_client.models.deck_items_add import DeckItemsAdd as DeckItemsAdd
from kotobaya_client.models.deck_items_add_items_inner import DeckItemsAddItemsInner as DeckItemsAddItemsInner
from kotobaya_client.models.deck_list import DeckList as DeckList
from kotobaya_client.models.deck_list_catalogue import DeckListCatalogue as DeckListCatalogue
from kotobaya_client.models.deck_list_catalogue_entry import DeckListCatalogueEntry as DeckListCatalogueEntry
from kotobaya_client.models.deck_patch import DeckPatch as DeckPatch
from kotobaya_client.models.deck_settings import DeckSettings as DeckSettings
from kotobaya_client.models.deck_type import DeckType as DeckType
from kotobaya_client.models.deck_updated_schema import DeckUpdatedSchema as DeckUpdatedSchema
from kotobaya_client.models.deck_updated_schema_set import DeckUpdatedSchemaSet as DeckUpdatedSchemaSet
from kotobaya_client.models.detection_rule import DetectionRule as DetectionRule
from kotobaya_client.models.device import Device as Device
from kotobaya_client.models.device_list import DeviceList as DeviceList
from kotobaya_client.models.device_patch import DevicePatch as DevicePatch
from kotobaya_client.models.dict_entry import DictEntry as DictEntry
from kotobaya_client.models.dict_entry_freqs_inner import DictEntryFreqsInner as DictEntryFreqsInner
from kotobaya_client.models.dict_entry_kanji_forms_inner import DictEntryKanjiFormsInner as DictEntryKanjiFormsInner
from kotobaya_client.models.dict_entry_pitch_inner import DictEntryPitchInner as DictEntryPitchInner
from kotobaya_client.models.dict_entry_readings_inner import DictEntryReadingsInner as DictEntryReadingsInner
from kotobaya_client.models.dict_entry_senses_inner import DictEntrySensesInner as DictEntrySensesInner
from kotobaya_client.models.dict_entry_senses_inner_gloss_inner import DictEntrySensesInnerGlossInner as DictEntrySensesInnerGlossInner
from kotobaya_client.models.dict_entry_summary import DictEntrySummary as DictEntrySummary
from kotobaya_client.models.dict_entry_summary_page import DictEntrySummaryPage as DictEntrySummaryPage
from kotobaya_client.models.dict_release import DictRelease as DictRelease
from kotobaya_client.models.dict_release_artifacts_inner import DictReleaseArtifactsInner as DictReleaseArtifactsInner
from kotobaya_client.models.dict_release_freq_lists_inner import DictReleaseFreqListsInner as DictReleaseFreqListsInner
from kotobaya_client.models.drill_session import DrillSession as DrillSession
from kotobaya_client.models.drill_session_request import DrillSessionRequest as DrillSessionRequest
from kotobaya_client.models.entry_examples import EntryExamples as EntryExamples
from kotobaya_client.models.event import Event as Event
from kotobaya_client.models.event_device import EventDevice as EventDevice
from kotobaya_client.models.event_hello import EventHello as EventHello
from kotobaya_client.models.event_note_created import EventNoteCreated as EventNoteCreated
from kotobaya_client.models.event_sync_changed import EventSyncChanged as EventSyncChanged
from kotobaya_client.models.event_type import EventType as EventType
from kotobaya_client.models.export_dump import ExportDump as ExportDump
from kotobaya_client.models.field_error import FieldError as FieldError
from kotobaya_client.models.fsrs_params_created_schema import FsrsParamsCreatedSchema as FsrsParamsCreatedSchema
from kotobaya_client.models.fsrs_params_deleted_schema import FsrsParamsDeletedSchema as FsrsParamsDeletedSchema
from kotobaya_client.models.fsrs_params_published_schema import FsrsParamsPublishedSchema as FsrsParamsPublishedSchema
from kotobaya_client.models.fsrs_params_published_schema_optimizer_stats import FsrsParamsPublishedSchemaOptimizerStats as FsrsParamsPublishedSchemaOptimizerStats
from kotobaya_client.models.fsrs_params_updated_schema import FsrsParamsUpdatedSchema as FsrsParamsUpdatedSchema
from kotobaya_client.models.fsrs_params_updated_schema_set import FsrsParamsUpdatedSchemaSet as FsrsParamsUpdatedSchemaSet
from kotobaya_client.models.grammar_created_schema import GrammarCreatedSchema as GrammarCreatedSchema
from kotobaya_client.models.grammar_created_schema_detection_rules_inner import GrammarCreatedSchemaDetectionRulesInner as GrammarCreatedSchemaDetectionRulesInner
from kotobaya_client.models.grammar_created_schema_links_inner import GrammarCreatedSchemaLinksInner as GrammarCreatedSchemaLinksInner
from kotobaya_client.models.grammar_deleted_schema import GrammarDeletedSchema as GrammarDeletedSchema
from kotobaya_client.models.grammar_link import GrammarLink as GrammarLink
from kotobaya_client.models.grammar_point import GrammarPoint as GrammarPoint
from kotobaya_client.models.grammar_point_create import GrammarPointCreate as GrammarPointCreate
from kotobaya_client.models.grammar_point_page import GrammarPointPage as GrammarPointPage
from kotobaya_client.models.grammar_point_patch import GrammarPointPatch as GrammarPointPatch
from kotobaya_client.models.grammar_rule_test_request import GrammarRuleTestRequest as GrammarRuleTestRequest
from kotobaya_client.models.grammar_rule_test_result import GrammarRuleTestResult as GrammarRuleTestResult
from kotobaya_client.models.grammar_rule_test_result_matches_inner import GrammarRuleTestResultMatchesInner as GrammarRuleTestResultMatchesInner
from kotobaya_client.models.grammar_rule_test_result_matches_inner_spans_inner import GrammarRuleTestResultMatchesInnerSpansInner as GrammarRuleTestResultMatchesInnerSpansInner
from kotobaya_client.models.grammar_span import GrammarSpan as GrammarSpan
from kotobaya_client.models.grammar_span_put import GrammarSpanPut as GrammarSpanPut
from kotobaya_client.models.grammar_span_put_schema import GrammarSpanPutSchema as GrammarSpanPutSchema
from kotobaya_client.models.grammar_span_ref import GrammarSpanRef as GrammarSpanRef
from kotobaya_client.models.grammar_span_removed_schema import GrammarSpanRemovedSchema as GrammarSpanRemovedSchema
from kotobaya_client.models.grammar_updated_schema import GrammarUpdatedSchema as GrammarUpdatedSchema
from kotobaya_client.models.grammar_updated_schema_set import GrammarUpdatedSchemaSet as GrammarUpdatedSchemaSet
from kotobaya_client.models.health import Health as Health
from kotobaya_client.models.hot_kanji import HotKanji as HotKanji
from kotobaya_client.models.hot_kanji_list import HotKanjiList as HotKanjiList
from kotobaya_client.models.hot_word import HotWord as HotWord
from kotobaya_client.models.hot_word_list import HotWordList as HotWordList
from kotobaya_client.models.job import Job as Job
from kotobaya_client.models.job_kind import JobKind as JobKind
from kotobaya_client.models.job_page import JobPage as JobPage
from kotobaya_client.models.job_payload_deck_build import JobPayloadDeckBuild as JobPayloadDeckBuild
from kotobaya_client.models.job_payload_dict_refresh import JobPayloadDictRefresh as JobPayloadDictRefresh
from kotobaya_client.models.job_payload_fsrs_optimize import JobPayloadFsrsOptimize as JobPayloadFsrsOptimize
from kotobaya_client.models.job_payload_media_parse import JobPayloadMediaParse as JobPayloadMediaParse
from kotobaya_client.models.job_result_deck_build import JobResultDeckBuild as JobResultDeckBuild
from kotobaya_client.models.job_result_dict_refresh import JobResultDictRefresh as JobResultDictRefresh
from kotobaya_client.models.job_result_fsrs_optimize import JobResultFsrsOptimize as JobResultFsrsOptimize
from kotobaya_client.models.job_result_jpdb_history import JobResultJpdbHistory as JobResultJpdbHistory
from kotobaya_client.models.job_result_jpdb_history_kanji_unmapped_inner import JobResultJpdbHistoryKanjiUnmappedInner as JobResultJpdbHistoryKanjiUnmappedInner
from kotobaya_client.models.job_result_jpdb_history_unmapped_inner import JobResultJpdbHistoryUnmappedInner as JobResultJpdbHistoryUnmappedInner
from kotobaya_client.models.job_result_media import JobResultMedia as JobResultMedia
from kotobaya_client.models.job_result_media_unmapped_inner import JobResultMediaUnmappedInner as JobResultMediaUnmappedInner
from kotobaya_client.models.job_result_tts import JobResultTts as JobResultTts
from kotobaya_client.models.job_status import JobStatus as JobStatus
from kotobaya_client.models.jpdb_deck_import_request import JpdbDeckImportRequest as JpdbDeckImportRequest
from kotobaya_client.models.jpdb_history_import_request import JpdbHistoryImportRequest as JpdbHistoryImportRequest
from kotobaya_client.models.kanji import Kanji as Kanji
from kotobaya_client.models.login_request import LoginRequest as LoginRequest
from kotobaya_client.models.media import Media as Media
from kotobaya_client.models.media_content_published_schema import MediaContentPublishedSchema as MediaContentPublishedSchema
from kotobaya_client.models.media_created_schema import MediaCreatedSchema as MediaCreatedSchema
from kotobaya_client.models.media_deck_options import MediaDeckOptions as MediaDeckOptions
from kotobaya_client.models.media_deleted_schema import MediaDeletedSchema as MediaDeletedSchema
from kotobaya_client.models.media_import_file import MediaImportFile as MediaImportFile
from kotobaya_client.models.media_import_request import MediaImportRequest as MediaImportRequest
from kotobaya_client.models.media_import_request_options import MediaImportRequestOptions as MediaImportRequestOptions
from kotobaya_client.models.media_kind import MediaKind as MediaKind
from kotobaya_client.models.media_page import MediaPage as MediaPage
from kotobaya_client.models.media_patch import MediaPatch as MediaPatch
from kotobaya_client.models.media_segment import MediaSegment as MediaSegment
from kotobaya_client.models.media_updated_schema import MediaUpdatedSchema as MediaUpdatedSchema
from kotobaya_client.models.media_updated_schema_set import MediaUpdatedSchemaSet as MediaUpdatedSchemaSet
from kotobaya_client.models.meta import Meta as Meta
from kotobaya_client.models.meta_dict_release import MetaDictRelease as MetaDictRelease
from kotobaya_client.models.meta_limits import MetaLimits as MetaLimits
from kotobaya_client.models.note import Note as Note
from kotobaya_client.models.note_base_type import NoteBaseType as NoteBaseType
from kotobaya_client.models.note_create import NoteCreate as NoteCreate
from kotobaya_client.models.note_created_schema import NoteCreatedSchema as NoteCreatedSchema
from kotobaya_client.models.note_deleted_schema import NoteDeletedSchema as NoteDeletedSchema
from kotobaya_client.models.note_field import NoteField as NoteField
from kotobaya_client.models.note_merged_schema import NoteMergedSchema as NoteMergedSchema
from kotobaya_client.models.note_page import NotePage as NotePage
from kotobaya_client.models.note_patch import NotePatch as NotePatch
from kotobaya_client.models.note_summary import NoteSummary as NoteSummary
from kotobaya_client.models.note_type import NoteType as NoteType
from kotobaya_client.models.note_type_create import NoteTypeCreate as NoteTypeCreate
from kotobaya_client.models.note_type_created_schema import NoteTypeCreatedSchema as NoteTypeCreatedSchema
from kotobaya_client.models.note_type_created_schema_fields_inner import NoteTypeCreatedSchemaFieldsInner as NoteTypeCreatedSchemaFieldsInner
from kotobaya_client.models.note_type_deleted_schema import NoteTypeDeletedSchema as NoteTypeDeletedSchema
from kotobaya_client.models.note_type_list import NoteTypeList as NoteTypeList
from kotobaya_client.models.note_type_patch import NoteTypePatch as NoteTypePatch
from kotobaya_client.models.note_type_updated_schema import NoteTypeUpdatedSchema as NoteTypeUpdatedSchema
from kotobaya_client.models.note_type_updated_schema_set import NoteTypeUpdatedSchemaSet as NoteTypeUpdatedSchemaSet
from kotobaya_client.models.note_updated_schema import NoteUpdatedSchema as NoteUpdatedSchema
from kotobaya_client.models.note_updated_schema_set import NoteUpdatedSchemaSet as NoteUpdatedSchemaSet
from kotobaya_client.models.op_schema import OpSchema as OpSchema
from kotobaya_client.models.override_token import OverrideToken as OverrideToken
from kotobaya_client.models.pair_request import PairRequest as PairRequest
from kotobaya_client.models.pair_response import PairResponse as PairResponse
from kotobaya_client.models.pairing import Pairing as Pairing
from kotobaya_client.models.parse_override import ParseOverride as ParseOverride
from kotobaya_client.models.parse_override_deleted_schema import ParseOverrideDeletedSchema as ParseOverrideDeletedSchema
from kotobaya_client.models.parse_override_page import ParseOverridePage as ParseOverridePage
from kotobaya_client.models.parse_override_put import ParseOverridePut as ParseOverridePut
from kotobaya_client.models.parse_override_put_schema import ParseOverridePutSchema as ParseOverridePutSchema
from kotobaya_client.models.parse_override_put_schema_tokens_inner import ParseOverridePutSchemaTokensInner as ParseOverridePutSchemaTokensInner
from kotobaya_client.models.parse_request import ParseRequest as ParseRequest
from kotobaya_client.models.parse_result import ParseResult as ParseResult
from kotobaya_client.models.parsed_sentence import ParsedSentence as ParsedSentence
from kotobaya_client.models.preset import Preset as Preset
from kotobaya_client.models.preset_create import PresetCreate as PresetCreate
from kotobaya_client.models.preset_list import PresetList as PresetList
from kotobaya_client.models.preset_patch import PresetPatch as PresetPatch
from kotobaya_client.models.principal import Principal as Principal
from kotobaya_client.models.problem import Problem as Problem
from kotobaya_client.models.pull_response import PullResponse as PullResponse
from kotobaya_client.models.push_request import PushRequest as PushRequest
from kotobaya_client.models.push_request_ops_inner import PushRequestOpsInner as PushRequestOpsInner
from kotobaya_client.models.push_response import PushResponse as PushResponse
from kotobaya_client.models.push_result import PushResult as PushResult
from kotobaya_client.models.push_result_error import PushResultError as PushResultError
from kotobaya_client.models.queue_item import QueueItem as QueueItem
from kotobaya_client.models.retention_group import RetentionGroup as RetentionGroup
from kotobaya_client.models.review_answer_request import ReviewAnswerRequest as ReviewAnswerRequest
from kotobaya_client.models.review_button import ReviewButton as ReviewButton
from kotobaya_client.models.review_log import ReviewLog as ReviewLog
from kotobaya_client.models.review_logged_schema import ReviewLoggedSchema as ReviewLoggedSchema
from kotobaya_client.models.review_page import ReviewPage as ReviewPage
from kotobaya_client.models.review_queue import ReviewQueue as ReviewQueue
from kotobaya_client.models.review_queue_counts import ReviewQueueCounts as ReviewQueueCounts
from kotobaya_client.models.review_result import ReviewResult as ReviewResult
from kotobaya_client.models.review_type import ReviewType as ReviewType
from kotobaya_client.models.review_undo_request import ReviewUndoRequest as ReviewUndoRequest
from kotobaya_client.models.review_undone_schema import ReviewUndoneSchema as ReviewUndoneSchema
from kotobaya_client.models.schedule_preview import SchedulePreview as SchedulePreview
from kotobaya_client.models.schedule_preview_option import SchedulePreviewOption as SchedulePreviewOption
from kotobaya_client.models.scope import Scope as Scope
from kotobaya_client.models.sentence import Sentence as Sentence
from kotobaya_client.models.sentence_page import SentencePage as SentencePage
from kotobaya_client.models.sentence_token import SentenceToken as SentenceToken
from kotobaya_client.models.setting import Setting as Setting
from kotobaya_client.models.setting_list import SettingList as SettingList
from kotobaya_client.models.settings_patch import SettingsPatch as SettingsPatch
from kotobaya_client.models.settings_set_schema import SettingsSetSchema as SettingsSetSchema
from kotobaya_client.models.span_origin import SpanOrigin as SpanOrigin
from kotobaya_client.models.stats_calibration import StatsCalibration as StatsCalibration
from kotobaya_client.models.stats_calibration_bins_inner import StatsCalibrationBinsInner as StatsCalibrationBinsInner
from kotobaya_client.models.stats_forecast import StatsForecast as StatsForecast
from kotobaya_client.models.stats_forecast_days_inner import StatsForecastDaysInner as StatsForecastDaysInner
from kotobaya_client.models.stats_forecast_hours_inner import StatsForecastHoursInner as StatsForecastHoursInner
from kotobaya_client.models.stats_heatmap import StatsHeatmap as StatsHeatmap
from kotobaya_client.models.stats_heatmap_days_inner import StatsHeatmapDaysInner as StatsHeatmapDaysInner
from kotobaya_client.models.stats_known_words import StatsKnownWords as StatsKnownWords
from kotobaya_client.models.stats_known_words_current import StatsKnownWordsCurrent as StatsKnownWordsCurrent
from kotobaya_client.models.stats_known_words_series_inner import StatsKnownWordsSeriesInner as StatsKnownWordsSeriesInner
from kotobaya_client.models.stats_retention import StatsRetention as StatsRetention
from kotobaya_client.models.stats_summary import StatsSummary as StatsSummary
from kotobaya_client.models.stats_summary_done_today import StatsSummaryDoneToday as StatsSummaryDoneToday
from kotobaya_client.models.stats_summary_due import StatsSummaryDue as StatsSummaryDue
from kotobaya_client.models.stats_summary_totals import StatsSummaryTotals as StatsSummaryTotals
from kotobaya_client.models.tatoeba_sentence import TatoebaSentence as TatoebaSentence
from kotobaya_client.models.template import Template as Template
from kotobaya_client.models.token import Token as Token
from kotobaya_client.models.token_create import TokenCreate as TokenCreate
from kotobaya_client.models.token_created import TokenCreated as TokenCreated
from kotobaya_client.models.token_kind import TokenKind as TokenKind
from kotobaya_client.models.token_list import TokenList as TokenList
from kotobaya_client.models.tts_request import TtsRequest as TtsRequest
from kotobaya_client.models.word_status import WordStatus as WordStatus
from kotobaya_client.models.word_status_cleared_schema import WordStatusClearedSchema as WordStatusClearedSchema
from kotobaya_client.models.word_status_set import WordStatusSet as WordStatusSet
from kotobaya_client.models.word_status_set_schema import WordStatusSetSchema as WordStatusSetSchema
from kotobaya_client.models.word_status_value import WordStatusValue as WordStatusValue

