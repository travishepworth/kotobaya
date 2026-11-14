# Dictionary device package and diff format (C-3)

Frozen interface. Producer: the worker's `dict-refresh` job. Consumers: the server
(`GET /dict/release`, `GET /blobs/{hash}`) and the device installer (Android). Nobody needs to read
worker code; where the worker differs from this document, this document wins.

Key words MUST / MUST NOT / SHOULD / MAY are normative.

## 1. Artifacts

A release (`ref.dict_release` row, id `R`) carries a list of **artifacts** in
`ref.dict_release.artifacts`, published unchanged as `DictRelease.artifacts`:

```json
{"kind": "full", "scope": "top60k", "from_release": null, "format_version": 1,
 "blob_hash": "<64 lowercase hex>", "size_bytes": 96468992, "uncompressed_size_bytes": 412090368}
```

| Field | Meaning |
|---|---|
| `kind` | `full` = a whole package file (section 2); `diff` = a patch from an older release (section 3). |
| `scope` | `top60k` or `all` (section 2.3). |
| `from_release` | `diff`: the release id the diff upgrades from (`< R`). `full`: `null`. |
| `format_version` | `full`: the package's `PRAGMA user_version`. `diff`: the diff envelope's `format_version`. Currently `1` for both. |
| `blob_hash` | Lowercase hex SHA-256 of the **compressed** bytes, i.e. exactly the bytes stored in object storage and served by `GET /blobs/{hash}`. |
| `size_bytes` | Length of those compressed bytes. |
| `uncompressed_size_bytes` | Length after zstd decompression (the SQLite file, or the diff JSON). Lets a device check free space before downloading. |

Rules for the list of one release `R`:

1. Every artifact describes release `R` (a `full` is the file of `R`; a `diff` produces the file of
   `R`). There is no `to_release` field: it is always the release's `id`.
2. At most one artifact per `(kind, scope, from_release, format_version)`.
3. For every scope that appears at all there is a `full` artifact of that scope.
4. A `diff` exists only if its `from_release` file was built for the same scope and the same
   format generation (`user_version` 1 ↔ diff `format_version` 1). The worker builds a diff from
   the immediately preceding release; it MAY add diffs from older releases. Diffs are never
   chained by the device (section 5).
5. **Diff base = the published file, nothing else.** The source file of a diff MUST be the
   decompressed bytes of the `full` artifact that `ref.dict_release.artifacts` of `from_release`
   lists for the same `scope` and `format_version`. The producer obtains it either by downloading
   that blob (object key = its `blob_hash`) or from a local copy, and in both cases MUST first
   check that SHA-256 of the compressed bytes equals that `blob_hash`. It MUST NOT rebuild the
   source file from `ref.*` (which already holds newer content). If the published `full` is
   missing, unreadable or fails the check, the producer emits **no** diff for that
   `from_release`; devices then download the `full` (section 5).
6. `[]` is valid (release loaded, no device builds yet); the device then keeps what it has.

## 2. Full package (`kind = full`)

### 2.1 Bytes

- The blob is **zstd** (RFC 8878) compression of one SQLite 3 database file. One or more zstd
  frames, no external dictionary; decoders MUST accept concatenated frames. Compression level and
  frame checksum are not part of the contract.
- The decompressed file is a SQLite database in rollback-journal mode (`journal_mode = DELETE`,
  no `-wal`/`-journal` sidecar), already `VACUUM`ed. Page size is unspecified.
- Content type stored in `app.blob.content_type`: `application/zstd`.

### 2.2 Schema

- Exactly the tables and indexes of `sqldelight/ref/kotobaya/db/ref/Dict.sq` (with the
  SQLDelight `AS kotlin.*` annotations removed), no others, and `PRAGMA user_version = 1`.
  A future incompatible schema uses a new `user_version` (section 6).
- Column values follow `SCHEMA.md` 5.1: booleans `0`/`1`, JSON as `TEXT`, `built_at` epoch ms.
- "Read-only" is not a flag inside the file. It is an open-mode rule: devices open the installed
  file with `SQLITE_OPEN_READONLY` (or `mode=ro`) for every query. Only the install/update
  procedure of section 5 writes, and only to a staging copy.

