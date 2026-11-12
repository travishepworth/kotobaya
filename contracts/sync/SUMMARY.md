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
- **Reviews:** all kept. A card's schedule is recomputed from scratch whenever a review arrives
  that belongs earlier than one already counted, a review is undone, cards are merged, or the
  card's preset or its settings change.
- **A wrong phone clock is bounded.** The server refuses an op stamped more than a day ahead of its
  own clock, and a review dated before 2020 or before its card existed. The phone warns at 5
  minutes off and stops recording reviews at a day off. A refused op can only be discarded, which
  also puts the phone's clock state right.
- **A restored server is detected.** Every sync answer names the log (`log_epoch`, new after every
  restore) and says how far the last verified backup reaches (`durable_seq`). A phone keeps its
  own synced changes until a backup covers them; when it sees a new log it sends them again and
  re-downloads everything. The server's clock is moved forward on restore so it never repeats one.
- **Rejected ops** (should only happen with a client bug, a too-new client or a wrong clock) stay visible on the
  device with the reason. The user can retry or discard. Discarding rebuilds the phone's learning
  data from the server's log, because that is the only rollback that is certainly correct.
- **"Converged" is defined precisely** for the tests: all rows equal, except that a note or card
  that was merged away is compared only on its id, what it was merged into, and when.
- **15 machine-readable scenarios** cover every conflict case in the spec and every follow-up
  from the database review. A small reference model replays each one in 300 random orders with
  duplicate deliveries and checks the result is always the same. The model also plays the server:
  every merge decision in the scenarios is computed from the phones' ops, not written by hand, and
  a simulated backup-restore shows the old rules losing reviews and the new ones losing none.

## Alternatives rejected

- **Accepting any clock (attempt 1).** A phone set a year ahead would have hidden cards for a year
  on every device and won every edit conflict; one reset to 1970 would have produced a 56-year
  interval. Rejected in review: the reviews made with a badly wrong clock are lost instead.
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

1. **Reviews made with a clock more than a day off are lost.** The server refuses them; the phone
   blocks reviewing once it notices, but anything recorded before the first sync is discarded.
   Below a day, edits from a fast clock can win conflicts for up to that day.
2. **Discarding a rejected op re-downloads everything.** On a phone with years of history that
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
- If a new note is refused by the server, the reviews of its cards are refused too and can only
  be discarded (a client bug is the only cause; the client checks notes before creating them).
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
- Four extra bookkeeping keys on the phone (`rebuild_pending`, `log_epoch`, `durable_seq`,
  `hlc_acked`) are defined here, in the existing key/value table. The database contract's wording
  "acked rows may be purged once pulled" is narrowed here to "once pulled and backed up"; the
  database contract should say the same and list the keys (follow-up for `contracts/db`).
- The server stores `log_epoch` and `durable_seq` where the server and deploy tickets choose; the
  backup job sets `durable_seq` after verifying a backup.
