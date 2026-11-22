# HANDOFF CLI-34

STATUS: DONE
BRANCH: t/CLI-34-until-stop (rebased onto main f07e240d)
CHECK: `make check MODULE=cli` → green

## Changed
- daemon: `Loop.until_stop` (backend.py from `wire.until_stop`), state field `until_stop`; `ready` kept.
- kby: "語 N" and `{counts_text}` = until_stop, fallback ready, then counts; `until_stop` in flat JSON and `kby fields`; help text and default widget comment fixed.
- watch.py: waybar drops the "N later" segment (tooltip keeps later).
- schema state.v1.json, README updated.

## Acceptance → tests
- ready 2, later 13 → 2; ready 2, new 10 → 12 → test_kby.py::test_text_number_is_until_stop (parametrized)
- waybar no later chip → test_until_stop_parts.py::test_waybar_hides_zero_new_and_never_shows_later

## New dependencies
none

## For the reviewer
- Older servers (no until_stop) fall back to `ready`, as before.
