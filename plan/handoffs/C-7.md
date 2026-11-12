# HANDOFF C-7

STATUS: DONE
BRANCH: t/C-7-settings-keys (rebased on main; attempt 2)
CHECK: `make check MODULE=contracts` → green; `make check MODULE=core` → green

## Changed (≤ 6 lines)
- SCHEMA §6 table: added `scheduler.kanji_before_vocab` (bool, false) and `scheduler.mined_priority` (int4 per 6.1, default 0).
- §6.1: one validity test, used by updateSettings (422 `/values/<key>`) and by every loader (Android, web, server); core never reads settings. An int is a JSON number whose binary64 value is an integer in range (int4 unless narrower), so `1.0` = 1, `1e2` = 100, `-0` = 0. A bool must be exactly `true`/`false`. Anything invalid uses the default. Includes a worked table.
- §6.2: `add_kanji` counts as present only as exactly `"off"`, `"before"` or `"alongside"`; anything else is absent, so the kanji_before_vocab fallback applies. Includes a worked table.
- QueueBuilder.kt KDoc: added the presence rule, the int test for `decklessPriority`, and the rule-4 home new-source requirement for kanji cards (ADR-006). `decklessAddKanji` defaults to OFF.
- db/ and core/ SUMMARY.md: added C-7 notes.

## Acceptance → tests
- Contract/KDoc only → `make check MODULE=contracts`, `MODULE=core`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- I made "mathematical value" precise as the nearest binary64 to the JSON text, matching JSON.parse and toDouble. Otherwise `1.0000000000000001` would split the platforms.
- Valid values are stored as sent; the server does not normalise them, so `1.0` stays `1.0`.
- T212b's "unknown key 422" contradicts the contract: unknown keys are stored.
