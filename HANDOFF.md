# HANDOFF C-31

STATUS: DONE
BRANCH: t/C-31-local-first (attempt 2)
CHECK: `make check MODULE=contracts` → green; `make check MODULE=core` → green

## Changed (≤ 6 lines)
- BLOCKER 1: openapi C8 — a `422` at `/reviewed_at` keeps the outbox entry and resends it without `reviewed_at`; only other `422`s are final.
- MAJOR 2: SHELL.md rule L-10 — 17 writes (cards, presets, note types, confusables, grammar, spans, media) and 6 reads of their tables are `local` (66 local, 11 cached, 15 proxy); cross-check, SUMMARY, 5 parity scenarios.
- Minors: open-unit exception to ADR-052 (a) named (rule 6 "Target", W4, I5); I8 includes `kanjiToMaterialize`; C2 target adds pending NEW answers.
- New ticket: scratchpad `local-first-tickets/LF-A8.md` (new, not an LF-A4 extension).

## Acceptance → tests
- L-10 → `ops_map_local_writes`, `selftest_ops_local_writes`, `ops_map_counts`
- New scenarios → `parity_mandatory_covered` (`cards.single_writes_local`, `presets.`/`note_types.`/`confusables.`/`grammar.writes_local`)
- Earlier bullets → unchanged from attempt 1

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Still `proxy`: `putParseOverride` / `deleteParseOverride` (`device_applies: false`, no device table); `createDeck type=list` forwarded.
- Device cannot check `detection_rules` syntax (documented difference); media writes have no parity scenario (seed cannot create media).
- LF-A8's android paths are proposed, unverified; `contracts/db/check.sh` allow-list line remains.
