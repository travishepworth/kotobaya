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

## C-25 changes (additive): example words for kanji cards (ADR-029)
- **What.** New `KanjiExamplePicker` (`contracts/dict/KanjiExamples.kt`), implemented once in `commonMain`
  (`kotobaya.dict.DefaultKanjiExamplePicker`). `classify` says how a word reads a kanji (on / kun / other)
  from the kanji's own KANJIDIC readings; `pick` chooses up to 3 example words. The server calls it to
  fill `QueueItem.kanji_examples`; Android calls it offline. Not exported to JS: the web shows what the
  server sends.
- **classify.** The word's reading is aligned to its headword: kana must match, every other character
  takes at least one kana, and the kanji takes one of its readings — on'yomi (also geminated, 学校 がっ),
  kun'yomi stems (also with the okurigana absorbed, 取引 とり), both also voiced by rendaku when not
  word-initial. The longest reading that fits wins; a tie goes to on'yomi; nothing fits → other.
- **pick.** Drop blacklisted/ignored words and entries whose headword lacks the kanji. Order: the user's
  words (learning/known/never_forget, has a note, or in a deck), then frequency rank, then seq. Look at
  the first 50 only. Take the best on'yomi word, the best kun'yomi word, fill up to 3, show in that order.

Rejected:
- Aligning with the readings of the *other* kanji too (a full furigana solver). More accurate on a few
  compounds, but the picker would need the whole kanji table as input on every host.
- JMdict furigana data (JmdictFurigana) as a new reference table: a DB contract change and a download
  for a display hint.
- Classifying in SQL or per client. Three implementations of rendaku rules would drift.
- Ranking by frequency only (ticket wording "by frequency"): the user asked for the smartest pick, and a
  word they are learning is a better hook than a more frequent stranger.

Worth a second look:
1. **Heuristic misfires are accepted.** Irregular readings come out as `other` (日本 にほん), and a
   jukujikun whose tail equals a short kun stem comes out as `kun` (下手 へた via `た-`). It only decides
   the small on/kun tag and which words get the two reserved places.
2. **The user's words outrank frequency entirely.** A rank-20 000 word in one of their decks beats the
   kanji's most common word. With many deck words the three examples are all "theirs".
3. **Window of 50.** A kanji whose only kun'yomi word is beyond its first 50 candidates shows no kun
   word. The window is what lets the server pre-limit in SQL (one statement per queue page).

## C-26 changes (additive): Keep going (ADR-031)
- **What.** `QueueLimits.keepGoing` (default false) and `keepGoingNewBatch` (default 10),
  `ReviewQueue.moreAvailable` (default false) and the computed `QueueItem.isNew` (`kind == NEW`).
  New `QueueBuilder` rule 6: with `keepGoing` the global and per-deck review caps and the new caps
  (daily, session, per deck) are lifted, new cards are bounded by the batch per build (an add_kanji
  group is drawn whenever the budget is >= 1), and the interleaved part becomes reviews then new.
  Eligibility, sibling burying, deck tiers and order, disabled decks, learn-ahead: unchanged.
  `moreAvailable` = the normal build is empty and the rule-6 build is not (computed only then).
- **Why stateless.** It is a per-build flag; nothing is stored or synced. Cards studied through it
  are ordinary reviews, so `today` accounting is untouched and tomorrow's allowance never grows.
- Rejected: a keep-going setting or op (sync state that expires at rollover); raising the limits by
  N per tap (the user asked for an endless stream); an unbounded new budget (a device would
  materialize every pending entry of every deck on one tap).
- Worth a second look: (1) reviews strictly before new under keep going; (2) per-deck review caps
  lifted as well; (3) batch overshoot by add_kanji groups.
- Attempt 2: rule 6 "Scope" (one review-screen visit; cleared on leave, study-day change, restart;
  never persisted), `newShownThisSession` keeps counting during keep going, and `moreAvailable` may
  be true when only the session cap is reached ("done for now").

## ADR-034 changes (additive): no daily review limit
- **What.** `QueueLimits.reviewsUnlimited` (default false), fed from the synced setting
  `scheduler.reviews_unlimited` (SCHEMA.md 6.3). Rule 3 then treats the global and per-deck review
  remainders as unlimited (exactly as rule 6 does for reviews). Rules 1, 2, 4, 5, keep going and
  `moreAvailable` are unchanged; default false is bit-for-bit the old behaviour.
- Rejected: a nullable `reviewsPerDay` (breaking for every caller; a stored sentinel such as
  `reviews_per_day = 0`/`-1` would collide with the meaning "no reviews" and lose the user's cap
  when toggled back); making it the default (ADR-034 chose opt-in).
