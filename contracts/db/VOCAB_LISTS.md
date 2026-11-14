# Vocabulary lists (JLPT) and list-deck builds (C-21)

Frozen interface between the dict-refresh loader (worker), the `deck-build` job (worker), the
server (`GET /deck-lists`, the `deck-publish` runner) and devices (who only ever see the
resulting deck-item archives). DDL: `V3__vocab_list.sql`. Archive format: `SNAPSHOT.md` 4.
Payload schema: `media/deck-publish.v1.schema.json` (checked by `media/validate.py
deck-publish`). Key words MUST / MUST NOT / SHOULD / MAY are normative. Where code differs from
this document, this document wins. Decision record: ADR-022 in `docs/DECISIONS.md`.

## 1. What a list deck is built from

| `list_key` | Source of membership | Defined in |
|---|---|---|
| `jlpt-n5` … `jlpt-n1` (any `ref.vocab_list.id`) | `ref.vocab_list_item` | 3 |
| `freq-<slug>-<N>`, N ∈ {1000, 2000, 5000, 10000, 20000} | `ref.entry_freq` of the frequency list whose `id` with `_` replaced by `-` is `<slug>`, `rank ≤ N` | 4.2 |

Both are reference data of the **current** dictionary release. A list deck's `deck_item` rows
are never written by ops; they arrive only as a published deck-item archive (`SNAPSHOT.md` 7).

## 2. Source (JLPT)

### 2.1 Data and licence

Jonathan Waller's JLPT vocabulary lists (tanos.co.uk, **CC BY**), in the machine-readable form
maintained by stephenmk's `yomitan-jlpt-vocab` (<https://github.com/stephenmk/yomitan-jlpt-vocab>,
repository licence **CC BY-SA 4.0**, keeps Waller's attribution), which adds the JMdict sequence
number of every word. Files `original_data/n5.csv` … `n1.csv`.

Built-in default (used when `DICT_SOURCES_JSON` has no `jlpt` key), pinned to commit
`b062d4e38c4bdd0950ae1d4ec55f04b176182e03` (release tag `2025.08.01.0`):

| Level | URL (`https://raw.githubusercontent.com/stephenmk/yomitan-jlpt-vocab/b062d4e38c4bdd0950ae1d4ec55f04b176182e03/original_data/…`) | SHA-256 | Data rows |
|---|---|---|---|
| n5 | `n5.csv` | `07dc6f197b51cc076c65c3c9f23218d10c2b67f76545f5c1c4d9e3750495533a` | 684 |
| n4 | `n4.csv` | `a14dcc7fdc02259b22331a486c6ff66df74c16472c8aeb5a0ebe7e5fa8ee8eb4` | 640 |
| n3 | `n3.csv` | `bd4d68c59cfee861351e1bdf9d99818e434392201eabbed4d3657225fe53a3ac` | 1730 |
| n2 | `n2.csv` | `42a4413326e857d701dc9659b2711637ab612304a339fe0d09f4f0f042d3a214` | 1812 |
| n1 | `n1.csv` | `7a58f0584e9ec2b0299ccb9b109f3bd03e08b90d129a714307c0a1cb72f07b32` | 3427 |

(Profile of the pinned files, for the implementer's tests: n1 has 14 rows with an empty
`jmdict_seq`; every level repeats some seqs (alternative readings/spellings: n5 17, n4 5, n3 20,
n2 18, n1 27); 448 seqs occur in more than one level.)

Provenance: the loader MUST add to `ref.dict_release.sources` the key `jlpt` =
`{"version": "<commit sha, or the sha256 of the n5 file when a custom url is configured>",
"license": "CC BY-SA 4.0 (list data: Jonathan Waller, tanos.co.uk, CC BY)", "url":
"https://github.com/stephenmk/yomitan-jlpt-vocab"}`. The attribution page (SPEC "Licences")
shows it like every other source. The list itself never leaves the server; devices receive only
derived deck-item archives (entry seqs).

### 2.2 Configuration (`DICT_SOURCES_JSON`, worker)

Additive key next to `freq_lists` (format owned by the docstring of
`worker/src/kotobaya_worker/jobs/dict_refresh.py`, which JLPT-1 extends):

```json
{"jlpt": {"n5": {"url": "...", "sha256": "..."}, "n4": {...}, "n3": {...}, "n2": {...}, "n1": {...}}}
```

