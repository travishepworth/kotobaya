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
  (`core_scheduler_version` on the phone; a finished `core-replay` job row on the server).
- **A broken op from the server stops the pull**, like an unknown op: never skipped, shown as
  "update required / server sent an invalid op".
- **Op size is measured the same way everywhere:** bytes of the op in the core's `CanonicalJson`
  form, checked first, so an oversized op is `op_too_large` even if it is also unknown or invalid.
- **Published FSRS weights are range-checked:** exactly 21 FSRS-6 weights, each within py-fsrs
  6.3.2's bounds; anything else is rejected rather than producing broken schedules.
