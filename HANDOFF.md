# HANDOFF CLI-30

STATUS: BLOCKED
BRANCH: t/CLI-30
CHECK: `cd cli && uv run pytest` → green (124 passed, no code changed)

## Changed (≤ 6 lines)
- HANDOFF.md only. No code change yet.

## Acceptance → tests
- cli tests green → `uv run pytest` (124 passed on the unchanged branch)

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- The CLI does show loop phase and loop numbers (`kby` text/tooltip, `daemon/session.py` `_loop_fields`), so the ticket's condition holds.
- `kby` tooltip "N learning later" already equals `until_stop_parts.later` (= `later_today.count`), so the display would not change.
- `watch` / waybar / `{new} {learning} {review}` read the deprecated `counts`; switching them changes visible numbers.

## If BLOCKED
QUESTION: Should `watch`, waybar and the `{new} {learning} {review}` template move off the deprecated `counts` onto `until_stop_parts`? If yes, which mapping: (a) learning = parts.learning + parts.later (legacy "learning includes later") and new = parts.new, review = parts.review; or (b) learning = parts.learning, with `later` added as a new template field. Options for the rest: (1) also pass `until_stop_parts` through the daemon state line and `state.v1.json` (additive), with `kby` reading `.until_stop_parts.later // .later_today` (no visible change); or (2) no-op, leave `counts` as is and say so. Recommendation: (1) plus (2) for the watch counts until the mapping is decided.
