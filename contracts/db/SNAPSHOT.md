# Media content and deck-item snapshot archives (C-11)

Frozen interface. Producers: the worker (media import, media parse, jpdb deck import; later the
list-deck `deck-build` job). Consumers: the server's media-publish runner (`MEDIA_PIPELINE.md` 3)
and, optionally, devices. Where code differs from this document, this document wins.
`contracts/sync/SYNC.md` 7.6 ("the blob formats are not part of this contract") points here.

Key words MUST / MUST NOT / SHOULD / MAY are normative.

| File | What |
|---|---|
| `media/content.v1.schema.json` | One JSON Schema per record kind of the media content archive (`$defs`). |
| `media/deck-items.v1.schema.json` | Same for the deck-item archive. |
| `media/validate.py` | Checks an archive (schemas plus every cross-record rule below), a media/deck pair, or a `media-publish` payload with its blobs. No arguments = self-test over `media/examples/` and built-in negative cases; run by `contracts/db/check.sh`. |
| `media/examples/` | Worked examples: `.jsonl` (readable) and the identical `.jsonl.zst` (the real archive bytes). |

## 1. The two archives

| Archive | `format` | Announced by | Rows it carries |
|---|---|---|---|
| Media content | `kotobaya-media-content` | `media.content_published` (`content_blob_hash`, `content_version`) | all `media_segment`, `sentence`, `occurrence` and **detected** `sentence_grammar_span` rows of one media |
| Deck items | `kotobaya-deck-items` | `deck.content_published` (`content_blob_hash`, `content_version`) | all `deck_item` rows of one media or list deck |

An archive is always **complete** for its version (never a diff): installing it replaces every
row of that media or deck. Archives are immutable blobs.

## 2. Bytes (both archives)

1. The blob is **zstd** (RFC 8878) of the archive text: one or more frames, no dictionary;
   decoders MUST accept concatenated frames. Level and frame checksum are not part of the
   contract.
2. The archive text is **JSON Lines**: UTF-8, no BOM; each line is one JSON object (RFC 8259)
   terminated by exactly one `LF` (`0x0A`), the last line included. No empty lines, no `CR`, no
   leading or trailing whitespace on a line. A line is at most 16 MiB of UTF-8.
3. **Line 1 is the header** (`"record": "header"`); every other line is a data record. The
   `record` member names the kind. An unknown `record` value, a second header, or a member that
   appears twice in one object makes the archive invalid.
4. Values: integers are JSON integers (never `1.0`, never `NaN`/`Infinity`); strings are valid
   Unicode scalar values (no lone surrogate escapes) and contain no U+0000 anywhere, including
   inside `tokens` and `location` (`SCHEMA.md` 2.10); JSON columns (`tokens`, `location`) are
   embedded JSON values, **not** JSON-in-a-string; nullable columns are written as `null`, never
   omitted.
5. **Producers** write every member of the record schema (closed schemas, `additionalProperties:
   false`) as compact JSON with members sorted by key (Python: `json.dumps(r, sort_keys=True,
   separators=(",", ":"), ensure_ascii=False)`), and records in the order of 3.2 / 4.2, so that
   identical content yields identical bytes and therefore the same blob hash at the same zstd
   level (a retry re-uploads nothing new). **Consumers** MUST NOT depend on member order or byte
   identity; they compare contents.
6. Hash, storage and serving exactly as `DICT_PACKAGE.md` 4: `blob_hash` = lowercase hex SHA-256
   of the **compressed** bytes = the object key at the bucket root; after the object is durably
   stored the producer inserts `app.blob(hash, size_bytes, content_type = 'application/zstd',
   kind = 'snapshot', origin = 'build')` `ON CONFLICT (hash) DO NOTHING`; served by
   `GET /blobs/{hash}`.
7. Size bounds: compressed ≤ 512 MiB (`media-publish` payload `size_bytes` maximum);
   decompressed ≤ 4 GiB; counts as in the header schemas (≤ 10,000 segments, ≤ 5,000,000
   sentences, ≤ 1,000,000 deck items). Consumers MUST stream (read line by line) rather than
   hold the decompressed text in memory, and MUST reject an archive that exceeds a bound.
8. Readers: Python `zstandard` (worker dependency) or `compression.zstd` (3.14+); Kotlin
   `com.github.luben:zstd-jni` `ZstdInputStream` (already used by the Android dictionary
   installer) plus kotlinx.serialization per line.

## 3. Media content archive (`kotobaya-media-content`, `format_version` 1)

### 3.1 Records

One row per record. Member names and meanings are the column names and comments of
`V1__init.sql`; the table says only what differs.

