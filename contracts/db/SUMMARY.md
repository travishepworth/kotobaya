# Database contract — summary for the human

## What was decided

- **Two schemas.** `ref.*` (dictionary release, read-only) and `app.*` (learning data, media,
  system). `app` has no foreign keys into `ref`: entries are referenced by JMdict sequence number
  and may be missing (phone subset) or retired.
- **Every spec table exists**, with the spec's names. Added beyond the spec's list:
  `ref.dict_release`, `ref.dict_entry_form`, `ref.dict_entry_gloss`, `ref.entry_kanji`,
  `ref.freq_list`, `ref.example_sentence_entry` (search and link tables), `app.job` (the
  Postgres work queue the spec describes but does not list), `app.sentence_grammar_span` (the
  spec's "tagged spans on sentence"), and three device-only tables (`pending_op`, `sync_state`,
  `blob_cache`).
- **Last-writer-wins is stored per register** in a `clocks` JSON column (`{"fields.meaning": hlc,
  "tags": hlc, …}`), which is what "per field, by clock" needs. HLCs are 54-character strings
  that sort byte-wise, safe in JavaScript (no 64-bit integers).
- **`card` schedule columns are a cache**; `review_log` is append-only with an `undone_at` marker
  for undo. `review_log.card_id` is never rewritten.
- **"Same word added twice offline"** is resolved by the server: partial unique indexes allow one
  live note per entry/kanji/grammar point and one live card per (note, kind); the loser is kept
  as a tombstone with `merged_into_id`, and its reviews replay as part of the survivor. The
  server only *decides* the merge; every replica then performs it the same way (the loser's
  clocked values are forwarded to the survivor, and so is every later edit addressed to the
  loser), so nothing a still-offline device does to its duplicate is lost. A note's entry can be
  re-matched at any time; a re-match that lands on an existing note merges instead of failing.
- **Manual grammar highlights are keyed by what they highlight** (sentence, grammar point, span),
  not by a random id, and have no hard link to the sentence: they survive re-parses and media
  deletion and two devices highlighting the same span get one row.
- **Media and list decks are created only by the server**, so two devices cannot create the same
  one twice. The review log and the sync log are append-only by database trigger.
- **Word status** keeps the automatic value and the explicit override in separate columns, so
  clearing an override falls back to the automatic status without losing either.
- **Media**: the small `media` and `deck` rows go through ops; segments, sentences, occurrences
  and generated deck items are server-built and delivered as versioned snapshots
  (`content_version` + `content_blob_hash`), as the spec's "large content" rule says.
- **Device schema** targets the SQLite that ships with Android 8 (3.18): no generated columns, no
  JSON functions, arrays as JSON text. The dictionary is a separate SQLite file built by the worker.
- Built-in note types, the Default preset and the server device have fixed ids seeded on both
  sides; `check.sh` verifies the seeds and columns match between Postgres and SQLite.

## Alternatives rejected

- **Deterministic ids for dictionary-backed notes** (id derived from the entry, so duplicates
  cannot exist and no merge is needed). Simpler, but contradicts the spec's "all ids are UUIDv7"
  and "merged on the server". The schema does not prevent switching later: T005 could adopt
  derived ids and the merge columns would simply go unused.
- **Row-level last-writer-wins** (one clock per row). Would lose a concurrent tag edit when another
  device edits a field; the spec asks for per-field.
- **Postgres enum types.** Text + CHECK is easier to widen in a later migration and identical on
  SQLite.
- **Foreign keys from `app` to `ref`.** Would block dictionary refreshes and cannot hold on the phone.
- **A release id on every `ref` row** (several releases side by side). One current release, replaced
  in a transaction, is enough for one user; retired entries are flagged instead of deleted.
- **Storing leech tags, sibling burying, hot-word scores.** All derivable from the log; storing
  them would create values that two devices could disagree on.
- **FTS5 on the device.** Not reliably present in Android 8's SQLite; prefix indexes plus `LIKE`
  are enough at dictionary scale. Can be added later behind a bundled SQLite.

## Three choices most worth a second look

1. **`grammar_point` lives in `app.*`, not `ref.*`.** The spec lists it under reference data, but
   it is "your own library", edited by you and creatable offline from a highlighted span. It
   therefore syncs through ops like notes. If you would rather ship grammar points as read-only
   content with dictionary releases, this needs to change before T005.
