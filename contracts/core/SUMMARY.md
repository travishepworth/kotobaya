# Core contract — summary for the human (T006)

## What was decided
- **Interfaces only, in `kotobaya.contracts`.** Eleven areas: HLC rules (incl. the device clock guards and the re-mint shift), canonical JSON + op codec, FSRS scheduler, replay engine + `review_log` rules, queue builder + word auto-status, card generator, hot scorer, search parser, card renderer, and the web facade `CoreJs`. All pure functions: no clock, no I/O, no randomness that isn't seeded from stored data. Callers pass rows in; the core returns values out.
- **FSRS = py-fsrs 6.3.2 behaviour**, mapped onto our cache columns. The golden vectors are the test. `new` is a fresh py-fsrs card; elapsed time is whole days (as py-fsrs does it).
- **Fuzz is deterministic**: seeded by the review's id with SplitMix64, so replay on any device gives the same due date. A preview that is given the review id the answer will use shows exactly the interval that answering will produce.
- **Op encoding is one canonical compact JSON form** (non-ASCII written raw, numbers the way JavaScript prints them). The op size limit (`max_op_bytes`) is measured on that form, so phone, web and server always agree on whether an op is too big. Unknown op types or newer versions decode to `Unsupported` (the lenient path: stop pulling, never skip).
- **Search language**: Anki-like (`deck:`, `tag:`, `is:`, `-`, `or`, parentheses, `*` wildcards), plus `kind:`, `type:`, `status:`, `kanji:`, `entry:`, `rated:N[:R]`, `added:N`, `field:<key>:<text>` and comparisons (`lapses>3`, `due<=0`, `stability>10`). Grammar in `search.ebnf`, checked against 57 example queries, including the one in the spec.
- **Card renderer**: one template language (`{{field}}`, `{{furigana:field}}`, sections, `{{FrontSide}}`), a strict sanitiser and a fixed CSS class list. Empty fields of dictionary notes are filled from dictionary data that the caller passes in.
- **Web facade**: four methods (parse search, complete search, render card, preview intervals). They use JS-friendly classes only: no Long, List or Map. Instants are passed as numbers.

## Alternatives rejected
- **Typed Kotlin classes for all 45 op payloads.** Rejected: that is a second copy of the JSON Schemas to keep in sync. Payloads stay a JSON tree. Only the fields the core reasons about (reviews) are typed.
- **kotlinx.serialization / kotlinx-datetime in the contract.** Rejected: no new dependencies in an interface. The JSON tree and `StudyDay` (computed by platform code) keep the contract library-free; implementers may still use those libraries internally.
- **Treating unknown `key:` as a field search** (Anki style). Rejected: a typo such as `dek:` would silently match nothing. Unknown keys are now an error, and field search is explicit (`field:`).
- **Exporting the common interfaces directly to JS.** Rejected: Long and List don't cross the boundary cleanly. `CoreJs` is a thin, flat layer instead.

## Three choices most worth a second look
1. **No load balancing based on the whole collection.** The spec says "fuzz and load balancing spread reviews across days". Real load balancing looks at how many other cards are due on each day. The card cache must be a pure function of that card's own reviews (SYNC.md 9.3), so load balancing would make two devices disagree. What we have instead: fuzz seeded by the review id, and Easy Days weights over the fuzz range (using the review's own UTC offset; ignored when fuzz is off). If you want true load balancing, it has to be a queue-time display shift that is never stored in the cache.
2. **"Set due date" restarts the elapsed-time clock.** The database says `last_review_at` is the time of the last folded row, and manual rows count as folded. So after a reschedule, FSRS measures the next review's elapsed time from the reschedule, not from the last real answer. That is conservative and consistent, but it is not what Anki does. Fixing it needs an additive database column (`last_rated_at`) via a contract change.
3. **Queue policy defaults.** These are all guesses at intent; they are easy to change now and expensive to change after cards are scheduled with them:
   - Cards that belong to no deck (e.g. mined with Yomitan) are introduced before the regular decks.
   - Reviews are ordered by due date.
   - New and review cards are interleaved evenly.
   - A word counts as auto-"known" when every started card has an interval of 21 days or more.
   - Hot-word weights are provisional (`HotConfig.DEFAULT`).

## Also decided, smaller
- `set_due` on a new card does nothing (a new card cannot have a due date). `reset` also zeroes reps and lapses, so a rewritten leech starts clean.
- In replay, a cache whose weights version doesn't match is never folded into. It is always fully replayed first.
- A device that pulls an op that fails its schema treats it like an unknown op: it stops pulling and never skips it.
- Search terms are lowercase only; ASCII is case-insensitive in matching, with no kana or width folding.
