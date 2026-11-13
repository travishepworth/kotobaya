# Media pipeline: publish hand-off, parse status, jpdb mapping corrections (C-11)

Frozen interface between the worker's media jobs, the server, and the API. Archive formats are in
`SNAPSHOT.md`; job-table rules for internal kinds in `SCHEMA.md` 4.4 (C-9). Key words MUST /
MUST NOT / SHOULD / MAY are normative. Where code differs from this document, this document wins.

## 1. Who writes what

| Rows | Written by | How |
|---|---|---|
| `media` | server only | ops `media.created` / `media.content_published` (runner, reconciler, API); `media.updated` / `media.deleted` (clients, API) |
| media deck `deck` row | server only | `deck.created` (runner), `deck.content_published` (runner); `deck.updated` (clients) |
| `media_segment`, `sentence`, `occurrence`, detected `sentence_grammar_span` | **server only**, inside the publish transaction (3.3), from the content archive; hard-deleted by `media.deleted` | install rule `SNAPSHOT.md` 7 |
| media deck `deck_item` | **server only**, inside the publish transaction, from the deck-item archive | install rule `SNAPSHOT.md` 7 |
| `blob` (`kind = snapshot`, `origin = build`), the archive objects | worker | `SNAPSHOT.md` 2.6 |
| `job` `media-publish` rows | worker inserts, server runner updates | 3 |
| `jpdb_vocab`, `jpdb_vocab_media` (`V2__jpdb_vocab.sql`) | runner (rows and links), API (resolution) | 5 |

The worker **never writes** `app.media*`, `sentence`, `occurrence`, `sentence_grammar_span` or
`deck_item`. It only reads them (base version for id stability, `SNAPSHOT.md` 3.4). This keeps one
install path (server and devices use the same archive and the same rule), keeps every op minted
by the server (`CLAUDE.md` rule 8), and solves the `media` foreign-key ordering: the media row and
its content appear in the same transaction. (Refines ADR-014.)

## 2. The public jobs involved

| Parent kind | Started by | Payload (`openapi.yaml`) | New media? |
|---|---|---|---|
| `media-import` | `importMedia` | `MediaImportRequest` + server-set `media_id` | yes, or append (existing `media_id`) |
| `media-parse` | `reparseMedia` | `JobPayloadMediaParse` | never |
| `jpdb-deck-import` | `importJpdbDeck`, or a mapping correction (5.4, `remap_only: true`) | `JpdbDeckImportRequest` + server-set `media_id` (+ `remap_only`) | yes for an unknown `jpdb_id`, else existing |

Every one of them ends by publishing through exactly one succeeded `media-publish` child (3). A
job that fails or is cancelled before that publishes nothing: the media keeps its previous
version (a new media does not exist at all, so a failed first import leaves no row).

## 3. The `media-publish` hand-off (server-internal job kind)

Registered in `SCHEMA.md` 4.4. Mirrors C-9 (`importJpdbHistory` "Execution"): never exposed by
the API, visible only through its parent.

### 3.1 Child row and payload

The worker inserts, in one statement:

```
INSERT INTO app.job (id, kind, payload, dedupe_key, max_attempts)
VALUES (<new UUIDv7>, 'media-publish', <payload>, 'media-publish:<parent id>', 1)
ON CONFLICT DO NOTHING
```

then reads the `queued` or `running` row holding that `dedupe_key` (a child left by an earlier
attempt of the parent is reused, not duplicated). The payload is
`media/media-publish.v1.schema.json` (closed). Examples:
`media/examples/media-publish.new-media.json`, `media/examples/media-publish.jpdb-reimport.json`.

