# Sync ops reference (T005)

One section per op type. Read [SYNC.md](SYNC.md) first: it defines the envelope (section 2), the clock rule
(7.1), the creation rule (7.2), tombstones (7.3), merging (8) and replay (9) that the entries below refer to.

Conventions used in every entry:

- **Emitter** `client`: any replica may mint the op (phone, web app, script, and the server itself on behalf
  of a REST call). `server`: only the server mints it; a pushed one is rejected with `server_only`.
- **Entity**: `entity_type`, and how `entity_id` is derived from the payload.
- **Payload**: the JSON Schema is the definition of every property (meaning, constraints, default).
- **Server rejects**: checks in addition to the common ones that apply to every pushed op
  (`schema_invalid`, `unsupported_op`, `device_mismatch`, `server_only`, and `clock_ahead` /
  `clock_behind` on `wall(hlc)`; SYNC.md 5.3, 3.4). Devices never
  validate pulled ops: whatever is in the log is applied.
- **Conflict rule**: what happens when the op meets a concurrent or later-arriving op on the same value.
  An op that loses is still accepted, logged and delivered to everyone.
- **Apply**: effect on a replica, identical on server and device unless stated.
- **Replay**: whether applying the op forces FSRS replay of card caches (SYNC.md 9.2).
- `wall(hlc)` = the wall-clock milliseconds in the op's hlc (SYNC.md 3).

Names: the op names are those of `contracts/db/SCHEMA.md` 4.1 and 4.3, unchanged. The ticket text's
`settings.updated` and `grammar_point.*` are `settings.set` and `grammar.*` here. The merge payloads use
`loser_id` / `survivor_id` where SCHEMA.md writes `{loser, survivor}`.

