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

## Follow-ups outside this ticket's paths

- `assets/card/card.css` lacks most classes of `CardClasses.ALL` (T213 adds them).
- `make check MODULE=contracts` should run `contracts/core/check.sh` (FOLLOWUPS: Makefile wiring).
- SCHEMA.md 6 should list `scheduler.deckless_new_priority` (int, default 0; ADR-006).
- `make check MODULE=core` should run `:core:jsTest` (T107, with `nodejs()` on the js target).
- Card media serving: the web API route and the Android `WebViewAssetLoader` path behind
  `RenderOptions.mediaUrlTemplate` (T213 / the media ticket).