### 2.3 Content and integrity

1. `dict_release` holds **exactly one row**: `id = R`, `version`, `built_at` and `sources` as in
   `ref.dict_release`, and `scope` = the artifact's scope.
2. Entry set:
   - `all`: every `ref.dict_entry` row, retired ones included (`retired = 1`).
   - `top60k`: entries with `retired_release_id IS NULL AND freq_rank <= 60000`, plus every
     *referenced* seq that exists in `ref.dict_entry` (retired or not, any `freq_rank`).
     Referenced = chosen by the job; it MUST include every `entry_seq` of a live `app.note` and
     of every `app.word_status` row at build time, SHOULD include every `entry_seq` of a live
     `app.deck_item`, and MAY include more. The set is fixed at build time: an entry referenced
     only after the build (e.g. a note added later) and outside the top 60k is missing on the
     device until the next release includes it. Readers already tolerate this (soft references).
3. `kanji` and `freq_list` are always complete (every `ref.*` row), whatever the scope.
   `ref.vocab_list*` (C-21) are server-only and never in a package; devices get list-deck
   membership only through deck-item archives (`VOCAB_LISTS.md`).
4. Every other child table (`dict_entry_form`, `dict_entry_gloss`, `entry_kanji`, `entry_freq`,
   `entry_pitch`, `example_sentence_entry`) holds exactly the `ref.*` rows whose `seq` is in the
   entry set; `example_sentence` holds exactly the sentences linked from those
   `example_sentence_entry` rows. `dict_entry_gloss.gloss_lower` = `gloss` lowercased.
5. **No dangling rows**: `PRAGMA foreign_key_check` returns nothing.
6. **Nothing dropped**: for each table the row count equals the count of the corresponding
   `ref.*` selection; the producer MUST fail the build otherwise.
7. Rows are inserted in primary-key order. Byte-for-byte reproducibility is not guaranteed and
   nobody may rely on it; equality of two packages is defined by table contents only.

## 3. Diff (`kind = diff`, format `kotobaya-dict-diff` v1)

### 3.1 Bytes

zstd (as 2.1) of one UTF-8 JSON document (RFC 8259, no BOM). Content type
`application/zstd`. The producer writes exactly the keys below; a reader MUST ignore unknown
top-level keys (section 6) but MUST reject unknown or missing tables (3.2).

### 3.2 Envelope

```json
{"format": "kotobaya-dict-diff", "format_version": 1,
 "from_release": 2, "to_release": 3, "scope": "top60k",
 "tables": {"<table>": {"columns": ["..."], "pk": ["..."],
                        "upsert": [[<row values in column order>], ...],
                        "delete": [[<pk values in pk order>], ...]}, ...},
 "row_counts": {"<table>": <rows after applying>, ...}}
```

| Key | Rule |
|---|---|
| `format` | Exactly `"kotobaya-dict-diff"`. |
| `format_version` | Integer `1`. Equals the manifest artifact's `format_version`. |
| `from_release` | Id of the release the diff applies to. Equals the artifact's `from_release`. |
| `to_release` | Id of the release produced. Equals the `DictRelease.id` that lists the artifact. |
| `scope` | Scope of both the source and the target file; equals the artifact's `scope`. |
| `tables` | Object with **exactly** the 11 `Dict.sq` tables as keys (empty lists when unchanged). |
| `row_counts` | Object with exactly the 11 tables as keys: `count(*)` of each table in the target file. |
| `table_digests` | Object with exactly the 11 tables as keys: the table digest (3.5) of each table in the target file, lowercase hex. Required in v1. |

### 3.3 Per table