2. **A fifth review type, `manual`.** The spec lists scheduled, learning, drill, cram. Bulk
   "reschedule" and "reset" in the card browser change a schedule without a review, and since the
   log is the only source of truth they must be log entries. `manual` rows carry `set_due` (with a
   date) or `reset` and no rating.
3. **Presets attach to cards (`card.preset_id`), and FSRS weights live on the preset row.** The
   spec says only "per preset". Because cards are global rather than deck-owned, the Anki habit of
   deck-level presets does not fit. Every card starts on the Default preset. New optimizer weights
   bump `weights_version`, which marks every card cache on that preset stale and triggers a full
   replay with the new weights — so due dates can shift after the weekly optimizer run. If you
   want weights to affect only future reviews, the scheduling contract (T006) must say so and the
   cache rule here changes.

## Known trade-offs (accepted, not fixed)

- **Tags are one value per note.** If two devices change a note's tags while both are offline, the
  later change wins as a whole and the other device's added tag is lost. This matches the spec
  ("last writer wins per field") and tags are cheap to re-add; per-tag merging can be added later
  without changing the table (extra `tag.<name>` registers in `clocks`).
- **A dictionary entry is always a vocab note.** `entry_seq` is allowed only on `vocab` notes, so
  a JMdict expression (e.g. 気を付ける) is carded as vocab and takes part in word status, coverage
  and de-duplication. The `phrase` type is for free-form phrases with no dictionary entry; those
  do not count towards coverage.
- **A note created as a duplicate does not overwrite with blanks.** Values left empty at creation
  carry no clock, so only what the duplicate actually filled in reaches the survivor.
- **Deleting a duplicate never deletes the survivor**, even if the delete was made before the
  device learned about the merge.
- **If one review is undone on two devices, the earlier undo is recorded.** Either way the review
  is undone.
- **The append-only triggers exist only in Postgres.** On the phone the same rule is the apply
  code's job.

## Smaller things to know

- The migration **refuses to run on a database whose `LC_CTYPE` is `C`**, because trigram search
  would silently return nothing for Japanese. CloudNativePG defaults to `C`; the Helm chart must
  set a UTF-8 ctype (`C.UTF-8` works).
- Deletes are permanent (no undelete), except removing an item from a user deck or a manual
  grammar highlight.
- `review_log.utc_offset_min` is empty when unknown (imports) rather than pretending UTC.
- No table yet for: the web password hash / TOTP secret (assumed to come from a Kubernetes
  Secret), a TTS cache index, cached coverage
  numbers. Each can be added by the ticket that needs it as an additive migration.
- The sync log (`change`) is never pruned in v1.

## C-1 changes

- A card's reviews are replayed in answer-time order (`reviewed_at`, then `hlc`, then `id`), as
  the sync contract already said; the old "(hlc, id)" wording is gone. A matching index was added.
- `card.last_review_at` is now the time of the last *rated* review. Rescheduling a card leaves
  it alone; resetting a card clears it. Without this, the first review after a reschedule would
  be treated as a same-day review and get a far too long interval.
- The phone remembers which scheduler version built its card schedules (`core_scheduler_version`);
  after an app update that changes the scheduler it recomputes every card first. The server keeps
  the same marker in a new server-only table, `app.server_state` (key/value, not synced, not in
  the API, never cleaned up). This is the only new table. It is not in the job table, because jobs
  are cleaned up and the API's job kinds are a closed list.
- No existing column, table or seed changed.

## C-2 changes
- **Device outbox text brought up to date.** SCHEMA.md 5.3 and the `Local.sq` comments now say
  what SYNC.md already said: an acked op may be deleted only once a verified server backup covers
  it, and a refused op leaves that state only by Retry, Discard or Re-mint. All `sync_state` keys
  are listed. Where SCHEMA.md and SYNC.md overlap, SYNC.md wins.
- **Backup/restore handshake.** `app.server_state` gets three more keys: `log_epoch`, `durable_seq`
  (written by the backup job once a backup is verified, never lowered) and `restore_pending` (the
  restore script sets it, and the server finishes the restore at startup). Only comments changed.
- The old table↔op↔API cross-check in §7 was stale. It is replaced by a pointer to SYNC.md §14 and
  the API summary. The `job.kind` comment now lists the API's job kinds.

## C-7 changes (settings keys; additive)

- Two new well-known settings in SCHEMA.md §6: `scheduler.kanji_before_vocab` (bool, default
  `false`) and `scheduler.mined_priority` (int, default `0`). These are the names the web settings
  page already writes. No table, column or op changed. `settings.set` still accepts any JSON.
