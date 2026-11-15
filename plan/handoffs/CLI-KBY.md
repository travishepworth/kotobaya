# HANDOFF CLI-KBY

STATUS: DONE
BRANCH: t/CLI-KBY
CHECK: `make check MODULE=cli` → green (97 tests, mypy, ruff)

## Changed
- Commit 1: `cli/bin/kby` imported verbatim; commit 2 builds on it (diff is reviewable).
- A1 `daemon/server.py`: grade on a revealed-less card is refused with notice "reveal the card before grading", nothing else changes. A2 `session.py`: hidden card publishes only id/kind/front/revealed/previews. A3 reuses `previews`.
- `bin/kby`: `serve`, `do ACTION [--rev]`, help text; `-1..-4 -r ...` and `do` share `send`. `state_name` also understands serve lines (state.json holds them).
- README section + schema description; existing tests now reveal before grading (`grade` helper).

## Acceptance → tests
- A1 → test_review.py::test_grade_on_a_hidden_card_is_refused_and_changes_nothing
- A2 → test_daemon.py::test_empty_day_has_null_card_and_text_fields_are_optional
- B → tests/test_kby.py (fake `kotobaya watch`, temp runtime dir): loading/v1/rev, front omits answers, stale rev + notice, exit 2, offline + error recovery, SIGTERM, `-f` byte-identical to `tests/fixtures/kby.orig` (copy of the original).

## New dependencies
none (kby needs jq, already required)

## For the reviewer
- Stale `--rev` drop writes `$RUNTIME_DIR/kotobaya/kby-notice` and sends `refresh` so the daemon emits a line; serve consumes the file. Without serve, rev is compared by card id only (daemon has no counter).
- `serve` and `-f` share state.json; serve deletes it on exit. `-1..-4` flags are not enabled-checked (daemon refuses instead).
- Reading is treated as answer-side. Not tested against the live daemon/systemd.
