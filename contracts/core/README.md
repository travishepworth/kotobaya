# Core contract (T006)

Frozen interfaces of the shared Kotlin Multiplatform core (`core/`, targets jvm, android, js). The
Kotlin files are the contract; this README maps them to the spec and lists the tests implementers
must write. Interfaces and data holders only: no algorithm lives in `kotobaya.contracts`.

| File | What |
|---|---|
| `core/src/commonMain/kotlin/kotobaya/contracts/**` | The interfaces, data classes and enums, with normative KDoc. |
| `search.ebnf` | Grammar of the card search language (W3C EBNF). Normative for syntax. |
| `search-examples.tsv` | Queries that must / must not parse. Shared by the grammar check and T108's tests. |
| `check.sh` (`check_search.py`, `check_kotlin.py`) | Self-check: grammar vs examples; `OpType` vs `contracts/sync/ops/index.json`; KDoc on every public member; `@JsExport` only in `CoreJs.kt`. Stdlib Python. |
| `SUMMARY.md` | For the human: decisions, rejected alternatives, what to look at twice. |

Precedence: `contracts/sync` and `contracts/db` win over this contract for anything they define;
this contract adds the algorithms they leave to "the core". It assumes the C-1 alignment of those
contracts: (a) replay order `(reviewed_at, hlc, id)` everywhere; (b) `card.last_review_at` = the last
**rated** row, manual rows leave it, reset clears it; (c) device `sync_state` key
`core_scheduler_version` (SYNC.md 10.5); (d) a schema-invalid pulled op stops the pull like an
unknown op; (e) the server measures `op_too_large` on the canonical compact JSON of the raw tree
before decoding; (f) `fsrs_params.published` weights bounded to py-fsrs 6.3.2 parameter bounds.

## Cross-platform determinism (normative)

Caches must be bit-identical on jvm, android and js (SYNC.md 11.3). `Scheduler` and `ReplayEngine`
are one `commonMain` implementation that uses only IEEE basic operations plus
`fsrs/PortableMath.kt` — a pure-Kotlin port of fdlibm 5.3 `pow`/`exp`/`log` (= `StrictMath`).
**`kotlin.math` transcendentals (`pow`, `exp`, `ln`, `log*`, trig, …) and `Math.*` are banned** on
that path, as are `roundToInt`/`roundToLong`; py-fsrs's `round()` is round-half-to-even and is
spelled out in `Scheduler`'s KDoc. py-fsrs is matched within 1e-6; our platforms are matched exactly.

## Other deviations from py-fsrs / the spec, on purpose

- Fuzz is seeded by the review id (SplitMix64), not `random()`; no collection-wide load balancing
  (ADR-005).
- A null `review_log.utc_offset_min` (all jpdb imports) is UTC for easy days: the scheduler is pure
  and cannot read `scheduler.timezone`.

## Interfaces → sources → implementer

Package prefix `kotobaya.contracts`.

| Interface (file) | Defined by | Implemented in |
|---|---|---|
| `Reserved`, `Uuid`, `HlcString`, `InstantMs`, `NoteBaseType`, `WordStatus` (`Common.kt`) | SCHEMA.md 2, SYNC.md 3.4 | — (constants) |
| `HlcRules`, `HlcState`, `ClockGuardResult` (`sync/Hlc.kt`) | SYNC.md 3.1–3.4 (format, compare, `mint`, `mint_at`, `observe`, guards G1/G2) | T202 |
| `RemintRules` (`sync/Hlc.kt`) | SYNC.md 10.4 step 5 (the shift arithmetic only) | T202 |
| `CanonicalJson`, `JsonValue` (`sync/Json.kt`) | SYNC.md 5.3/5.4 (op size on canonical compact UTF-8 JSON, sorted members; C-1e) | T202 |
| `OpCodec`, `OpType`, `Op`, `OpDecodeResult` (`sync/OpCodec.kt`) | SYNC.md 2, 5.3 (`schema_invalid`, `unsupported_op`, `op_too_large`), 6.1 (lenient unknown-op path), 15; `ops/index.json`; all schemas | T202 |
| `PortableMath` (`fsrs/PortableMath.kt`) | fdlibm 5.3 `e_pow.c`/`e_exp.c`/`e_log.c`; cross-platform determinism above | T107 |
| `Scheduler` and FSRS types (`fsrs/Scheduler.kt`) | SPEC Scheduling › FSRS, Grading; SCHEMA `card` cache columns, `fsrs_params`; `fixtures/fsrs` (py-fsrs 6.3.2) | T107 |
| `ReplayEngine` (`replay/ReplayEngine.kt`) | SYNC.md 9.1–9.3 (replay set, fold condition, full replay, merged sets), 8.2 step 4 | T203 |
| `ReviewLogRules` (`replay/ReplayEngine.kt`) | SYNC.md 7.9 (append-only, earliest undo wins) | T203 (device apply path) |
| `QueueBuilder`, `StudyDay`, `WordStatusRules` (`queue/QueueBuilder.kt`) | SPEC Scheduling › Queue building, sibling burying; ADR-006 (deckless source); SCHEMA 6 settings; `deck` columns and `deck.settings` keys; `word_status.auto_status` | T205 |
| `CardGenerator` (`cards/CardGenerator.kt`) | SYNC.md 4 (default cards, `card_kinds`, no knowing duplicates); SPEC card kinds table; kanji notes for a word (ADR-040; `add_kanji` deprecated) | T205 (or the note-creation ticket) |
| `KanjiRequirement` (`cards/KanjiRequirement.kt`) | ADR-042, ADR-045: learnable kanji (always required), word-level tags (furigana only), furigana front; SCHEMA.md 6.5 | CORE-28 |
| `DeckProgress` (`decks/DeckProgress.kt`) | ADR-041: "N of M words learned"; API `Deck.progress` | CORE-28 |
| `WordAudioResolver`, `WordAudioRules` (`render/WordAudio.kt`) | ADR-047: which sound a vocab note has, speech text, effective voice; SCHEMA.md 6.6; API `QueueItem.word_audio` | CORE-29 |
| `KanjiParts` (`dict/KanjiParts.kt`) | ADR-041: kanji breakdown of a vocab back, used reading; API `QueueItem.kanji_parts` | CORE-28 |
| `HotScorer` (`hot/HotScorer.kt`) | SPEC Leeches and hot words | hot-words ticket |
| `SearchQueryParser`, AST (`search/Search.kt`) + `search.ebnf` | SPEC Public API › Card search language | T108 |
| `CardRenderer`, `CardClasses` (`render/CardRenderer.kt`) | ADR-002; `note_type.templates`/`css`, `note.fields` comments | T213 |
| `RenderOptions.fieldRegions`, `RenderedCard.fields`, `RenderedField`, `FieldSource` (`render/CardRenderer.kt`); `JsRenderInput.fieldRegions`, `JsRenderedField` (`js/CoreJs.kt`) | C-19 (field regions for the WYSIWYG note view, FEAT-x); KDoc "Field regions" on `CardRenderer` | C-19 |
| `CardTextRenderer`, `TextRenderInput`, `CardText` (`render/CardTextRenderer.kt`) | ARCH-CARD-TEXT (plain-text render for text-only clients; API `CardText`, `QueueItem.text`); CLI-DAEMON "Card content" | ARCH-CARD-TEXT (`kotobaya.render.DefaultCardTextRenderer`) |
| `KanjiExamplePicker`, `KanjiReadings`, `KanjiExampleCandidate`, `KanjiExample`, `KanjiReadingType` (`dict/KanjiExamples.kt`) | ADR-029; API `QueueItem.kanji_examples`, `KanjiExample` | C-25 (`kotobaya.dict.DefaultKanjiExamplePicker`) |
| `CoreJs` + `Js*` classes (`js/CoreJs.kt`) | ADR-002 (search parse/complete, card render, FSRS preview for the web) | T108 + T213 + T107 (each fills its methods) |

**The web entry point.** The first ticket that implements a `CoreJs` method adds, in package
`kotobaya.core` next to `coreVersion()`, `@JsExport public fun kotobayaCore(): CoreJs` returning one
object whose unimplemented methods throw `NotImplementedError`. The web imports
`import { kotobayaCore } from '@kotobaya/core'`. Nothing else in `kotobaya.contracts` is exported.

