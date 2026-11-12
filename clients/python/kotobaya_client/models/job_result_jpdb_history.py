# coding: utf-8

"""
    Kotobaya API

    Versioned REST/JSON interface at `/api/v1`. The web app uses nothing else; the Android app uses `sync`, `blobs`, `dict/release`, `devices/pair`, `devices/self` and whatever conveniences it likes.  Implementers: this file is the contract. If something you need is missing or wrong, do not work around it; return `CONTRACT_CHANGE_REQUEST`. Additive changes stay in v1; breaking changes go to `/api/v2`.  ## 1. Conventions  - **JSON.** Request and response bodies are `application/json; charset=utf-8` unless stated.   Property names are `snake_case` and equal the column names of `contracts/db/V1__init.sql`   wherever a property mirrors a column; the column `COMMENT` is then the definition. - **Unknown properties.** Servers reject unknown request properties and unknown query   parameters with `422 validation_failed`. Clients MUST ignore unknown response properties and   MUST treat unknown enum values in responses as \"other\" (v1 may add both). - **Absent vs null.** In `PATCH` bodies an absent property means \"leave unchanged\"; `null` means   \"set to NULL\" where the property is nullable and is a validation error otherwise.   Responses always include every documented property; nullable ones are `null`, never absent,   unless the property is documented as \"omitted when ...\". - **Ids.** Row ids are UUIDv7 in canonical lowercase `8-4-4-4-12` text. Uppercase ids in   requests are rejected with `422`. \"Entry\" always means a JMdict sequence number (`seq`,   JSON integer). - **Time.** Instants are RFC 3339 UTC strings with millisecond precision and a `Z` suffix,   e.g. `2026-11-12T09:30:00.000Z`. Servers accept any RFC 3339 offset on input and normalise   to UTC. A \"study day\" is a local date `YYYY-MM-DD` computed from the settings   `scheduler.timezone` and `scheduler.day_rollover_hour`: study day D covers local time   `D rollover:00` (inclusive) to `D+1 rollover:00` (exclusive). - **Text offsets** into sentence text are UTF-16 code units, half-open `[start, end)`. - **Blobs** (audio, images, uploads, snapshots) are addressed by the lowercase hex SHA-256 of   their bytes and transferred through `/blobs/{hash}`, never inline in JSON.  ## 2. Authentication and scopes  Three credential kinds, all rows of `app.api_token` (stored hashed):  | Kind | How obtained | Sent as | Scopes | |---|---|---|---| | `session` | `POST /auth/login` | cookie `kotobaya_session` (HTTP-only) | all five | | `device` | `POST /devices/pair` | `Authorization: Bearer kbd_...` | `read`, `write`, `review` | | `personal` | `POST /tokens` | `Authorization: Bearer kbp_...` | chosen at creation |  Token strings are `<3-letter kind prefix>_<40 characters of [A-Za-z0-9]>` (`kbs_`, `kbd_`, `kbp_`), generated from a CSPRNG. `token_hash` = lowercase hex SHA-256 of the whole string; `token_prefix` = its first 8 characters. A token is valid iff `revoked_at IS NULL` and (`expires_at IS NULL` or `expires_at > now()`) and, when it has a `device_id`, that device is not revoked. Validity is checked on every request (no caching longer than 5 seconds), so revocation takes effect at once.  Scopes are independent (no scope implies another):  | Scope | Grants | |---|---| | `read` | Every `GET` of learning, media and reference data; `POST /parse`; `GET /events`; `GET /sync/pull`; exports. | | `write` | Create, change, delete notes, cards, decks, grammar, note types, presets, settings, word status, parse overrides, media metadata; upload blobs; `POST /sync/push` (non-review ops). | | `review` | `POST /review/answer`, `POST /review/undo`, `POST /drill/session`, `POST /cards/bulk/schedule`; `review.*` ops inside `POST /sync/push` (per op; without it the op is rejected with `insufficient_scope`). | | `import` | Start and cancel import, parse, dictionary and optimizer jobs; upload blobs. | | `admin` | Devices, pairing, tokens (a device's own row: `/devices/self` with `read`/`write`). |  Each operation lists the scope it needs in its `security` requirement: the array after the scheme name. When two alternatives are listed for one scheme the caller needs either. A request with no or an invalid credential gets `401 unauthenticated`; a valid credential lacking the scope gets `403 insufficient_scope`. Operations with `security: []` are public.  There are exactly two places where who may call is not just \"scheme + scope\", and both are declared in the operation, not only in prose: - `GET/PATCH /devices/self` (`read` / `write`, bearer only): the token must be bound to a   device, else `403 device_required`. A device never needs `admin` to manage its own row;   `/devices/{device_id}` is admin only. - `POST /ankiconnect` (`x-auth: body-key`): the token may instead be sent in the JSON body   property `key`; the empty `{}` alternative in its `security` only signals that no header is   needed. The scope for `key` is `x-body-key-scope`.  **CSRF.** A request authenticated by the session cookie whose method is not `GET` or `HEAD` MUST carry the header `X-Kotobaya-Csrf: 1`; otherwise `403 csrf_required`. The cookie is set with `HttpOnly; Secure; SameSite=Strict; Path=/api`. The server sends no CORS headers (the web app is served from the same origin). If both a cookie and an `Authorization` header are present, the header wins and the cookie is ignored.  **Audit.** Every request is logged with `token.id` and `token.name` (structured server log; there is no audit table or endpoint in v1). Ops record the token in `change.token_id`.  ## 3. Writes are sync ops  Learning data is only ever changed by applying sync ops (`contracts/sync`). Every REST operation that changes learning data is a convenience wrapper: the server builds the op(s) named in the operation's `x-emits-ops`, applies them through the same op-apply path as `POST /sync/push` in one transaction, and they then appear in `GET /sync/pull` for every device. Op names are those of `contracts/db/SCHEMA.md` section 4; `contracts/sync` is authoritative for names and payloads.  For an op the server builds on behalf of a REST call:  - `op_id` is a fresh UUIDv7; origin `device_id` is the **server device**   `00000000-0000-7000-8000-000000000001` (also for session and device tokens: only one party   ever generates HLCs for a given device id); `hlc` comes from the server's HLC clock;   `change.token_id` is the calling token. - A property absent from a `PATCH` body produces no register write. A property present with   the value the row already has still produces a write (it bumps the register's clock). - If a call changes nothing (documented per operation, e.g. deleting an already deleted   note), no op is emitted and the response is still successful. - Responses to mutating calls carry the header `Kotobaya-Seq`: the `change.seq` of the last op   the call emitted. A client holding a local replica knows it has seen the effect once its   pull cursor is at or past that value. The header is absent when no op was emitted.  **Idempotent creation.** Create bodies accept an optional client-generated `id` (UUIDv7). Re-sending a create with an `id` that already exists returns the existing resource with status `200` instead of `201` and emits nothing, whatever the rest of the body says. Clients that may retry MUST send `id`. When `id` is omitted the server generates one. `POST /review/answer` requires `id` (a duplicate review would corrupt scheduling); `POST /media/import` takes the same optional idempotency id as `media_id`.  The existence check and the emit are **one transaction that takes advisory lock `7033001` before the check** and holds it until commit (the lock `POST /sync/push` takes per op, SYNC.md 5.2): check whether `id` exists; if not, mint, apply and log the op(s) - for `createNote` the `note.created` and every `card.created` - then commit. Without the lock two concurrent retries of the same create both pass the check; the second `note.created` is a no-op on the row (SYNC.md 7.2) but its `card.created` ops carry fresh card ids, so the note would get duplicate cards. The same holds for every other id-idempotent call (`POST /review/answer`, `POST /review/undo`, `POST /media/import`), and a pushed op that creates the same id races safely because it takes the same lock.  **Tombstones and merges.** Deleted learning rows are tombstones (`deleted_at` set). `GET` by id of a deleted row returns `410 gone` unless `include_deleted=true`; lists omit deleted rows unless `include_deleted=true`. A note or card that lost a merge (`merged_into_id` set) is answered with its survivor: `GET`, `PATCH` and review calls addressed to the loser's id act on the survivor and the response body is the survivor (its `id` differs from the requested id; the response has `Content-Location` with the survivor's URL).  ## 4. Pagination  List operations that take `limit` and `cursor` return `{\"items\": [...], \"next_cursor\": ...}`. `next_cursor` is an opaque string, or `null` on the last page. Pass it back unchanged as `cursor` together with the same other parameters; changing any other parameter while passing a cursor is `422 validation_failed` (`pointer` `/cursor`). `limit` defaults to 50, maximum 500. Order is stable and documented per operation; rows created while paging may or may not appear.  **Cursor semantics (keyset, no snapshot).** A cursor encodes the sort-key values and the `id` of the last row returned; the next page is the rows strictly after that tuple in the current order, evaluated against the data at the time of that request. The order is not snapshotted. Consequently, while a client pages through a list sorted by a mutable key (`due_at`, `updated_at`, `lapses`, ...): - a row whose key changes so that it moves from after the cursor to before it is **skipped**; - a row whose key changes so that it moves from before the cursor to after it is **returned   again** (with its new values); - a row deleted while paging disappears; one created may or may not appear. Rows that do not change are returned exactly once. Clients MUST de-duplicate by `id` (last occurrence wins). A client that needs an exact set (bulk actions, exports) uses an `id`-sorted list where offered, `POST /cards/bulk` with `query`, or `GET /export`. Lists sorted by an immutable key (`id`, `created_at`) have no skips or repeats. An empty result is `{\"items\": [], \"next_cursor\": null}` with status `200`.  ## 5. Errors  Every non-2xx response has the body `application/problem+json` (RFC 9457), schema `Problem`. `code` is the stable machine-readable identifier; `type` is `urn:kotobaya:problem:<code>`.  | Status | `code` | When | |---|---|---| | 400 | `invalid_request` | Body is not valid JSON, wrong content type framing, malformed header; `POST /sync/push` body not valid against `PushRequestLenient`. | | 401 | `unauthenticated` | No credential, or it is unknown, expired or revoked. | | 401 | `invalid_credentials` | Wrong password or TOTP code (`POST /auth/login`). | | 401 | `totp_required` | TOTP is enabled and `totp_code` was not sent. | | 401 | `invalid_pairing_code` | Pairing code unknown, expired or already used. | | 403 | `insufficient_scope` | Credential lacks the scope; `required_scope` names it. | | 403 | `csrf_required` | Cookie-authenticated write without `X-Kotobaya-Csrf`. | | 403 | `device_required` | `POST /sync/push` or `/devices/self` with a credential that has no `device_id`. | | 404 | `not_found` | No such resource (also: id of another resource kind). | | 409 | `already_exists` | Natural-key collision; `existing_id` names the holder. | | 409 | `builtin_immutable` | Deleting a built-in note type or the Default preset, or removing a built-in field. | | 409 | `in_use` | Deleting a note type that live notes use. | | 409 | `deck_read_only` | Changing items or deleting a `media`/`list` deck through deck endpoints. | | 409 | `not_applicable` | The resource kind does not support the operation (e.g. items of a smart deck). | | 409 | `card_not_reviewable` | Card is suspended. | | 409 | `job_not_cancellable` | Job already finished. | | 410 | `gone` | Tombstoned row requested without `include_deleted=true`. | | 413 | `payload_too_large` | Body over the limit in `GET /meta`. On `POST /sync/push` only for the whole batch (the client halves it); one op over `max_op_bytes` is a per-op `op_too_large` result instead. | | 415 | `unsupported_media_type` | Wrong `Content-Type`. | | 422 | `validation_failed` | Well-formed but invalid; `errors[]` lists each problem. | | 422 | `invalid_query` | Card search string does not parse; `errors[0].position` is the UTF-16 offset. | | 422 | `bulk_too_large` | Bulk selector matched more than 5000 cards. | | 422 | `hash_mismatch` | Uploaded bytes do not hash to the `{hash}` in the path. | | 429 | `rate_limited` | Too many login or pairing attempts; see `Retry-After`. | | 500 | `internal` | Bug. Safe to retry idempotent calls. | | 503 | `unavailable` | Database or object store unreachable. Retry with backoff. |  Check order: 401, then 403, then 404/410, then 415/400, then 422, then 409.  ## 6. Events  `GET /events` is a server-sent events stream; see that operation. Event payload schemas are `Event*` in components. 

    The version of the OpenAPI document: 1.0.0
    Generated by OpenAPI Generator (https://openapi-generator.tech)

    Do not edit the class manually.
"""  # noqa: E501