- Key absent → the built-in default of 2.1. `"jlpt": null` → no JLPT lists (the tables end up
  without `jlpt` rows).
- If present, all five levels are required, each with `url` and `sha256` (mandatory pin, like
  every non-EDRDG source). Anything else is a configuration error: the dict-refresh job fails
  before downloading (`ValueError`, like the existing source checks).
- A download whose digest differs from the pin fails the job (existing `fetch_source` rule).
- The five files' digests are part of the refresh's source fingerprint: changing the JLPT
  configuration (including going from "not loaded" to the default) counts as a changed source,
  so a refresh with `force: false` builds a new release.

### 2.3 File format

RFC 4180 CSV, UTF-8 (a leading BOM is ignored), LF or CRLF. The first line is the header and
MUST contain the columns `jmdict_seq`, `kana`, `kanji` (any order; other columns such as
`waller_definition` are ignored). A missing column fails the job (`invalid_source: jlpt nX`).
Every later line that is not empty is one **data row**, numbered 1, 2, 3… in file order (this is
`source_row` / `position`). Each value is trimmed of ASCII whitespace and NFC-normalised.

## 3. Loading (dict-refresh)

### 3.1 Lists

| `id` | `family` | `ordinal` | `title` |
|---|---|---|---|
| `jlpt-n5` | `jlpt` | 0 | `JLPT N5` |
| `jlpt-n4` | `jlpt` | 1 | `JLPT N4` |
| `jlpt-n3` | `jlpt` | 2 | `JLPT N3` |
| `jlpt-n2` | `jlpt` | 3 | `JLPT N2` |
| `jlpt-n1` | `jlpt` | 4 | `JLPT N1` |

Lists are processed in ascending `ordinal` (easiest first); rows within a file in order.

### 3.2 Matching rule (one data row → at most one entry)

"Live" = `ref.dict_entry.retired_release_id IS NULL` in the release being loaded. "Kanji forms" /
"readings" = the `text` members of `kanji_forms` / `readings` (equivalently `ref.dict_entry_form`
rows of kind `kanji` / `reading`, compared on `form`, exact string equality — not on `norm`, so
katakana and hiragana stay distinct).

1. **Invalid row** → `invalid_row`: the row has fewer than the header's columns, or `kana` is
   empty, or `jmdict_seq` is non-empty and not `^[1-9][0-9]{0,15}$`.
2. **By sequence number:** if `jmdict_seq` is non-empty and a **live** entry with that `seq`
   exists, the row matches it. The forms are not compared (the curator chose the seq; JMdict
   seqs are stable).
3. **By forms** (seq empty, unknown, or retired): candidates = live entries whose readings
   contain `kana` and, if `kanji` is non-empty, whose kanji forms contain `kanji`.
   - exactly one candidate → match;
   - more than one and `kanji` is empty → keep only kana-only candidates (`kanji_forms = []`);
     exactly one left → match;
   - none → `no_entry`; still more than one → `ambiguous` (all candidates recorded, ascending).

### 3.3 Accounting (nothing silently dropped)

For a matched row with entry E in list L (family F):

1. E is already an item of a list of F with a lower ordinal → **shadowed** (`shadowed_rows`).
   A word belongs to its easiest level only.
2. else E is already an item of L (an earlier row matched it) → **duplicate** (`duplicate_rows`);
   the earlier row keeps its position.
3. else insert `vocab_list_item(L, E, position = source_row)`.

An unmatched row inserts `vocab_list_unmatched(L, source_row, jmdict_seq if valid else NULL,
kana, kanji, reason, candidates)`. Invariants the loader MUST check before committing (fail the
job otherwise; the release is not made current):

- `source_rows = entry_count + duplicate_rows + shadowed_rows + unmatched_rows` (also a `CHECK`);
- `entry_count` = the list's item rows; `unmatched_rows` = the list's unmatched rows;
- no seq is an item of two lists of one family.

A list with `entry_count = 0` is still stored (its report matters) but is not offered
(4.1). The loader logs one line per list with the five counts, and puts them in
`JobResultDictRefresh.vocab_lists` (`openapi.yaml`). The full unmatched report is
`ref.vocab_list_unmatched` (query it with psql; no API in v1).

### 3.4 When and how

