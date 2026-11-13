# HANDOFF T604

STATUS: DONE
BRANCH: t/T604-cli
CHECK: `make check MODULE=cli` → green (30 pytest, mypy --strict, ruff)

## Changed (≤ 6 lines)
- `cli/`: `kotobaya` package on the generated `clients/python` (path dep), commands `health`, `cards find`, `media import`, `jobs list|watch`.
- `cli/uv.lock` added; `cli/tests/` fakes HTTP at `RESTClientObject.request`.
- `Makefile`: `cli` in MODULES, `check-cli` target only.

## Acceptance → tests
- argument parsing for each command → `test_cards_find_parses_query_json_and_limit`, `test_media_import_parses_options`, `test_jobs_watch_requires_id_and_positive_interval`, …
- `--json` output → `test_cards_find_json_prints_raw_page`, `test_jobs_list_json`
- 401 → exit 1, "token missing or revoked" → `test_401_is_exit_1_with_token_message`
- `kotobaya --help` lists commands → `test_help_lists_all_commands`

## New dependencies
- none runtime (pydantic, urllib3 come via kotobaya_client). Dev group pytest, ruff, mypy, same as worker.

## For the reviewer (≤ 3 lines)
- Per orchestrator: `--no-deck` and `--new-per-day N` map to `deck`; `--kind` defaults to `other`; `--title` defaults to file stem. `import_id` (UUIDv7) is always sent.
- `KOTOBAYA_URL` may be an origin; `/api/v1` is appended. Token only via env, no flag.
- `health` is public, so no bearer header is sent there.
