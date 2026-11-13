# Core contract — summary for the human (T006)

## What was decided
- **Interfaces only, in `kotobaya.contracts`.** Eleven areas: HLC rules (incl. the device clock guards and the re-mint shift), canonical JSON + op codec, FSRS scheduler, replay engine + `review_log` rules, queue builder + word auto-status, card generator, hot scorer, search parser, card renderer, and the web facade `CoreJs`. All pure functions: no clock, no I/O, no randomness that isn't seeded from stored data. Callers pass rows in; the core returns values out.
- **FSRS = py-fsrs 6.3.2 behaviour**, mapped onto our cache columns. The golden vectors are the test. `new` is a fresh py-fsrs card; elapsed time is whole days (as py-fsrs does it), measured from the last rated review.
- **Bit-identical on every platform** (attempt 2). Phone, web and server each have their own maths library, and these can differ in the last bit. Such a difference can move an interval by a whole day, and the replicas would then disagree forever. So the scheduler does all powers and exponentials with its own pure-Kotlin copy of the classic fdlibm routines (the same results as Java's `StrictMath`), and it never calls the platform's maths functions. Rounding is half-to-even, as Python's `round` does it. A test checks that the golden vectors give the same bits on jvm and js.
- **Fuzz is deterministic**: seeded by the review's id with SplitMix64, so replay on any device gives the same due date. A preview that is given the review id the answer will use shows exactly the interval that answering will produce.
- **Op encoding is one canonical compact JSON form** (object members sorted by key, non-ASCII written raw, numbers the way JavaScript prints them). The op size limit (`max_op_bytes`) is measured on that form, so phone, web and server always agree on whether an op is too big. The server measures each op as received, before decoding it. Unknown op types or newer versions decode to `Unsupported` (the lenient path: stop pulling, never skip).
- **Search language**: Anki-like (`deck:`, `tag:`, `is:`, `-`, `or`, parentheses, `*` wildcards), plus `kind:`, `type:`, `status:`, `kanji:`, `entry:`, `rated:N[:R]`, `added:N`, `field:<key>:<text>` and comparisons (`lapses>3`, `due<=0`, `stability>10`). Grammar in `search.ebnf`, checked against 67 example queries, including the one in the spec. The check script also proves that printing a parsed query and parsing it again gives the same tree.
- **Card renderer**: one template language (`{{field}}`, `{{furigana:field}}`, sections, `{{FrontSide}}`), a strict sanitiser and a fixed CSS class list. Empty fields of dictionary notes are filled from dictionary data that the caller passes in. Each host passes a media URL template (web: an API path; Android: a WebView asset path), because browsers cannot load the stored `blob:<hash>` form.
- **Web facade**: four methods (parse search, complete search, render card, preview intervals). They use JS-friendly classes only: no Long, List or Map. Instants are passed as numbers.

## Alternatives rejected
- **Typed Kotlin classes for all 45 op payloads.** Rejected: that is a second copy of the JSON Schemas to keep in sync. Payloads stay a JSON tree. Only the fields the core reasons about (reviews) are typed.
- **kotlinx.serialization / kotlinx-datetime in the contract.** Rejected: no new dependencies in an interface. The JSON tree and `StudyDay` (computed by platform code) keep the contract library-free; implementers may still use those libraries internally.
- **Treating unknown `key:` as a field search** (Anki style). Rejected: a typo such as `dek:` would silently match nothing. Unknown keys are now an error, and field search is explicit (`field:`).
- **Exporting the common interfaces directly to JS.** Rejected: Long and List don't cross the boundary cleanly. `CoreJs` is a thin, flat layer instead.

## Three choices most worth a second look
1. **No load balancing based on the whole collection — decided (ADR-005, provisional).** The spec says "fuzz and load balancing spread reviews across days". Real load balancing looks at how many other cards are due on each day. The card cache must be a pure function of that card's own reviews (SYNC.md 9.3), so load balancing would make two devices disagree. What we have instead: fuzz seeded by the review id, and Easy Days weights over the fuzz range (using the review's own UTC offset, or UTC when it is unknown, as for jpdb imports; ignored when fuzz is off). Any later smoothing may only be a queue-time display shift, never stored in the cache.
2. **"Set due date" no longer restarts the elapsed-time clock — resolved (C-1).** `card.last_review_at` now means the last *rated* review. A reschedule leaves it alone, and a reset clears it. So the next review after a reschedule measures elapsed time from the last real answer, as Anki does. No new column was needed.
3. **Queue policy defaults.** These are all guesses at intent; they are easy to change now and expensive to change after cards are scheduled with them:
   - Cards that belong to no deck at all (e.g. mined with Yomitan) form their own new-card source. Its priority is a setting (default 0, ADR-006), so they take turns with your priority-0 decks. Decks of equal priority take turns, one card each. A card whose decks are all disabled is not introduced.
   - With sibling burying on, one queue never holds two new/review cards of the same note.
   - Reviews are ordered by due date.
   - New and review cards are interleaved evenly.
   - A word counts as auto-"known" when every started card has an interval of 21 days or more.
   - Hot-word weights are provisional (`HotConfig.DEFAULT`).