| Op | Emitter | Table | Conflict rule in one line |
|---|---|---|---|
| [`note_type.created`](#note_typecreated) | client | `note_type` | id exists -> no-op |
| [`note_type.updated`](#note_typeupdated) | client | `note_type` | per-register clock rule |
| [`note_type.deleted`](#note_typedeleted) | client | `note_type` | tombstone, permanent |
| [`note.created`](#notecreated) | client | `note` | id exists -> no-op |
| [`note.updated`](#noteupdated) | client | `note` | per-register clock rule |
| [`note.deleted`](#notedeleted) | client | `note` | tombstone, permanent |
| [`note.merged`](#notemerged) | server | `note` | server verdict; no-op if already merged |
| [`card.created`](#cardcreated) | client | `card` | id exists -> no-op |
| [`card.suspended`](#cardsuspended) | client | `card` | register `suspended`, clock rule |
| [`card.unsuspended`](#cardunsuspended) | client | `card` | register `suspended`, clock rule |
| [`card.unburied`](#cardunburied) | client | `card` | register `buried_until`, clock rule |
| [`card.deleted`](#carddeleted) | client | `card` | tombstone, permanent |
| [`card.buried`](#cardburied) | client | `card` | register `buried_until`, clock rule |
| [`card.flag_set`](#cardflag_set) | client | `card` | register `flags`, clock rule |
| [`card.preset_set`](#cardpreset_set) | client | `card` | register `preset_id`, clock rule |
| [`card.merged`](#cardmerged) | server | `card` | server verdict; no-op if already merged |
| [`review.logged`](#reviewlogged) | client | `review_log` | never conflicts; id exists -> no-op |
| [`review.undone`](#reviewundone) | client | `review_log` | earliest undo wins |
| [`word.status_set`](#wordstatus_set) | client | `word_status` | one register per entry, clock rule |
| [`word.status_cleared`](#wordstatus_cleared) | client | `word_status` | same register, value NULL |
| [`deck.created`](#deckcreated) | client | `deck` | id exists -> no-op |
| [`deck.updated`](#deckupdated) | client | `deck` | per-register clock rule |
| [`deck.deleted`](#deckdeleted) | client | `deck` | tombstone, permanent |
| [`deck.content_published`](#deckcontent_published) | server | `deck` | higher `content_version` wins |
| [`deck.item_put`](#deckitem_put) | client | `deck_item` | whole row, clock rule |
| [`deck.item_removed`](#deckitem_removed) | client | `deck_item` | whole row, clock rule (reversible) |
| [`confusable.suggested`](#confusablesuggested) | server | `confusable` | user decision beats suggestion; else clock |
| [`confusable.confirmed`](#confusableconfirmed) | client | `confusable` | user decision beats suggestion; else clock |
| [`confusable.rejected`](#confusablerejected) | client | `confusable` | user decision beats suggestion; else clock |
| [`grammar.created`](#grammarcreated) | client | `grammar_point` | id exists -> no-op |
| [`grammar.updated`](#grammarupdated) | client | `grammar_point` | per-register clock rule |
| [`grammar.deleted`](#grammardeleted) | client | `grammar_point` | tombstone, permanent |
| [`grammar.span_put`](#grammarspan_put) | client | `sentence_grammar_span` | whole row, clock rule |
| [`grammar.span_removed`](#grammarspan_removed) | client | `sentence_grammar_span` | whole row, clock rule (reversible) |
| [`settings.set`](#settingsset) | client | `settings` | one register per key, clock rule |
| [`fsrs_params.created`](#fsrs_paramscreated) | client | `fsrs_params` | id exists -> no-op |
| [`fsrs_params.updated`](#fsrs_paramsupdated) | client | `fsrs_params` | per-register clock rule |
| [`fsrs_params.deleted`](#fsrs_paramsdeleted) | client | `fsrs_params` | tombstone, permanent |
| [`fsrs_params.published`](#fsrs_paramspublished) | server | `fsrs_params` | higher `weights_version` wins |
| [`media.created`](#mediacreated) | server | `media` | id exists -> no-op |
| [`media.updated`](#mediaupdated) | client | `media` | per-register clock rule |
| [`media.deleted`](#mediadeleted) | client | `media` | tombstone, permanent |
| [`media.content_published`](#mediacontent_published) | server | `media` | `content_version` >= stored; seq order |
| [`parse_override.put`](#parse_overrideput) | client | `parse_override` | whole row, clock rule; server only |
| [`parse_override.deleted`](#parse_overridedeleted) | client | `parse_override` | sticky tombstone; server only |

## `note_type`

### `note_type.created`

- **Emitter:** client (any replica).
- **Entity:** `note_type`; `entity_id` = `id`.
- **Payload:** [`ops/note_type.created.schema.json`](ops/note_type.created.schema.json)
- **Server rejects:** `invalid_value`: duplicate `key` in `fields`; or `base_type` is `vocab` and `fields` lacks any of the keys `expression`, `reading`, `meaning` (C-2, see below).
- **Required keys (C-2).** A note type with `base_type = vocab` MUST have field keys `expression`, `reading` and `meaning` (any labels, any order, any field `kind`, more fields allowed). The core relies on them: built-in vocab templates, dictionary fill-in of empty fields (`CardRenderer`), duplicate detection and search. A custom vocab type (e.g. one created for Takoboto or AnkiConnect) maps its source fields onto these keys (e.g. Takoboto "Expression"/"Kana"/"Meaning" → `expression`/`reading`/`meaning`) and may add others. Clients MUST check this before minting.
- **Conflict rule:** Creation rule (SYNC.md 7.2): if the id already exists the op is a no-op, whatever the existing row holds. Never conflicts otherwise.
- **Apply:** Insert with `builtin = false`. Clocks: `name`, `fields`, one `templates.<kind>` per kind, and `css` iff non-empty.
- **Replay:** None.
- **Example:** [`note_type.created.json`](examples/ops/note_type.created.json)

### `note_type.updated`

- **Emitter:** client (any replica).
- **Entity:** `note_type`; `entity_id` = `id`.
- **Payload:** [`ops/note_type.updated.schema.json`](ops/note_type.updated.schema.json)
- **Server rejects:** `unknown_reference`: note type does not exist. `invalid_value`: duplicate `key` in `fields`; or the type's `base_type` is `vocab` and `set.fields` lacks any of `expression`, `reading`, `meaning` (C-2, `note_type.created`). `immutable`: the type is built-in and the op removes a seeded field key from `fields` or sets a seeded `templates.<kind>` to null.
- **Conflict rule:** Register write by the clock rule (SYNC.md 7.1): applied iff the op's hlc is greater than the register's stored clock; forwarded to the survivor if the row is a merge loser (8.3). Registers: `name`, `fields` (whole array), `css`, `templates.<kind>` (one per key of `set.templates`).
- **Apply:** For each register in `set`: clock rule. `templates.<kind>` = null removes the key from the object and keeps the clock.
- **Replay:** None.
- **Example:** [`note_type.updated.json`](examples/ops/note_type.updated.json)

### `note_type.deleted`

- **Emitter:** client (any replica).
- **Entity:** `note_type`; `entity_id` = `id`.
- **Payload:** [`ops/note_type.deleted.schema.json`](ops/note_type.deleted.schema.json)
- **Server rejects:** `unknown_reference`: note type does not exist. `immutable`: the type is built-in.
- **Conflict rule:** Register `deleted` by the clock rule: `deleted_at` = wall time of the delete op with the highest hlc. Permanent: no op ever clears `deleted_at`; later and concurrent register writes still apply to the tombstone.
- **Apply:** Write register `deleted`. Notes of a deleted type stay, keep rendering with its templates, and can still be edited; clients do not offer a deleted type for new notes.
- **Replay:** None.
- **Example:** [`note_type.deleted.json`](examples/ops/note_type.deleted.json)

## `note`

### `note.created`

- **Emitter:** client (any replica).
- **Entity:** `note`; `entity_id` = `id`.
- **Payload:** [`ops/note.created.schema.json`](ops/note.created.schema.json)
- **Server rejects:** `unknown_reference`: `note_type_id` or `grammar_point_id` does not exist. `invalid_value`: more than one of `entry_seq`, `kanji_literal`, `grammar_point_id` is non-null, or the one given does not belong to the note type's `base_type` (entry_seq: vocab, kanji_literal: kanji, grammar_point_id: grammar).
- **Conflict rule:** Creation rule (SYNC.md 7.2): if the id already exists the op is a no-op, whatever the existing row holds. Never conflicts otherwise. **Server only:** if the new note is live and another live note already holds the same `entry_seq`, `kanji_literal` or `grammar_point_id`, the new note is the loser of a merge (SYNC.md 8.1). The op is still accepted.
- **Apply:** Insert: `type` = the note type's `base_type`; `created_by_device_id` = the op's `device_id`; `created_at` = `updated_at` = wall(hlc). Keys of `fields` whose value is `""` are dropped. Clocks: `entry_seq` iff non-null; `tags` iff non-empty; `mnemonic` iff non-empty; `source_sentence` iff any of the three source columns is non-default; `fields.<key>` per kept key.
- **Replay:** None directly. A merge it triggers does (see `card.merged`). Every replica re-derives `word_status.auto_status` for the entry.
- **Example:** [`note.created.duplicate.json`](examples/ops/note.created.duplicate.json), [`note.created.json`](examples/ops/note.created.json), [`note.created.second.json`](examples/ops/note.created.second.json)

### `note.updated`

- **Emitter:** client (any replica).
- **Entity:** `note`; `entity_id` = `id`.
- **Payload:** [`ops/note.updated.schema.json`](ops/note.updated.schema.json)
- **Server rejects:** `unknown_reference`: note does not exist. `invalid_value`: `set.entry_seq` is non-null and the note's `type` is not vocab.
- **Conflict rule:** Register write by the clock rule (SYNC.md 7.1): applied iff the op's hlc is greater than the register's stored clock; forwarded to the survivor if the row is a merge loser (8.3). Registers: `fields.<key>` (one per key of `set.fields`), `tags` (whole set), `mnemonic`, `source_sentence` (the three source columns together), `entry_seq`. `entry_seq` is never forwarded. **Server only:** if the op *wins* `entry_seq` on a live note and another live note holds the new value, the addressed note is the loser of a merge (8.1). If it loses the register there is no merge.
- **Apply:** For each register in `set`: clock rule on the addressed note; if that note has `merged_into_id`, the same write (same value, same hlc) is also applied to the survivor, except `entry_seq`. Applied to tombstones too.
- **Replay:** None directly. A change of `entry_seq` makes every replica re-derive `word_status.auto_status` for the old and the new entry.
- **Example:** [`note.updated.json`](examples/ops/note.updated.json)

### `note.deleted`

- **Emitter:** client (any replica).
- **Entity:** `note`; `entity_id` = `id`.
- **Payload:** [`ops/note.deleted.schema.json`](ops/note.deleted.schema.json)
- **Server rejects:** `unknown_reference`: note does not exist.
- **Conflict rule:** Register `deleted` by the clock rule: `deleted_at` = wall time of the delete op with the highest hlc. Permanent: no op ever clears `deleted_at`; later and concurrent register writes still apply to the tombstone. Beats every edit, older or newer. Addressed to a merge loser it affects only the loser (never forwarded).
- **Apply:** Write register `deleted` on the addressed note only. Its cards are not tombstoned; they are hidden because readers filter on the parent note. If the note is a merge survivor, the cards merged into its cards are hidden with it.
- **Replay:** None (the cards leave the queue; their caches are no longer read). Re-derive `word_status.auto_status` for the entry.
- **Example:** [`note.deleted.json`](examples/ops/note.deleted.json)

### `note.merged`

- **Emitter:** server only.
- **Entity:** `note`; `entity_id` = `loser_id`.
- **Payload:** [`ops/note.merged.schema.json`](ops/note.merged.schema.json)
- **Server rejects:** Always `server_only` when pushed.
- **Conflict rule:** Not a register write and never loses: the server emits at most one per loser. If the loser already has `merged_into_id`, the op is a no-op. If the survivor itself has `merged_into_id` (cannot happen in seq order), its target is used instead.
- **Apply:** SYNC.md 8.2, identical on every replica: (1) forward every clocked register of the loser except `entry_seq` and `deleted` to the survivor by the clock rule; (2) set the loser's `deleted_at` = wall(hlc) **only if it is NULL**, do not write `clocks.deleted`; set `merged_into_id`; `updated_at` = max(`updated_at`, wall(hlc)); (3) re-point every note whose `merged_into_id` is the loser. Cards are NOT paired by replicas: they follow as `card.merged` ops.
- **Replay:** None directly; the `card.merged` ops that follow do.
- **Example:** [`note.merged.json`](examples/ops/note.merged.json)

## `card`

### `card.created`

- **Emitter:** client (any replica).
- **Entity:** `card`; `entity_id` = `id`.
- **Payload:** [`ops/card.created.schema.json`](ops/card.created.schema.json)
- **Server rejects:** `unknown_reference`: `note_id` or `preset_id` does not exist.
- **Conflict rule:** Creation rule (SYNC.md 7.2): if the id already exists the op is a no-op, whatever the existing row holds. Never conflicts otherwise. **Server only:** if the note already has another live card of the same `kind`, the new card is the loser of a `card.merged`; if the note has `merged_into_id`, the new card is merged into the survivor note's live card of that kind, which the server creates first if missing (8.1). The op is still accepted.
- **Apply:** Insert with cache columns at their defaults (`state = 'new'`, `due_at` NULL, `reps` 0, `lapses` 0, `weights_version` 0). Clocks: `preset_id` iff not the Default preset; `suspended` iff true; `buried_until` iff non-null; `flags` iff non-zero.
- **Replay:** None (a new card has an empty replay set). Re-derive `word_status.auto_status` if the note is a vocab note with an entry.
- **Example:** [`card.created.duplicate.json`](examples/ops/card.created.duplicate.json), [`card.created.json`](examples/ops/card.created.json)

### `card.suspended`

- **Emitter:** client (any replica).
- **Entity:** `card`; `entity_id` = `id`.
- **Payload:** [`ops/card.suspended.schema.json`](ops/card.suspended.schema.json)
- **Server rejects:** `unknown_reference`: card does not exist.
- **Conflict rule:** Register write by the clock rule (SYNC.md 7.1): applied iff the op's hlc is greater than the register's stored clock; forwarded to the survivor if the row is a merge loser (8.3). Register `suspended`, value true. Competes with `card.unsuspended`.
- **Apply:** Clock rule on the addressed card; forwarded to the survivor card if merged.
- **Replay:** None.
- **Example:** [`card.suspended.json`](examples/ops/card.suspended.json)

### `card.unsuspended`

- **Emitter:** client (any replica).
- **Entity:** `card`; `entity_id` = `id`.
- **Payload:** [`ops/card.unsuspended.schema.json`](ops/card.unsuspended.schema.json)
- **Server rejects:** `unknown_reference`: card does not exist.
- **Conflict rule:** Register write by the clock rule (SYNC.md 7.1): applied iff the op's hlc is greater than the register's stored clock; forwarded to the survivor if the row is a merge loser (8.3). Register `suspended`, value false. Competes with `card.suspended`.
- **Apply:** Clock rule on the addressed card; forwarded to the survivor card if merged.
- **Replay:** None.
- **Example:** [`card.unsuspended.json`](examples/ops/card.unsuspended.json)

### `card.unburied`

- **Emitter:** client (any replica).
- **Entity:** `card`; `entity_id` = `id`.
- **Payload:** [`ops/card.unburied.schema.json`](ops/card.unburied.schema.json)
- **Server rejects:** `unknown_reference`: card does not exist.
- **Conflict rule:** Register write by the clock rule (SYNC.md 7.1): applied iff the op's hlc is greater than the register's stored clock; forwarded to the survivor if the row is a merge loser (8.3). Register `buried_until`, value null.
- **Apply:** Clock rule; forwarded if merged.
- **Replay:** None.
- **Example:** [`card.unburied.json`](examples/ops/card.unburied.json)

### `card.deleted`

- **Emitter:** client (any replica).
- **Entity:** `card`; `entity_id` = `id`.
- **Payload:** [`ops/card.deleted.schema.json`](ops/card.deleted.schema.json)
- **Server rejects:** `unknown_reference`: card does not exist.
- **Conflict rule:** Register `deleted` by the clock rule: `deleted_at` = wall time of the delete op with the highest hlc. Permanent: no op ever clears `deleted_at`; later and concurrent register writes still apply to the tombstone. Addressed to a merge loser it affects only the loser.
- **Apply:** Write register `deleted` on the addressed card only. Its `review_log` rows stay. A card whose own `deleted_at` is set and whose `merged_into_id` is NULL has an unmaintained cache.
- **Replay:** None. Re-derive `word_status.auto_status`.
- **Example:** [`card.deleted.json`](examples/ops/card.deleted.json)

### `card.buried`

- **Emitter:** client (any replica).
- **Entity:** `card`; `entity_id` = `id`.
- **Payload:** [`ops/card.buried.schema.json`](ops/card.buried.schema.json)
- **Server rejects:** `unknown_reference`: card does not exist.
- **Conflict rule:** Register write by the clock rule (SYNC.md 7.1): applied iff the op's hlc is greater than the register's stored clock; forwarded to the survivor if the row is a merge loser (8.3). Register `buried_until`, value `until`. Competes with `card.unburied` and other `card.buried`.
- **Apply:** Clock rule; forwarded if merged. An `until` in the past is stored as given (the card is simply not hidden).
- **Replay:** None. Sibling burying is not an op; the queue builder derives it from today's reviews.
- **Example:** [`card.buried.json`](examples/ops/card.buried.json)

### `card.flag_set`

- **Emitter:** client (any replica).
- **Entity:** `card`; `entity_id` = `id`.
- **Payload:** [`ops/card.flag_set.schema.json`](ops/card.flag_set.schema.json)
- **Server rejects:** `unknown_reference`: card does not exist.
- **Conflict rule:** Register write by the clock rule (SYNC.md 7.1): applied iff the op's hlc is greater than the register's stored clock; forwarded to the survivor if the row is a merge loser (8.3). Register `flags`.
- **Apply:** Clock rule; forwarded if merged.
- **Replay:** None.
- **Example:** [`card.flag_set.json`](examples/ops/card.flag_set.json)

### `card.preset_set`

- **Emitter:** client (any replica).
- **Entity:** `card`; `entity_id` = `id`.
- **Payload:** [`ops/card.preset_set.schema.json`](ops/card.preset_set.schema.json)
- **Server rejects:** `unknown_reference`: card or preset does not exist.
- **Conflict rule:** Register write by the clock rule (SYNC.md 7.1): applied iff the op's hlc is greater than the register's stored clock; forwarded to the survivor if the row is a merge loser (8.3). Register `preset_id`.
- **Apply:** Clock rule; forwarded if merged. A tombstoned preset is a valid value: the card then schedules with the Default preset.
- **Replay:** **Yes** - full replay of every card on which the write won (the addressed card if not merged, else its survivor).
- **Example:** [`card.preset_set.json`](examples/ops/card.preset_set.json)

### `card.merged`

- **Emitter:** server only.
- **Entity:** `card`; `entity_id` = `loser_id`.
- **Payload:** [`ops/card.merged.schema.json`](ops/card.merged.schema.json)
- **Server rejects:** Always `server_only` when pushed.
- **Conflict rule:** As `note.merged`: never loses; no-op if the loser already has `merged_into_id`; a merged survivor is resolved to its target.
- **Apply:** SYNC.md 8.2: forward every clocked register of the loser except `deleted` (`suspended`, `buried_until`, `flags`, `preset_id`) to the survivor by the clock rule; set the loser's `deleted_at` = wall(hlc) only if NULL; set `merged_into_id`; re-point cards merged into the loser. The loser's cache columns are no longer maintained.
- **Replay:** **Yes** - full replay of the survivor: its replay set now includes the loser's reviews.
- **Example:** [`card.merged.json`](examples/ops/card.merged.json)

## `review_log`

### `review.logged`

- **Emitter:** client (any replica).
- **Entity:** `review_log`; `entity_id` = `id`.
- **Payload:** [`ops/review.logged.schema.json`](ops/review.logged.schema.json)
- **Server rejects:** `unknown_reference`: `card_id` does not exist on the server. `server_only`: `import_source` or `replay_hlc` is non-null in a pushed op. `clock_ahead`: `reviewed_at` is more than 24 h after the server's clock. `clock_behind`: `reviewed_at` is before 2020-01-01 (`1577836800000`) (SYNC.md 3.4). There is no check against the card's `created_at`. Not checked for server-minted imports. A device re-mints a clock-rejected review, and a review rejected `unknown_reference` because its card was itself created with a wrong clock, with a corrected `reviewed_at` and a new `id` (SYNC.md 10.4).
- **Conflict rule:** Never conflicts. A `review_log.id` that already exists makes the op a no-op even if its content differs (first write wins). The op is still accepted and logged.
- **Apply:** Insert one row: `device_id` = the op's `device_id`; `hlc` = `replay_hlc` if given, else the op's hlc; `undone_at`, `undone_hlc` NULL; omitted nullable properties NULL. Inserted also when the card or its note is tombstoned or merged; `card_id` is stored as given and never rewritten.
- **Replay:** Only for `review_type` scheduled, learning, manual (drill and cram never touch a cache). Target card T = the card, or its survivor if merged. If (T has no `last_review_id`, or the new row sorts after T's last folded review in `(reviewed_at, hlc, id)` order (SYNC.md 9.1)) **and** `T.weights_version` equals the effective preset's `weights_version`: fold it in (incremental). Otherwise: **full replay** of T (SYNC.md 9.2) — never fold into a cache that a `fsrs_params.published` has made stale. A row with `import_source = 'jpdb'` is folded by the import-graduation rule (SYNC.md 9.5): a pass at least one day after the previous rated review graduates a (re)learning card to `review`; native rows are folded as before.
- **Example:** [`review.logged.import.json`](examples/ops/review.logged.import.json), [`review.logged.json`](examples/ops/review.logged.json), [`review.logged.manual.json`](examples/ops/review.logged.manual.json)

### `review.undone`

- **Emitter:** client (any replica).
- **Entity:** `review_log`; `entity_id` = `review_id`.
- **Payload:** [`ops/review.undone.schema.json`](ops/review.undone.schema.json)
- **Server rejects:** `unknown_reference`: review does not exist on the server.
- **Conflict rule:** **Earliest undo wins**: applied iff `undone_hlc IS NULL OR op.hlc < undone_hlc`; otherwise a no-op (still accepted and logged). There is no op that clears an undo.
- **Apply:** When applied: `undone_hlc` = op hlc, `undone_at` = wall(hlc). On a device, an undo for a review id it does not have is a no-op.
- **Replay:** **Yes**, but only on the transition not-undone -> undone of a schedule-feeding review: full replay of the target card. Lowering `undone_hlc` of an already undone review changes no replay set.
- **Example:** [`review.undone.json`](examples/ops/review.undone.json)

## `word_status`

### `word.status_set`

- **Emitter:** client (any replica).
- **Entity:** `word_status`; `entity_id` = `entry_seq`.
- **Payload:** [`ops/word.status_set.schema.json`](ops/word.status_set.schema.json)
- **Server rejects:** None beyond the common checks (`entry_seq` is a soft reference). `server_only`: `origin = jpdb_import` in a pushed op.
- **Conflict rule:** One register per entry (`override_status` + `override_origin` at `override_hlc`): applied iff `override_hlc IS NULL OR op.hlc > override_hlc`. Competes with `word.status_cleared`.
- **Apply:** Upsert the row (a new row starts with `auto_status` NULL). When applied: `override_status` = `status`, `override_origin` = `origin`, `override_hlc` = hlc, and in the same statement `status = coalesce(override_status, auto_status, 'new')`.
- **Replay:** None. Word status does not change any card's schedule; `never_forget` and `blacklisted` are honoured by the queue builder.
- **Example:** [`word.status_set.json`](examples/ops/word.status_set.json)

### `word.status_cleared`

- **Emitter:** client (any replica).
- **Entity:** `word_status`; `entity_id` = `entry_seq`.
- **Payload:** [`ops/word.status_cleared.schema.json`](ops/word.status_cleared.schema.json)
- **Server rejects:** None beyond the common checks.
- **Conflict rule:** Same register as `word.status_set`, value NULL: applied iff `override_hlc IS NULL OR op.hlc > override_hlc`.
- **Apply:** Upsert the row. When applied: `override_status` = NULL, `override_origin` = NULL, `override_hlc` = hlc (kept, so older sets arriving later lose), `status = coalesce(auto_status, 'new')`. The row is never deleted.
- **Replay:** None.
- **Example:** [`word.status_cleared.json`](examples/ops/word.status_cleared.json)

## `deck`

### `deck.created`

- **Emitter:** client for `type` user and smart; server only for media and list.
- **Entity:** `deck`; `entity_id` = `id`.
- **Payload:** [`ops/deck.created.schema.json`](ops/deck.created.schema.json)
- **Server rejects:** `server_only`: `type` is media or list in a pushed op. `invalid_value`: `type = media` and `id <> media_id`.
- **Conflict rule:** Creation rule (SYNC.md 7.2): if the id already exists the op is a no-op, whatever the existing row holds. Never conflicts otherwise. User and smart decks have no natural key and cannot collide. Media and list decks are created only by the server, under the op-apply lock, at most one live deck per `media_id` / `list_key`.
- **Apply:** Insert with `content_version` 0. Keys of `settings` whose value is null, `""`, `[]` or `{}` are dropped. Clocks: `name`; `priority` iff non-zero; `enabled` iff false; `new_cards_per_day`, `reviews_per_day`, `query` iff non-null; `settings.<key>` per kept key.
- **Replay:** None.
- **Example:** [`deck.created.json`](examples/ops/deck.created.json), [`deck.created.media.json`](examples/ops/deck.created.media.json), [`deck.created.smart.json`](examples/ops/deck.created.smart.json)

### `deck.updated`

- **Emitter:** client (any replica).
- **Entity:** `deck`; `entity_id` = `id`.
- **Payload:** [`ops/deck.updated.schema.json`](ops/deck.updated.schema.json)
- **Server rejects:** `unknown_reference`: deck does not exist. `invalid_value`: `set.query` on a deck that is not smart.
- **Conflict rule:** Register write by the clock rule (SYNC.md 7.1): applied iff the op's hlc is greater than the register's stored clock; forwarded to the survivor if the row is a merge loser (8.3). Registers: `name`, `priority`, `enabled`, `new_cards_per_day`, `reviews_per_day`, `query`, `settings.<key>` (one per key of `set.settings`).
- **Apply:** For each register in `set`: clock rule. `settings.<key>` = null removes the key and keeps the clock.
- **Replay:** None.
- **Example:** [`deck.updated.json`](examples/ops/deck.updated.json)

### `deck.deleted`

- **Emitter:** client (any replica).
- **Entity:** `deck`; `entity_id` = `id`.
- **Payload:** [`ops/deck.deleted.schema.json`](ops/deck.deleted.schema.json)
- **Server rejects:** `unknown_reference`: deck does not exist. `invalid_target`: a pushed op addresses a media deck (delete the media instead; the server then emits this op itself with `caused_by_op_id`).
- **Conflict rule:** Register `deleted` by the clock rule: `deleted_at` = wall time of the delete op with the highest hlc. Permanent: no op ever clears `deleted_at`; later and concurrent register writes still apply to the tombstone.
- **Apply:** Write register `deleted`. `deck_item` rows are kept and hidden with the deck. Deleting a list deck is how a list is disabled; enabling it again creates a new deck with a new id.
- **Replay:** None.
- **Example:** [`deck.deleted.cascade.json`](examples/ops/deck.deleted.cascade.json), [`deck.deleted.json`](examples/ops/deck.deleted.json)

### `deck.content_published`

- **Emitter:** server only.
- **Entity:** `deck`; `entity_id` = `deck_id`.
- **Payload:** [`ops/deck.content_published.schema.json`](ops/deck.content_published.schema.json)
- **Server rejects:** Always `server_only` when pushed.
- **Conflict rule:** Higher version wins: applied iff `content_version` > the stored `content_version`. Emitted only by the server, with strictly increasing versions per deck.
- **Apply:** Set `content_version`, `content_blob_hash`; `updated_at` = max(`updated_at`, wall(hlc)). A device then (outside the op transaction, any time later) downloads the blob, replaces all `deck_item` rows of that deck in one transaction and sets `installed_content_version`; until then it keeps the old items. Snapshot rows have `hlc` NULL.
- **Replay:** None.
- **Example:** [`deck.content_published.json`](examples/ops/deck.content_published.json)

## `deck_item`

### `deck.item_put`

- **Emitter:** client (any replica).
- **Entity:** `deck_item`; `entity_id` = `deck_id` + `|` + `item_key`.
- **Payload:** [`ops/deck.item_put.schema.json`](ops/deck.item_put.schema.json)
- **Server rejects:** `unknown_reference`: deck does not exist, or the key is `n:<id>` and the note does not exist. `invalid_target`: the deck's `type` is not user.
- **Conflict rule:** Whole-row last-writer-wins on `deck_item.hlc`, shared with `deck.item_removed`: applied iff no row exists or the op's hlc > the row's `hlc`. Identity is `(deck_id, item_key)`, so two devices adding the same thing get one row.
- **Apply:** When applied, the op determines every column: `entry_seq` / `note_id` from the key, `position`, `occurrence_count` = 0, `added_at` = wall(hlc), `deleted_at` = NULL, `hlc`. Applied to tombstoned decks too. An `n:` key whose note is, or later becomes, a merge loser is never rewritten; readers resolve it (SYNC.md 8.5).
- **Replay:** None.
- **Example:** [`deck.item_put.json`](examples/ops/deck.item_put.json)

### `deck.item_removed`

- **Emitter:** client (any replica).
- **Entity:** `deck_item`; `entity_id` = `deck_id` + `|` + `item_key`.
- **Payload:** [`ops/deck.item_removed.schema.json`](ops/deck.item_removed.schema.json)
- **Server rejects:** `unknown_reference`: deck does not exist, or the key is `n:<id>` and the note does not exist. `invalid_target`: the deck's `type` is not user.
- **Conflict rule:** Same register as `deck.item_put`. Reversible: a later put brings the item back.
- **Client duty:** when the user removes an item, mint one `deck.item_removed` for **every** live key of the deck with the same effective identity (SYNC.md 8.5: `n:<loser>` keys and the `e:<seq>` key of the same word), in one transaction; otherwise the next equivalent key surfaces.
- **Apply:** When applied, the op determines every column: `position` = 0, `occurrence_count` = 0, `added_at` = `deleted_at` = wall(hlc), `hlc`. If no row exists, a tombstone row is inserted, so that an older put arriving later loses.
- **Replay:** None.
- **Example:** [`deck.item_removed.json`](examples/ops/deck.item_removed.json)

## `confusable`

### `confusable.suggested`

- **Emitter:** server only.
- **Entity:** `confusable`; `entity_id` = `note_a_id` + `|` + `note_b_id` + `|` + `reason`.
- **Payload:** [`ops/confusable.suggested.schema.json`](ops/confusable.suggested.schema.json)
- **Server rejects:** Always `server_only` when pushed.
- **Conflict rule:** Rank, then clock: compare `(rank(status), hlc)` with the stored pair, where rank(suggested) = 0 and rank(confirmed) = rank(rejected) = 1; applied iff strictly greater or no row exists. A suggestion therefore never replaces a user decision, whatever the clocks.
- **Apply:** Upsert the row keyed `(note_a_id, note_b_id, reason)`. Always: `created_at` = min(`created_at`, wall(hlc)). When applied: `status`, `hlc`, `updated_at` = wall(hlc). The server suggests only pairs of live, unmerged notes and never re-suggests an existing row.
- **Replay:** None.
- **Example:** [`confusable.suggested.json`](examples/ops/confusable.suggested.json)

### `confusable.confirmed`

- **Emitter:** client (any replica).
- **Entity:** `confusable`; `entity_id` = `note_a_id` + `|` + `note_b_id` + `|` + `reason`.
- **Payload:** [`ops/confusable.confirmed.schema.json`](ops/confusable.confirmed.schema.json)
- **Server rejects:** `unknown_reference`: either note does not exist. `invalid_value`: `note_a_id >= note_b_id`.
- **Conflict rule:** Rank, then clock, as `confusable.suggested`: beats any suggestion; between user decisions the higher hlc wins.
- **Apply:** As `confusable.suggested`, with status `confirmed`. Creates the row if nobody suggested the pair. Ids are stored as given even if a note is a merge loser; readers resolve them (SYNC.md 8.5).
- **Replay:** None.
- **Example:** [`confusable.confirmed.json`](examples/ops/confusable.confirmed.json)

### `confusable.rejected`

- **Emitter:** client (any replica).
- **Entity:** `confusable`; `entity_id` = `note_a_id` + `|` + `note_b_id` + `|` + `reason`.
- **Payload:** [`ops/confusable.rejected.schema.json`](ops/confusable.rejected.schema.json)
- **Server rejects:** `unknown_reference`: either note does not exist. `invalid_value`: `note_a_id >= note_b_id`.
- **Conflict rule:** Rank, then clock, as `confusable.confirmed`.
- **Apply:** As `confusable.confirmed`, with status `rejected`. The row stays so the pair is not suggested again.
- **Replay:** None.
- **Example:** [`confusable.rejected.json`](examples/ops/confusable.rejected.json)

## `grammar_point`

### `grammar.created`

- **Emitter:** client (any replica).
- **Entity:** `grammar_point`; `entity_id` = `id`.
- **Payload:** [`ops/grammar.created.schema.json`](ops/grammar.created.schema.json)
- **Server rejects:** None beyond the common checks.
- **Conflict rule:** Creation rule (SYNC.md 7.2): if the id already exists the op is a no-op, whatever the existing row holds. Never conflicts otherwise. Grammar points have no natural key: two devices creating the same pattern get two points (the user deletes one). Seed stubs are created by the server with fixed ids, so a re-run of the seeding is a no-op.
- **Apply:** Insert. Clocks: `title`, and each other register iff its value differs from the column default (`''`, NULL, `[]`, false).
- **Replay:** None.
- **Example:** [`grammar.created.json`](examples/ops/grammar.created.json)

### `grammar.updated`

- **Emitter:** client (any replica).
- **Entity:** `grammar_point`; `entity_id` = `id`.
- **Payload:** [`ops/grammar.updated.schema.json`](ops/grammar.updated.schema.json)
- **Server rejects:** `unknown_reference`: grammar point does not exist.
- **Conflict rule:** Register write by the clock rule (SYNC.md 7.1): applied iff the op's hlc is greater than the register's stored clock; forwarded to the survivor if the row is a merge loser (8.3). Registers: `title`, `pattern`, `meaning`, `explanation`, `notes`, `jlpt`, `detection_rules` (whole array), `related_ids` (whole array), `links` (whole array), `is_stub`.
- **Apply:** For each register in `set`: clock rule. A winning `detection_rules` write makes the server enqueue re-detection (a worker job, not an op).
- **Replay:** None.
- **Example:** [`grammar.updated.json`](examples/ops/grammar.updated.json)

### `grammar.deleted`

- **Emitter:** client (any replica).
- **Entity:** `grammar_point`; `entity_id` = `id`.
- **Payload:** [`ops/grammar.deleted.schema.json`](ops/grammar.deleted.schema.json)
- **Server rejects:** `unknown_reference`: grammar point does not exist.
- **Conflict rule:** Register `deleted` by the clock rule: `deleted_at` = wall time of the delete op with the highest hlc. Permanent: no op ever clears `deleted_at`; later and concurrent register writes still apply to the tombstone.
- **Apply:** Write register `deleted`. A grammar note studying the point and manual spans pointing at it stay; readers hide spans of deleted points.
- **Replay:** None.
- **Example:** [`grammar.deleted.json`](examples/ops/grammar.deleted.json)

## `sentence_grammar_span`

### `grammar.span_put`

- **Emitter:** client (any replica).
- **Entity:** `sentence_grammar_span`; `entity_id` = `sentence_id` + `|` + `grammar_point_id` + `|` + `start_offset` + `|` + `end_offset` + `|` + the literal `manual`.
- **Payload:** [`ops/grammar.span_put.schema.json`](ops/grammar.span_put.schema.json)
- **Server rejects:** `unknown_reference`: grammar point does not exist. `invalid_value`: `end_offset <= start_offset`. The sentence is never checked: a span for an unknown or purged sentence is accepted.
- **Conflict rule:** Whole-row last-writer-wins on `sentence_grammar_span.hlc`, shared with `grammar.span_removed`: applied iff no manual row with that key exists or the op's hlc > the row's `hlc`. Detected rows (same span, `origin = 'detected'`) are different rows and are never touched by ops.
- **Apply:** When applied, the op determines every column of the manual row: `origin` = manual, `media_id`, `rule_index` = NULL, `created_at` = wall(hlc), `deleted_at` = NULL, `hlc`.
- **Replay:** None.
- **Example:** [`grammar.span_put.json`](examples/ops/grammar.span_put.json)

### `grammar.span_removed`

- **Emitter:** client (any replica).
- **Entity:** `sentence_grammar_span`; `entity_id` = `sentence_id` + `|` + `grammar_point_id` + `|` + `start_offset` + `|` + `end_offset` + `|` + the literal `manual`.
- **Payload:** [`ops/grammar.span_removed.schema.json`](ops/grammar.span_removed.schema.json)
- **Server rejects:** `unknown_reference`: grammar point does not exist. `invalid_value`: `end_offset <= start_offset`.
- **Conflict rule:** Same register as `grammar.span_put`. Reversible.
- **Apply:** When applied, the op determines every column: `media_id` = NULL, `rule_index` = NULL, `created_at` = `deleted_at` = wall(hlc), `hlc`. Inserts a tombstone row if none exists.
- **Replay:** None.
- **Example:** [`grammar.span_removed.json`](examples/ops/grammar.span_removed.json)

## `settings`

### `settings.set`

- **Emitter:** client (any replica).
- **Entity:** `settings`; `entity_id` = `key`.
- **Payload:** [`ops/settings.set.schema.json`](ops/settings.set.schema.json)
- **Server rejects:** None beyond the common checks. Unknown keys are accepted and stored (forward compatibility); values are not type-checked by the server.
- **Conflict rule:** One register per key: applied iff no row exists or the op's hlc > the row's `hlc`.
- **Apply:** Upsert `value`, `hlc`, `updated_at` = wall(hlc). JSON null is stored as JSON null and means 'use the default'; the row is never deleted.
- **Replay:** None. `scheduler.*` settings change queue building and day boundaries, never a card's cache.
- **Example:** [`settings.set.json`](examples/ops/settings.set.json)

## `fsrs_params`

### `fsrs_params.created`

- **Emitter:** client (any replica).
- **Entity:** `fsrs_params`; `entity_id` = `id`.
- **Payload:** [`ops/fsrs_params.created.schema.json`](ops/fsrs_params.created.schema.json)
- **Server rejects:** None beyond the common checks.
- **Conflict rule:** Creation rule (SYNC.md 7.2): if the id already exists the op is a no-op, whatever the existing row holds. Never conflicts otherwise.
- **Apply:** Insert with `weights`, `fsrs_version`, `optimized_at`, `optimizer_stats` NULL and `weights_version` 0. Clocks: `name`, and each other register iff its value differs from the column default by JSON equality (0.9, 36500, [60,600], [600], true, [1,1,1,1,1,1,1]).
- **Replay:** None.
- **Example:** [`fsrs_params.created.json`](examples/ops/fsrs_params.created.json)

### `fsrs_params.updated`

- **Emitter:** client (any replica).
- **Entity:** `fsrs_params`; `entity_id` = `id`.
- **Payload:** [`ops/fsrs_params.updated.schema.json`](ops/fsrs_params.updated.schema.json)
- **Server rejects:** `unknown_reference`: preset does not exist.
- **Conflict rule:** Register write by the clock rule (SYNC.md 7.1): applied iff the op's hlc is greater than the register's stored clock; forwarded to the survivor if the row is a merge loser (8.3). Registers: `name`, `desired_retention`, `maximum_interval_days`, `learning_steps_s`, `relearning_steps_s`, `fuzz`, `easy_days`. The Default preset can be updated.
- **Apply:** For each register in `set`: clock rule.
- **Replay:** **Yes** - if any register other than `name` won: full replay of every live card whose effective preset is this one (SYNC.md 9.2).
- **Example:** [`fsrs_params.updated.json`](examples/ops/fsrs_params.updated.json)

### `fsrs_params.deleted`

- **Emitter:** client (any replica).
- **Entity:** `fsrs_params`; `entity_id` = `id`.
- **Payload:** [`ops/fsrs_params.deleted.schema.json`](ops/fsrs_params.deleted.schema.json)
- **Server rejects:** `unknown_reference`: preset does not exist. `immutable`: the Default preset.
- **Conflict rule:** Register `deleted` by the clock rule: `deleted_at` = wall time of the delete op with the highest hlc. Permanent: no op ever clears `deleted_at`; later and concurrent register writes still apply to the tombstone.
- **Apply:** Write register `deleted`. Cards keep their `preset_id`; their effective preset becomes Default.
- **Replay:** **Yes** - on the first transition to tombstoned: full replay of every live card with this `preset_id`.
- **Example:** [`fsrs_params.deleted.json`](examples/ops/fsrs_params.deleted.json)

### `fsrs_params.published`

- **Emitter:** server only.
- **Entity:** `fsrs_params`; `entity_id` = `id`.
- **Payload:** [`ops/fsrs_params.published.schema.json`](ops/fsrs_params.published.schema.json)
- **Server rejects:** Always `server_only` when pushed (a pushed op with out-of-range weights gets `schema_invalid` first, 5.3 order).
- **Weight bounds (C-1):** `fsrs_version` is `"6"` and `weights` has exactly 21 numbers, each within the inclusive per-index bounds of py-fsrs 6.3.2 (`LOWER_BOUNDS_PARAMETERS` / `UPPER_BOUNDS_PARAMETERS`; the schema's `prefixItems`). The server MUST validate an op it mints (optimizer output) against the schema before inserting it; an optimizer result outside the bounds is not published (the job fails or clamps before minting — T-worker's choice). **C-16:** the only minter is the server's `fsrs-params-publish` runner, from an `fsrs-optimize` job's hand-off and only when the new weights have a strictly lower held-out log loss than the stored ones (`contracts/db/FSRS_PUBLISH.md`); it sets `optimizer_stats` to `{review_count, log_loss, rmse_bins, log_loss_before, holdout_review_count}` (the last two are additional keys of the open object; readers treat them as optional). A device that pulls one that fails anyway stops at it (SYNC.md 6.1). The bounds guarantee initial stability > 0 and decay w[20] ≥ 0.1; finite, positive schedules follow only **together with the core's clamps** (T006: stability ≥ `STABILITY_MIN`, difficulty in [1, 10], interval ≤ `maximum_interval_days`).
- **Conflict rule:** Higher version wins: applied iff `weights_version` > the stored one. The server increments it under the op-apply lock, so versions are unique per preset.
- **Apply:** Set `weights_version`, `fsrs_version`, `weights`, `optimized_at`, `optimizer_stats`; `updated_at` = max(`updated_at`, wall(hlc)). Applied to tombstoned presets too.
- **Replay:** **Yes** - every card whose effective preset is this one now has `card.weights_version` <> the preset's and must be replayed before its cache is next read or folded into (may be done lazily; the mismatch is the durable marker; SYNC.md 9.2-9.3).
- **Example:** [`fsrs_params.published.json`](examples/ops/fsrs_params.published.json)

## `media`

### `media.created`

- **Emitter:** server only.
- **Entity:** `media`; `entity_id` = `id`.
- **Payload:** [`ops/media.created.schema.json`](ops/media.created.schema.json)
- **Server rejects:** Always `server_only` when pushed (media are created by import jobs).
- **Conflict rule:** Creation rule (SYNC.md 7.2): if the id already exists the op is a no-op, whatever the existing row holds. Never conflicts otherwise. Re-importing a jpdb deck whose `jpdb_id` already has a live media emits no `media.created`; the job updates that media.
- **Apply:** Insert with `parse_status = 'pending'`, `content_version` 0. Clocks: `title`, `kind`, and `cover_blob_hash` iff non-null. The server emits the media's `deck.created` (id = media id) right after, with `caused_by_op_id`.
- **Replay:** None.
- **Example:** [`media.created.json`](examples/ops/media.created.json)

### `media.updated`

- **Emitter:** client (any replica).
- **Entity:** `media`; `entity_id` = `id`.
- **Payload:** [`ops/media.updated.schema.json`](ops/media.updated.schema.json)
- **Server rejects:** `unknown_reference`: media does not exist.
- **Conflict rule:** Register write by the clock rule (SYNC.md 7.1): applied iff the op's hlc is greater than the register's stored clock; forwarded to the survivor if the row is a merge loser (8.3). Registers: `title`, `kind`, `cover_blob_hash`.
- **Apply:** For each register in `set`: clock rule. The media deck's `name` is a separate register and is not changed by this op.
- **Replay:** None.
- **Example:** [`media.updated.json`](examples/ops/media.updated.json)

### `media.deleted`

- **Emitter:** client (any replica).
- **Entity:** `media`; `entity_id` = `id`.
- **Payload:** [`ops/media.deleted.schema.json`](ops/media.deleted.schema.json)
- **Server rejects:** `unknown_reference`: media does not exist.
- **Conflict rule:** Register `deleted` by the clock rule: `deleted_at` = wall time of the delete op with the highest hlc. Permanent: no op ever clears `deleted_at`; later and concurrent register writes still apply to the tombstone.
- **Apply:** Write register `deleted`. On the first transition to tombstoned: the server emits `deck.deleted` for the media deck (`caused_by_op_id` = this op) and hard-deletes the media's segments, sentences, occurrences and detected spans; a device deletes the same installed rows and sets `installed_content_version` to 0. Manual spans, notes and reviews are untouched and may dangle.
- **Replay:** None.
- **Example:** [`media.deleted.json`](examples/ops/media.deleted.json)

### `media.content_published`

- **Emitter:** server only.
- **Entity:** `media`; `entity_id` = `media_id`.
- **Payload:** [`ops/media.content_published.schema.json`](ops/media.content_published.schema.json)
- **Server rejects:** Always `server_only` when pushed.
- **Conflict rule:** Applied iff `content_version` >= the stored one. With an equal version the op carries a newer parse status and overwrites it; this relies on seq order, in which every replica receives server-emitted ops (SYNC.md 7.6).
- **Apply:** Set `parse_status`, `parse_error`, `content_version`, `content_blob_hash`; `updated_at` = max(`updated_at`, wall(hlc)). Ignored for the snapshot (but still applied to the row) if the media is tombstoned. A device that has the media installed downloads the new snapshot later, replaces the bulk rows in one transaction and sets `installed_content_version`.
- **Replay:** None.
- **Example:** [`media.content_published.json`](examples/ops/media.content_published.json)

## `parse_override`

### `parse_override.put`

- **Emitter:** client (any replica). Devices skip it.
- **Entity:** `parse_override`; `entity_id` = `id`.
- **Payload:** [`ops/parse_override.put.schema.json`](ops/parse_override.put.schema.json)
- **Server rejects:** `invalid_value`: token surfaces do not concatenate to `match_text`.
- **Conflict rule:** Whole-row last-writer-wins on `parse_override.hlc`: values are applied iff no row exists or the op's hlc > the row's `hlc`. A put never clears `deleted_at`.
- **Apply:** Server: upsert `media_id`, `sentence_id`, `match_text`, `tokens`, `note`, `hlc`, `updated_at` = wall(hlc); `created_at` = min(`created_at`, wall(hlc)). Takes effect at the next parse. **Devices do nothing** (no such table) and just advance their cursor.
- **Replay:** None.
- **Example:** [`parse_override.put.json`](examples/ops/parse_override.put.json)

### `parse_override.deleted`

- **Emitter:** client (any replica). Devices skip it.
- **Entity:** `parse_override`; `entity_id` = `id`.
- **Payload:** [`ops/parse_override.deleted.schema.json`](ops/parse_override.deleted.schema.json)
- **Server rejects:** `unknown_reference`: parse override does not exist.
- **Conflict rule:** Sticky: always applied. `deleted_at` = min(existing `deleted_at`, wall(hlc)); permanent.
- **Apply:** Server: set `deleted_at` as above; if the op's hlc > `hlc`: `hlc` = op hlc, `updated_at` = wall(hlc). **Devices do nothing.**
- **Replay:** None.
- **Example:** [`parse_override.deleted.json`](examples/ops/parse_override.deleted.json)