| Member | Meaning |
|---|---|
| `format_version` | `1`. |
| `parent_job_id` | The public parent. |
| `media_id` | The media (= its deck id). For new media the id the server pre-allocated in the parent payload. |
| `base_content_version` | The media's `content_version` the worker built on; `0` for new media. The archives are published as `base_content_version + 1` and their headers MUST say so. |
| `create` | Non-null iff `base_content_version = 0`: `title` (1–300), `kind`, `source`, `jpdb_id` (iff `source = jpdb`), `deck {priority, enabled, new_cards_per_day}` (the parent's `MediaDeckOptions`, defaults filled: `0`, `true`, `null`). |
| `content`, `deck_items` | `{blob_hash, size_bytes}` of the two archives. Both blobs and their `app.blob` rows exist before the child is inserted. |
| `unmapped` | Becomes `JobResultMedia.unmapped` (jpdb: the unresolved vocab, 5.3; others `[]`). |
| `jpdb` | jpdb media: `{jpdb_id, vocab[]}` (5.3); else `null`. |

`validate.py publish <payload> <content.zst> <deck.zst>` checks a payload with its blobs and
prints the `JobResultMedia` the runner will report.

### 3.2 Worker protocol (per attempt of the parent)

1. If a `media-publish` row with `payload.parent_job_id` = the parent's id has already
   `succeeded`, end the parent `succeeded` with that row's `result`, `progress = 1`, and stop
   (an earlier attempt got that far; nothing is published twice).
2. Plan: read `base_content_version` and the base rows (`SNAPSHOT.md` 3.4) in one `REPEATABLE
   READ` transaction; extract, parse, map, aggregate; build both archives; upload them
   (object, then `app.blob` row). Parent `progress` stays in [0, 0.9].
3. Insert or reuse the child (3.1).
4. Wait for the child (poll, heartbeat the parent, honour parent cancellation):
   - `succeeded` → parent `succeeded`, `result` = the child's `result`, `progress = 1`.
   - `failed` with an `error` starting `stale_base:` → go back to step 2 (re-plan on the new
     base), at most 3 publishes per attempt; the 4th `stale_base` fails the attempt with that error.
   - `failed` otherwise → the parent attempt fails with the child's `error` (retried per
     `max_attempts`; a retry starts at step 1).
   - `cancelled` → the parent is already `cancelled`; nothing to do.

### 3.3 Server runner (`MediaPublishRunner`, the only claimer of the kind)

Claim: `SELECT … FROM app.job WHERE kind = 'media-publish' AND status = 'queued' AND run_after <=
now() ORDER BY run_after, created_at FOR UPDATE SKIP LOCKED LIMIT 1`; set `running`, `attempts +
1`, `started_at`, `locked_by`, `heartbeat_at`; heartbeat every 30 s while working. A `running` child
whose `heartbeat_at` is older than 5 minutes (server restart) is re-queued by the runner whatever
its `attempts`.

**Before the transaction** (no locks held):

1. Validate the payload against the schema (`invalid_payload`; `format_version` ≠ 1 →
   `unsupported_format`).
2. If the parent is not `running`: end the child `cancelled`; stop.
3. Fetch both blobs from the object store, check length and SHA-256 (`blob_missing` if absent),
   decompress and validate both archives and the pair fully (`SNAPSHOT.md` 3.3, 4.2, 5;
   `invalid_snapshot`), check the headers against the payload (`media_id`, `content_version =
   base_content_version + 1`) and, for jpdb, the `validate.py publish` rules (`invalid_payload`).

**The transaction** — everything below commits together or not at all, under the op-apply lock
(`pg_advisory_xact_lock(7033001)`, taken by OpApply), with every op minted through OpApply as a
server op (server device id, server HLC, `change` row):

4. Re-check: child still `running` and locked by this runner; parent `running` (else end the child
   `cancelled`, commit only that).
5. `SELECT … FROM app.media WHERE id = media_id FOR UPDATE`.
   - `create` ≠ null: no `media` row with that id may exist (else `stale_base`); no `deck` row with
     that id (any type, live or not; else `deck_id_taken`); if `source = jpdb`, no live media with
     that `jpdb_id` (else `jpdb_id_taken`).
   - `create` = null: the media exists (`unknown_media`), is live (`media_deleted`), its
     `content_version` = `base_content_version` (else `stale_base`); its deck exists with `type =
     media`, is live and has `content_version` = `base_content_version` (else
     `invariant_violation`). For jpdb publishes `media.source = jpdb` (else `invalid_payload`).
   - Every sentence id of the archive that already exists in `app.sentence` belongs to this
     media (else `invalid_snapshot`); every detected span's `grammar_point_id` exists in
     `app.grammar_point` (else `invalid_snapshot`).
6. If `create` ≠ null, in this order:
   - `media.created {id: media_id, title, kind, source, jpdb_id, cover_blob_hash: null}`;
   - `deck.created {id: media_id, name: <title cut to its first 200 code points>, type: media,
     media_id, priority, enabled, new_cards_per_day, settings: {"order": "deck_frequency"}}` with
     `change.caused_by_op_id` = the `media.created` op's id. This is the "server emits the media's
     `deck.created` right after" of OPS.md `media.created`: the runner emits it explicitly;
     applying `media.created` does not.
7. Install the content archive and the deck-item archive (`SNAPSHOT.md` 7 steps 3–4).
8. `media.content_published {media_id, parse_status: parsed, parse_error: null, content_version:
   base + 1, content_blob_hash: content.blob_hash}`, then `deck.content_published {deck_id:
   media_id, content_version: base + 1, content_blob_hash: deck_items.blob_hash}`.
