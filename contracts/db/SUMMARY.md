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
  Secret), the jpdb "unmapped vocabulary" review queue, a TTS cache index, cached coverage
  numbers. Each can be added by the ticket that needs it as an additive migration.
- The sync log (`change`) is never pruned in v1.
