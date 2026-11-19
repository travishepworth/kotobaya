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
  makes you learn and whether its front shows the reading above the word. Only jōyō kanji and the
  2,500 most frequent count, and they always count (ADR-045, C-28e: also for words that Japanese
  normally writes in kana, or whose kanji are ateji / rare / out-dated / irregular). Those tagged
  words, and words that need no kanji, show furigana.
- **Deck progress and kanji tiles (ADR-041).** Two small calculators: "N of M words learned" for a
  deck, and the per-kanji breakdown of a word with the reading that word uses.

Alternatives rejected:
- **Skipping a block that does not fit the rest of today's goal.** First draft; reversed in review
  (ADR-043): a word needing more kanji than the daily goal would never come. Now the kanji that
  fit are shown today and the word follows on a later day, right behind its last kanji; its deck
  does not jump ahead to other words meanwhile. The goal is never overshot.
- **Remembering "a block is in progress" in session state.** Rejected: the queue is rebuilt from
  the data after every answer and must give the same result on every device. Instead the builder
  recognises a started block from the data: the last card introduced today is a kanji that a
  waiting word needs, and no review was answered since.
- **Leaving words you already study without their kanji.** First draft; reversed in review
  (ADR-043): kanji that have no card yet are now created for those words too, oldest word first,
  never more than half the daily goal per day while there are new words to learn (ADR-044).
- **A per-kanji "learnable" list shipped with the app.** Rejected: KANJIDIC grade and frequency
  are already in the dictionary on the server and on the phone.

Three choices most worth a second look:
1. **A deck's explicit `add_kanji: off` no longer opts out.** ADR-040 says "always". The ways out
   are deleting or suspending the kanji card, or (for non-learnable kanji) the ADR-042 setting. If you want a per-deck
   opt-out back, it should be a new, clearly named deck setting.
2. **Backfill takes half of the new-card slots (ADR-044, replacing "all of them").** With a
   large imported history, the kanji of words you already study are created and come first, but
   only up to half the daily goal each day (rounded down, at least one); the other half goes to
   new words with their own kanji. When there are no new words to draw, the kanji use the whole
   goal. With a goal of 1 the single slot goes to those kanji until they are done.
   Under Keep going there is no daily goal to halve, so the rule there is "these kanji are never
   more than half of the new cards introduced today": after the goal you get a word and a kanji
   in turn. This is my reading of "keep-going batches: same half share"; a fixed share per batch
   cannot be kept when the queue is rebuilt after every answer.
   Also (ADR-044): a kanji with several new cards that is larger than the whole goal is shown as
   the first new cards of a day and goes over the goal that once; it was never shown before.
3. **Real dictionary data may disagree with two examples in ADR-042.** To my knowledge JMdict tags
   林檎 and 薔薇 "usually kana" (neither word is in the test dictionary, so this is unverified here).
   If so, both show furigana. Since ADR-045 (C-28e) the tag no longer removes kanji cards: 林檎
   gets a card for 林, and 薔薇 one for 薔 (verified in the test KANJIDIC: 薔 has a frequency rank,
   2356). The contract follows the rule as written, not the examples.
   C-28e also keeps the field name `KanjiRequirementResult.wordExempt` (and `EXEMPT_FORM_TAGS`)
   although it now means "word-level tagged, furigana only": renaming would break every caller
   for no change in behaviour. Rename later if the name misleads.

Also decided, smaller:
- A kanji card whose own decks are all disabled is still pulled in by its word.
- A kanji card you buried holds its word back until the bury ends; a suspended one does not.
- Review-scoped to one deck: the kanji of that deck's words are passed along as "companions" so
  they still come first.
- `PendingEntry.newKanjiCards` is replaced by a list of the kanji to create, so two words that
  share a missing kanji count it once.
- Deck progress counts a word as learned when one of its cards is in review, or its status is
  known. Blacklisted and ignored words are left out of both numbers. Smart decks have no progress.
- The percent is rounded down, so 100% means every word.

## C-28c changes: four queue-order cases from the core implementation

The implementer found four places where the kanji-first rules disagreed with each other.

Decided:
- **New cards are shown in the order they were picked.** A word is no longer moved up the queue
  because one of its kanji was just shown. It still follows its own kanji without a review in
  between when it is next in line. Moving it up could put a word ahead of one of its kanji, and
  made the queue change order after an answer.
- **A deck's daily limit counts the word and its kanji from that deck together.** With a limit of
  1, the kanji is shown today and the word tomorrow. Before, the queue listed kanji and word,
  then dropped the word once the kanji was answered.
- **A buried kanji holds only its word.** The word's other kanji can still be shown that day.
- Answering the first card and rebuilding always gives the rest of the same queue. This is now a
  written rule with tests.

Rejected: letting the word through over the deck limit after its kanji (one kanji could then
carry many words past the limit); keeping the move-up and correcting the half-share count
(cannot be told apart from stored data alone).

