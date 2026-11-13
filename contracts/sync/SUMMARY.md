# Sync contract — summary for the human

## What was decided

- **45 op types**, with exactly the names the database contract already used (`settings.set`,
  `grammar.*`, …). Each has a JSON Schema, a conflict rule, and at least one example
  (`OPS.md`, `ops/`, `examples/ops/`). The check script fails if any of the three is missing.
- **One op = one row change.** Bulk actions are many ops. An op carries its own id, the device
  that made it, and a clock; nothing else identifies it.
- **The op's clock is its timestamp.** There is no separate "created at" in an op: created,
  updated and deleted times are read from the clock, which is why they come out identical on the
  phone and the server. Only a review carries a separate "when was this answered" time.
- **Push** sends up to 500 ops; the server answers per op: accepted, duplicate, or rejected with a
  reason code. One bad op never blocks the rest. **Pull** returns everything after a sequence
  number, including the device's own ops (applying them again is harmless by design, so there is
  no special case for them).
- **Every replica runs the same apply rules.** The server additionally validates, and decides
  merges. Rules are written so that applying the same ops in a different order, or twice, gives
  the same data.
- **Deletes are permanent and beat every edit, older or newer.** Edits that arrive for a deleted
  item are still recorded on it, but it stays deleted.
- **Same word added on two devices:** the server picks the survivor (the one it saw first) and
  announces it; each device then merges from its own data. Later edits aimed at the duplicate
  are applied to the survivor too.
- **Reviews are replayed in the order they were answered** (`reviewed_at`, then clock, then id),
  not in clock order. Every device stores those values identically, so the order is the same
  everywhere; a review that arrives late with an earlier answer time forces a full recompute.
  This keeps re-sent reviews from landing after other phones' later ones.