## Tests implementers must write

These follow CLAUDE.md "Tests that must exist". All in `core/src/commonTest` (run on jvm by
`make check MODULE=core`) unless stated.

**T107 — FSRS**
- Golden vectors: every review of all 240 sequences of `fixtures/fsrs/vectors.json` through
  `Scheduler.review` with the file's scheduler block (fuzz off): `state`, `step`, `dueAt`
  (= `review_at + interval_seconds`) exact; `stability`, `difficulty` within 1e-6.
- **Cross-platform bit identity** (in `commonTest`, run on **jvm and js**, and android when its unit
  tests run): fold the `toRawBits()` of every `stability` and `difficulty` and every `dueAt` of all
  240 golden sequences, plus one fuzz-on pass with fixed review ids, into an FNV-1a 64 digest and
  assert it equals one hard-coded constant (computed once on jvm). T107 adds `nodejs()` to the js
  target and makes `make check MODULE=core` run `:core:jsTest` as well as `:core:jvmTest`.
- `PortableMath`: bit-equal to `StrictMath.pow/exp/log` on jvm for ≥ 1e6 seeded arguments plus
  fdlibm's special cases; the same arguments' digest equal on jvm and js.
- Rounding: `roundHalfEven` on 2.5 → 2, 3.5 → 4, 0.5 → 0, 2.4999999999999996 → 2.
- `paramsOf`: one weight just outside its py-fsrs bound (and a NaN) → all default weights.
- Manual rows (C-1b): `set_due` then a rated review → elapsed measured from the previous rated
  review (not same-day); `reset` → `lastReviewAt` null.
- Fuzz determinism: the same review id gives the same interval on jvm and js (run the test in
  `jsTest` too, or compare against fixed expected values computed once); all-ones easy days give
  `lo + floor(u·(hi−lo+1))`; an easy-day weight of 0 is never chosen unless all are 0.
- `preview` with a review id equals `review` with that id, for all four ratings.

**T203 — replay and review_log rules**
- `fold(replay(S), r) == replay(S + r)` for random S and r sorting last (property test, ≥ 1000 cases, fixed seed).
- `decide`: each of the six branches; a review with an earlier `reviewed_at` but later `hlc` →
  FULL_REPLAY; a `weights_version` mismatch → FULL_REPLAY even when sorting last.
- Replay order is `(reviewed_at, hlc, id)`: two reviews with equal `reviewed_at` order by `hlc`, then `id`.
- Manual rows: `reset` → NEW with reps/lapses 0 and `lastReviewAt` null; `set_due` on NEW → unchanged;
  `set_due` on REVIEW moves `dueAt` and keeps `lastReviewAt`; fold = replay across manual rows.
- `ReviewLogRules` (SYNC.md 7.9; a device has no trigger): duplicate insert with different content
  keeps the first; undo A(h=20) then B(h=15) → 15; B then A → 15; same undo twice → unchanged and
  `apply = false`; undo of an unknown review id → no row, no error; lowering an existing undo →
  `replayTarget = false`. Also on the device apply path: no statement updates a `review_log` column
  other than `undone_at`/`undone_hlc`, and no `pending_op`/`change` row is updated except the
  columns SYNC.md 5.4 names (append-only device log).
- SYNC worked examples, applied through the real device apply path from an empty replica, compared
  with `expected` (rows, replay sets) and then with random interleavings as SYNC.md 13 says:
  every file in `contracts/sync/examples/scenarios/`, explicitly including
  **`merge-two-level-cards`** (replay set of a survivor two merges deep, undos addressed to losers),
  **`clock-behind-remint`** (re-minted reviews replay in answer order; also `RemintRules.shift`
  reproduces its `remint` block) and **`undo-on-two-devices`**.
- `ahead_story` / `remint_story` (SYNC.md 12.6, 12.7; reference implementation in
  `contracts/sync/tools/validate.py`): ported to a Kotlin test against the device implementation —
  T202/T313 own the device and server sides; T203 owns the assertion that the survivor's replay set
  interleaves A's and B's reviews in answer order and that replayed `reviewed_at` values equal the
  real answer times.
- Convergence fuzz test (T202/T313): generated op logs, random interleavings, SYNC.md 11 projection.

**T202 — HLC and codec**
- `HlcRules`: the three `mint` examples in its KDoc; counter overflow; `observe` never lowers;
  `mintAt` below `last` lands just above it; `guard` boundaries (`pt == FLOOR` OK, `pt == wall − 24 h` OK).
- `OpCodec`: every file in `contracts/sync/examples/ops/` decodes `Known` and re-encodes to its
  canonical form; every file in `examples/invalid/` that is a single op decodes `Invalid`; an op with
  `op_type` `foo.bar` or `op_version` 2 decodes `Unsupported` (lenient path) with its `seq`;
  `sizeBytes` equals the UTF-8 length of `encode`; `rawSizeBytes` of a raw tree with extra whitespace
  and shuffled members equals `sizeBytes` of its decoded op, and is computed for unsupported/invalid
  ops too; equal ops with differently ordered payload members encode identically; an op of exactly
  `max_op_bytes` is allowed and one byte more is refused; canonical numbers (`1.0` → `1`, `0.9`,
  `1e20`, `1e21`, `1e-7`) match JavaScript's `JSON.stringify` (run in `jsTest` too) and
  `decode(encode(v)) == v` (incl. `1e20`); an integer-typed property of `2^53` → `Invalid`, a
  number-typed one is fine.

**T108 — search**: every `+` line of `search-examples.tsv` parses without errors and
`parse(format(tree))` gives the same tree (ignoring spans), and `format` equals the canonical text
`check_search.py` produces (its `fmt` is the reference); every `-` line yields at least one
`SearchError` with the code its note implies. Also a property test: random well-formed trees
(incl. `Not(And)`, `Not(Or)`, `Or` in `And`, texts `or`/`-a`/`a:b`) round-trip.

**T205 — queue** (data-affecting: which cards are introduced): a NEW card whose decks are all
disabled is never introduced; a deckless NEW card is (ADR-006 tier interleaving); with
`burySiblings`, one build never holds two NEW/REVIEW cards of one note.

UI code (renderer HTML, queue, completions, hot scores) needs tests only where its ticket says so.

**C-19 — field regions** (`core/src/commonTest/kotlin/kotobaya/render/`, run on jvm and js):
- `RenderGoldens.kt` unchanged and still matched with the option off (its default); `RenderRegionGoldens.kt`
  holds the same 36 cases with the option on, and equals the plain goldens once the two attributes are removed.
- Forgery: `data-field` / `data-field-source` / `kb-field-*` in a field value, a custom template or
  dictionary data never produce or alter a region (`FieldRegionTest`), and the whole hostile-payload
  suite of `SanitizerTest` runs with the option off and on through `RegionChecker.assertConsistent`.
- `fields`: sources (note wins, dictionary, kanji, empty), order of first reference, hidden sections,
  `{{FrontSide}}`, values that sanitise to nothing, placeholders inside template attributes.

How a host uses it (UI-WEB): render with `fieldRegions: true`; for each `[data-field]` element show
an edit affordance, marked "overridden" when `data-field-source="note"`; the editor's initial text is
`note.fields[key]` (empty for dictionary/kanji values), saving emits `note.updated` with that key,
"reset to dictionary" clears the field (same op). Fields with `rendered: false` have no element to attach to: list
them next to the card (filtered by `noteType.fieldKeys`) so they can be added. Never parse a region's
text back into a field value.

**C-25 — kanji example words** (`core/src/commonTest/kotlin/kotobaya/dict/KanjiExamplePickerTest.kt`, jvm and js):
- `classify` goldens for 15 kanji with their KANJIDIC readings (食 生 日 人 大 行 上 取 語 学 一 手 時 今 受):
  okurigana, rendaku, gemination, absorbed okurigana (取引, 受付, 物語), jukujikun → OTHER (今日, 大人, 時計).
- Edges: katakana folding, a headword without the kanji, small kana / `ん` cannot start a neighbour's
  reading, no gemination at the end and no rendaku at the start of a word, ON wins a tie, a kanji outside the BMP.
- `pick`: the KDoc example; one ON and one KUN kept when they rank low; tier / rank / seq order; dropped
  candidates (blacklisted, ignored, headword without the kanji, duplicate seq); `limit` 0, 1, 2, more than
  there are; the 50-candidate window, and that pre-limiting to the window gives the same result.