- `columns`: every column of the table in `Dict.sq` declaration order. `pk`: the primary-key
  columns in declaration order of the `PRIMARY KEY` (below). A reader MUST reject the diff if
  either differs from its own schema.

  | Table (canonical order) | `pk` |
  |---|---|
  | `dict_release` | `id` |
  | `dict_entry` | `seq` |
  | `dict_entry_form` | `seq, kind, ord` |
  | `dict_entry_gloss` | `seq, sense_ord, gloss_ord` |
  | `entry_kanji` | `seq, literal` |
  | `freq_list` | `id` |
  | `entry_freq` | `seq, list_id` |
  | `entry_pitch` | `seq, reading` |
  | `kanji` | `literal` |
  | `example_sentence` | `id` |
  | `example_sentence_entry` | `sentence_id, seq` |

  Canonical order is parents before children; it is the apply order (3.4).
- `upsert`: every row present in the target file that is absent from the source file or differs
  from it in any column. Full rows, values in `columns` order.
- `delete`: the primary key of every row present in the source file and absent from the target
  file. Values in `pk` order.
- The two lists of one table never share a primary key. Both are sorted ascending by primary key
  (integers numerically, text by code point); readers MUST NOT depend on the order.
- Values: SQLite `INTEGER` → JSON integer (never a float, never `true`/`false`); `TEXT` → JSON
  string (JSON-holding columns stay strings, e.g. `"[\"v1\"]"`); `NULL` → `null`. The device MUST
  bind integers as INTEGER and strings as TEXT.
- What this produces in practice: an entry entering the scope is upserted with its child rows;
  an entry leaving it is deleted with its child rows; a retired entry is a changed `dict_entry`
  row with `retired = 1` (and, per `SCHEMA.md` 4.5, deletes of its form/gloss rows); the release
  row is always `delete [[from_release]]` + `upsert [[to_release, ...]]` in `dict_release`.

### 3.4 Applying (normative)

On a connection with `PRAGMA foreign_keys = OFF` (set before `BEGIN`; this overrides the
"`foreign_keys = ON` on every connection" rule of `SCHEMA.md` 5 for this one connection only):

1. Check `format`, `format_version` (supported), the 11-table key set and every `columns`/`pk`.
2. Check the file: `PRAGMA user_version = 1`, `dict_release` has exactly one row and it is
   `(from_release, scope)`.
3. `BEGIN`.
4. For each table in **reverse** canonical order: `DELETE FROM t WHERE <pk> = ?` for each `delete`
   entry. A key that matches no row is not an error.
5. For each table in canonical order: `INSERT OR REPLACE INTO t (<columns>) VALUES (...)` for each
   `upsert` row.
6. Verify, still inside the transaction: `count(*)` of every table equals `row_counts`;
   the table digest (3.5) of every table equals `table_digests`; `dict_release` is exactly one
   row `(to_release, scope)`; `PRAGMA foreign_key_check` returns nothing.
7. All checks pass → `COMMIT`. Any check fails or any statement errors → `ROLLBACK`; the diff is
   unusable for this file (section 5 falls back to `full`).

### 3.5 Table digest

The digest of table `t` in a package file is SHA-256 over the concatenation, for every row of
`SELECT <columns> FROM t ORDER BY <pk>` (columns and pk as in 3.3, SQLite default `BINARY`
collation, ascending), of the encoding of each column value in `columns` order:

| `typeof(value)` | Bytes |
|---|---|
| `null` | `0x00` |
| `integer` | `0x01`, then the value as 8-byte big-endian two's-complement int64 |
| `text` | `0x02`, then the UTF-8 byte length as 4-byte big-endian unsigned, then the UTF-8 bytes (no terminator, no normalisation) |

There are no row or table separators (the encoding is self-delimiting). A value of type `real`
or `blob` never occurs in a valid package; a digest computation that meets one fails, and so
does the apply. An empty table digests to SHA-256 of zero bytes
(`e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`). Digests are computed on the
target file by the producer and on the patched copy by the device; they prove the patched file
has the target's exact content, not merely the target's row counts.

## 4. Hashes and storage

- `blob_hash` = SHA-256 over the compressed bytes. The uncompressed bytes are not hashed; they
  are covered transitively, because decompression is deterministic. The only other hashes are
  the per-table content digests of a diff (3.5), which cover table contents, not file bytes.