| `record` | Row | Members | Differences from the table |
|---|---|---|---|
| `header` | — | `format`, `format_version`, `media_id`, `content_version`, `counts {segment, sentence, occurrence, grammar_span}` | `counts` = number of records of each kind that follow. |
| `segment` | `app.media_segment` | `id`, `ordinal`, `kind`, `title`, `sentence_count`, `token_count`, `unmapped_token_count` | no `media_id` (= header). `title` ≤ 300 code points (producers truncate). |
| `sentence` | `app.sentence` | `id`, `segment_id`, `ordinal`, `text`, `tokens`, `origin`, `start_ms`, `end_ms`, `audio_blob_hash`, `image_blob_hash`, `location` | no `media_id` (= header); `unknown_count` is server-only and never in a snapshot (installed as NULL). |
| `occurrence` | `app.occurrence` | `segment_id`, `entry_seq`, `count`, `pos_class`, `sentence_ids` | no `media_id` (= header). |
| `grammar_span` | `app.sentence_grammar_span`, `origin = 'detected'` | `sentence_id`, `grammar_point_id`, `start_offset`, `end_offset`, `rule_index` | installed with `origin = 'detected'`, `media_id` = header, `deleted_at` and `hlc` NULL, `created_at` = install time. Manual spans are never in a snapshot. None are produced until Phase 5 (grammar detection). |

Example header (`media/examples/new-media.content.v1.jsonl`, line 1):

```json
{"content_version":1,"counts":{"grammar_span":1,"occurrence":11,"segment":2,"sentence":3},"format":"kotobaya-media-content","format_version":1,"media_id":"0193a1b2-0007-7000-8000-000000000001","record":"header"}
```

### 3.2 Order

Header, then all `segment` records by `ordinal`; then all `sentence` records by (segment
ordinal, sentence ordinal); then all `occurrence` records by (segment ordinal, `entry_seq`); then
all `grammar_span` records by (position of their sentence in the archive, `start_offset`,
`end_offset`, `grammar_point_id`). Out-of-order records make the archive invalid.

### 3.3 Integrity rules (checked by `validate.py`; consumers MUST reject an archive that breaks one)

1. `counts` equals the number of records of each kind (detects truncation at a line boundary).
2. Segments: at least one; `ordinal`s are exactly `0 … k-1`; ids unique.
3. Sentences: `segment_id` names a segment of the archive; per segment the `ordinal`s are
   exactly `0 … n-1`; ids unique across the archive; `segment.sentence_count` = n;
   `start_ms`/`end_ms` both null or both set with `end_ms ≥ start_ms`.
4. Tokens (`app.sentence.tokens`; element shape owned by the parser contract C-12): every token
   has integer `s`, `e` with `0 ≤ s < e ≤` UTF-16 length of `text`, ascending and
   non-overlapping; `seq` is null or a positive integer.
5. Occurrences: `(segment_id, entry_seq)` unique; every `sentence_ids` element is a sentence of
   the **same** segment, in ascending ordinal; at most 20.
6. Per segment: `unmapped_token_count ≤ token_count` and `token_count = sum(occurrence.count) +
   unmapped_token_count`.
7. **Occurrences are the aggregation of the tokens** (every segment that has sentences): over
   the segment's sentences with `origin = 'media'` (generated sentences never count), the
   occurrence entries are exactly the distinct non-null token `seq`s; `count` = number of tokens
   with that `seq`; `sentence_ids` = the first 20 of those sentences (by ordinal) containing such
   a token; `pos_class` = a most frequent token `class` among them (ties: C-12's rule);
   `unmapped_token_count` = number of tokens whose `seq` is null.
8. Grammar spans: `sentence_id` is a sentence of the archive; `start_offset < end_offset ≤` UTF-16
   length of its text; the 4-tuple of 3.2 is unique.
9. **jpdb media** (`media.source = 'jpdb'`): exactly one segment, `kind = 'whole'`, `ordinal = 0`,
   no sentences, every occurrence has `sentence_ids = []` (rule 7 does not apply; rule 6 does).

### 3.4 Producer obligations: id stability

Notes, `review_log.sentence_id` and manual grammar spans point at sentences by id (soft
references), so ids MUST be stable across versions of the same media:

- **Segment id**: the id of the base version's segment with the same `ordinal`; a new ordinal
  gets a new UUIDv7.
- **Sentence id**: if the base version's segment with the same segment ordinal (hence the same
  segment id) has a sentence with the **same sentence `ordinal` and byte-identical `text`** (no
  normalisation), its id MUST be reused; otherwise a new UUIDv7 MUST be minted. This is the
  `V1__init.sql` rule "keep the id when `(segment_id, ordinal, text)` is unchanged". An id is
  never reused for a different `(segment, ordinal, text)` and never taken from another media.
  Tokens, timestamps and blobs may change under a kept id.
