# HANDOFF BUG-6

STATUS: BLOCKED (owned work done, green; server/src/main change needed in the same image)
BRANCH: t/BUG-6-import-graduation
CHECK: `make check` contracts, core (jvm+js), server → green

## Changed
- SYNC.md 9.5 rule, 12.8 worked example, 10.5, 17; OPS.md; SUMMARY.md; model.py docstring.
- Core: `ReplayReview.importSource` (defaulted); jpdb pass on (re)learning ≥ 1 day after last rated review folds with empty step lists (same FSRS S/D, graduates); `SCHEDULER_VERSION` `fsrs6-2`; `DeviceCardCache` passes `import_source`. FsrsScheduler untouched.
- server/src/test `RefReplica` passes `import_source`.

## Acceptance → tests
- Golden vectors/digest unchanged → existing tests.
- Ticket cases → `ImportGraduationTest`.
- Fuzz → `ImportReplayPropertyTest`, `DeviceConvergenceFuzzTest.replicasConvergeWithImportedHistoryMixedIn`.
- Bump → Review → `ImportGraduationApplyTest`.
- Server replay = device replay → missing (question).

## New dependencies
- none

## For the reviewer
- Re-replay trigger: server on startup of the new image (`StartupSequence`, under lock 7033001, before readiness); Android on first app start after the update (`StartupReplay`, also `DeviceOpApplier.ensure`). No ops, no log changes.
- Mixed window (server 6-2, Android 6-1): caches differ only for imported cards. Safe: caches never travel, the log is shared, startup replay fixes them.
- ADR-021 (proposed): "jpdb import graduation — 2026-11-13 — imported jpdb pass (Hard/Good/Easy) on a (re)learning card ≥ 1 day after the last rated review graduates to Review with FSRS's own S/D (SYNC.md 9.5); native reviews keep py-fsrs semantics; shipped as core scheduler version fsrs6-2 so 10.5 re-replays every replica — jpdb has no learning steps; hard-as-repeat stranded ~1,100 cards — Reverse: drop the rule and bump the version again."

## If BLOCKED
QUESTION: server `CoreCardCache.loadReviews` does not select `r.import_source`. If deployed like this, the server stores `fsrs6-2` but keeps stuck caches, and a later fix needs another bump. Can I have `server/src/main/.../CoreCardCache.kt` (plus Stats/Struggle/Drill `replayReview`) and a server test? Or should this merge only with a follow-up? Patch: select `, r.import_source` (col 11); `importSource = r.get(11, String::class.java)`.