The vocabulary lists are matched against the release being loaded, after its `dict_entry` rows
are final, and written **in the transaction that sets `is_current` on that release**: `DELETE
FROM ref.vocab_list` (cascades), then insert lists, items, unmatched rows. Readers therefore
never see lists matched against a release other than the current one, nor a half-loaded list.
`vocab_list.release_id` = that release. A refresh that builds no new release (nothing changed,
`force: false`) leaves the tables untouched.

After that transaction commits, the dict-refresh job enqueues a rebuild of every live list deck
(their membership or order may have changed):

```sql
INSERT INTO app.job (id, kind, payload, dedupe_key)
SELECT <new UUIDv7 per row>, 'deck-build', jsonb_build_object('deck_id', id), 'deck-build:' || id
  FROM app.deck WHERE type = 'list' AND deleted_at IS NULL
ON CONFLICT DO NOTHING
```

(a build already queued or running for a deck is kept; it reads the new release when it plans),
and sends `job.updated` for each inserted row. An unchanged result publishes nothing (4.4), so
this costs devices nothing when a list did not change.

## 4. Lists offered and `deck-build`

### 4.1 Catalogue (`GET /deck-lists`)

With no `ref.dict_release` row: empty. Otherwise, ordered by `list_key` (byte order):

- every `ref.vocab_list` row with `entry_count > 0`: `list_key = id`, `title`, `item_count =
  entry_count`;
- for every `ref.freq_list` row and N in (1000, 2000, 5000, 10000, 20000): `list_key =
  'freq-' || replace(id, '_', '-') || '-' || N`, `title = <freq_list.title> || ' top ' || N`,
  `item_count` = number of `ref.entry_freq` rows of the list with `rank ≤ N` whose entry is
  live; offered only if `item_count > 0`.
- `deck_id` = the live list deck with that `list_key`, or null.

`item_count` MUST equal the number of items a `deck-build` of that key would publish against the
same release (same filters as 4.3). A JLPT key is therefore absent until the JLPT source is
loaded, and `createDeck` for it is `422` like any unknown key. A live list deck whose key is no
longer offered keeps its items; its next build fails `unknown_list` (4.4).

The dict-refresh loader MUST reject (fail before downloading) frequency slugs whose
`replace(id, '_', '-')` forms collide, so a `freq-` key names one list.

### 4.2 Resolving a `list_key` (deck-build and catalogue agree)

1. A `ref.vocab_list` row with `id = list_key` → that vocabulary list.
2. Else `^freq-(.+)-(1000|2000|5000|10000|20000)$` and exactly one `ref.freq_list` with
   `replace(id, '_', '-') = <group 1>` → that band.
3. Else unknown.

### 4.3 Items and order

Only live entries (`retired_release_id IS NULL`). Order, then `position = 1024 · k` for k = 1…n
(every build, so archives are comparable), `occurrence_count = 0`:

| Kind | Items | Order |
|---|---|---|
| vocabulary list | `vocab_list_item` rows of the list | `dict_entry.freq_rank` ascending NULLS LAST, then `vocab_list_item.position`, then `seq` |
| frequency band | `entry_freq` rows of the list with `rank ≤ N` | `entry_freq.rank`, then `seq` |