Worth a second look:
1. A backfill kanji and a new word that needs it can now be several new cards apart.
2. A deck limited to 1 a day teaches a kanji one day and its word the next.
3. Decks of equal priority (and cards in no deck, by default) now share new cards evenly over
   the day: the one with the fewest new cards today goes next. Before, the first deck was
   always drawn first after each answer, so it used up the goal before the others got a turn.
   A kanji card that belongs to no deck and is needed by one of your words does not count as a
   turn for anyone; one that no word needs counts like any other card.

## C-28d changes: the last three cases where the queue changed after an answer

After C-28c the rule "answer the first card, rebuild, get the rest of the same queue" still had
three exceptions. All three are closed; the rule has no exception left for ordinary cards.

Decided:
- **A kanji that two decks' next words both need belongs to the first deck in a fixed order**
  (decks by id, cards in no deck last), not to the deck with fewer new cards today. The count
  changes while the kanji's group is being answered; the fixed order does not. If the deck whose
  turn it is would show a kanji that an earlier deck's next word needs too, the earlier deck goes
  first. Before, a word could be shown behind its kanji and then be replaced by another deck's
  word once the kanji was answered.
- **Keep going: a kanji of an already studied word is not put between a new word's kanji and the
  word.** It comes right after the word instead.
- **A deck that reaches its daily limit with a kanji stops there.** Further kanji of the same word
  that live in other decks wait for the day the word comes. Before, they were listed and then
  dropped once the first kanji was answered (the progress bar jumped by 2).

Rejected: remembering which deck showed a kanji (the queue must be computable from stored data
alone, on every device); letting a deck at its limit finish the kanji it had started (it needs a
second kind of turn for a deck at its limit, and those kanji are not needed until the word comes);
applying the keep-going rule to the normal queue too (not needed there, and it would change the
order of an existing example).

Worth a second look:
1. With two decks of equal priority whose next words share a kanji, the deck with the smaller id
   gets that kanji's word first, even if it has had more new cards today. The other deck catches
   up on the following turns.
2. A deck limited to 1 a day, word with one kanji in the deck and one elsewhere: day 1 the first
   kanji, day 2 the second kanji and the word. Before it tried to show both kanji on day 1.
3. The tests no longer skip any random input. If the core writer finds a failing seed, the
   contract is wrong, not the test: it comes back as a change request.

## C-28f: rare kanji forms and tagged words (ADR-046)

What changed, in plain words:
- **Kana words.** A dictionary word whose kanji spellings are all marked "rare" or "search only"
  (新嘉坡 for シンガポール, 歌留多 for かるた, 彼 for あれ, 居る for いる) is now simply a kana
  word: the dictionary build stores the reading as the word's display form. Cards, lists and
  the queue show the kana, no kanji card is made for it, and searching the kanji spelling still
  finds it. In the test dictionary this applies to 137 of 2,000 entries.
- **Tagged words need only jōyō kanji.** A word marked "usually kana", ateji, rare, out-dated or
  irregular makes you learn its jōyō kanji and nothing else. Untagged words are unchanged
  (jōyō or the 2,500 most frequent). 薔薇 no longer creates a card for 薔.
- **Kanji cards already created** for the old rule (嘉) are kept; delete them by hand if unwanted.

Alternatives rejected:
- **Deciding "kana word" in the core at read time** (pass every form's tags to
  `KanjiRequirement`). Rejected: the display form is also shown by lists, deck views, kanji
  examples and the API's `headword`, which do not go through that rule; two definitions of "what
  this word looks like" would drift. One stored value has one answer everywhere. Cost: it needs a
  dictionary rebuild, and a phone shows the old spelling until it has the new package.
- **A new column** (`display_form`) next to `headword`. Rejected: `headword` already means
  "display form"; a new column would need a package format bump and every reader changed.
- **Deleting unreviewed kanji cards that no word needs any more.** Rejected for now: a delete is
  permanent and looks like the user's own, so that kanji could never be created again, and a
  phone with an older dictionary could delete a card the server still needs (SCHEMA.md 6.5).

Most worth a second look:
1. **Kanji cards like 嘉 stay** (the ticket preferred automatic removal). Removing them safely
   needs a new, reversible sync op; say so if you want it. Until then: delete the note by hand.
2. **"All forms rare" is read literally.** Only `rK` and `sK` count. A word whose forms are rare
   plus one out-dated form (軈て / 頓て `rK`, 漸て `oK`) keeps its kanji spelling, with furigana and
   no card for non-jōyō kanji. Widening the rule to `oK` / `iK` is a one-line change in SCHEMA.md 4.5.
3. **Common words become kana.** いる (居る), する (為る), それ (其れ), この (此の), あれ (彼) lose their
   kanji spelling and their kanji cards-to-be (居, 為 are jōyō and were required under ADR-045).
   This is what ADR-046 (a) says; notes whose own text field holds the kanji are not affected.