- Object-storage key = the bare `blob_hash` at the root of the configured bucket (no prefix),
  as `app.blob.hash` defines ("Also the object key") and as the `302` example of
  `GET /blobs/{hash}` shows.
- After the object is durably stored and **before** the artifact appears in
  `ref.dict_release.artifacts`, the producer inserts `app.blob(hash, size_bytes,
  content_type = 'application/zstd', kind = 'snapshot', origin = 'build')`
  (`ON CONFLICT (hash) DO NOTHING`). `GET /blobs/{hash}` serves it like any other blob.
- Blobs are immutable. A blob referenced by the current release's artifacts MUST NOT be deleted.
  Blobs only referenced by older releases MAY be deleted; a device that gets `404` for a hash
  re-reads `GET /dict/release` and restarts its decision (section 5).

## 5. Device install and update

Device state: the installed file (opened read-only), its `dict_release.id`, its `scope` and
`user_version`; the user's chosen scope (setting). **The file's own `dict_release` row is
authoritative** for the installed id and scope. `sync_state.dict_release_id` is only a cache of
it: at app startup (after any interrupted install, step 10) the device reads the row from the
installed file and rewrites `sync_state.dict_release_id` if it differs, or deletes the key if
there is no installed file. Decisions below always use the file's row.

1. `GET /dict/release`. `404` → no release yet; keep the current file (or none). Done.
2. If installed id = `id` and installed scope = chosen scope → done.
3. Candidate artifacts: those with the chosen `scope` and a `format_version` the app supports
   (for `full`: user_version it can read; for `diff`: diff versions it can apply).
4. If a `diff` candidate has `from_release` = installed id, the installed `user_version` matches
   its generation and installed scope = chosen scope, use it; otherwise use the `full` candidate
   with the highest supported `format_version`. No usable candidate → keep the current file and
   surface "dictionary update needs a newer app". Downgrades never happen: an installed id greater
   than `id` (server restored from backup) keeps the installed file.
5. Before downloading, check free space ≥ `size_bytes` + `uncompressed_size_bytes` (+ the size of
   the installed file for a diff, which is applied to a copy).
6. Download with `GET /blobs/{blob_hash}` (follow redirects, `Range` resume allowed). Verify
   length = `size_bytes` and SHA-256 = `blob_hash`; mismatch → delete and retry once, then treat
   as failed.
7. Build a staging file next to the installed one (same directory, fixed name). First delete any
   leftover staging file and its `-journal` (and `-wal`/`-shm`, if present) from an earlier
   attempt, then:
   - `full`: decompress into it; check `user_version` = `format_version`, `dict_release` is one row
     `(id, scope)`, and `PRAGMA foreign_key_check` is empty.
   - `diff`: copy the installed file, decompress the diff, apply it to the copy per 3.4.
8. On success: close the staging connection (no `-journal` may remain), `fsync` the staging file,
   atomically rename it over the installed file, `fsync` the parent directory, reopen read-only,
   then set `sync_state.dict_release_id` from the new file's `dict_release` row.
9. On any failure of a `diff` (verification, rollback, parse error, `404`): discard the staging
   file and its `-journal` (and `-wal`/`-shm`) and go to step 6 with the `full` candidate. A
   failed `full` is discarded the same way and leaves the installed file untouched; retry on the
   next sync with backoff.
10. At startup, before opening the installed file, delete any staging file and its sidecars: an
    install interrupted before the rename never took effect.

The learning database never depends on the swap (all `entry_seq` references are soft), so the
installer may run at any time; readers holding the old file keep working until they reopen.

## 6. Versioning

- Additive only. `Dict.sq` changes that a v1 reader would mis-handle (new table, new column,
  changed meaning) bump `PRAGMA user_version` and the diff `format_version` together; a new index
  alone does not (indexes are rebuilt by `full` installs only, so a new index also needs a
  `user_version` bump if devices must have it).
- During a transition the server MAY list artifacts of several `format_version`s for the same
  scope (rule 1.2 keys on it); older apps keep using the version they understand.
- New optional fields may appear in artifact objects and in the diff envelope; readers ignore
  unknown fields there (but not unknown tables, 3.2).