9. jpdb only: 5.2.
10. Child: `status = succeeded`, `result` = `JobResultMedia` computed from the archives
    (`media_id`, `content_version = base + 1`, `segments`, `sentences`, `tokens` = Σ
    `token_count`, `unmapped_tokens` = Σ `unmapped_token_count`, `unique_entries` = deck items,
    `unmapped` = payload `unmapped`), `progress = 1`, `finished_at`. Parent: `progress =
    greatest(progress, 0.95)`, `progress_message = 'publishing'` `WHERE status = 'running'`
    (send its `job.updated` after commit).

Any error inside the transaction rolls everything back; the runner then ends the child `failed`
with `error = '<code>: <message>'` (`internal` for unexpected exceptions) in a new transaction.
Error codes: `invalid_payload`, `unsupported_format`, `blob_missing`, `invalid_snapshot`,
`stale_base`, `unknown_media`, `media_deleted`, `deck_id_taken`, `jpdb_id_taken`,
`invariant_violation`, `internal`.

### 3.4 Idempotency, retries, concurrency

- The child's `succeeded` status commits in the same transaction as its effects, so a child is
  applied at most once; a crash before commit leaves no ops and no rows, and the stale-heartbeat
  rule re-runs it from the start.
- A parent retry never publishes twice (3.2 step 1). Re-uploading identical archives is a no-op
  (same bytes, same hash, `ON CONFLICT DO NOTHING`).
- One active child per parent (`dedupe_key`). Two parents may publish the same media
  concurrently (e.g. an append `media-import` and a `media-parse`, or a full jpdb import and a
  remap, whose dedupe keys differ): the `FOR UPDATE` on the media row orders them, and the second
  sees `content_version ≠ base_content_version` → `stale_base` → its worker re-plans on top of the
  first (3.2 step 4). Nothing is lost and versions stay strictly increasing by one.
- `deleteMedia` cancels the media's queued and running jobs; a runner that still gets there
  finds the parent not `running` (step 4) or the media tombstoned (`media_deleted`).

### 3.5 Worked example (ops in `seq` order)

First import of 葬送のフリーレン (`media-publish.new-media.json`, base 0), then a re-parse:

| Step | Ops minted (all server device) | `caused_by` |
|---|---|---|
| publish v1 | `media.created` (parse_status pending, version 0) | — |
| | `deck.created` (type media, priority 5, new_cards_per_day 10) | the `media.created` |
| | (rows installed: 2 segments, 3 sentences, 11 occurrences, 1 detected span; 6 deck items) | |
| | `media.content_published` (parsed, v1, hash `9587…1af7`) | — |
| | `deck.content_published` (v1, hash `4242…5fad`) | — |
| `reparseMedia` | `media.content_published` (parsing, v1, hash `9587…1af7`) — API, with the job insert (4) | — |
| publish v2 | `media.content_published` (parsed, v2), `deck.content_published` (v2) | — |

A device pulling the log sees `pending → parsed → parsing → parsed`, installs the v1 deck items
(and, optionally, content) after the first two `*.content_published`, and the v2 archives later.

## 4. `parse_status`

`parse_status` tells the user whether the media's content is being rebuilt. It never blocks
anything, and `content_version`/`content_blob_hash` always name the last good content (the
archive of version n stays valid while n+1 is being built, and after a failed rebuild).

| Transition | Who | Op (all `media.content_published`, same `content_version` and `content_blob_hash` unless stated) |
|---|---|---|
| (none) → `pending` → `parsed` | runner, first publish (3.3 steps 6, 8) | `media.created` inserts `pending` with version 0; `parsed` with version 1 follows in the same transaction. A new media is never seen `parsing` or `failed`: until its first publish it does not exist (its job is visible in `listJobs`). |
| `parsed` \| `failed` → `parsing` | the API handler that enqueues a job for an **existing live** media: `importMedia` (append), `importJpdbDeck` (known `jpdb_id`), `reparseMedia`, `putJpdbVocabResolution` / `clearJpdbVocabResolution` (remap, 5.4) | `parsing`, `parse_error: null`. Only when a job row was actually inserted (not when an existing job is returned) and the status is not already `parsing`. SHOULD be the transaction that inserts the job; otherwise insert the job first and emit after (the reconciler repairs the gap). |
| `parsing` → `parsed` (version + 1) | runner, successful publish | 3.3 step 8. |
| `parsing` → `failed` \| `parsed` (same version) | server **reconciler** | below. |

