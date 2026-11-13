# HANDOFF BUG-6

STATUS: DONE
BRANCH: t/BUG-6-import-graduation
CHECK: `make check` contracts, core (jvm+js), server → green

## Changed
- Contracts (SYNC.md 9.5, 12.8, 10.5, 17; OPS.md; model.py), core rule + `SCHEDULER_VERSION` `fsrs6-2`: attempt 1.
- Server: `CoreCardCache` and the replay mappings in StatsQueries/StruggleModule/DrillSources select `r.import_source` → `ReplayReview.importSource`.
- `server/src/test/.../merge/ImportReplayServerTest`.

## Acceptance → tests
- Goldens, ticket cases, fuzz, device bump → core tests.
- Server replay = device replay, live Again/Good/Hard/Hard stays learning; bump fixes stuck server caches → `ImportReplayServerTest`.

## New dependencies
- none

## For the reviewer
- Deploy re-replay: the new image's `StartupSequence.checkSchedulerVersion` sees stored `core_scheduler_version` `fsrs6-1` ≠ `fsrs6-2`, runs `CoreSchedulerReplay.replayAll` (every live card) under lock 7033001 in one transaction, upserts the version; `/readyz` fails until committed. Android: `StartupReplay` on first start after update (and `DeviceOpApplier.ensure`). No ops minted.
- Mixed window: only imported-card caches differ; caches never travel, so safe.
- BUG-1 rebase: StatsQueries diff is the select + one mapping line.
- ADR-021 (proposed): "jpdb import graduation — 2026-11-13 — an imported jpdb pass on a (re)learning card ≥ 1 day after the last rated review graduates to Review with FSRS's own S/D (SYNC.md 9.5); native reviews unchanged; shipped as core scheduler fsrs6-2 so 10.5 re-replays every replica — jpdb has no learning steps; ~1,100 cards stranded — Reverse: drop the rule, bump again."
