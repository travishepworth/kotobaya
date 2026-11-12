# HANDOFF T006

STATUS: BLOCKED
BRANCH: t/T006-core-interfaces
CHECK: `make check MODULE=core` → green; `make check MODULE=web` → green; android compiles; `contracts/core/check.sh` → green

## Changed (≤ 6 lines)
- core/src/commonMain/kotlin/kotobaya/contracts/: HLC/remint, canonical JSON + OpCodec/OpType, Scheduler, ReplayEngine + ReviewLogRules, QueueBuilder + WordStatusRules, CardGenerator, HotScorer, Search AST/parser, CardRenderer, CoreJs facade
- contracts/core/: README.md (map + required tests), search.ebnf, search-examples.tsv (57), check.sh

## Acceptance → tests
- jvm/android/js compile → make check core/web, :core:compileDebugKotlinAndroid
- KDoc on every public member → check_kotlin.py
- EBNF parses spec examples → check_search.py

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- No collection-aware load balancing (deterministic fuzz seeded by review id); set_due restarts elapsed time.
- Unknown search keys are errors; field search is `field:key:value`.

## If BLOCKED
QUESTION: The Write tool refused contracts/core/SUMMARY.md ("Subagents should return findings as text, not write report files"). Its full text is in my reply. Should the orchestrator commit it, or allow me to write it?
