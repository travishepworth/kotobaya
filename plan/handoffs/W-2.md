# HANDOFF W-2

STATUS: DONE
BRANCH: t/W-2-wiring
CHECK: `make check MODULE=server` -> green

## Changed
- Main.kt `composeApp`: one `SyncModule(db, SyncWiring.config(events -> jobs.bus.syncChangedInTx))`; Notes/Decks/Settings modules share `sync.opApply`; all handlers registered. `startup` defaults to `SyncWiring.startup(db)`.
- Startup.kt: `PLACEHOLDER_SCHEDULER_VERSION` removed. `StartupSequence` defaults are now the core's version AND `CoreSchedulerReplay` (a real version can't pair with a no-op replay).
- ScenarioTest: stale `mergeScenariosAreLeftToT202c` deleted (KDoc fixed).
- New `wiring/SyncWiringTest`: real Netty app; readiness + epoch + replay version; REST note -> push review -> card cache -> pull; duplicate note from 2nd device merges (not 503); 401/403/insufficient_scope.

## Outside owned paths (needed)
- `sync/restore/StoryServer.kt` (test helper): pass `schedulerVersion="unset", replay=NoOp` explicitly, since StartupReplayTest relies on the old stale-cache default.

## New dependencies
- none

## For the reviewer
- Pairing codes are in-memory (single replica), unrelated to this ticket.