**Reconciler** (server, part of the publish runner's service; runs at startup and every 60 s; MAY
also run right after the server cancels a media job). For each live media with `parse_status =
'parsing'`: in one transaction under the op-apply lock, re-read the media; if it is still
`parsing` and **no** job of kind `media-import`, `media-parse` or `jpdb-deck-import` with
`payload->>'media_id'` = its id is `queued` or `running`, take the most recently finished such job
(`finished_at` descending, then `id` descending):
- its status is `failed` → emit `failed` with `parse_error` = its `error` (first 2,000 code
  points; `"job failed"` if null);
- otherwise (cancelled, succeeded without publishing, or no job at all) → emit `parsed`,
  `parse_error: null`.

Because it only looks at the job table, the reconciler also covers a worker that died on its last
attempt and jobs enqueued by paths that did not emit `parsing`. Worker-enqueued jobs emit nothing
(the worker cannot mint ops). A `parsing` status may therefore briefly coexist with an already
published newer version (seq order: an API `parsing` minted before a concurrent publish); that is
harmless and the reconciler or the next publish resolves it.

## 5. jpdb mapping corrections (unmapped vocabulary review queue)

Spec: "Anything that doesn't map goes to a review queue in the web app."

### 5.1 Decision: import configuration, not learning data

Corrections are stored in server-only tables (`V2__jpdb_vocab.sql`), changed through plain REST
(`putJpdbVocabResolution`, `clearJpdbVocabResolution`), never through ops, never synced. Why:
- no device reads them (devices never import or map); their only effect on learning data is a
  new media/deck version, which already travels as `media.content_published` /
  `deck.content_published`;
- a new op type stops every older device at the first one it pulls (SYNC.md 6.1, 15);
- `parse_override` is op-synced only because T003 put it in the log; corrections have no history
  that needs replaying (rebuilding the server from the log is not a feature).
Rejected: an op `jpdb.mapping_set` (device update for nothing); storing corrections inside the
job payload (lost on the next import); per-media corrections (a vid means the same word in every
deck, so one correction fixes all decks).

### 5.2 Tables and population

`app.jpdb_vocab` — one row per jpdb vid that a published jpdb deck contained **unmapped by the
automatic mapper**, with its `resolution` (`unresolved` | `mapped` + `entry_seq` | `ignored`).
Rows are never deleted. `app.jpdb_vocab_media` — which media contain the vid, with jpdb's
occurrence count, as of each media's latest publish.

