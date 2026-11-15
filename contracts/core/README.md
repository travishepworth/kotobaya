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
| `CardGenerator` (`cards/CardGenerator.kt`) | SYNC.md 4 (default cards, `card_kinds`, no knowing duplicates); SPEC card kinds table; `add_kanji` | T205 (or the note-creation ticket) |
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
  `scheduler.kanji_before_vocab` (→ `QueueDeck.addKanji` fallback, `QueueInput.decklessAddKanji`).
- `make check MODULE=core` should run `:core:jsTest` (T107, with `nodejs()` on the js target).
- Card media serving: the web API route and the Android `WebViewAssetLoader` path behind
  `RenderOptions.mediaUrlTemplate` (T213 / the media ticket).

**C-26 — keep going** (`core/src/commonTest/kotlin/kotobaya/queue/QueueKeepGoingTest.kt`): the rule-6
KDoc example (normal empty with `moreAvailable`, keep-going `R4 N1 N2 N3`, batch 2); default false is
identical to before; daily/session/per-deck new caps ignored with deck tiers kept; global and per-deck
review caps lifted; siblings and exclusions kept; nothing not yet due; disabled decks; learn-ahead keeps
the normal queue non-empty; reviews before new; add_kanji overshoot; pending entries bounded by the
batch and counted for `moreAvailable`; batch < 1 = 1; `isNew` exactly for NEW items.

## C-26: keep going on a device (Android)

The device builds with `QueueLimits(keepGoing = true)` (leave `keepGoingNewBatch` at its default)
while the user keeps going, on every rebuild, and with `keepGoing = false` otherwise. "Out" =
`items` and `toMaterialize` empty: show Keep going iff `moreAvailable` (say "done for now": it is
also true when only the session cap is reached), else "done" (plus `nextLearningDueAt` if set).
Materialize `toMaterialize` exactly as in a normal build. Keep incrementing `newShownThisSession`
for new cards shown while keeping going. Show the "New" badge from `QueueItem.isNew`.

**Scope (normative).** Keep going is UI state scoped to one visit to the review screen. It is
cleared when the user leaves the review screen, when the study day (the local date of `StudyDay`
on a device; `study_day` in the API) differs from the one it was started in, and on app restart.
The normal Review entry point and the Today counts always use `keepGoing = false` /
`keep_going=false`. Never persist it (no saved state, no preference). A device checks the day on
every rebuild: if `day.startMs` differs from the day keep going started, it drops the flag and
builds normally.

## C-27: pending entries and materialization (ADR-032)

**Which entries a loader passes as `QueueInput.pendingEntries`** (every platform, normative): deck
items `e:<seq>` (`deck_item.deleted_at IS NULL`) of live, **enabled**, non-smart decks, such that
- the dictionary entry `seq` exists locally (`ref.dict_entry`; a device without the dictionary
  entry skips it until it has it);
- no **live** note holds `entry_seq = seq`;
- no **user-deleted** note held it: no note with `entry_seq = seq`, `deleted_at IS NOT NULL` and
  `merged_into_id IS NULL` (merge losers do not count). Deleting the note of a deck word drops it
  for good; it is never re-created by the queue (adding a note by hand still works);
- `word_status.override_status` is not KNOWN, NEVER_FORGET, BLACKLISTED or IGNORED (the builder
  skips those anyway; filtering first keeps the bound below exact).

A loader may pass only the first `2 × budget` such entries per deck in `(position, item_key)`
order, with `budget` = `max(1, keepGoingNewBatch)` when keeping going, else `max(0, min(newPerDay,
newPerSession))`: the builder draws at most `budget` entries in total and skips an entry otherwise
only when another deck already drew the same `entrySeq`, so the result is identical.

**Materializing `toMaterialize`** (every platform, same ops): for each entry, in order, skip it if a
live note now holds the entry; else, if the drawing deck's `add_kanji` is **present** and not `off`
(SCHEMA.md 6.2), one kanji note per `CardGenerator.kanjiToAdd(headword, live kanji literals, mode)`
(`origin` `auto_kanji`, built-in kanji type, `kindsForNewNote(kanji type, null)`); then
`note.created` {built-in vocab type `…0101`, `entry_seq`, `fields` `{}`, `origin` `deck`} and one
`card.created` per `kindsForNewNote(vocab type, deck card_kinds)`. Then rebuild. The server does
this inside `GET /review/queue` as the server device (openapi `getReviewQueue`); a device does it
locally through its op path. A device and the server creating a note for the same entry converge
by SYNC.md 8.1 (the note that held the entry first on the server survives; the other's cards and
their reviews are merged into it).
