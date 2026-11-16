# LOOP-CLI — CLI daemon + widget on `loop`

- Phase: P10 · Model: sonnet · Risk: low-med
- Deps: LOOP-C (regenerated Python client); LOOP-SRV for the live check
- Branch: t/LOOP-CLI-review-loop
- Owned paths (only edit these): cli/src/kotobaya_cli/daemon/**, cli/src/kotobaya_cli/{watch.py,commands.py} (display only), cli/bin/kby, cli/schema/state.v1.json (additive), cli/tests/**, cli/README.md
- Module(s) for CI: cli
- Gradle-heavy: no

## Read
- Design: docs/REVIEW-LOOP.md §4, §5, §8
- Contracts: openapi `getReviewQueue`, `ReviewLoop`
- Decisions: ADR-037

## Do
1. `backend.py` / `session.py`: `QueueSnapshot` carries `loop` (typed dataclass mirroring the schema). Missing `loop` → status `server_too_old` (one clear line; no fallback derivation).
2. Daemon state (`state.v1.json`, additive): `phase`, `ready`, `later_today`, `next_due_at`, `backlog`, `backlog_beyond_limit`, `reviews_today`, `reviews_limit`, `new_today`, `new_goal`. `counts`, `more_available`, `can_keep_going` stay (deprecated; `can_keep_going` = phase in goal_reached/done_for_today).
3. Keep going (C2): persist the study day in the daemon's state dir; restore on start; drop on `study_day` mismatch. `keep_going` / `stop_keep_going` commands unchanged.
4. C1: refetch before serving the next card once `now >= T`; C4: one timer at `next_due_at` only in phase `waiting`.
5. Widget (`kby`): number = ready now (C3); tooltip/alt lines: new `k/goal`, backlog when beyond limit, "N learning later · next in M min"; class per phase.

## Acceptance (each bullet → at least one named test)
- [ ] `test_daemon.py::test_status_per_phase` (five phases from mocked snapshots)
- [ ] `test_daemon.py::test_keep_going_day_survives_restart` and `::test_keep_going_dropped_on_new_day`
- [ ] `test_daemon.py::test_due_card_served_before_cached_new` (C1)
- [ ] `test_kby.py::test_widget_ready_not_later` (9 ready + 26 later → 9) and `::test_server_too_old`
- [ ] `make check MODULE=cli` green

## Out of scope
Server changes; new socket commands.

## Notes from previous attempts
