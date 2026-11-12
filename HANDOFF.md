# HANDOFF C-8

STATUS: DONE
BRANCH: t/C-8-sync-gaps
CHECK: `make check MODULE=contracts` → green; `core` → green; `server` → green (unchanged code; new scenarios not yet run by it)

## Changed (≤ 6 lines)
- SYNC.md: new 9.4 (refused cache); 6.2/6.3 (backups disabled, `durable_seq` persisted); 10.4 "window row" rule, new list `D`; 13/16/17.
- Core contract KDoc: `Reserved.REPLAY_REFUSED_ID` (nil UUID); `ReplayEngine.decide` step 1b; `QueueBuilder` rule 4 (add_kanji counts against its own home source).
- SCHEMA.md: 6.1 settings stored canonical (`1.0`→`1`); refused-cache and `durable_seq` writer notes.
- `examples/remint-scenarios/` (3 files); scenario schema adds `after_seq` and `creations_from`/`creations_to`/`discarded`; model/validate updated, plus a backups-toggle story.

## Follow-ups to ticket (breaking for T202c; no data impact, no migration)
- Server: `CoreCardCache.guarded` writes `CardCache(NEW, REPLAY_REFUSED_ID, params.weightsVersion)`. With backups disabled, raise `durable_seq` by `greatest()` at startup (6.3 step 2b) and in every tx that inserts `change`. Run the remint-scenarios (honour `after_seq`). Drop stale `mergeScenariosAreLeftToT202c`.
- Core: `DefaultReplayEngine.decide` step 1b. `DefaultQueueBuilder`: charge add_kanji cards to their home source, and skip one whose home deck is at its limit.
- Device (Re-mint): compute list `D` and discard it; SHOULD show the discard count before confirming.
- T112 restore tool: warn when live `durable_seq` > backup `S`.

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Re-mint scenarios live outside `examples/scenarios/` so the server's file-count tests stay green.
- `e:<seq>` items and word statuses of window words are discarded, not re-minted.
