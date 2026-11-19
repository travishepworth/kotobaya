# HANDOFF CLI-30

STATUS: DONE
BRANCH: t/CLI-30
CHECK: `cd cli && uv run pytest` → green (132 passed); ruff check, ruff format and mypy strict → clean

## Changed (≤ 6 lines)
- `daemon/backend.py`, `daemon/session.py`: `Loop.until_stop_parts` (new `UntilStopParts`) from the wire; state line carries `until_stop_parts` (null from an old server).
- `schema/state.v1.json`: additive `until_stop_parts` (nullable object, four ints).
- `watch.py`: `new`/`learning`/`later`/`review` from `until_stop_parts`, else deprecated `counts` (later 0). Waybar shows `N new` and `K later` only when > 0.
- `bin/kby`: tooltip "N learning later" reads `until_stop_parts.later`, else `later_today`.

## Acceptance → tests
- state carries the four parts and validates → `test_state_carries_the_four_parts_and_validates`
- old server → null parts, fallback → `test_older_server_state_has_null_parts_and_validates`, `test_fields_read_the_parts_and_fall_back_to_counts`, `test_waybar_falls_back_to_counts_on_old_server`
- waybar hides zero new/later → `test_waybar_hides_zero_new_and_later`; kby → `test_tooltip_later_reads_until_stop_parts_then_later_today`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Decided: `learning` template field is now the learning-only part (not learning + later); old-server fallback keeps the legacy meaning.
- Waybar text changed from `new / learning / review` to labelled segments, per the coordinator's web-mirroring decision.
- Backend mapping (`UntilStopParts`) is not unit-tested; covered only by the generated-model shape.