## Also decided, smaller
- `set_due` on a new card does nothing (a new card cannot have a due date). `reset` also zeroes reps and lapses, so a rewritten leech starts clean.
- In replay, a cache whose weights version doesn't match is never folded into. It is always fully replayed first.
- A device that pulls an op that fails its schema treats it like an unknown op: it stops pulling and never skips it.
- Search terms are lowercase only; ASCII is case-insensitive in matching, with no kana or width folding.

## C-2 changes
- **Kanji render data.** New `KanjiData` (literal, meanings, on/kun readings, stroke count) is an
  optional last field of `RenderInput`, and `JsKanji` is the same for `JsRenderInput`. Empty kanji
  fields are filled from it the way dictionary data fills vocab fields. Both default to null, so
  existing callers still compile.
- **Search evaluation.** The KDoc lists what a search compiler needs: the study day, now, the leech
  threshold, the hot note ids and merge resolution. On the phone's SQLite it may use an SQL
  prefilter plus a Kotlin check on the rows that come back (for tags, fields, text, `kanji:`, `is:hot`).
- KDoc only: duplicate JSON keys (the decoder rejects them; the server's push parse is separate),
  the media URL example is now `/api/v1/blobs/{sha256}`, and vocab note types always have
  `expression`/`reading`/`meaning`.

## C-7 changes (additive)
- The mined-cards priority setting is now `scheduler.mined_priority`. It was
  `scheduler.deckless_new_priority`, which nothing ever wrote. It feeds `decklessPriority`.
- New `QueueInput.decklessAddKanji` (default `OFF`, so existing callers still compile). It sets the
  kanji ordering for mined cards and is `BEFORE` when `scheduler.kanji_before_vocab` is true.
  Decks without their own `add_kanji` get the same fallback, but a deck's own setting, even `off`,
  always wins. SCHEMA.md 6.2 has the rules.

## C-19 changes (additive): field regions for the WYSIWYG note view
- **What.** New render option `fieldRegions` (default off). On, the span the renderer already puts
  around every field value also says which field it is and where the value came from:
  `<span class="kb-field-keyword" data-field="keyword" data-field-source="kanji">`. Sources are
  `note` (the user's own value), `dictionary`, `kanji`. The result also gets `fields`: every field
  the card side refers to, its source (or `empty`), and whether it is visible in the HTML.
- **Off means untouched.** With the option off the HTML is byte-for-byte what it was; the 36 existing
  goldens are unchanged, and 36 new ones cover the option on. Review screens keep it off.
- **Cannot be forged.** The sanitiser allowlist did not change at all. The attributes are written by
  the renderer after sanitising, so a field value, a custom template or dictionary text that contains
  `data-field` just loses it.

Rejected:
- Teaching the sanitiser to keep `data-field` (as the ticket's wording suggests). Then note content
  could mark itself as another field. Not needed: the wrapper never passes through the sanitiser.
- A new wrapper element. The `kb-field-<key>` span was already there; reusing it keeps layout and CSS identical.
- Always emitting the attributes. It would change every golden and the review HTML for no benefit.
- Returning text offsets instead of attributes. Offsets break as soon as the host touches the DOM.

Worth a second look:
1. **Empty fields get no box in the card.** An empty field renders nothing, as before, so there is
   nothing to click. Instead `fields` lists it (`empty`, not rendered) and the web shows an "add"
   row beside the card. The alternative, an empty placeholder span inside the card, would shift
   layout and show up inside hidden sections.
2. **`fields` includes fields inside hidden sections** (for example `notes` when it is empty). That
   is what lets the web offer them, but it also lists keys a custom template only tests with `{{#key}}`.
3. **Mnemonic, pitch and cloze sentence are not regions.** They are not note fields. If the note
   view should edit the mnemonic in place, that is a small follow-up (a marker on `kb-mnemonic`).