- Worth a second look: (1) per-deck review caps are lifted too, not only the global one; (2) rule 5
  still interleaves new cards among a large review backlog (keep going puts reviews first).

## ADR-035 changes (additive): one card per word (C-28)
- **What.** `kotobaya.contracts.cards.CardKinds` holds the only retirement rule (`isRetired`), the
  key `cards.reading_enabled` and its bool test (`readingEnabled`). `CardGenerator` methods,
  `QueueInput` and `WordStatusRules.autoStatus` gain a `readingEnabled` parameter/field defaulting
  to **false**; `WordCardState` gains `kind` (null = unknown = not retired). QueueBuilder rule 0
  drops retired candidates and today's reviews of them before every other rule. The device apply
  path re-derives all vocab entries on `settings.set` of the key.
- **Default false, explicitly.** The first ADR-035 draft said "missing key = true = old
  behaviour"; the user wants reading cards off by default, so a missing/null/invalid value means
  false, and reversal is by storing `true` (SCHEMA.md 6.4).
- Rejected: a stored retired flag or mass `card.suspended` (needs ops, mixes with user
  suspensions); a template-default change (second source of truth; deck `card_kinds` and explicit
  requests would still create reading cards); default `true` for the new parameters (every
  unwired call site would keep creating and serving reading cards).
- Worth a second look: (1) today's reviews of a retired card no longer count against today's
  limits (consistent "absent" rule; matters only on the day of the flip or for offline reviews);
  (2) `createNote card_kinds:[reading]` silently creates a note without cards while
  `createCard {kind: reading}` is `422`; (3) between this merge and ONE-CARD-SRV the server's
  `auto_status` still counts reading cards (it passes no `kind`) while devices do not, so derived
  word statuses may differ for words with a reviewed reading card until the server ticket lands
  (`auto_status` is outside the SYNC.md 11 projection; `status` is not).

## ADR-037 changes: one review loop (LOOP-C)
- **What.** New contract file `contracts/queue/ReviewLoop.kt`: `LoopPhase` (`reviewing`,
  `goal_reached`, `done_for_today`, `waiting`, `empty`), `LegacyCounts`, `LoopState`, `LoopResult`,
  `ReviewLoop.evaluate(QueueInput)`. It always runs the normal build N and the keep-going build K
  from one input, serves K or N by the flag, and derives every number and the phase; all numbers
  except `phase` / `keepGoing` are identical with and without keep going. Nobody else counts or
  decides an end state. No signature of `QueueBuilder.kt` changed (KDoc only).
- **QueueBuilder rule changes (KDoc; implemented by LOOP-CORE).** Rule 4: the normal new budget is
  the day's remaining goal, drawn at most a batch (`max(1, keepGoingNewBatch)`) per build, groups
  atomic against the day; `newPerSession` / `newShownThisSession` are ignored. Rule 5: the old
  interleave becomes the **proportional mix**, a stride on what remains: next is a new card iff
  `s * gRem >= rRem` (`s` = reviews answered since the last new card today, `rRem` = ready reviews
  left, `gRem` = new cards still introducible today under the goal and the deck caps), else the
  most overdue review - never two new cards in a row while a review is left, both modes, and the
  order of one build equals the order of rebuilding after each answer. `TodayReview.reviewedAt`
  (additive, default 0) carries the answer order. Rule 6 (keep going):
  unchanged order (due learning, all reviews, new; no mix), scope now per client per study day.