from __future__ import annotations
import pprint
import re  # noqa: F401
import json

from pydantic import BaseModel, ConfigDict, Field
from typing import Any, ClassVar, Dict, List
from typing_extensions import Annotated
from kotobaya_client.models.job_result_jpdb_history_kanji_unmapped_inner import JobResultJpdbHistoryKanjiUnmappedInner
from kotobaya_client.models.job_result_jpdb_history_unmapped_inner import JobResultJpdbHistoryUnmappedInner
from typing import Optional, Set
from typing_extensions import Self
from pydantic_core import to_jsonable_python

class JobResultJpdbHistory(BaseModel):
    """
    Result of a jpdb history import. Every input record is counted in exactly one bucket (C-2, C-4; mapping rules on `importJpdbHistory`): - rows of the vocab arrays: `vocabulary_total = vocabulary_carded + vocabulary_status_only   + vocabulary_en_jp_ignored + len(unmapped)`; - rows of the kanji arrays: `kanji_total = kanji_carded + len(kanji_unmapped)`; - review events of all four arrays: `reviews_total = reviews_imported + reviews_already_imported   + reviews_skipped + reviews_unknown_grade + status_events_unknown + status_events_known   + reviews_duplicate_dropped`. An event's bucket is the first that applies: (1) its row is a `cards_vocabulary_en_jp` row or is unmapped → `reviews_skipped`; (2) malformed → `reviews_unknown_grade`; (3) exact duplicate within its group → `reviews_duplicate_dropped`; (4) grade `unknown` → `status_events_unknown`; (5) grade `known` → `status_events_known` (whether or not it set a word status); (6) any other non-rating grade → `reviews_unknown_grade`; (7) rating with `import_reviews = false` → `reviews_skipped`; (8) rating whose review id already exists → `reviews_already_imported`; (9) else `reviews_imported`. Rows count rows, not distinct words: rows that repeat a vid are each counted in `vocabulary_total` and in their group's bucket (`unmapped` lists each row). `notes_*`, `cards_created`, `kanji_notes_*`, `kanji_cards_*` and `word_statuses_set` count what was written, not input records. With `import_reviews = true`: `notes_created + notes_existing` = distinct seqs of carded vocab groups; `cards_created <= notes_created + notes_existing`; `word_statuses_set <=` distinct seqs of status-only groups; `kanji_notes_created + kanji_notes_existing` = distinct characters of mapped kanji rows; `kanji_cards_suspended <= kanji_cards_created`. An export without kanji arrays reports the kanji counts as 0.
    """ # noqa: E501
    cards_created: Annotated[int, Field(strict=True, ge=0)] = Field(description="Vocabulary `meaning` cards created, at most one per carded group, so about equal to `notes_created` (kanji cards are `kanji_cards_created`).")
    kanji_carded: Annotated[int, Field(strict=True, ge=0)] = Field(description="Kanji rows mapped to a kanji note and a card of kind `keyword` (char → keyword) or `writing` (keyword → char), known-only rows included. `writing` cards and known-only cards are created suspended.")
    kanji_cards_created: Annotated[int, Field(strict=True, ge=0)] = Field(description="Kanji cards created (one per (character, kind) without a live card), suspended ones included.")
    kanji_cards_suspended: Annotated[int, Field(strict=True, ge=0)] = Field(description="Of `kanji_cards_created`, those created with `suspended = true` - every created `writing` card plus created `keyword` cards of known-only groups. Existing cards are never counted or changed. Added by C-4.")
    kanji_notes_created: Annotated[int, Field(strict=True, ge=0)] = Field(description="Kanji notes created (one per character, shared by both arrays).")
    kanji_notes_existing: Annotated[int, Field(strict=True, ge=0)] = Field(description="Distinct characters that already had a live kanji note.")
    kanji_total: Annotated[int, Field(strict=True, ge=0)] = Field(description="Kanji records in the export (rows of `cards_kanji_char_keyword` and `cards_kanji_keyword_char`).")
    kanji_unmapped: List[JobResultJpdbHistoryKanjiUnmappedInner] = Field(description="Kanji rows whose `character` is not exactly one Unicode code point. Their events count as `reviews_skipped`.")
    notes_created: Annotated[int, Field(strict=True, ge=0)] = Field(description="Vocabulary notes created, one per carded group (distinct seq), not per row (kanji notes are `kanji_notes_created`).")
    notes_existing: Annotated[int, Field(strict=True, ge=0)] = Field(description="Carded vocabulary groups (distinct seqs) that already had a live note (kanji - `kanji_notes_existing`).")
    reviews_already_imported: Annotated[int, Field(strict=True, ge=0)] = Field(description="Reviews skipped because an earlier run imported them.")
    reviews_duplicate_dropped: Annotated[int, Field(strict=True, ge=0)] = Field(description="Events of mapped rows dropped as exact duplicates - same `timestamp` and `grade` as an earlier event of the same group (vocab - rows sharing a seq; kanji - rows sharing character and card kind). Any grade, status events included. Added by C-4.")
    reviews_imported: Annotated[int, Field(strict=True, ge=0)] = Field(description="Reviews logged by this run.")
    reviews_skipped: Annotated[int, Field(strict=True, ge=0)] = Field(description="Events of unmapped rows (`unmapped`, `kanji_unmapped`) and of `cards_vocabulary_en_jp` rows (any grade), and rating events when `import_reviews` is false.")
    reviews_total: Annotated[int, Field(strict=True, ge=0)] = Field(description="Review events in the export (every element of every row's `reviews[]`, all four arrays, status events included).")
    reviews_unknown_grade: Annotated[int, Field(strict=True, ge=0)] = Field(description="Events of a mapped row that are malformed (not an object with an integer `timestamp` and a string `grade`) or whose `grade` is none of the known grades; nothing is written for them.")
    status_events_known: Annotated[int, Field(strict=True, ge=0)] = Field(description="`known` events of mapped rows, duplicates excluded, never a review. All of them count here: those of status-only vocab groups (which may set a word status), those of carded vocab groups (ignored; FSRS schedules from the reviews) and those of kanji groups (a known-only kanji card is created suspended).")
    status_events_unknown: Annotated[int, Field(strict=True, ge=0)] = Field(description="`unknown` events of mapped rows, duplicates excluded (card creation only, no review).")
    unmapped: List[JobResultJpdbHistoryUnmappedInner] = Field(description="`cards_vocabulary_jp_en` rows that map to no JMdict entry, one element per row.")
    vocabulary_carded: Annotated[int, Field(strict=True, ge=0)] = Field(description="Mapped `cards_vocabulary_jp_en` rows whose group (rows sharing the mapped seq) is carded - it has at least one kept non-`known` event, or no well-formed events; the group's note has a `meaning` card after the run (unless `import_reviews` is false). One note and one card per group, not per row.")
    vocabulary_en_jp_ignored: Annotated[int, Field(strict=True, ge=0)] = Field(description="Rows of `cards_vocabulary_en_jp` (C-4). Never mapped, never listed in `unmapped`; no note, card, status or review; their events count as `reviews_skipped`. Added by C-4.")
    vocabulary_status_only: Annotated[int, Field(strict=True, ge=0)] = Field(description="Mapped `cards_vocabulary_jp_en` rows whose group's kept well-formed events are all `known` (at least one); the word gets status `known` (unless it already has an override or `import_word_status` is false), no note or card.")
    vocabulary_total: Annotated[int, Field(strict=True, ge=0)] = Field(description="Vocabulary records in the export (rows of `cards_vocabulary_jp_en` and `cards_vocabulary_en_jp`).")
    word_statuses_set: Annotated[int, Field(strict=True, ge=0)] = Field(description="Overrides written (`word.status_set` ops emitted), at most one per status-only vocab group.")
    __properties: ClassVar[List[str]] = ["cards_created", "kanji_carded", "kanji_cards_created", "kanji_cards_suspended", "kanji_notes_created", "kanji_notes_existing", "kanji_total", "kanji_unmapped", "notes_created", "notes_existing", "reviews_already_imported", "reviews_duplicate_dropped", "reviews_imported", "reviews_skipped", "reviews_total", "reviews_unknown_grade", "status_events_known", "status_events_unknown", "unmapped", "vocabulary_carded", "vocabulary_en_jp_ignored", "vocabulary_status_only", "vocabulary_total", "word_statuses_set"]

    model_config = ConfigDict(
        validate_by_name=True,
        validate_by_alias=True,
        validate_assignment=True,
        protected_namespaces=(),
    )


    def to_str(self) -> str:
        """Returns the string representation of the model using alias"""
        return pprint.pformat(self.model_dump(by_alias=True))

    def to_json(self) -> str:
        """Returns the JSON representation of the model using alias"""
        return json.dumps(to_jsonable_python(self.to_dict()))

    @classmethod
    def from_json(cls, json_str: str) -> Optional[Self]:
        """Create an instance of JobResultJpdbHistory from a JSON string"""
        return cls.from_dict(json.loads(json_str))

    def to_dict(self) -> Dict[str, Any]:
        """Return the dictionary representation of the model using alias.

        This has the following differences from calling pydantic's
        `self.model_dump(by_alias=True)`:

        * `None` is only added to the output dict for nullable fields that
          were set at model initialization. Other fields with value `None`
          are ignored.
        """
        excluded_fields: Set[str] = set([
        ])

        _dict = self.model_dump(
            by_alias=True,
            exclude=excluded_fields,
            exclude_none=True,
        )
        # override the default output from pydantic by calling `to_dict()` of each item in kanji_unmapped (list)
        _items = []
        if self.kanji_unmapped:
            for _item_kanji_unmapped in self.kanji_unmapped:
                _items.append(_item_kanji_unmapped.to_dict() if _item_kanji_unmapped is not None else None)
            _dict['kanji_unmapped'] = _items
        # override the default output from pydantic by calling `to_dict()` of each item in unmapped (list)
        _items = []
        if self.unmapped:
            for _item_unmapped in self.unmapped:
                _items.append(_item_unmapped.to_dict() if _item_unmapped is not None else None)
            _dict['unmapped'] = _items
        return _dict

    @classmethod
    def from_dict(cls, obj: Optional[Dict[str, Any]]) -> Optional[Self]:
        """Create an instance of JobResultJpdbHistory from a dict"""
        if obj is None:
            return None

        if not isinstance(obj, dict):
            return cls.model_validate(obj)

        _obj = cls.model_validate({
            "cards_created": obj.get("cards_created"),
            "kanji_carded": obj.get("kanji_carded"),
            "kanji_cards_created": obj.get("kanji_cards_created"),
            "kanji_cards_suspended": obj.get("kanji_cards_suspended"),
            "kanji_notes_created": obj.get("kanji_notes_created"),
            "kanji_notes_existing": obj.get("kanji_notes_existing"),
            "kanji_total": obj.get("kanji_total"),
            "kanji_unmapped": [JobResultJpdbHistoryKanjiUnmappedInner.from_dict(_item) for _item in obj["kanji_unmapped"]] if obj.get("kanji_unmapped") is not None else None,
            "notes_created": obj.get("notes_created"),
            "notes_existing": obj.get("notes_existing"),
            "reviews_already_imported": obj.get("reviews_already_imported"),
            "reviews_duplicate_dropped": obj.get("reviews_duplicate_dropped"),
            "reviews_imported": obj.get("reviews_imported"),
            "reviews_skipped": obj.get("reviews_skipped"),
            "reviews_total": obj.get("reviews_total"),
            "reviews_unknown_grade": obj.get("reviews_unknown_grade"),
            "status_events_known": obj.get("status_events_known"),
            "status_events_unknown": obj.get("status_events_unknown"),
            "unmapped": [JobResultJpdbHistoryUnmappedInner.from_dict(_item) for _item in obj["unmapped"]] if obj.get("unmapped") is not None else None,
            "vocabulary_carded": obj.get("vocabulary_carded"),
            "vocabulary_en_jp_ignored": obj.get("vocabulary_en_jp_ignored"),
            "vocabulary_status_only": obj.get("vocabulary_status_only"),
            "vocabulary_total": obj.get("vocabulary_total"),
            "word_statuses_set": obj.get("word_statuses_set")
        })
        return _obj


