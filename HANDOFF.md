# ADR033-WEB handoff
Branch worktree-agent-ad2600a438dd9cff3 (merged main for the ADR-033 contract first).

**Done** (web/src only)
- Waiting state: empty `items` + `nextLearningDueAt` (any mode) shows "Waiting for the next card / Next card in m:ss" (1 s display tick only); one timer refetches at due+1 s (min 3 s apart, so skew cannot poll); `visibilitychange` catches up when the tab returns; timer cleared on leave/start. Keep going offer remains on that screen when `more_available`. Without the field the old end states apply.
- Counts: removed all `keepGoing` special-casing; header counts and "x of y" use server counts in both modes. Home already used server stats.

**Audit findings (fixed)**
1. Unguarded fetches (start/reload/keepGoingOn) were not covered by the epoch: a response built before an answer was applied could re-show the card once the outbox emptied. Now cards pending at fetch start or answered during it are dropped (`outbox.answeredSince`).
2. A guarded refresh dropped as stale in normal mode left an empty queue and a false "done" screen; retry now applies in all modes.
3. Checked, no hole: `guard` epoch (tap/undo/restore/advance bump it), `refreshWhenIdle` (awaits outbox tail), undo (epoch bump before refresh, queued answers cancelled locally).

make check MODULE=web green; make fmt run. No new deps. No tests added (UI).
