# HANDOFF JLPT-1a

STATUS: DONE
BRANCH: t/JLPT-1a-worker-lists
CHECK: `make check MODULE=worker` → green (815 passed, mypy, ruff)

## Changed (≤ 6 lines)
- `dict/vocab.py` (CSV parse, §3.2 matching, §3.3 accounting, invariants); `load_release`/`refresh` take `vocab_lists` and write them in the is_current transaction; `LoadResult.vocab_lists`.
- `jobs/dict_refresh.py`: `jlpt` config (default pinned / null / 5 pinned levels), fingerprint + `sources.jlpt` provenance, `vocab_lists` in result, post-load rebuild enqueue (`decks/enqueue.py`).
- `decks/build.py`: `deck-build` handler (plan, unchanged, archive, `deck-publish` child, stale_base re-plan); registered in `runner/__main__.py`.
- `runner/core.py`: exception may carry `job_error` (exact `<code>: <msg>`) and `non_retryable` (deterministic codes fail at once).
- Fixtures `fixtures/dict/jlpt/n5.csv`, `n4.csv` (+ README).

## Acceptance → tests
- every API kind has a handler → `test_every_api_job_kind_has_a_handler`
- mapping counts → `test_mapping_counts_nothing_dropped`
- archive/payload pass validate.py, count = matched → `test_deck_build_publishes_archive_and_payload`
- empty list → `test_empty_list_fails_without_child`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- JLPT is now default-on, so any `DICT_SOURCES_JSON` without `"jlpt"` downloads 5 CSVs; `test_refresh_resume` now sets `"jlpt": null`. Provenance `sources.jlpt` has an extra `sha256` (combined digest) used for resume/new-release detection.
- Test DB template now also applies V3 (tests/dict/conftest.py). Real-network default digests untested (offline).