- **Mined priority** uses the same scale as deck priority: lower is drawn first, and the range
  is the same int4 range. At the default 0, mined cards take turns with your priority-0 decks
  (ADR-006). Set it to -1 to put them first, or to 1 to put them after all priority-0 decks.
- **Kanji before vocab** only changes the order of cards. It applies to decks that have no
  `add_kanji` setting of their own, and to mined cards. A deck's own `add_kanji` (including
  `off`) always wins. It never creates kanji cards.
- §6 now says plainly: any key is stored; only well-known keys are type-checked, and only by
  `PATCH /settings` (422 at `/values/<key>`). Values that arrive by sync are not checked, so a
  bad value is read as the default.
- **One validity test, used everywhere** (the API check and the Android, web and server loaders),
  so every device builds the same queue. A setting of type int is valid if it is a JSON number
  whose value is a whole number in range: `1.0` and `1e2` are fine and mean 1 and 100. Anything
  else falls back to the default. A bool must be exactly `true` or `false`. A deck's `add_kanji`
  counts only if it is exactly `"off"`, `"before"` or `"alongside"`; anything else, such as
  `"Before"`, is treated as not set, so kanji-before-vocab applies.
- Kanji cards pulled in front of a word still need an enabled deck, or no deck at all, to be shown
  (ADR-006).
- The core contract follows the same rules (`QueueBuilder.kt`). The priority key is now called
  `scheduler.mined_priority` there too; it was `deckless_new_priority`. The new
  `QueueInput.decklessAddKanji` (default `OFF`, so existing callers still compile) holds the kanji
  ordering for mined cards.
- Worth a second look: whether kanji-before-vocab should override a deck's explicit `add_kanji`,
  instead of only filling in where the deck has none.

## C-9 changes (internal job kinds; additive, no DDL)
- `app.job` may hold **server-internal kinds**: any `kind` that is not an API `JobKind`. The
  existing `CHECK` (a slug regex) already allows them, so `V1__init.sql` is unchanged (its
  `job.kind` comment is stale; SCHEMA.md 4.4 is authoritative). The only one is
  `jpdb-history-apply` (worker plans, server applies through OpApply).
- Alternative rejected: a `parent_job_id` column or an `internal` flag. Both need DDL in an
  applied migration; a payload member and the "not in `JobKind`" rule need none.

## C-11 changes (media pipeline; additive: `V2__jpdb_vocab.sql`, new documents)
- **Snapshot archives defined** (`SNAPSHOT.md`): a media's content and a deck's items are each
  one zstd-compressed JSON Lines file: a header line (which media or deck, which version, how
  many records follow) and then one line per row, in a fixed order. Records match the table
  columns. `media/validate.py` checks an archive against per-record JSON Schemas plus every
  cross-row rule (counts add up, occurrences are exactly what the tokens say, a media deck's items
  are exactly the media's words). Worked examples are checked in under `media/examples/`.
- **Only the server writes media rows.** The worker builds the archives, uploads them, and hands
  them to the server through a second internal job kind, `media-publish`. The server creates
  the media and its deck (first import), installs segments, sentences, occurrences and deck
  items, and announces the new version, all in one transaction (`MEDIA_PIPELINE.md` 3).
  Devices install the same archives with the same rule. (Refines ADR-014.)
- **Concurrent publishes of one media** (an append import during a re-parse): the second one is
  refused as stale and its worker rebuilds on top of the first, so nothing is lost.
- **`parse_status`**: a new media appears already `parsed`. For an existing one, the request
  that queues work sets `parsing`; the publish sets `parsed`; a server reconciler sets `failed`
  (or back to `parsed` after a cancel) when the work ended without publishing.
- **jpdb review queue**: two server-only tables (`jpdb_vocab`, `jpdb_vocab_media`) hold the jpdb
  words that did not map, which media contain them, and your fix (map to an entry, or ignore).
  A fix is import configuration, not learning data: no op, not synced, so no app update is
  needed. Each fix re-publishes the affected jpdb decks from the importer's cache. (Refines
  ADR-015.)
- Deck items from a snapshot get `added_at` = the deck's creation time, so every replica holds
  identical rows.
- `check.sh` runs Postgres on a tmpfs data directory (it previously failed when the Docker disk
  was full) and applies V2 after V1.
