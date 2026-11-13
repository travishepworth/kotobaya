# HANDOFF T403c+T408a

STATUS: DONE
BRANCH: t/T403c+T408a-parse-sentences-api
CHECK: `make check MODULE=server` → green (exit=0), rebased on main

## Changed (≤ 6 lines)
- server/.../parse/: ParseModule, ParseOverrideApi, ParserClient (HTTP client + interface) for parseText and override list/put/delete (ops via OpApply).
- server/.../sentences/SentencesModule.kt: listMediaSentences, listSentences, getSentence, listEntryExamples (query-time i+1/i+0).
- Tests: parse/ParseApiTest (4), sentences/SentencesApiTest (8). Recovered the crashed writer's files as sound.

## Acceptance → tests
- All bullets → ParseApiTest, SentencesApiTest (all green).

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Modules are not wired (W-5), so WiringAuditTest allowlist unchanged (all 4 parse + 3 sentence ops remain listed).
- F-17 already registered listEntryExamples in dict/; SentencesModule also provides it per ticket. W-5 should merge only one (drop one).
