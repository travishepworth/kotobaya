# HANDOFF F-5

STATUS: DONE
BRANCH: t/F-5-server-auto-status
CHECK: `make check MODULE=server` → green

## Changed (≤ 6 lines)
- server/.../sync/CoreAutoStatusHook.kt: real AutoStatusHook using core's DefaultWordStatusRules (SQL inputs, same row policy as device AutoStatus.rederive).
- server/.../sync/restore/SyncWiring.kt: binds it in the production SyncConfig.
- server/.../sync/OpApplier.kt: touchAfter() re-derives entries after review.logged/undone, card.preset_set, note.merged, fsrs_params.* (previously only note/card create/delete/merge did).
- No core change (AutoStatus stays internal; only public DefaultWordStatusRules is reused).

## Acceptance → tests
- server vs core device applier give identical displayed status → AutoStatusHookTest (new, learning, known, overridden, known→learning)
- make check green → done

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- OpApplier edit was needed: without it review ops never reached the hook. fsrs_params ops re-derive every entry (mirrors device allEntries).
- Default `SyncConfig()` still has NoOp; only SyncWiring.config binds the real hook. updated_at uses DB now().