## C-25: review back-side extras offline (Android)

The web gets `QueueItem.decks`, `deck_count` and `kanji_examples` from the server. Android builds its
queue locally and computes the same three values from its replica; nothing here is learning data, no op
is emitted, nothing is stored. Compute them when the item is built and keep them for the whole showing.

**Example words** (cards whose note has a `kanji_literal`):
1. Readings: the local `kanji` row of the literal → `KanjiReadings(literal, on_readings, kun_readings)`.
   No row: empty lists (every word is then `OTHER`; still show the words).
2. Candidates: the local dictionary entries that are not retired and have an `entry_kanji` row for the
   literal, each as `KanjiExampleCandidate(seq, headword, headword_reading, freq_rank, status, hasNote, inDecks)`:
   `status` = local `word_status.status` (no row: `NEW`); `hasNote` = a live local note with that
   `entry_seq`; `inDecks` = a live local `deck_item` `e:<seq>` in a live local deck. Pass them all, or
   let SQL return only the picker's window (first `CANDIDATE_LIMIT` in pick order, "pick" 1-3); both give
   the same result.
3. `DefaultKanjiExamplePicker.pick(readings, candidates)` → up to 3 `KanjiExample`s, in display order.
   Never re-rank, filter or re-classify the result. `gloss` for display = the English glosses of the
   entry's first sense joined with `"; "`.
4. The dictionary is not installed yet, or has no entry for the kanji: empty list, show no block.

**Decks** (every card; the UI shows them on vocab cards):
1. The note's decks = live local decks (`deleted_at` null, any `enabled`, `type` ≠ `smart`) with a live
   `deck_item` (`deleted_at` null) that is `n:<id>` for this note or for a note whose `merged_into_id` is
   this note, or `e:<entry_seq>` when the note has an `entry_seq`. A tombstoned note has none.
2. Sort by `priority` ascending, then deck id ascending as lower-case text. Show the first 3 names;
   "+N" with N = total − 3 when positive.
3. Edge cases: a deck created offline counts at once; an item whose deck row is missing or tombstoned
   (deleted parent, or the item arrived before its deck) is skipped; a re-added item (`deck.item_put`
   after `deck.item_removed`, tombstone cleared) counts again; snapshot-delivered items of media and
   list decks count like any other; two items of one deck (`e:` and `n:`) are one deck.

The local values may differ from what the server would send while ops are pending; the local replica wins.

## Follow-ups outside this ticket's paths

- `assets/card/card.css` lacks most classes of `CardClasses.ALL` (T213 adds them).
- `make check MODULE=contracts` should run `contracts/core/check.sh` (FOLLOWUPS: Makefile wiring).
- Done in C-7: SCHEMA.md 6 lists `scheduler.mined_priority` (→ `QueueInput.decklessPriority`) and
  `scheduler.kanji_before_vocab` (deprecated and ignored since ADR-040, with `QueueDeck.addKanji`
  and `QueueInput.decklessAddKanji`).
- `make check MODULE=core` should run `:core:jsTest` (T107, with `nodejs()` on the js target).
- Card media serving: the web API route and the Android `WebViewAssetLoader` path behind
  `RenderOptions.mediaUrlTemplate` (T213 / the media ticket).

**ADR-037 — review loop** (`core/src/commonTest/kotlin/kotobaya/queue/ReviewLoopTest.kt`, jvm and js;
ticket LOOP-CORE; write the tests first, from the worked examples): A1, B3, B4, A3 of the `ReviewLoop`
KDoc and A2, A4, B1, B2, B5-B8, E1-E6 of `docs/REVIEW-LOOP.md` 10; the rule-5 mix examples of the
`QueueBuilder` KDoc (`R1 R2 N1 R3 N2 R4`; 200 ready / 40 available; 9 ready / 28 available; cap 2,
goal 1 → `R1 N1 R2`), "never two new cards in a row while a review is left", "at most
`ceil(rRem / gRem)` reviews between two new cards", and "the order of one build equals the order of
rebuilding after each answer" (answer the head, append the matching `TodayReview` with a later
`reviewedAt`, rebuild, compare with the tail); `s` counts only reviews after the latest
introduction (`reviewedAt` ties do not count; learning answers change nothing); rule-4 budget (day
remaining + batch, atomic against the day, 39/40 with a 3-card group) and `availableToday` (goal
remainder, small pool, deck cap); `LegacyCounts.new` is not bounded by the batch; `newPerSession` /
`newShownThisSession` change nothing. Properties (extend `QueuePropertyTest`): counts invariant under
`keepGoing`; `backlog >= readyReviews`; `REVIEWING` iff the served queue is non-empty;
`GOAL_REACHED` / `DONE_FOR_TODAY` imply K non-empty; no LEARNING item with `dueAt > now`; the result
is independent of the order of candidates, decks and `today`; the mix is independent of the batch.
Tests of the old rule 5 stride and of the session cap in `QueueBuilderTest` / `QueueKeepGoingTest` are
replaced, not kept.

**C-26 — keep going** (`core/src/commonTest/kotlin/kotobaya/queue/QueueKeepGoingTest.kt`): the rule-6
KDoc example (normal empty with `moreAvailable`, keep-going `R4 N1 N2 N3`, batch 2); default false is
identical to before; daily/session/per-deck new caps ignored with deck tiers kept; global and per-deck
review caps lifted; siblings and exclusions kept; nothing not yet due; disabled decks; learn-ahead keeps
the normal queue non-empty; reviews before new; kanji-unit overshoot; pending entries bounded by the
batch and counted for `moreAvailable`; batch < 1 = 1; `isNew` exactly for NEW items.

## C-26 / ADR-037: the review loop on a device (Android)

Superseded text: before ADR-037 this section told a device to call `QueueBuilder.build`, to keep
going for "one visit" and to count `newShownThisSession`. All of that is gone.

- **One entry point.** Every native caller - the local API's `getReviewQueue`, the widget, the due
  notification, the nudge threshold - calls `ReviewLoop.evaluate(input)` (contract
  `kotobaya.contracts.queue.ReviewLoop`, implementation `kotobaya.queue.DefaultReviewLoop`) and
  renders `LoopResult`. Nobody calls `QueueBuilder.build` for something shown, counts cards with
  SQL, or derives an end state from `moreAvailable` / `nextLearningDueAt`.
- **Input.** `QueueLimits.learnAheadMs = 0`; `newPerSession = 0`, `newShownThisSession = 0` (both
  ignored); `keepGoingNewBatch`: the local API passes the request's `limit` (as the server, so
  parity holds); native count-only callers leave the default 10. `keepGoing`: the local API passes
  the request's `keep_going`; native count-only callers pass `false`.
- **Loader.** `TodayReview.reviewedAt` = `review_log.reviewed_at` for every row (the rule 5 mix
  needs the answer order).
- **What to show.** Items: `LoopResult.queue.items`. Phase: `LoopState.phase`. The single "to do"
  number (widget, notification and its nudge threshold, CLI / waybar text, SPA Home, the review
  header at `done` = 0): `LoopState.untilStop` of an evaluation with `keepGoing = false`
  (ADR-054 / C-34; before it, `readyLearning + readyReviews`), so the widget needs no keep-going
  state and shows what the SPA shows. `readyLearning + readyReviews` (`ready.total`) is no
  surface's headline. New bar: `newToday / newGoal`. The "New" badge: `QueueItem.isNew`.
- **Materialization (C-27).** Materialize exactly `LoopResult.queue.toMaterialize` (the served
  build) and re-evaluate; never the other build's entries. A count-only caller (widget) does not
  materialize.
- **Keep-going target (C-31, ADR-052).** `QueueLimits.keepGoingNewTarget`: the local API passes
  the request's `keep_going_new_target` when `keep_going=true`, else `null`; native count-only
  callers always pass `null` (with `keepGoing = false`). Native code never computes or stores a
  target. See "C-31" at the end of this file.