In the publish transaction (3.3 step 9) the runner, for a jpdb media:
1. for every `jpdb.vocab` element: `INSERT INTO app.jpdb_vocab (jpdb_vid, spelling, reading,
   first_seen_at) VALUES (…, now()) ON CONFLICT (jpdb_vid) DO UPDATE SET spelling = EXCLUDED.spelling,
   reading = EXCLUDED.reading` (resolution columns are never written by the runner; the
   payload's `resolution` is informational);
2. `DELETE FROM app.jpdb_vocab_media WHERE media_id = $1`, then insert one link per `jpdb.vocab`
   element (`jpdb_vid`, `media_id`, `occurrence_count`).

### 5.3 What the importer does with them (`jpdb-deck-import`, T401b)

- After fetching and before mapping, read `SELECT jpdb_vid, resolution, entry_seq FROM
  app.jpdb_vocab WHERE jpdb_vid = ANY(<the deck's vids>) AND resolution <> 'unresolved'` (remap
  jobs: only after 5.4 step R1).
- Per fetched row with a vid: a stored correction wins over the automatic mapper. `mapped` → the
  row maps to `entry_seq` (merged with other rows of that seq, counts summed); `ignored` → the row
  stays unmapped; no correction → the automatic mapper (`map_vocab`).
- `jpdb.vocab` (payload) = every row that has a stored correction or that the automatic mapper
  could not map, one element per vid (repeats merged, counts summed), with the resolution
  applied (`unresolved` when there is no correction); ordered by `occurrence_count` descending,
  then `jpdb_vid`. `unmapped` = its `unresolved` elements (same order, four members).
- Rows without a vid are not mapping candidates (the importer reports them as skipped).
- Counting (checked by `validate.py publish`): `unmapped_token_count` of the single segment =
  Σ `occurrence_count` of `unresolved` and `ignored` vocab; every `mapped` vocab's `entry_seq` has
  an occurrence. An `ignored` vid therefore leaves `unmapped` but its tokens still count as
  unmapped in coverage.
- Corrections persist across re-imports, media deletion and re-creation (keyed by vid). In v1
  they apply to `jpdb-deck-import` only (not to `jpdb-history-import`).

### 5.4 Resolving: API and the remap job

`putJpdbVocabResolution` (`PUT /import/jpdb/vocab/{jpdb_vid}/resolution`) and
`clearJpdbVocabResolution` (`DELETE …`), one transaction each:

1. `SELECT pg_advisory_xact_lock(7033011)` (the jpdb-mapping lock).
2. The vid must have a row (`404`). For `mapped`: `entry_seq` must be a non-retired
   `ref.dict_entry` (`422`, pointer `/entry_seq`). If the stored resolution already equals the
   request (same `resolution` and `entry_seq`; for clear: already `unresolved`) → `200` with
   `jobs: []`, nothing written.
3. Write `resolution`, `entry_seq`, `resolved_at = now()` (clear: `unresolved`, `NULL`, `NULL`).
4. For each **live** media with `source = 'jpdb'` linked to the vid in `jpdb_vocab_media`
   (ascending media id): `INSERT INTO app.job (id, kind, payload, dedupe_key) VALUES (<UUIDv7>,
   'jpdb-deck-import', {"jpdb_id": media.jpdb_id, "media_id": media.id, "remap_only": true},
   'jpdb-deck-remap:<media id>') ON CONFLICT DO NOTHING`; if a row was inserted, emit `parsing`
   (section 4). The response lists, per affected media, the inserted job or the queued job that
   holds the key.

The remap job is an ordinary `jpdb-deck-import` (3.2) with two differences:
- **R1.** Its first action, before reading any correction, is a short transaction
  `SELECT pg_advisory_xact_lock(7033011); UPDATE app.job SET dedupe_key = NULL WHERE id = <self>;`
  committed. So at most one remap per media *waits*; a correction committed before R1 is seen by
  this job, and one committed after R1 inserts a new remap (the key is free). Without the lock a
  correction could commit between R1 and the read and find the key still taken: lost.
- **R2.** `remap_only: true` → use cached jpdb responses regardless of age; fetch over the network
  only what the cache lacks. Title, kind and deck options are not used (the media exists). If the
  media is no longer live or not `source = jpdb`, the job ends `failed` (`media_deleted`).

Concurrency with a full import of the same deck (dedupe key `jpdb-deck-import:<jpdb_id>`) is
settled by `stale_base` (3.4): whichever publishes second re-plans and reads the corrections again.

### 5.5 Worked example

State: media J (`anime/5370`, version 3) contains vids 9300001 フリーレン (312), 9300002 ヒンメル
(40), 9300003 魔族 (75), all `unresolved`; J's segment has `unmapped_token_count = 427`.

1. `PUT /import/jpdb/vocab/9300002/resolution {"resolution": "ignored"}` → row `ignored`; remap
   job X inserted (`jpdb-deck-remap:J`), `media.content_published` (parsing, v3).
2. `PUT /import/jpdb/vocab/9300003/resolution {"resolution": "mapped", "entry_seq": 1612400}`
   immediately after → row `mapped`; X is still queued, so nothing is inserted (`jobs` = [X]);
   no op (already `parsing`).
3. X runs: clears its key (R1), reads both corrections, maps from the cache, publishes v4
   (`media-publish.jpdb-reimport.json`): 魔族 becomes an occurrence of 1612400 (75) and deck item
   3; `unmapped_token_count` = 312 + 40 = 352; `unmapped` = [フリーレン]; the three links stay.
   Ops: `media.content_published` (parsed, v4), `deck.content_published` (v4).
4. `GET /import/jpdb/vocab` (default `status=unresolved`) → only 9300001, `occurrence_total` 312.

## 6. Edge cases (index)

| Case | Rule |
|---|---|
| First import fails or is cancelled | No media row, no op; only the job shows it (2). |
| Append import and re-parse of one media at once | Second publish `stale_base` → re-plan (3.4). |
| Worker retries after the child already succeeded | Parent ends with the child's result (3.2 step 1). |
| Server crashes mid-install | Rolled back; child re-queued by heartbeat rule (3.3). |
| Media deleted while its job runs | Jobs cancelled; runner finds parent not running or `media_deleted` (3.4). |
| A user deck already has the media's id | `deck_id_taken`, nothing installed (3.3 step 5). |
| Sentence id collides with another media's | `invalid_snapshot` (3.3 step 5). |
| Correction for a vid in no live media | Stored; no job (5.4 step 4). |
| Correction while a remap or import runs | New remap queued / `stale_base` re-plan (5.4). |
| Status stuck at `parsing` | Reconciler (4). |
| Device holds an older archive version | Keeps old rows until it installs the announced one (`SNAPSHOT.md` 7). |