- **Op size is capped** (default 1 MiB per op, published by the server). The app refuses to make a
  bigger change and tells the user (embed fonts and images as media, not in a note type's CSS).
  If a push is still too big the app halves it, down to one op; one op that never fits is marked
  refused like any refused op, so it never blocks the rest.
- **Notes of a deleted note type stay usable** (they are not hidden), as the API says.
- **Reviews:** all kept. A card's schedule is recomputed from scratch whenever a review arrives
  that belongs earlier than one already counted, a review is undone, cards are merged, or the
  card's preset or its settings change.
- **A wrong phone clock is bounded, and reviews made with one are not lost.** The server refuses
  an op stamped more than a day ahead of its own clock (plus a minute of slack), and a review dated
  before 2020. The phone checks its own clock before every review, offline: if it reads before
  2020, or more than a day before a time it has already seen, it says "fix your clock" and records
  nothing. If reviews are refused anyway (a clock that was ahead, or an old app), the default action
  re-logs them: same answers, new ids, times shifted by the measured clock error as one block, so
  the gaps between reviews (what the scheduler uses) stay right; times are kept between the card's
  creation and now. Words added offline with the wrong clock are kept too: the default action
  re-sends them (same ids) with everything that depends on them — their cards, later edits to
  them, and the reviews of those cards — dated by the same measured error, before the reviews. If
  another phone added the same word meanwhile, it is merged as usual. Only edits to things the
  server already had are discarded (they could wrongly beat other phones' edits; they are easy to
  redo, reviews are not). Either action also puts the phone's clock state right.
- **A restored server is detected.** Every sync answer names the log (`log_epoch`, new after every
  restore) and says how far the last verified backup reaches (`durable_seq`). A phone keeps its
  own synced changes until a backup covers them; when it sees a new log it sends them again and
  re-downloads everything. The server's clock is moved forward on restore so it never repeats one.
- **Rejected ops** (should only happen with a client bug, a too-new client or a wrong clock) stay visible on the
  device with the reason. The user can retry or discard. Discarding rebuilds the phone's learning
  data from the server's log, because that is the only rollback that is certainly correct.
- **"Converged" is defined precisely** for the tests: all rows equal, except that a note or card
  that was merged away is compared only on its id, what it was merged into, and when.
- **16 machine-readable scenarios** cover every conflict case in the spec and every follow-up
  from the database review. A small reference model replays each one in 300 random orders with
  duplicate deliveries and checks the result is always the same. The model also plays the server:
  every merge decision in the scenarios is computed from the phones' ops, not written by hand, and
  a simulated backup-restore shows the old rules losing reviews and the new ones losing none, and a
  simulated week of 700 reviews on a phone reset to 2000 is re-logged with the right times and
  converges; and a simulated week on a phone 10 days ahead (100 new words with cards, edits and
  700 reviews, one word also added by another phone) keeps every word, edit and review, with the
  real dates, and converges.
- **After a server restore**, a phone's changes that were refused only because another phone had
  not yet re-sent its lost changes are retried automatically (up to 10 times).

## Alternatives rejected

- **Correcting a wrong review time on the server.** The phone's own review comes back to it in the
  next download and is ignored there (a review id is written once), so the phone would keep the
  wrong time and disagree with everyone else. Re-logging under a new id avoids that.
- **Clamping each review on its own to "not before its card, not after now".** With a badly wrong
  estimate of the clock error every review would land on the same instant and the scheduler would
  see a week of reviews as zero time apart. The whole batch moves together instead.
- **Accepting any clock (attempt 1).** A phone set a year ahead would have hidden cards for a year
  on every device and won every edit conflict; one reset to 1970 would have produced a 56-year
  interval.
- **Refuse-and-discard only (attempt 2).** A phone whose clock reset to 2000 lost a whole offline
  week of reviews.
- **Re-sending only reviews (attempt 3).** A phone whose clock was ahead lost every word it added
  offline, and every review of those words: the server refuses the new word for the clock and
  everything under it as "unknown", which re-sending reviews alone did not cover.
- **Re-sending offline-created words stamped "now".** Simpler, but their creation dates would be
  the sync day, and when the word is merged with another phone's copy, the re-sent edits would
  beat edits that phone made later in the week. They are stamped with the corrected original time
  instead.
- **Re-sending edits of existing items with a corrected time too.** It would decide real conflicts
  with other phones on an estimated time that can be days off; edits are cheap to redo.
- **A server-side "review not long before its card" check using the server's own receive time of
  the card.** A card created offline is legitimately reviewed days before the server receives it,
  and re-sent cards arrive later still. Kept as a known issue instead (below).
- **Size limits in the op schemas** (max lengths on fields or CSS). Tightening a published op
  version could make ops already in the log invalid, and a byte limit per op cannot be written in
  JSON Schema anyway; the limit is a number the server publishes and both sides check.
- **The server refusing a review dated more than a day before its card (attempt 2), now dropped.**
  The card's creation time comes from another phone's clock, which may itself be up to a day
  ahead, so two phones that were each within the tolerance (one 23 h ahead, one 2 h behind) got a
  correct review refused. Comparing with when the server received the card does not work either:
  a phone that created the card offline legitimately reviews it before the server has it. The
  phone's own check is exact instead: it has always seen the card's creation time before it can
  review the card, so "not more than a day before anything you have seen" covers it, and re-logged
  reviews are kept after their card's creation. Without the server check, a review from a broken
  app can be dated up to the 2020 floor before its card; the scheduler only sees a longer first gap.
- **Re-pairing a phone to fix a clock that ran ahead.** Correct, but it would strand the phone's
  other unsynced changes under the old device id. Lowering the clock to the last value the server
  acknowledged is just as safe.
- **Re-homing the children of a rejected note** onto another note. Needs rewriting rules per op
  type for a case only a client bug produces; the client validates new notes locally instead.
- **Undo information per op, to roll back a rejected op precisely.** More code on the path that
  must never be wrong, for an event that should not occur. Rebuilding from the log is slower but
  obviously right.
- **Skipping your own ops on pull.** Saves a little work, adds a special case, and breaks the
  rebuild. Not worth it.
- **Sending merged values inside the merge op.** The result would depend on what the server
  happened to hold at that moment; devices that had newer local edits would lose them.
- **Following the full chain of merges (A into B into C) instead of pointing straight at C.**
  Would make even merged-away rows identical everywhere, at the cost of chains everywhere a reader
  looks. Chose flat pointers and a narrower definition of "converged".
- **Open payloads that tolerate unknown fields.** Friendlier to old clients, but typos pass
  silently. Payloads are closed; an old client that meets an op it does not know stops pulling
  and asks for an update.
- **A wall-clock field in every op next to the clock.** Two sources of truth for the same time.

## Three choices most worth a second look

1. **Re-logged reviews and re-sent words keep their gaps but not always their exact times.** The
   clock error is measured at the first refusal. If the clock was changed again in between (the
   user fixed it before syncing), the whole block is moved just far enough to sit after its cards'
   creation and before now, so gaps are right but absolute dates can be off — and for a re-sent
   word that another phone also added, that estimated date decides which phone's edit wins. Edits
   of existing items made with the wrong clock are still lost, and below a day, edits from a fast
   clock can win conflicts for up to that day.
2. **Discarding a rejected op (or re-logging reviews) re-downloads everything.** On a phone with years of history that
   is a long sync, during which reviewing is blocked, and it needs a connection. It should
   essentially never happen; if it turns out to, a targeted rollback can replace it later without
   changing the protocol.
3. **Changing a preset's settings reschedules its cards.** The database contract says a card's
   due date is a pure function of its reviews and its preset. Followed strictly, changing desired
   retention or learning steps recomputes every card on that preset from its full history, on
   every device. That keeps devices in agreement, but it means past intervals are re-derived with
   the new settings (Anki only applies new settings going forward). The scheduling ticket (T006)
   must confirm this is wanted.

## Known trade-offs (accepted, not fixed)

- Whether two things get merged can depend on which device syncs first (re-matching a note to a
  different word, or deleting a word on one phone while adding it on another). All devices end up
  agreeing with the server either way.
- If you add a word that already exists, then delete your new copy before syncing, whatever you
  typed into the copy still lands on the original.
- If you delete a word on one phone while another phone adds it again, and the add syncs first,
  the word ends up deleted on both. The reviews are kept.
- A merge can give the surviving word a card type you had removed from it.
- A newer value typed into a duplicate replaces the original's value for that field.
- One op type (media parse status) relies on being delivered in server order. It always is.
- A brand-new device downloads the whole history; the log is never trimmed in v1.
- A phone a little behind can be stopped by its clock check if another phone was nearly a day
  ahead (together more than a day); it is told to fix its clock.
- After a restore, items created or changed on the server for up to a day show times up to a day
  ahead (the server's clock is moved forward so it never repeats itself).
- If a new note is refused by the server for anything but the clock, the reviews of its cards are
  refused too and can only be discarded (a client bug is the only cause; the client checks notes
  before creating them).
- If only part of an offline period with a fast clock is refused (ops pushed within a day of
  being made pass the 24 h tolerance), the accepted part keeps its too-late times and the re-sent
  part is moved back, so the two can replay slightly out of their real order.
- A review from a broken app can be dated up to the 2020 floor before its card (no server check;
  the phone's own clock guard prevents it in a correct app).
- After a server restore, changes made through the REST API or by server jobs since the last
  backup are lost (jobs should be re-run); phones' changes are not. Restoring anything but the
  newest verified backup can lose changes phones have already cleared.
- Phones keep their synced changes until a backup covers them; with backups disabled the server
  reports everything as covered.
- Removing a word from a deck removes every entry for it in that deck, but if two copies of the
  word are merged only later, the second entry can reappear.
- A rebuild that stops at an op the app does not know finishes with what it has; reviewing works,
  the rest arrives after an app update.

## Smaller things to know

- REST calls (scripts, AnkiConnect) become ops stamped with the server's own device id, so a
  review made through the REST API shows the server as its device. The calling token is in the log.
- A phone restored from a backup must be paired again.
- The format of the downloadable snapshots (media content, generated deck contents) is not
  defined here; the ops only announce a version and a file hash.
- Five extra bookkeeping keys on the phone (`rebuild_pending`, `log_epoch`, `durable_seq`,
  `hlc_acked`, `remint_offset`) are defined here, in the existing key/value table. The database contract's wording
  "acked rows may be purged once pulled" is narrowed here to "once pulled and backed up"; the
  database contract should say the same and list the keys (follow-up for `contracts/db`).
- `max_op_bytes` must be published by T004 in `GET /meta` as `limits.max_op_bytes` (default
  1 MiB, at most `max_push_bytes` − 4 KiB), and the server must answer an oversized single op with
  per-op `op_too_large` (follow-up for `contracts/api`).
- The server stores `log_epoch` and `durable_seq` where the server and deploy tickets choose; the
  backup job sets `durable_seq` after verifying a backup.

## C-1 changes

- **Rescheduling doesn't reset the review clock.** A card's "last reviewed" time is the last
  real (rated) review; a reschedule leaves it alone, a reset clears it.
- **Scheduler upgrades recompute everything first.** When the app or server runs a new scheduler
  version, every card's schedule is rebuilt before any new review is added
  (`core_scheduler_version`: on the phone in its bookkeeping table, on the server in the new
  server-only `app.server_state` table). The server re-checks the marker while holding the sync
  lock, so two server instances never redo the work.
- **A broken op from the server stops the pull**, like an unknown op: never skipped, shown as
  "update required / server sent an invalid op".
- **Op size is measured the same way everywhere:** bytes of the op in the core's `CanonicalJson`
  form, checked first, so an oversized op is `op_too_large` even if it is also unknown or invalid.
- **Published FSRS weights are range-checked:** exactly 21 FSRS-6 weights, each within py-fsrs
  6.3.2's bounds; anything else is rejected. Together with the scheduler's own limits this keeps
  schedules sane.
- **Rule for later:** once an op version has shipped, its format is never made stricter; a stricter
  format needs a new op version. Tightening the published-weights op was fine only because
  nothing is deployed yet.
- A review after a manual reschedule of a card that was never reviewed counts as its first review.

## C-2 changes
- **Duplicate JSON keys in a push.** The server reads the body with a parser that tolerates
  duplicate keys. A duplicate at the top level, or one `op_id` used twice with different
  contents, rejects the whole request (400). A duplicate inside one op rejects only that op
  (`schema_invalid`), before the size check, because such an op has no defined size. Clients
  always send the canonical encoder's output, which never has duplicates. Six raw example bodies
  in `examples/raw-push/` are checked by `check.sh`.
- **Backup and restore spelled out (new 6.3).** The backup job records how far a verified backup
  reaches (`durable_seq`), only if the log is still the same one. The restore script only sets a
  `restore_pending` flag. The server then does the rest at startup, under the op lock: a new log
  epoch, a new `durable_seq`, and a raised clock. Deploy and server can each build their part from
  this text alone.
- `insufficient_scope` now has a place in the rejection order (right after `device_mismatch`).
- A vocab note type must have the fields `expression`, `reading` and `meaning`
  (`invalid_value` otherwise). Custom types such as a Takoboto one map their fields onto these.

## C-5 changes
- **Schema patterns mean the same everywhere (new SYNC.md 2.1).** Patterns are ECMA-262, as JSON
  Schema says: `$` is the end of the text (an id with a trailing newline is invalid), and "space"
  is JavaScript's set (so a tag with an ideographic space, a no-break space or U+FEFF is invalid).
  Java and Python regexes differ here, so the server and the Python tools translate patterns.
  Without this the server could accept an op that the web client then refuses on pull, which stops
  that client's pull for good. `check.sh` now validates with these semantics; four new
  `examples/invalid` cases.
- **Two depth limits.** An op is at most 500 levels deep; a whole sync document (push or pull
  body) at most 512. So any legal op fits inside any envelope and a pull never fails on depth. A
  too-deep op in a push rejects only that op (`schema_invalid`); a body deeper than 512 is a 400.
  New raw push examples `op-too-deep` and `body-too-deep`.
- Worth a second look: the pattern translator raises on any regex feature it does not know, so a
  future schema using one fails `check.sh` until SYNC.md 2.1 gives it a meaning.

## C-6 changes (breaking, before any deployment)
- **Every valid value can be stored (new "Storable values" in SYNC.md 2.1).** Some schema-valid
  ops could not be written to Postgres, so the server could refuse them only after a device had
  made them. Now the contract rules them out first:
  - No U+0000 anywhere in an op (Postgres text and jsonb refuse it). Every typed string schema
    has the pattern `^[^\u0000]*$` (tags: `^[^\s\u0000]+$`). Strings and keys inside open JSON
    (settings values, unknown kept keys) are covered by a new op-level rule `NO_NUL`, checked by
    the same walk as the depth limit.
  - Integers stored in int4 columns have `maximum: 2147483647` (deck daily limits, learning and
    relearning steps, `content_version`, `weights_version`).
  - Instants stop at 9999-12-31T23:59:59.999Z (RFC 3339 cannot write a 5-digit year).
  - `parse_override.put` `match_text` is at most 200 code points (it has a btree index; the API
    already capped it at 200). `list_key` and `jpdb_id` (unique indexes) too.
  - `maximum_interval_days` is at most 36500, as in the API (attempt 2). With a huge maximum and a
    low retention the scheduler computed a `due_at` Postgres cannot store, so the server failed
    every retry of the next review and the outbox stuck. Derived cache columns now count as
    "storable" too. The 9999 cap is for op values only: a derived `due_at` can reach about year
    10099, so the server keeps writing 5-digit years.
- **REST and importers never build an invalid op.** REST answers `422 invalid_value`; importers
  split tags on JavaScript's whitespace set, drop and count the bad ones, and strip U+0000.
- SYNC.md 3.2/6.3 and SCHEMA.md 4.4 now name the server's clock row: `server_state.hlc_last`.
- Doc fixes: patterns are matched per code point (as with the `u` flag) and must work with and
  without it; a reschedule (`set_due`) of a new card changes nothing; `ReplayEngine.replay` lists
  what it throws on.
- **Alternative rejected:** one recursive "no NUL anywhere" schema. Simpler to read, but the core
  validator recurses once per nesting level, and on a legal 500-deep op the test JVM ran out of
  stack. So the rule went next to the depth walk instead.
- **Migration:** no real data exists. A development database cannot hold U+0000 at all. It could
  hold an instant after 9999 or a long `match_text` only in theory: a dev reset clears them.
  Reset dev device replicas and outboxes as well as the server
  database. Server: until core implements `NO_NUL` (T202d), T202b's interim `invalid_value` catches what
  the schemas miss.
- **Worth a second look:** (1) `NO_NUL` is a rule outside JSON Schema (like depth), so a plain
  schema validator misses U+0000 inside open JSON. (2) The 9999 cap is based on the API's text
  format, not on Postgres. (3) The `match_text` cap is new for sync but matches the API.

## C-8 changes (gaps found in the T202c review; attempt 2 after review)
- **A card the replay engine refuses (new SYNC.md 9.4).** The engine throws on a duplicate review
  id or a row that breaks the `review_log` checks. A correct replica never sees this. When it does
  happen, the server and every device now do the same thing: the card gets a fixed "refused" cache
  (state new, `last_review_id` = the nil UUID `Reserved.REPLAY_REFUSED_ID`), and the op still
  commits. `ReplayEngine.decide` (new step 1b) always answers full replay for such a cache, so
  nothing is ever folded into it. The card heals when the bad row is undone or the core is fixed.
  Queue loaders must leave refused cards out, so they are not offered as new every day.
- **Backups off, then on again (SYNC.md 6.2, 6.3).** With backups disabled the server now
  *persists* `durable_seq` at the newest seq, on every commit and at every startup. Re-enabling
  backups lowers nothing, so `durable_seq` never goes down within an epoch. Each backup now records
  the live `durable_seq` next to it. That lets the restore tool warn about a backup older than
  what devices may have purged, even when the live database is gone.
- **Re-mint: rules and text now agree (SYNC.md 10.4).**
  - An op that depends on a row created in the window is re-minted only if the row it *writes* is
    itself new: its `entity_id` contains a window id. That holds whichever code the op got.
  - Otherwise it is discarded like any edit of a shared row (new list `D`). Example:
    `card.preset_set` of an old card to a new preset.
  - New, after review: deck items `e:<seq>` and word statuses whose seq belongs to a word created in
    the window are *window-derived* and re-minted. The deck items are re-keyed `n:<note>`, which
    8.5 already treats as the same item. So a week of offline mining into "Mining" survives.
  - Attempt 3: a removal is re-keyed only when the window also re-keys a put of the same item;
    otherwise it removes the shared `e:<seq>` row. A put whose note was deleted in the window keeps
    `e:<seq>`. Word statuses and un-re-keyed items are shared registers decided by the estimated
    time. With `clock_behind`, a re-minted status can beat a later status from another device.
    That is accepted.
  - Attempt 4: a removal that is re-keyed is minted twice, on `n:<note>` and on the original
    `e:<seq>`. This covers an item that existed before the window and was re-put and then removed
    in it. Not done (optional): also re-minting a re-keyed put on `e:<seq>` to keep a reorder
    visible. It would re-add an item another device may have removed, so it is not trivially
    safe; a reorder of a pre-window item can be hidden by the old row's smaller position.
  - Before the user confirms Re-mint, the device must show the list of ops it will discard.
  - Three scenarios in `examples/remint-scenarios/`; `validate.py` recomputes the selection, the
    re-keyed payloads and the shift.
- **Also:**
  - Core `QueueBuilder` rule 4 (decided by the orchestrator): a vocab card and its add_kanji kanji
    are drawn as one group or not at all. The kanji count against their own home deck, even past
    its `newPerDay`, and never against the drawing deck. `today` counts them the same way.
  - SCHEMA.md 6.1: settings values are stored in canonical JSON (`1.0` → `1`).
- **Alternatives rejected:**
  - `weights_version = -1` as the stale marker: it needs a migration.
  - Discarding window-derived items and statuses (attempt 1): it loses whole offline mining
    sessions.
  - Re-minting them with the `e:` key: that row is shared, so an estimated time would decide it
    against other devices.
- **Worth a second look:**
  - (1) A window-derived word status is re-minted at an estimated time, so it can beat another
    device's earlier choice for that entry.
  - (2) Items re-keyed `n:<note>` mean the deck holds `n:` keys where a live client would have
    written `e:` keys.
  - (3) The re-mint scenarios live in their own directory to keep the server's file-count tests
    green.

## C-10 changes (log reset sets `pull_seq = 0`; non-breaking)

- **Problem (found reviewing T303a):** the 6.2 log reset kept the old `pull_seq`, so after a
  restore below it every later response failed `high_water_seq < pull_seq` again, including the
  re-push of rebuild step 0. Example: a device pulled to seq 10 (3–10 were server-minted REST
  edits), the server was restored to seq 2, the re-pushed op got seq 3: endless resets,
  `rebuild_pending` never cleared, reviewing blocked forever. `tools/model.py` had the same bug.
- **Decided:** the log reset also sets `pull_seq = "0"` (SYNC.md 6.2), in the same transaction.
  Safe because the device neither pulls nor purges while `rebuild_pending` is set, and rebuild step 1
  sets 0 anyway.
- **Checked:** new worked example SYNC.md 12.5.1; `validate.py` `restore_below_pull_seq_story` runs it
  with the new rule (converges) and with the old one (the model raises `Livelock`).
- **Alternatives rejected:** skipping the `high_water_seq < pull_seq` test while `rebuild_pending` is set
  (one more special case for every client; resetting the cursor removes the stale value instead of
  working around it); comparing only `log_epoch` (loses the backwards-log safety net).
- **Worth a second look:** (1) a log reset now also hides any "n changes to download" count until
  the rebuild's pull, which is cosmetic.

## C-16 (note only; no schema, op or model change)
- `fsrs_params.published` in OPS.md now names its only minter (the server's
  `fsrs-params-publish` runner, `contracts/db/FSRS_PUBLISH.md`) and the two extra
  `optimizer_stats` keys it writes (`log_loss_before`, `holdout_review_count`). The op schema is
  unchanged: `optimizer_stats` was already an open object.

## BUG-6 changes (replay semantics for imported jpdb history; core scheduler version `fsrs6-2`)
- **Problem:** py-fsrs treats Hard on a (re)learning card as "repeat this step" whatever the
  elapsed time; jpdb "hard" (ADR-008: a pass) is its most common grade, so ≈ 1,100 imported cards
  never left (re)learning.
- **Decided:** SYNC.md 9.5. An imported row (`import_source = 'jpdb'`) that passes (Hard/Good/Easy)
  a (re)learning card ≥ 1 day after the last rated review is folded with both step lists empty:
  FSRS's own S/D update, state `review`, interval from the new stability (fuzzed as usual). Native
  rows unchanged; the FSRS scheduler and its golden vectors unchanged. `ReplayReview.importSource`
  added (defaulted, additive). No op, schema or table change. Version `fsrs6-1` → `fsrs6-2`, so the
  10.5 startup replay re-derives every cache once on each replica. Worked example SYNC.md 12.8.
- **Alternatives rejected:** remapping jpdb hard → Good (contradicts ADR-008 and inflates
  stability); a migration rewriting card caches (caches are derived; 10.5 already does this
  safely); graduating on any import pass regardless of gap (jpdb same-session re-tests would
  skip FSRS's short-term handling); a heuristic on `hlc`/`device_id` instead of `import_source`
  (REST reviews share the server device id).
- **Worth a second look:** (1) the one-day boundary is exact milliseconds, not the user's day
  boundary (pure, time-zone free; matches FSRS's same-day switch); (2) the rule reads only the row
  being folded, so a native pass after imported failures keeps py-fsrs steps; (3) every replica
  must pass `import_source` to the engine — a replica that drops it silently computes the old
  caches under the new version string.
