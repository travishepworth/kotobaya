# HANDOFF T313

STATUS: DONE
BRANCH: t/T313-offline-chaos
CHECK: `make check MODULE=acceptance` → green (Postgres, 2.5 min; `-Pserver=memory` also green)

## Changed (≤ 6 lines)
- `acceptance/offline/`: SimDevice (real core stack, kills, faulty transport, own clock), PgSimServer (real `:server` SyncModule on Testcontainers Postgres, backups/restores by DB copy), MemSimServer (no-Docker fallback), Sim (actions, ledger, oracle), Chaos (seeded plans, shrinking, report).
- Registration lines (flag): `acceptance/build.gradle.kts`, `include(":acceptance")` in `settings.gradle.kts`, `Makefile` MODULES + `check-acceptance`. CLAUDE.md module table needs a human line for `acceptance/`.

## Acceptance → tests
- 100 runs, zero lost reviews → `OfflineWeekChaosTest` (`-Pseeds=`; report `acceptance/build/reports/offline-chaos/report.txt`; 216 skew anomalies/100 seeds)
- SYNC 12.1–12.3, 12.5–12.7 → `WorkedExamplesTest`; 12.4 → `ScenarioFilesTest` (all 16 files)
- Named → `NamedChaosTest` + `example12_2_editVersusDelete`, `example12_7_…` (clock ahead), `example12_6_…`/`clockBehindWeek_…`

## New dependencies
- none (server's jOOQ/Jackson/Hikari/pg/Testcontainers versions on the test classpath)

## For the reviewer (≤ 3 lines)
- Bugs found: none. Replicas are compared after pending lazy replays: a card created after `fsrs_params.published` keeps `weights_version` 0 (OPS.md), unlike a replica that replayed it.
- `NoteCreator` is `:android`-only; adds emulate its op shape.
