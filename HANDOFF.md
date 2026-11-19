# HANDOFF C-28f

STATUS: DONE
BRANCH: t/C-28f-contracts
CHECK: `make check MODULE=contracts` → green; `:core:compileKotlinJvm` green

## Changed (≤ 6 lines)
- (a) Kana word = dictionary build: `dict_entry.headword` = first reading when every kanji form is `rK`/`sK` (SCHEMA.md 4.5 "Headword", DICT_PACKAGE 2.3, openapi descriptions). Single source: lists, API, renderer, loader already read `headword`; forms/`entry_kanji` unchanged, so 新嘉坡 stays searchable.
- (b) `KanjiRequirement.kt`: tagged → `isJoyo(grade)` (new companion fn, impl still compiles); KDoc examples, SCHEMA 6.5, README "ADR-046", REVIEW-LOOP.
- New parity `queue-kanji-rare-forms` (2 mandatory tags); clients regenerated (doc comments).

## Acceptance → tests
- シンガポール/かるた → KDoc + SCHEMA 6.5 examples; parity uses fixture stand-ins イタリア 1021360, おにぎり 1001620 (hand-evaluated).

## New dependencies
- none

## CORE-28d
- core: `DefaultKanjiRequirement` one branch + re-record `KanjiRequirementTest`.
- worker: headword rule; force a new dict release. **Dictionary rebuild + device package refresh required.**
- server/android/web: no logic; fixture-dictionary loaders apply the rule (137 fixture entries); record new golden.

## For the reviewer (≤ 3 lines)
- Item 3: unrequired `auto_kanji` notes (嘉) **stay**; auto-delete rejected (`note.deleted` is permanent = never re-minted; stale replicas would delete required notes). Delete 嘉 by hand.
- いる/する/それ/あれ become kana words (居る `rK` etc.); `oK`-mixed entries (軈て) do not.