- Worth a second look: (1) media decks list every word, particles included (`unique_entries`
  already meant that); (2) the snapshot integrity rule that occurrences equal the token
  aggregation ties the formats to the token shape C-12 finalises; (3) corrections are not in
  exports or the sync log, so a device restore cannot recover them (the server backup does).

## C-16 changes (FSRS weights publication; additive, no DDL)
- **What was missing:** the optimizer runs in the worker, but only the server may change learning
  data, and there was no defined way to pass the result across. T606 could not be built.
- **Decided:** a third server-internal job kind, `fsrs-params-publish` (`FSRS_PUBLISH.md`). The
  worker's `fsrs-optimize` job trains, measures the old and the new weights on reviews it kept
  out of training, and only if the new ones are strictly better hands them over. The server
  checks that rule and the weight bounds again, then mints one `fsrs_params.published` with the
  next `weights_version`, all in one transaction with marking the hand-off done.
- **Not publishing is a success, not a failure:** too few reviews, no improvement, weights out
  of range, or a deleted preset end the job `succeeded` with `published: false` and a `reason`.
- **Never twice:** a retried job finds its earlier hand-off and reuses the result; a second
  hand-off for the same job is a no-op; and every hand-off names the `weights_version` it was
  measured against, so one that arrives after the preset moved on is refused (`stale_base`).
- **Stats:** `optimizer_stats` gains `log_loss_before` and `holdout_review_count` (the object
  was already open, so the op schema and devices are unchanged).
- `optimizer/validate.py` is the server's decision as a small reference function with two worked
  examples and 24 negative cases each; `check.sh` runs it.
- Alternatives rejected: (a) the worker writes its numbers into the parent job's `result` and the
  server watches for finished `fsrs-optimize` jobs: the public result would double as a private
  protocol and "succeeded" would be visible before the weights exist; (b) an internal HTTP
  endpoint on the server for the worker: a new authenticated surface, where C-9/C-11 already
  give a queue with retries; (c) a deterministic op id derived from the parent job: op ids are
  UUIDv7 everywhere else, and the same-transaction rule already gives exactly-once.
- Worth a second look: (1) the comparison is strict (`<`) with no minimum margin, so a 0.0001
  improvement publishes and triggers a replay of the preset's cards; (2) how reviews are selected
  and split, and the minimum count, are left to T606 as worker configuration; the contract
  only requires the held-out reviews to be unseen in training; (3) a job cancelled in the last
  instant can end `cancelled` with the weights already published (not reverted).

## C-21 changes (JLPT vocabulary lists for list decks; additive: `V3__vocab_list.sql`, `VOCAB_LISTS.md`)

- **Where JLPT membership lives:** three new server-only reference tables, `ref.vocab_list`
  (one row per list, id = the deck `list_key`, with row accounting), `ref.vocab_list_item`
  (list, entry seq, source position) and `ref.vocab_list_unmatched` (every source row that
  matched nothing, with a reason). Replaced wholesale by each dict-refresh in the transaction
  that makes the release current. Not on devices.
- **Source:** Jonathan Waller's JLPT lists (CC BY) via stephenmk/yomitan-jlpt-vocab (CC BY-SA
  4.0), which carries JMdict seqs; pinned to a commit with SHA-256 per file, and on by default
  (no deployment config needed).
- **Matching:** by JMdict seq if live, else exact kanji+kana forms (unique live candidate);
  a word belongs to its easiest level only; source rows = items + duplicates + shadowed +
  unmatched, enforced by a `CHECK`.
- **Builds:** the worker's `deck-build` computes items (JLPT: by global frequency, then source
  order), refuses to publish an empty list, publishes through a new internal `deck-publish`
  kind (a deck-only `media-publish`); identical rebuilds publish nothing. Every new release
  re-queues a build for each live list deck.
- Alternatives rejected: (a) JLPT levels as `freq_list` rows: would feed `dict_entry.freq_rank`,
  spawn `freq-jlpt-…` bands and ship to devices; (b) the server builds list decks itself
  (simpler, but a second archive producer and heavy work in the API process); (c) a JLPT
  column on `dict_entry`: not a list, can't hold the unmatched report.
- Worth a second look: (1) shadowing — N4 excludes words also listed in N5 (jisho-style), so
  the five decks are disjoint; (2) JLPT decks ordered by global frequency, not the list's
  alphabetical order; (3) the source repo's CC BY-SA 4.0 licence (upstream CC BY), personal use.
