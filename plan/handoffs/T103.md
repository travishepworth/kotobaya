# HANDOFF T103

STATUS: DONE
BRANCH: t/T103-dict-package
CHECK: `make check MODULE=worker` → green (29 passed)

## Changed (≤ 6 lines)
- `worker/src/kotobaya_worker/dict/package/`: schema.py (Dict.sq copy), source.py (ref.* → device rows), sqlite.py (build/check/diff/apply), s3.py (stdlib SigV4, bucket create, `blobs/<sha256>`), release.py (zstd, hash, upload, `build_release`). Diff format is in the package docstring.
- Tests in `worker/tests/dict/package/` (Postgres test needs docker, as T101's).
- `worker/pyproject.toml` and `uv.lock`: added dependency.

## Acceptance → tests
- Top-60k under 150 MB → `test_top60k_budget_scaled` (6k synthetic entries x10: raw 1109 B/entry, zst 172 B/entry; synthetic is too repetitive to trust). Real number NOT measured: no real JMdict in fixtures.
- Diff A + apply == fresh B, table by table → `test_diff_a_to_b_equals_fresh_b`, `test_diff_survives_compressed_blobs`
- Read-only, user_version 1, no dangling rows → `test_output_readonly_user_version_no_dangling`, `test_dangling_child_rows_rejected`
- Schema equals Dict.sq → `test_schema_equals_dict_sq`; no silent drops → `test_nothing_silently_dropped`, `test_postgres_to_package_mapping_counts` (per-table counts vs separate ref.* count queries; `build_release` raises on mismatch)

## New dependencies
- zstandard@0.25.0 — zstd compression; Python 3.12 has no stdlib zstd. S3 is hand-rolled over http.client (no boto3).

## For the reviewer (≤ 3 lines)
- ARCHITECT: diff format (`kotobaya-dict-diff` v1: per-table upsert/delete + row_counts, zstd JSON) and blob key `blobs/<sha256-of-compressed>` are not in contracts; the server blob endpoint must agree.
- `dict_release` row of a diff is delete(old id)+upsert(new id). Referenced entries outside top N have `freq_rank` from ref (may be NULL). `kanji`/`freq_list` always complete. Previous package file must be kept/re-downloaded by the caller for diffs.
- pyproject.toml is outside owned paths (needed for the dependency).