- **User amendment 2026-11-17 (after the design).** The design removed the interleave ("reviews
  first, then new"); the user called that a regression and asked for a proportional mix in both
  modes for the normal session, and confirmed backlog-first for capped keep going, dropping
  `new_per_session`, and keep going per device.
- **Decisions the ticket did not make.** (a) `evaluate` forces `learnAheadMs = 0` for both builds,
  so the served queue as well as the counts never contain a not-yet-due learning card. (b) Only the
  served build's `toMaterialize` is ever materialized. (c) The deprecated members carry no
  `@Deprecated` annotation (no warnings for existing callers). (d) Review round 1: the mix is a
  stride on the remaining quantities with `s` read from today's answers (attempt 1 compared
  progress against the day's totals, which served new cards in a run after a lopsided history);
  `LegacyCounts.new` is the day's remainder from an unbounded normal build, not the batch.
- Rejected: fixing only `counts.learning`; keep going as a synced setting; a phase derived per
  client; instant-granular review readiness; keeping `new_per_session` as a third stop state;
  reviews strictly before new in the normal session (user: regression); a mix that also applies
  to capped keep going (user: backlog first); a mix ratio from the batch-bounded number of drawn
  new cards (the order would depend on `limit`: server and device would disagree).
- Worth a second look: (1) **the mix formula** - when more new cards than reviews remain it
  alternates review / new and the surplus new cards come last; with a deck that has more pending
  entries than the loader's window `gRem` is a lower bound (new cards slightly sparser). (2) **Keep going is per client**: pressing it on the phone does not
  switch web or the widget; numbers stay identical everywhere. (3) **`done_for_today` vs
  `goal_reached` is chosen by `reviews_unlimited`**, not by which cap bound (B7).

## C-28 changes: progress bar, kanji first, deck progress, kanji tiles, exemptions (ADR-038..042)

What was decided:
- **Progress bar total (ADR-038).** The loop now also returns "answers left until the stop screen"
  (`untilStop`): learning cards ready or due later today, reviews within today's limit, and the new
  cards still left in today's goal. Each card counts once, so the total grows when you press Again
  or when a new card starts its learning steps. While you keep going, the stream has no end, so
  the number is what is in sight: the backlog plus the current batch of new cards.
- **Keep going lasts one visit (ADR-039).** Wording only: nothing is stored any more.
- **Kanji always come before their first word (ADR-040).** A new word and the new kanji cards it
  needs are one block: kanji first, then the word, never with a review in between, and the whole
  block counts against the daily new-card goal. This is now the rule for every deck and for words
  in no deck. The old per-deck switch (`add_kanji`) and the old setting are no longer read. If a
  kanji has no card yet, it is created at the moment its word is drawn, and the word waits until
  it exists. Kanji that are still new although you already study a word with them come first of
  all new cards.
- **Which kanji count (ADR-042).** One rule (`KanjiRequirement`) decides, per word, which kanji it
  makes you learn and whether its front shows the reading above the word. Words that Japanese
  normally writes in kana, or whose kanji are ateji / rare / out-dated / irregular, need no kanji.
  Otherwise only jōyō kanji and the 2,500 most frequent count. A word that needs none shows furigana.
- **Deck progress and kanji tiles (ADR-041).** Two small calculators: "N of M words learned" for a
  deck, and the per-kanji breakdown of a word with the reading that word uses.

Alternatives rejected:
- **Splitting a block when it does not fit the rest of today's goal** (show the kanji today, the
  word tomorrow). Rejected: you asked for "same session". The block waits for a day it fits.
- **Remembering "a block is in progress" in session state.** Rejected: the queue is rebuilt from
  the data after every answer and must give the same result on every device. Instead the builder
  recognises a started block from the data: the last card introduced today is a kanji that a
  waiting word needs, and no review was answered since.
- **Creating kanji cards for every word you already know.** Rejected: an imported history would
  turn into thousands of new kanji cards. Only kanji cards that already exist are pulled forward.
- **A per-kanji "learnable" list shipped with the app.** Rejected: KANJIDIC grade and frequency
  are already in the dictionary on the server and on the phone.

Three choices most worth a second look:
1. **A deck's explicit `add_kanji: off` no longer opts out.** ADR-040 says "always". The ways out
   are deleting or suspending the kanji card, or the ADR-042 exemptions. If you want a per-deck
   opt-out back, it should be a new, clearly named deck setting.
2. **A block bigger than the daily goal never fits a normal day.** With a goal of 2 and a word
   with two new kanji (3 cards) the word only comes through Keep going. With the default goal of
   20 (and your 40) this does not happen. The alternative is to let such a block fill the rest of
   a day with its kanji and continue the next day.
3. **Real dictionary data may disagree with two examples in ADR-042.** To my knowledge JMdict tags
   林檎 and 薔薇 "usually kana" (neither word is in the test dictionary, so this is unverified here).
   If so, the word-level rule exempts both (furigana, no kanji cards) and 林檎 gets no card for 林.
   Verified in the test KANJIDIC: 薔 has a frequency rank (2356), so without that tag 薔薇 would get
   a card for 薔 and no furigana. The contract follows the rule as written, not the examples.

Also decided, smaller:
- A kanji card whose own decks are all disabled is still pulled in by its word.
- Review-scoped to one deck: the kanji of that deck's words are passed along as "companions" so
  they still come first.
- `PendingEntry.newKanjiCards` is replaced by a list of the kanji to create, so two words that
  share a missing kanji count it once.
- Deck progress counts a word as learned when one of its cards is in review, or its status is
  known. Blacklisted and ignored words are left out of both numbers. Smart decks have no progress.
- The percent is rounded down, so 100% means every word.
