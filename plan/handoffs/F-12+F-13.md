# HANDOFF F-12+F-13

STATUS: DONE
BRANCH: t/F-12+F-13-dict-refresh
CHECK: `make check MODULE=worker` → green

## Changed
- `dict/fetch.py`: `SourceSpec.sha256` optional (ADR-020); new `fetch_source` records digest + Last-Modified/ETag (fetcher may return headers); unpinned always re-downloads; pin mismatch raises `ChecksumMismatch`.
- `dict/pipeline.py`, `dict/extra/pipeline.py`: provenance built from fetched specs.
- `jobs/dict_refresh.py`: EDRDG `sha256` in `DICT_SOURCES_JSON` optional; sources resolved first, then the current release is resumed (missing extras loaded, package rebuilt if any scope lacks a full artifact) instead of returning no-op. Completed run is a no-op. Payload unchanged (`freq_lists`, `force`).

## Acceptance → tests
- F-12 resume publishes once; completed re-run no-op → `tests/dict/test_refresh_resume.py`
- F-13 unpinned changed file records digest; pin mismatch fails → `tests/dict/test_dict_units.py`

## New dependencies
- none

## For the reviewer
- Resume applies only while the current release's core digests equal the freshly fetched ones; after a day gap (EDRDG changed) a new release is made. Non-current partial releases are not resumed.
- Kanjium/Tatoeba/frequency keep mandatory pins. deploy/ not touched; if Helm sets EDRDG sha256 in DICT_SOURCES_JSON, drop them (follow-up).
