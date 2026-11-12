# HANDOFF T006 (attempt 2)

STATUS: DONE
BRANCH: t/T006-core-interfaces
CHECK: `contracts/core/check.sh` green (67 examples, 44 round trips); `make check MODULE=core` green; `make check MODULE=web` green; android + js compile

## Changed (≤ 6 lines)
- fsrs: new `PortableMath` (fdlibm 5.3 port, kotlin.math transcendentals banned); `Scheduler` bit-identity rules, `roundHalfEven`, py-fsrs weight bounds in `paramsOf`, C-1b manual rows, null-offset deviation
- replay: cross-platform invariant, C-1a/C-1b/C-1c; queue: deckless source (ADR-006, `decklessPriority`, tier round-robin), disabled-only cards not introduced, in-build sibling burying
- search: exact `format` rules (`Not(And)` → `-(a b)`); `check_search.py` reference formatter checks the round trip; 10 new examples
- sync: sorted canonical member order, `sizeOf`/`rawSizeBytes` (C-1e), ≥ 2^53 only for integer-typed properties, C-1d; Hlc example fixed
- render: `RenderOptions.mediaUrlTemplate` (+ `JsRenderInput`); README tests incl. jvm/js bit identity

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Equal-priority decks now also take turns (round-robin), from ADR-006's "interleaved".
- Setting `scheduler.deckless_new_priority` needs a row in SCHEMA.md 6 (follow-up).

## SUMMARY.md
The Write tool refused `contracts/core/SUMMARY.md` again; the updated text is in my reply. Please commit it.