- **Keep going scope (normative; `QueueBuilder` rule 6 "Scope", ADR-039).** One review visit of
  one client, held in memory: `keepGoing = true` only on the builds of the visit in which the
  user pressed Keep going; every new visit, restart, study-day change and 10 minutes without an
  answer end it. In the shell the client is the SPA (openapi `getReviewQueue` rule C2); native
  code stores nothing and always builds with `false`. Never an op, never a synced setting, never
  persisted (the ADR-037 `kotobaya.keepGoingDay` storage key is gone).
- **Progress bar (ADR-038, amended by ADR-054).** `LoopState.untilStop` (the cards doable now:
  `untilStopParts.learning + review + new`, `later` not summed); the bar is
  `done / (done + untilStop)`.
  Native surfaces show no bar.
- **Waiting (ADR-049, C-30).** Only `LoopPhase.WAITING` waits; resume at `LoopState.nextDueAt`.
  `WAITING` is decided before the stop screens: whenever the served queue is empty and
  `laterToday > 0`, in both modes. A caller that shows a state re-evaluates at `nextDueAt`
  whatever the phase, and offers Keep going iff `LoopState.keepGoingAvailable`.

## ADR-035: reading cards retired by a setting (C-28)

One rule, `kotobaya.contracts.cards.CardKinds` (`CardKinds.kt`): a card is retired iff
`kind == "reading"`, the note's base type is vocab and the typed setting `cards.reading_enabled`
(SCHEMA.md 6.4; default **false**, `CardKinds.readingEnabled(jsonValue)` = the 6.1 bool test) is
false. Each platform reads the setting once per operation and passes the bool; the core applies it:

| Core API (additive; old calls compile) | Caller passes | Effect when false |
|---|---|---|
| `CardGenerator.kindsForNewNote(type, deckCardKinds, readingEnabled = false)` | the setting | never returns `reading` for vocab |
| `CardGenerator.missingKinds(type, liveKinds, requested, readingEnabled = false)` | the setting | never returns `reading` for vocab (API `createCard` → 422 `/kind`) |
| `QueueInput.readingEnabled = false` | the setting | QueueBuilder rule 0: retired candidates and today's reviews of them are dropped |
| `WordCardState.kind = null`, `WordStatusRules.autoStatus(noteLive, cards, readingEnabled = false)` | `card.kind` of each card + the setting | retired cards ignored; `kind = null` is never retired |
| Device apply (`DeviceOpApplier`, `AutoStatus`, internal) | — (reads the replica's `settings` row) | `settings.set` of the key re-derives every vocab entry; derivation ignores retired cards |

Defaults are the product default (false), so a caller that is not wired yet already creates and
serves no reading cards; it only cannot turn them back on. Loaders keep passing retired cards as
candidates (they are live): the builder alone removes them, so every platform agrees.

**Server (ONE-CARD-SRV)** must: pass `readingEnabled` to `QueueInput` (QueueLoader) and to every
`kindsForNewNote` / `missingKinds` call (NoteApi, DeckEntryPlan, AnkiConnect, createCard → 422);
pass `kind` in `WordCardState` and `readingEnabled` to `autoStatus` (CoreAutoStatusHook,
StatsQueries); re-derive every vocab entry when applying `settings.set` of the key (OPS.md) and
once at startup under a new `server_state` marker (SCHEMA.md 6.4, "One-time re-derive");
validate the key as `bool` in `updateSettings`.
**Android (ONE-CARD-AND)** must: pass `readingEnabled` in LocalQueueLoader and to
NoteCreator/DeckEntryPlan's generator calls; the toggle writes `settings.set`; word-status
re-derivation (on the setting and once after upgrade, `StartupReplay` key
`client.word_status_rules`) is already done by the core.

**Tests** (`core/src/commonTest`, jvm and js): `queue/QueueRetiredCardsTest.kt` (never served in any
state, no `nextLearningDueAt`, no sibling burying incl. through today's reviews, no limit use, only
vocab, same result with or without retired candidates, pending entries unchanged, `moreAvailable`);
`cards/CardGeneratorTest.kt` (defaults, deck `card_kinds: ["reading"]` → `[]` → fallback
`["meaning"]`, `missingKinds`, non-vocab `reading` kept, bool test);
`queue/WordStatusRulesTest.kt` (`retiredReadingCardsAreIgnored`, a learning reading card);
`sync/client/apply/ReadingRetiredApplyTest.kt` (scenario `reading-retired-offline-review`, setting
flips re-derive op by op and in a batch); `BatchApplyEquivalenceTest` logs flip the setting.

## C-27: pending entries and materialization (ADR-032, ADR-040, ADR-042)

Materialization is defined once, here, and every platform does exactly this: the server inside
`GET /review/queue` (openapi `getReviewQueue`, as the server device), a device locally through its
op path. **When a device materializes (C-31, ADR-050; normative):** iff it is paired, its first
complete pull has finished (`sync_state.last_sync_at` is set) and no rebuild is pending
(`sync_state.rebuild_pending` unset). Never conditioned on a sync cycle that is running, has
failed or is backing off, nor on connectivity (SYNC.md 8.1 "When a device materializes", SHELL.md
4.3 `getReviewQueue`). A loader that withholds materialization for any other reason is wrong: it
produces `REVIEWING` with no item, the state ADR-051 forbids on the phone. Clients of the API never create these notes. Since ADR-040 it creates **two** things:
kanji notes for the words a build draws (existing notes and deck entries alike, whatever a deck's
`add_kanji` says) and, as before, the notes of drawn deck entries.

**What materializing a served build `Q` creates** (normative; one op transaction, in this order):

A. **Kanji notes**: for each literal of `Q.kanjiToMaterialize`, in order: skip it if a live kanji
   note now holds that `kanji_literal` (idempotent) or a **user-deleted** one does (a kanji note
   with `deleted_at` set and no `merged_into_id`: never re-created). Otherwise
   `note.created` {built-in kanji type `…0102`, `kanji_literal`, `fields` `{}`, `origin`
   `auto_kanji`} and one `card.created` per default kind of the kanji type whose front is not
   empty (with the kanji's `ref.kanji` data, or the literal alone when the dictionary lacks it).
B. **Entries**: for each entry `e:<seq>` of `Q.toMaterialize`, in order, drawn by deck D:
   1. Skip the entry if a live note now holds `entry_seq = seq` (idempotent) or the dictionary entry
      is missing.
   2. **Vocab card kinds** `V` = `CardGenerator.kindsForNewNote(vocab type, D.card_kinds, readingEnabled)`;
      if that is empty (`card_kinds` is `[]` or names no kind of the type, or only retired kinds,
      e.g. `["reading"]` while reading cards are off — ADR-035), the type's default kinds
      (`kindsForNewNote(vocab type, null, readingEnabled)`, i.e. `["meaning"]`). `readingEnabled` =
      setting `cards.reading_enabled` (SCHEMA.md 6.4, `CardKinds.readingEnabled`). Then drop every kind whose front would render empty:
      `DefaultCardRenderer.frontIsEmpty(vocab type, kind, {}, dictionary = the entry's data)` (e.g.
      `listening` without audio; the same rule devices apply when adding a note). If `V` is empty the
      entry is not materialized, and loaders never pass it (below), so it is never drawn.
   3. `note.created` {built-in vocab type `…0101`, `entry_seq`, `fields` `{}`, `origin` `deck`}, then
      one `card.created` per kind of `V`.
Then rebuild. There is no per-entry kanji step any more: the kanji of an entry are in
`kanjiToMaterialize` like those of an existing note, so two words that share a missing kanji mint
it once.

**What a loader computes per vocab note and per pending entry** (normative; the same on every
platform, from the same rows):
- `form` = `WordForm(expression, formTags, usuallyKana)` as `KanjiRequirement.kt` defines
  (expression = the note's `expression` text, else the entry's `headword`; for an entry: its
  `headword`. The `headword` is taken **as stored**; since ADR-046 (a) it is the reading for an
  entry whose kanji forms are all `rK` / `sK`, SCHEMA.md 4.5 "Headword", so such a word has no
  kanji here and the loader does not look at its other forms), `facts` = `ref.kanji.grade` / `freq_rank` of its kanji, `enabled` =
  `KanjiRequirement.exemptionsEnabled(settings["cards.kanji_exemptions"])`.
- `expressionKanji` = `CardGenerator.kanjiOf(expression)` (as before).
- `requiredKanji` = `KanjiRequirement.evaluate(form, facts, enabled).learnableKanji`.
- `missingKanji` = for each literal of `requiredKanji`, in order, with no live kanji note and no
  user-deleted kanji note for it: `MissingKanji(literal, newCards)`, `newCards` = the number of
  kinds step A would create for it, at most 1 when `scheduler.bury_siblings`; a literal that
  would get no card is left out.
- The item's `front_furigana` / `RenderInput.frontFurigana` is the same evaluation's
  `frontFurigana` (computed where the item is rendered; not part of `QueueInput`).
- **Scoped builds** (`deck_id`): besides the cards in scope, pass every live card of every live
  kanji note whose literal is a required kanji of a passed vocab candidate or pending entry and
  that is not in scope, with `QueueCandidate.companion = true`.
- `QueueDeck.addKanji` = `OFF`, `decklessAddKanji` = `OFF`, `PendingEntry.newKanjiCards` = 0 (all
  ignored).

**Which entries a loader passes as `QueueInput.pendingEntries`** (normative): deck items `e:<seq>`
(`deck_item.deleted_at IS NULL`) of live, **enabled**, non-smart decks, such that
- the dictionary entry exists locally (`ref.dict_entry`; a device without it skips it until it has it);
- no **live** note holds `entry_seq = seq`;
- no **user-deleted** note held it: no note with `entry_seq = seq`, `deleted_at IS NOT NULL` and
  `merged_into_id IS NULL` (merge losers do not count). Deleting the note of a deck word drops it
  for good; it is never re-created by the queue (adding a note by hand still works);
- `word_status.override_status` is not KNOWN, NEVER_FORGET, BLACKLISTED or IGNORED;
- step B.2 leaves at least one vocab kind.

The builder draws a word as the **unit** it will be once everything exists (QueueBuilder rule 4
"Kanji first"): its vocab card, the existing new kanji cards of its required kanji and the cards
of the kanji still to mint. A unit that does not fit today's goal leads with the kanji that fit
(ADR-043): only those are claimed and minted, the entry itself stays pending. Backfill claims the
missing required kanji of words already studied, within its half share of the day (ADR-044;
rule 4 "Half share"): at most `max(1, newPerDay / 2)` backfill cards a study day while a source
has something to draw, the remainder of the goal when none has. In every case nothing is created that the
rebuild would not show, and the counts (`drawn`, `availableToday`, `untilStop`) are the
same before and after the rebuild. An existing vocab card with a missing kanji is **withheld**
from `items` until step A has run.

A loader may pass only the first `2 × B + 16` such entries per deck in `(position, item_key)` order,
`B = max(1, keepGoingNewBatch)` in both modes (ADR-037: the batch bounds the normal build as well
as the keep-going one, and `ReviewLoop.evaluate` runs both builds from the one loaded input, so one
window serves both). An entry beyond the window is reached by a later build.

**Read-only callers** cannot run step A either: `kanjiToMaterialize` counts into the API's
`to_materialize`, the withheld word is not in `items`, and the phase is `REVIEWING`.

**Edge cases** (each is a test): a kanji minted by step A and its word deleted before the rebuild
→ the kanji card stays, a deckless new card drawn on its own turn; the same literal claimed by a
device offline and by the server → two kanji notes for one literal converge by SYNC.md 8.1 (one
live note per `kanji_literal`; the loser's card and reviews merge into the survivor); a literal
whose note the user deleted → never in `missingKanji`, never minted; a kanji absent from the
dictionary → minted with the literal alone; `kanjiToMaterialize` non-empty but every literal
skipped by step A (the replica changed between load and transaction) → the rebuild sees the
existing notes, nothing is created twice; the rounds limit below still applies.

**Which build is materialized (ADR-037).** `ReviewLoop.evaluate` returns the build to serve
(`LoopResult.queue`). Only its `toMaterialize` is materialized: the normal build's for a normal
request, the keep-going build's for a keep-going request. The other build exists only for the
counts and the phase; materializing its entries would create notes past the day's goal on a
request that merely asked for the state. The new-card budget of the normal build is the day's
remaining goal and the batch (QueueBuilder rule 4), not `min(day, session)`.

**Rebuild rounds (server and device).** After materializing, rebuild; materialize again (at
most 3 rounds) only if the previous round's notes appear in `items` (a safety net: a mismatch can
never create notes that are not shown). A kanji note of step A appears as the kanji card at the
head of its word's unit.

**Convergence.** A device and the server creating a note for the same entry converge by SYNC.md 8.1
(the note that held the entry first on the server survives; the other's cards and their reviews are
merged into it; the arrival-order trade-offs of SYNC.md 8.4 apply).

**Tests** (`core/src/commonTest/kotlin/kotobaya/queue/QueuePendingGroupTest.kt`, jvm and js): an
entry whose unit does not fit is not drawn (and `moreAvailable` is true); existing new kanji cards
join and are taken; the same count before and after materializing; `newKanjiCards` and every
`AddKanjiMode` value change nothing; two entries sharing a missing kanji claim it once. The old
"OFF = 1 card" and "negative `newKanjiCards`" cases are replaced, not kept.

## ADR-038..042: until_stop, kanji first, deck progress, kanji parts, exemptions (C-28)

Contract files: `queue/QueueBuilder.kt` (rule 4 "Kanji first", rule 5 units and "Open group
first", rule 6 Scope), `queue/ReviewLoop.kt` (`LoopState.untilStop`), `cards/KanjiRequirement.kt`,
`decks/DeckProgress.kt`, `dict/KanjiParts.kt`, `render/CardRenderer.kt` and `CardTextRenderer.kt`
("Furigana front"), `js/CoreJs.kt` (`JsRenderInput.frontFurigana`). Everything is additive on the
types: old calls compile and give the pre-C-28 result except where a rule itself changed (the
kanji modes, `newKanjiCards`).

| Core API | Caller passes | Notes |
|---|---|---|
| `QueueCandidate.requiredKanji`, `.missingKanji`, `.companion`; `PendingEntry.requiredKanji`, `.missingKanji` | the loader, as "C-27" above | `requiredKanji = null` = every kanji (ADR-042 off) |
| `ReviewQueue.kanjiToMaterialize` | — (output) | materialize step A, then rebuild |
| `LoopState.untilStop` | — (output) | API `loop.until_stop` |
| `KanjiRequirement.evaluate(form, facts, enabled)` | loader (queue), renderer host (front), materializer | one predicate; never restated |
| `RenderInput.frontFurigana`, `TextRenderInput.frontFurigana`, `JsRenderInput.frontFurigana` | `evaluate(...).frontFurigana` / `QueueItem.front_furigana` | default false = old output |
| `DeckProgress.count(words, readingEnabled)` | `listDecks` / `getDeck` handlers (server, local API) | API `Deck.progress` |
| `KanjiParts.build(expression, reading, sources)` | queue item builders (server, local API) | API `QueueItem.kanji_parts` |

**Not exported to JS.** The web takes `kanji_parts`, `front_furigana` and `progress` from the API
(server or the shell's local API) and only renders them; `CoreJs` gains nothing but
`JsRenderInput.frontFurigana`.

**Android / local API (AND-28).** `getReviewQueue`: loader as "C-27" above, materialize steps A
and B through the device's op path, `kanji_parts` by `KanjiParts.build` and `front_furigana` by
`KanjiRequirement` over the local dictionary (SHELL.md L-7 steps 1–2 only). `listDecks` /
`getDeck`: `progress` by `DeckProgress.count` over the replica. `getDeckCoverage` stays proxied.

**Tests implementers must write** (`core/src/commonTest`, jvm and js; write them first, from the
KDoc examples):
- `queue/QueueKanjiFirstTest.kt`: every rule-4 example (withheld and after minting; lead at 39/40;
  no starvation over three days with `newPerDay` 2; buried kanji holds the word and the source
  goes on: `[たべる, 校]`, the next day `[学, 学校]` (C-28c); the three "deck limit" variants of
  the C-28c example (`newPerDay` 1 → the kanji alone, `[]` after its answer, the word the next
  day; `newPerDay` 2 → whole; kanji note deckless → whole) and a deck with `newPerDay` 1 whose
  word has two kanji homed in it (one kanji today, one tomorrow, the word on day 3); the C-28d
  example "a lead ends at the deck's limit" (`[学]`, after its answer `[]`, the next day
  `[校, 学校]`; with 校 deckless `[学, 校]`, after 学 `[校]`) and `untilStop` never dropping by more
  than 1 for one answer in it; backfill with minting, order by oldest word, same order after minting); a lead stops
  its source but not the others; a lead of an entry leaves the entry out of `toMaterialize`; the
  goal is never overshot by a normal build whose kanji parts all have size 1 (property); a deckless word gets its kanji first; a deck with `add_kanji` `off` behaves the
  same; the 40-card budget includes kanji; a kanji card whose decks are all disabled joins its
  word's unit; a started, a suspended and a manually buried kanji do not block; two words
  sharing a kanji; `burySiblings` false; companions (new only through a unit, learning served,
  review never); `requiredKanji` empty = no unit, null = `expressionKanji`; the deckless
  tie-break by literal; counts equal before and after materialization.
- `queue/QueueBackfillShareTest.kt` (ADR-044, C-28b): the three rule-4 "half share" examples
  (`newPerDay` 4 → `[日, 本, うっかり, Ｔシャツ]`, after two answers `[うっかり, Ｔシャツ]`, next day `[語]`;
  no words → `[日, 本, 語]`; `newPerDay` 5 → 語 last) and the keep-going example with its
  alternating variant; the C-28d keep-going example with an open unit (`学 校 学校 日`, after 学
  `校 学校 日`, after 校 `学校 日`) and a share step that ends right after a part whose kanji a
  source claims (the claimed unit comes before the next part); `newPerDay` 1 and 3 → share 1; `bToday` counts a kanji of a word
  introduced on an earlier day or earlier today, not a kanji answered ahead of its own word (unit
  and lead), not when `reviewedAt` ties; a source stopped by a lead or at its per-deck limit
  hands the remainder to backfill; a deckless NEW card of a backfill kanji is never drawn on a
  deckless-source turn (same tier as the deck, and with a lower `decklessPriority`): the
  half-share example gives the same N in both; the deck-homed examples (日 in an enabled kanji deck →
  `[日, 本, うっかり, Ｔシャツ]`, drawn by the share step and counted against that deck's daily
  count; the same with the kanji deck at priority 10, at its per-deck limit, with `newPerDay` 0
  or disabled: 日 is still drawn via backfill; 語 in the kanji deck → `[日, 本, うっかり, 語]` on the
  deck's own turn, `bToday = 3` afterwards; a card taken by the share step is not drawn again
  on its deck's turn); the batch cuts a prefix of the unbounded draw
  (`availableToday` unchanged); a part of 2 cards with `newPerDay` 1 is drawn when nothing was
  introduced today and not again after its first card is answered, as a lead part and as a
  backfill part; `newPerDay` 0 draws nothing. Property (normal and keep going, random inputs
  with backfill, units and reviews): answering the head and rebuilding yields the previous
  build's tail (C-28d: the keep-going half uses the same inputs as the normal half, NEW words
  with kanji included; no `newWordKanji = false`); in a normal build `bToday + b <= share` after the share steps unless a single
  part is larger than the share.
- `queue/QueueMixUnitsTest.kt`: the rule-5 unit example (`R1 学 校 学校 R2 R3 たべる R4`) and its
  rebuild; the two C-28c examples (`日 家 本 語 日本語` and its rebuilds; `校 学校` with 学 introduced
  last: the word is never emitted before a kanji it requires); **rebuild = tail** (C-28c,
  replaces the narrower M4 extension): for random inputs with reviews, deckless words, decks
  with per-deck limits each in a tier of its own, deck-homed and deckless kanji cards shared
  between words, introduced words (backfill) and answers already given today, in the normal
  and the keep-going build, answering the head and rebuilding yields the previous build's tail;
  no exclusion for backfill kanji that a new word also requires or for kanji homed in a capped
  deck (the two cases the CORE-28 attempt 1 properties left out); **several sources in one
  tier** (decks and the deckless source at the same priority, the default setup) are part of
  the random inputs, and **no input is excluded** (C-28d: `QueueRandom.rebuildExcluded` is
  deleted; the keep-going half keeps its studied words, so backfill and units meet; seeds 506
  and 1471 of `QueueRandom.input(seed, keepGoing = false)` are named regression cases, run
  besides the seed range); the "Turn order" example
  (`a 学 校 学校`, after a `学 校 学校`, after 学 `校 学校`, the next day b first); the
  "kanji no word requires" example: a deck of 30 kana words and 50 deckless NEW kanji notes
  that no word requires alternate (`w1 k1 w2 k2 …`, 10 items at batch 10, `availableToday`
  20, 10 words among them), also after each answer, and the deck is never starved; the same
  with one of the kanji required by a NEW word (that card is left out of `t`, in today's
  reviews and in the build alike); `t(S)` counts deck-homed kanji cards; two sources whose
  next words both require a kanji just introduced (deckless, and homed in the later deck):
  rule (a) picks the **first in source order whatever the counts** (C-28d; replaces "the
  smaller `t`"); the C-28d example "a kanji that two sources need" (`a 日 本 語 日本語`, after a
  `日 本 語 日本語`, after 日 and 本 `語 日本語`, the next day `語 日本語 本`); rule (c): a source
  earlier in source order whose next card is the kanji card itself (own turn) takes the turn
  before a later source's unit that contains that kanji; a source at its per-deck limit or
  stopped by a lead claims nothing; rule (a) inside one build (a kanji drawn on a kanji deck's own turn
  is followed by the source whose next word requires it, whatever the counts); in every build no vocab item
  precedes a NEW kanji item of a kanji it requires; no review between the cards of a unit; a due learning card may come
  between and the group continues after it; the open group is not pulled after a review was
  answered (`s > 0`), after a day rollover, or when the last introduced card is a vocab card;
  keep going: open group, then reviews, then the rest.
## ADR-046: kana words and jōyō-only for tagged words (C-28f)

Contract files: `cards/KanjiRequirement.kt` (rule and KDoc), SCHEMA.md 4.5 "Headword" and 6.5,
`DICT_PACKAGE.md` 2.3, parity `queue-kanji-rare-forms`. Additive on the types:
`KanjiRequirement.isJoyo(grade)` and `KANA_WORD_FORM_TAGS` are new **companion** members with
bodies, so `DefaultKanjiRequirement` compiles unchanged; only `evaluate`'s behaviour changes.

| What | Where it is decided | Who changes |
|---|---|---|
| (a) An entry whose kanji forms are all `rK` / `sK` displays its reading | The **dictionary build** writes the first reading into `dict_entry.headword` (SCHEMA.md 4.5). Every reader already takes `headword` as stored. | worker (`dict-refresh`: headword rule, new release so packages and diffs are rebuilt); every Kotlin/TS fixture-dictionary loader used by tests (same rule); nothing in the core |
| (b) A word-level tagged word requires its jōyō kanji only | `KanjiRequirement.evaluate` step 2 | core `DefaultKanjiRequirement` (one branch: `wordExempt` → `isJoyo(grade)`, else `isLearnable`) |
| Kanji notes no word requires any more | SCHEMA.md 6.5: they stay; nothing deletes them | nobody |

Server, Android and web need **no code change** for (a) or (b) beyond taking the new core and
the new dictionary data: the loader, the materializer, the renderer host and the API all go
through `headword` and `KanjiRequirement.evaluate`. They MUST NOT re-derive "kana word" from
`kanji_forms` at read time.

Tests (CORE-28d):
- `cards/KanjiRequirementTest.kt`: re-record against the KDoc. Every worked example as the full
  triple, on and off: 海老, 寿司, 珈琲, 林檎, お化け, 彼 (field), 今日, 昨日, 鱈, 鱈子 **unchanged**;
  薔薇 tagged → `(true, [], true)`, untagged → `(false, [薔], false)`; 金糸雀 → `(true, [金, 糸],
  true)`, off `(false, [金, 糸, 雀], false)`; 鯛 tagged / untagged; シンガポール and かるた as kana
  forms → `(false, [], false)` on and off; 新嘉坡 (field) → `(true, [新], true)`, off all three;
  歌留多 (field) → `(true, [歌, 留, 多], true)`. Properties: for random forms and facts the tagged
  result is a sub-list of the untagged one and the difference holds only non-jōyō kanji with a
  rank in 1..2500; a jōyō kanji is in both; `isJoyo` for null, 0, 1, 6, 7, 8, 9, 10; `isJoyo(g)`
  implies `isLearnable(g, r)`. The C-28e assertion "`learnableKanji` equal with and without tags"
  is **replaced** by these, not kept.
- Worker: the headword rule on the fixture (the 137 entries of `JMdict_subset.xml` whose kanji
  forms are all `rK` / `sK` get their first reading, e.g. 1000580 あれ, 1021360 イタリア, 1001620
  おにぎり, 1577980 いる; 1012730 軈て and 1001670 お化け keep the kanji form); `dict_entry_form`
  and `entry_kanji` row counts unchanged by the rule.
- Parity: record the golden of `queue-kanji-rare-forms`; no other golden changes.

- `queue/QueueParityScenarioTest.kt`: mirrors of the three C-28d parity scenarios
  (`queue-kanji-shared-kanji`, `queue-kanji-keep-going-open-unit`, `queue-kanji-lead-deck-limit`;
  14 mirrors in all), every step's items and `until_stop`.
- `queue/ReviewLoopTest.kt`: `untilStop` for A1 (66), B3 (0), B4 (145), U1 (65, 47 with 12 new
  cards) and its three follow-ups (Again on a review keeps it, Good lowers it, a new card
  answered Good keeps it); equal before and after materialization; `REVIEWING` implies `>= 1`.
  (**Superseded by ADR-054, see the C-34 section:** A1 40, U1 60 / 42, every answer lowers it.)
- `cards/KanjiRequirementTest.kt` (ADR-045, C-28e; **amended by C-28f, see the ADR-046 section
  above** for 薔薇 and the "equal with and without tags" property): every worked example of the KDoc, on and off,
  as the full triple (海老, 寿司, 珈琲, 林檎, お化け, 彼, 今日, 昨日, 鱈, 鱈子, 薔薇 tagged and
  untagged); each word-level tag alone on a word with learnable kanji → `wordExempt`,
  `frontFurigana` **and the learnable kanji still in `learnableKanji`**; `learnableKanji` equal
  with and without tags for the same form and facts; a tagged word without learnable kanji →
  `[]`, furigana; `uk` on a later sense only → not tagged; `uk` on the first sense that applies
  to the form; a tag on a kana-only expression → `(false, [], false)`; wrong-case and unknown
  tags ignored; grade 8 / 9 / 10; rank 2500 / 2501; a kanji without facts; kana-only;
  `exemptionsEnabled` for null, `true`, `false`, `0`, `"false"`.
- `decks/DeckProgressTest.kt`: the KDoc example; every edge case listed there; `percent` for
  0/0, 1/3, 2/3, 3/3, 199/200.
- `dict/KanjiPartsTest.kt`: every `usedReading` example of the KDoc (大学, 学校, 食べる, 誕生日, 今日 →
  null, 日本 → null); `usedReading == null` iff `classify == OTHER` over the 15 kanji of the C-25
  goldens; `build`: order, de-duplication, `MAX_PARTS`, keyword from the note before the first
  meaning, a kanji without data.
- `render/`: furigana-front goldens (HTML and text) for 寿司 and 珈琲; off = byte-identical to the
  existing goldens; not applied to `reading`, `production`, `listening`, custom templates, an
  empty reading.

## ADR-049: the stop screen waits for today's learning (C-30)

Contract: `queue/ReviewLoop.kt` (KDoc of `LoopPhase`, `LoopState`, `ReviewLoop` "Phase", edge cases,
worked examples W1 and W2; new `UntilStopParts`). Design note: `docs/REVIEW-LOOP.md` section 15.

| Core member | Input | Output on the wire |
|---|---|---|
| `LoopPhase` order: `WAITING` (rule 2) before `GOAL_REACHED` / `DONE_FOR_TODAY` (rule 3) | — | API `loop.phase` (same values) |
| `LoopState.keepGoingAvailable` (default `false`) | — (output) | API `loop.keep_going_available` |
| `LoopState.untilStopParts: UntilStopParts(learning, later, review, new)` (default zeros) | — (output) | API `loop.until_stop_parts` |

No signature was removed or changed; both new members have defaults so that existing callers compile.
`ReviewLoop.evaluate` MUST set them. No op, no table, no setting, no `SCHEDULER_VERSION` change: the
loop is a pure view of the replica.

Implementation ticket CORE-30 (`kotobaya.queue.DefaultReviewLoop`), tests first:

- `ReviewLoopTest` (jvm + js): **W1** steps 1-4 with the numbers of the KDoc (9 ready / 14 later /
  707 new, goal 40/40, unlimited: `REVIEWING` 23 `(9,14,0,0)`; `WAITING` `keepGoingAvailable`
  `(0,23,0,0)`; keep going `REVIEWING` 33 `(0,23,0,10)`; `GOAL_REACHED` 0; **ADR-054 / C-34:
  `untilStop` 9, 0, 10, 0**); the capped twin ending
  in `DONE_FOR_TODAY`; **W2**; B6 now also asserts `keepGoingAvailable == false`; A1, A3, B3, B4,
  U1 gain their `untilStopParts` (`(0,26,0,40)`, `(0,0,0,0)`, `(0,0,0,0)`, `(0,0,135,10)`,
  `(0,5,30,30)`) and `keepGoingAvailable` (false, true, true, false, false).
- Edge cases of the KDoc, one test each: a learning card due at exactly `day.endMs` and one due
  1 ms before it (the first does not hold the day open, the second does); a learning card with
  `dueAt == now` is ready (`REVIEWING`), `dueAt == now + 1` is later (`WAITING`); the last
  waiting card suspended / buried → rule 3 or 4; `WAITING` with keep going on and K empty
  (`keepGoingAvailable` false); `new_per_day = 0` with a relearning card on its timer →
  `WAITING`; a read-only caller with `toMaterialize` non-empty stays `REVIEWING`.
- Properties (extend `QueuePropertyTest`; random inputs, both modes): `WAITING` iff served queue
  empty and `laterToday > 0`; stop phase implies `laterToday == 0 && readyLearning == 0 &&
  readyReviews == 0 && untilStop == 0`; `untilStop == 0` iff stop phase (**ADR-054: iff
  `phase != REVIEWING`**); `untilStopParts` sums to `untilStop` (**ADR-054: `learning + review +
  new`, `later` not summed**) with `learning == readyLearning`, `later == laterToday`, `review == (keepGoing ?
  backlog : readyReviews)`; `keepGoingAvailable == (!keepGoing && legacy.moreAvailable)`; every
  field except `phase`, `keepGoing`, `keepGoingAvailable`, `untilStop`, `untilStopParts` is equal
  across the flag; no LEARNING item with `dueAt > now` in either build (no learn-ahead).
- The existing tests that expect `GOAL_REACHED` / `DONE_FOR_TODAY` with `laterToday > 0` are
  changed to `WAITING`, not kept (the old ADR-037 edge case "a learning card falls due during
  goal_reached" becomes "…during waiting").

## C-31: keep-going target, fixed loop invariants, loop presenter (ADR-051, ADR-052)

Contract: `queue/QueueBuilder.kt` (`QueueLimits.keepGoingNewTarget`, rule 6 "Target" and "Scope"),
`queue/ReviewLoop.kt` (phase rule 3, `keepGoingAvailable`, `untilStop` with a target, invariants
I1 … I8, worked examples W3, W4, X1, X2, X3), new `queue/LoopPresenter.kt` (`LoopPresenter`,
`LoopPresentation`, `LoopScreen`, `PendingAnswer`). Design: `plan/tickets/LF-DESIGN.md` section 8.

| Core member | Input | Output on the wire / in the client |
|---|---|---|
| `QueueLimits.keepGoingNewTarget: Int?` (default `null`; last constructor parameter) | API query `keep_going_new_target` (only with `keep_going=true`) | — |
| `ReviewLoop` phase rule 3 without `!keepGoing`; K∞ / K_T | — | `loop.phase` may be `goal_reached` / `done_for_today` with `loop.keep_going = true` when a target was sent |
| `LoopState.keepGoingAvailable` = served queue empty and K∞ non-empty | — | `loop.keep_going_available` |
| `LoopState.untilStop` / `untilStopParts.new` with a target = new cards left to the target | — | `loop.until_stop`, `loop.until_stop_parts.new` |
| `LoopPresenter.present(state, pending, hasLocalCard, localHeadKind, dayEndsAt): LoopPresentation` | `loop`, `day_ends_at`, the client's pending answers and held card | review screen, header parts, progress bar (openapi rule C7); exported by `@kotobaya/core` |

Compatibility: nothing was removed. `keepGoingNewTarget` has a default and is the **last**
constructor parameter of `QueueLimits`, so positional callers compile; with `null` every result is
the pre-C-31 result bit for bit (the property tests of C-30 stay as they are, including
`keepGoingAvailable == (!keepGoing && legacy.moreAvailable)`, which now reads "when the target is
null"). No op, no table, no setting, no `SCHEDULER_VERSION` change: the loop and the presenter are
pure views.

**What it replaces of ADR-039** (one sentence each, nothing else): "within one visit keep going is
an endless stream" → with a target, one goal-sized batch per press and then the stop screen
again; "the stop screens only without keep going" → also with keep going once the target is
reached; "`keepGoingAvailable` is false in every keep-going evaluation" → false only while a card
is served or nothing is left. Kept from ADR-039: the flag (and now the target) lives in memory for
one review visit and is dropped when the visit ends; an unfinished batch is not owed afterwards
(ADR-052 b; invariant I8).

**Signature note.** The ticket named `present(state, pending, hasLocalCard, localHeadKind)`. The
contract adds a fifth parameter, `dayEndsAt`, because "does this pending learning card come back
today" needs the end of the study day and neither `LoopState` nor `PendingAnswer` carries it; the
alternative (clients compare `dueAt` with `day_ends_at` themselves) is exactly the client-side
arithmetic the presenter exists to remove.

Implementation ticket CORE-31 (`kotobaya.queue.DefaultQueueBuilder` rule 6 "Target",
`DefaultReviewLoop`, new `kotobaya.queue.DefaultLoopPresenter`, `CoreJs` export), **tests first**:

- `ReviewLoopTest` (jvm + js), one test per worked example with the numbers of the KDoc: **W3**
  steps 1-6 (goal 40/40, target 80: `(0,0,0,40)`; mid-batch `(1,3,0,15)` with the due learning
  card first; target reached → `GOAL_REACHED` with `keepGoing = true`, `keepGoingAvailable = true`;
  pool exhausted before the target → `EMPTY`), the capped twin ending in `DONE_FOR_TODAY`; **W4**
  (order: due learning, open group, review, new); **X1** steps 1-5 (exit mid-batch: Home has
  `parts.new == 0`; the next press gives `(0,0,0,20)`); **X2** (target already exceeded); **X3**
  (`newPerDay = 0`: one card per press).
- `QueueBuilderTest`: rule 6 "Target" example (`T = 4`: `N1 N2`, then `N2`, then empty; `T` below
  `iToday`: empty; `T = null`: all five; a due review first); a unit of three with
  `targetRemaining 1` leads with one kanji; `targetRemaining 0` draws nothing; a normal build
  ignores the target (same `ReviewQueue` for every target value).
- `LoopPresenterTest` (jvm + js): **P1 … P10** with the numbers of the KDoc, and each edge case
  listed there (held card with a terminal state, more pending answers than counted, `dayEndsAt`
  in the past, unknown `resultingState` per kind and rating, `localHeadKind == null`).
- `ReviewLoopInvariantFuzzTest` (jvm + js; seeded; at least 2 000 runs of at most 60 steps; the
  seed is printed on failure): the generator and the steps of `ReviewLoop` KDoc "Fuzz test
  shape"; after every step **I1 … I8** and the presenter's **P-I1 … P-I4** (ADR-054: I1 … I9,
  P-I1 … P-I5) plus its consistency
  bound. This is the test ADR-051 asks for; it must fail if phase rule 2 and 3 are swapped
  back, if `keepGoingAvailable` is computed from the served build, or if a normal build reads
  the target.
- Existing tests: none changes its expectation (every existing caller passes no target).

Named exception (attempt 2): the **open-unit exception to ADR-052 (a)** — the remaining cards of
a half-answered kanji unit are served before a review that has become due, never before a due
learning card (`QueueBuilder` rule 6 "Target", `ReviewLoop` W4, I5). I8 **includes**
`kanjiToMaterialize`: `|NEW items| + |toMaterialize| + |kanjiToMaterialize| <= max(0, newGoal −
newToday)` without keep going. The client's target is `newToday + pendingNew + max(1, newGoal)`
(`pendingNew` = pending answers of kind NEW; 0 on every presenter screen that shows the button).

Edge cases the KDoc fixes and the tests above cover: target already exceeded; `new_per_day = 0`;
pool smaller than the batch; a card coming due mid-batch; the visit ending mid-batch; a keep-going
`WAITING` that offers the button; day rollover (the client drops flag and target, C2); duplicate or
out-of-order pending answers; clock skew (the presenter reads no clock).

## ADR-054: until_stop counts only cards doable now (C-34)

Contract: KDoc only, `queue/ReviewLoop.kt` (`UntilStopParts`, `LoopState.untilStop` /
`untilStopParts`, the `LoopState` invariants, I3, I7 note, new **I9**, worked examples A1, U1,
W1–W3, X1) and `queue/LoopPresenter.kt` (`LoopPresentation`, projection table, P-I3, new **P-I5**,
the consistency check, P1–P10). No signature, type, default or wire shape changes. Amends ADR-038
("total = everything before the stop screen") and ADR-049 C3 ("header shows four parts summing to
until_stop"), as quoted in the sections above.

| Core member | Before (ADR-038 / ADR-049) | Now (ADR-054) | Wire |
|---|---|---|---|
| `LoopState.untilStop` | `learning + later + review + new` | `learning + review + new` of `untilStopParts` | `loop.until_stop` |
| `UntilStopParts.later` | summed | same value (`== laterToday`), **not** summed; later-today hint only | `loop.until_stop_parts.later` |
| `LoopPresentation.untilStop` | sum of the four projected parts | `learning + review + new` of the projected parts | client header / bar |
| `untilStop == 0` | iff stop phase | iff `WAITING` or a stop phase (I9) | — |

Implementation ticket LOOP-34 (core, server, android, web, CLI tests and goldens; merges together
with C-34). Tests to change first, from the KDoc numbers:
- `queue/ReviewLoopTest.kt`: A1 40 (was 66); U1 60, 42 with 12 new cards; its follow-ups 59, 58,
  57 (every answer lowers it by one), and +1 when a learning card comes due; W1 step 1 9 `(9,14,0,0)`,
  step 2 0 `(0,23,0,0)`, step 3 10 `(0,23,0,10)`, step 4 0; W2 0 `(0,2,0,0)` and 145 `(0,2,135,10)`;
  W3 step 3 16 `(1,3,0,15)`; X1 step 3 0. B3 0 and B4 145 are unchanged.
- `queue/QueuePropertyTest` / C-30 properties: `untilStop == 0` iff `phase != REVIEWING` (replaces
  "iff stop phase"); `untilStopParts.learning + review + new == untilStop`.
- `ReviewLoopInvariantFuzzTest`: assert **I9** after every step and **P-I5** for the presenter
  (I1 … I9, P-I1 … P-I5); it must fail if `later` is summed back into `untilStop`.
- `LoopPresenterTest`: P1 60, P2 59, P3 58, P4 1, P6 0 (and `WAITING` 0 with `(0,1,0,0)`), P10 37;
  a held LEARNING card whose floor takes one from `later` raises `untilStop` by one.
- `queue/QueueParityScenarioTest.kt` and every parity golden that pins `until_stop`: the
  expectations in `contracts/api/shell/parity/` were recomputed by C-34 (20 values in 7 scenarios);
  re-record the server and shell goldens to match.

Edge cases: `WAITING` with `laterToday > 0` has `untilStop == 0` (the countdown and hint show what
is coming); a learning card that comes due raises `untilStop` (and the bar's total) by one; in the
**loop** (`ReviewLoop.evaluate`, not the presenter) an answer whose card comes back already due
is ready learning and stays in the total, while the presenter's projection still moves it to
`later` (its table; corrected by the next state); `kinds` still does not affect it; with keep going and no target the new part is the batch as
before.