Vocabulary lists use global frequency (not the source's alphabetical order) so the deck's
default `settings.order = position` introduces common words first. With no frequency list
loaded every `freq_rank` is NULL and the order is the source order.

### 4.4 `deck-build` job (public kind, worker)

Payload `JobPayloadDeckBuild {deck_id}`; dedupe key `deck-build:<deck id>` (createDeck and 3.4).
Result `JobResultDeckBuild`. Follows `MEDIA_PIPELINE.md` 3.2 with `deck-publish` as the child:

1. If a `deck-publish` row with `payload.parent_job_id` = this job's id has already
   `succeeded`, end `succeeded` with its `result`, `progress = 1`.
2. **Plan**, in one `REPEATABLE READ` transaction: read the deck row and the current release,
   resolve the key (4.2), compute the items (4.3), read the installed items. Fail the attempt
   with `error = '<code>: <message>'` when:

   | Code | When |
   |---|---|
   | `unknown_deck` | no deck with `deck_id` |
   | `invalid_payload` | the deck's `type` is not `list` |
   | `deck_deleted` | the deck is tombstoned |
   | `no_release` | no current dictionary release |
   | `unknown_list` | the deck's `list_key` does not resolve (4.2) |
   | `empty_list` | the key resolves but has no live items — **never publish an empty list deck** |

   These are deterministic: retries (per `max_attempts`) reach the same error, which is
   acceptable; a handler MAY end the job `failed` without retrying if the runner supports it.
3. **Unchanged:** if the deck's `content_version ≥ 1` and its live `deck_item` rows equal the
   computed `(entry_seq, position)` set exactly, end `succeeded` with `{deck_id,
   content_version: <current>, items, list_key, changed: false, unmatched}` and publish nothing.
4. Otherwise build the archive (`SNAPSHOT.md` 4; header `deck_type = list`, `content_version =
   base + 1` where base = the deck's `content_version` read in step 2), upload it (object, then
   `app.blob` row `kind = snapshot, origin = build`, `SNAPSHOT.md` 2.6), insert or reuse the
   child (5.1), wait for it exactly as `MEDIA_PIPELINE.md` 3.2 step 4 (`stale_base` → re-plan
   from step 2, at most 3 publishes per attempt). `progress` stays in [0, 0.9] until the child
   ends.

`unmatched` = the vocabulary list's `unmatched_rows`, 0 for a frequency band. Deleting the deck
while a build is queued makes that build fail `deck_deleted` (or the child fail `deck_deleted`);
nothing is installed. Re-enabling the list creates a new deck and a new build.

## 5. The `deck-publish` hand-off (server-internal job kind)

Registered in `SCHEMA.md` 4.4. Mirrors `media-publish` (`MEDIA_PIPELINE.md` 3); only the
differences are written here.

### 5.1 Child row and payload

```
INSERT INTO app.job (id, kind, payload, dedupe_key, max_attempts)
VALUES (<new UUIDv7>, 'deck-publish', <payload>, 'deck-publish:<parent id>', 1)
ON CONFLICT DO NOTHING
```

then read the `queued`/`running` row with that key. Payload: `media/deck-publish.v1.schema.json`
(closed): `format_version` 1, `parent_job_id`, `deck_id`, `list_key`, `base_content_version`,
`deck_items {blob_hash, size_bytes}`, `unmatched`. Example
`media/examples/deck-publish.list-deck.json` with `media/examples/list-deck.deck-items.v1.jsonl.zst`.
`validate.py deck-publish <payload> <blob>` checks a payload with its blob and prints the result
the runner will report.

### 5.2 Server runner (`DeckPublishRunner`, the only claimer of the kind)

Claim, heartbeat and stale re-queue exactly as `MEDIA_PIPELINE.md` 3.3.

**Before the transaction:** (1) validate the payload (`invalid_payload`; `format_version` ≠ 1
→ `unsupported_format`); (2) parent not `running` → end the child `cancelled`; (3) fetch the
blob, check length and SHA-256 (`blob_missing`), decompress and validate (`SNAPSHOT.md` 4.2 and
the `validate.py deck-publish` rules: `deck_type = list`, header `deck_id` = payload `deck_id`,
`content_version = base_content_version + 1`, at least one item, positions `1024 · k`;
`invalid_snapshot`).

**The transaction**, under `pg_advisory_xact_lock(7033001)` (OpApply), all or nothing:

4. Re-check the child row as `MEDIA_PIPELINE.md` 3.3 step 4.
5. `SELECT … FROM app.deck WHERE id = deck_id FOR UPDATE`: missing → `unknown_deck`; `type ≠
   list` or `list_key ≠` payload `list_key` → `invalid_payload`; tombstoned → `deck_deleted`;
   `content_version ≠ base_content_version` → `stale_base`. Every item's `entry_seq` exists in
   `ref.dict_entry` → else `invalid_snapshot`.
6. Install the archive (`SNAPSHOT.md` 7 step 4).
7. Mint `deck.content_published {deck_id, content_version: base + 1, content_blob_hash:
   deck_items.blob_hash}` (server device, through OpApply).
8. Child `succeeded`, `result` = `{deck_id, content_version: base + 1, items, list_key, changed:
   true, unmatched}` (`JobResultDeckBuild`), `progress = 1`, `finished_at`. Parent `progress =
   greatest(progress, 0.95)`, `progress_message = 'publishing'` `WHERE status = 'running'`.

Errors as `MEDIA_PIPELINE.md` 3.3 (`'<code>: <message>'` in a new transaction). Codes:
`invalid_payload`, `unsupported_format`, `blob_missing`, `invalid_snapshot`, `stale_base`,
`unknown_deck`, `deck_deleted`, `internal`. Idempotency, retries and lock order: as
`MEDIA_PIPELINE.md` 3.4 (one child per parent; the deck row lock orders concurrent publishes;
versions strictly increase by one).

## 6. Worked example (`test/smoke_v3.sql`)

Release 2 is being loaded. Live entries: 会う 1198180 (freq_rank 300), 青 1381380 (none), 十
1579840 (120, readings じゅう, とお), じゃ 1005900 (kana-only, readings じゃ, じゃあ), 食べる
1358280 (250), 行く 1578850 (80); 乾 9990001 and 9990002 (both read かん). 1000010 (行く) is
retired.

`n5.csv` (the example shows only n5 and n4; n3–n1 are left out):

| Row | jmdict_seq | kana | kanji | Outcome |
|---|---|---|---|---|
| 1 | 1198180 | あう | 会う | item, position 1 (by seq) |
| 2 | 1381380 | あお | 青 | item 2 |
| 3 | 1579840 | じゅう | 十 | item 3 |
| 4 | 1579840 | とお | 十 | duplicate |
| 5 | | じゃ | | item 5 (by forms: only 1005900 reads じゃ) |
| 6 | 1358280 | たべる | 食べる | item 6 |

`n4.csv`:

| Row | jmdict_seq | kana | kanji | Outcome |
|---|---|---|---|---|
| 1 | 1358280 | たべる | 食べる | shadowed (already N5) |
| 2 | 1000010 | いく | 行く | item 2 (seq retired → by forms → 1578850) |
| 3 | | かん | 乾 | unmatched `ambiguous` {9990001, 9990002} |
| 4 | 9999999 | ほげ | 保下 | unmatched `no_entry` |
| 5 | abc | あ | 亜 | unmatched `invalid_row` (jmdict_seq NULL) |

Result: `jlpt-n5` source 6 = 5 entries + 1 duplicate; `jlpt-n4` source 5 = 1 entry + 1
shadowed + 3 unmatched. `JobResultDictRefresh.vocab_lists` = `[{"id":"jlpt-n5","source_rows":6,
"entries":5,"duplicates":1,"shadowed":0,"unmatched":0}, {"id":"jlpt-n4","source_rows":5,
"entries":1,"duplicates":0,"shadowed":1,"unmatched":3}]` (ordered by family, ordinal).

`GET /deck-lists` (no frequency list loaded) → `jlpt-n4` (1), `jlpt-n5` (5).

`createDeck {type: list, list_key: jlpt-n5}` → deck D, `content_version` 0, `deck-build`
queued. The build orders 十 (120), 食べる (250), 会う (300), 青 (null, row 2), じゃ (null, row
5) → positions 1024 … 5120, publishes v1 through `deck-publish`; the runner mints
`deck.content_published {D, 1, <hash>}`; result `{deck_id: D, content_version: 1, items: 5,
list_key: jlpt-n5, changed: true, unmatched: 0}`. A later refresh that changes nothing for N5
re-queues the build, which ends `changed: false, content_version: 1` and mints nothing.

## 7. Edge cases (index)

| Case | Rule |
|---|---|
| JLPT source not configured / `"jlpt": null` | no JLPT rows; keys not offered (4.1) |
| Source file changed upstream | pinned sha256 mismatch fails the refresh (2.2) |
| Row with empty or retired seq | form fallback (3.2 step 3) |
| Same entry twice in a level | duplicate, first position kept (3.3) |
| Same entry in two levels | easiest level only, others shadowed (3.3) |
| Entry retired by a later release | reload drops it (lists rematched per release, 3.4); rebuild removes it from decks |
| List matches nothing | stored with `entry_count 0`, not offered; a build fails `empty_list` |
| Existing live deck for a key no longer offered | items kept; builds fail `unknown_list` until offered again |
| 0.1.9 JLPT decks (built never ran, `content_version 0`) | their queued builds, or the post-refresh rebuild (3.4), publish v1 once the source is loaded |
| Deck deleted during a build | `deck_deleted`, nothing installed (4.4, 5.2) |
| Two builds of one deck | dedupe key; otherwise `stale_base` re-plan (5.2) |
| Rebuild with identical items | `changed: false`, no op, no blob (4.4 step 3) |