- "Base version" = the rows of `app.media_segment` / `app.sentence` for the media at the
  `base_content_version` the producer declares in its `media-publish` payload, read in the same
  database snapshot as that version (`REPEATABLE READ`). If the server's version has moved on by
  publish time the runner rejects the publish (`stale_base`) and the producer re-plans
  (`MEDIA_PIPELINE.md` 3.4), so ids are always derived from the version actually replaced.

Example: version 3 has segment 0 sentences `[0 "おはよう。" id A, 1 "行くぞ。" id B, 2 "待て！" id C]`.
A re-parse that inserts a sentence at ordinal 1 yields `[0 "おはよう。" A, 1 "えっ？" new D,
2 "行くぞ。" new E, 3 "待て！" new F]`: B and C changed ordinal, so they get new ids; A is kept.
A re-parse that only changes tokens keeps A, B, C.

## 4. Deck-item archive (`kotobaya-deck-items`, `format_version` 1)

### 4.1 Records

| `record` | Members | Meaning |
|---|---|---|
| `header` | `format`, `format_version`, `deck_id`, `deck_type` (`media` \| `list`), `content_version`, `counts {item}` | `deck_type` MUST equal the deck's `type`. |
| `item` | `entry_seq`, `position`, `occurrence_count` | One `deck_item` row. |

Only entry items exist in v1 (no `n:<note>` items in generated decks). Example:
`media/examples/new-media.deck-items.v1.jsonl`.

### 4.2 Order and rules

1. Items ordered by (`position`, `entry_seq`) ascending; `entry_seq` unique; `counts.item` =
   number of items.
2. **Media decks:** `position` = rank by in-deck frequency: `1 … n` dense, ordered by
   `occurrence_count` descending, ties by `entry_seq` ascending; `occurrence_count ≥ 1`.
3. **List decks:** `position ≥ 0` (sparse allowed), `occurrence_count = 0`.

### 4.3 Installed row

`deck_item(deck_id = header.deck_id, item_key = 'e:' || entry_seq, entry_seq, note_id = NULL,
position, occurrence_count, added_at = deck.created_at, deleted_at = NULL, hlc = NULL)`.
`added_at` is the deck's own `created_at` (equal on every replica), not the install time, so
every replica installs identical rows.

## 5. A media and its media deck

Every publish of a media publishes both archives with the **same** `content_version` (the media
deck's version moves in lockstep with its media: both start at 0 and only the media-publish
runner bumps them, by one each). For the pair:

- deck header `deck_id` = content header `media_id`, `deck_type = 'media'`, equal `content_version`;
- the deck items are **exactly** the distinct `entry_seq`s of the content's occurrences (all
  `pos_class`es), with `occurrence_count` = the sum of that entry's `count` over all segments.

`validate.py pair <content> <deck>` checks this. `JobResultMedia.unique_entries` = number of items.

## 6. Versioning

- Readers MUST reject an unknown `format` or `format_version` (devices: keep the old rows and
  report "needs a newer app"; the server runner: fail the publish).
- Readers MUST ignore unknown members of the header and of data records (a producer of a later
  minor revision may add optional members).
- A new record kind, a new required member, or a changed meaning bumps `format_version`. During
  a transition the producer publishes only the version every consumer reads; there is one blob
  per published version.

## 7. Installing (server and device)

Install = make the rows of one media or deck equal to an archive, **in one transaction**:

1. Download the blob by hash (`GET /blobs/{hash}`, or the object store directly on the server),
   verify length and SHA-256, decompress and validate the whole archive (3.3 / 4.2) **before**
   opening the transaction. Any failure: install nothing.
2. Check the header against the row that announced it: content `media_id` / deck `deck_id` and
   `content_version` equal the row's current `content_version` and `content_blob_hash` equals
   the archive's hash. If the row has moved on (a newer version was announced meanwhile) or the
   media/deck is tombstoned, install nothing (a device fetches the newer one later).
3. Media content: `DELETE FROM occurrence WHERE media_id = M`; `DELETE FROM sentence WHERE media_id
   = M`; `DELETE FROM media_segment WHERE media_id = M`; `DELETE FROM sentence_grammar_span WHERE
   media_id = M AND origin = 'detected'`; then insert segments, sentences, occurrences, spans
   (that order satisfies the foreign keys on both sides). Manual spans are never touched.
4. Deck items: `DELETE FROM deck_item WHERE deck_id = D`; insert the items per 4.3. (Media and
   list decks have no op-written items, so nothing user-made is lost.)
5. Device only: set `media.installed_content_version` / `deck.installed_content_version` to the
   header's `content_version`, in the same transaction. Until then the device keeps the old rows.

Devices MUST install deck-item archives of the media and list decks they hold (deck items drive
the new-card queue). Media content archives are optional on devices (storage budget; ADR-018: not
installed in v1); `installed_content_version` stays 0 then. The server installs both inside the
publish transaction (`MEDIA_PIPELINE.md` 3.3), not by downloading its own announcement later.
