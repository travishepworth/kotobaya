# HANDOFF T103b

STATUS: DONE
BRANCH: t/T103b-dict-package-c3
CHECK: `make check MODULE=worker` → green (60 tests, Postgres ones ran via docker)

## Changed (≤ 6 lines)
- s3.py: object key = bare hash; added `get_blob`. release.py: manifest gets `format_version`/`uncompressed_size_bytes`; `register_blobs` (app.blob snapshot/build/application/zstd after upload); `fetch_base` (published full, sha-checked, release/scope/user_version-checked); `build_release` derives base from published artifacts (no `previous_package`; `build_artifacts` takes `base`).
- sqlite.py: `table_digest(s)` (3.5), diff emits `table_digests`; `apply_diff` strict per 3.4 (11-table sets in tables/row_counts/table_digests, columns/pk, user_version, from/to release, counts, digests, fk check, rollback).
- source.py: `referenced_seqs` (live notes, word_status, live deck items).

## Acceptance → tests
- wrong base rejected → test_wrong_base_same_release_id_rejected, test_wrong_base_release_rejected, test_fetch_base_*
- digest mismatch same counts → test_digest_mismatch_with_same_counts_rejected
- restart without previous package → test_release_chain_blob_rows_and_published_base (missing/corrupt/unlisted base: no diff; diff applies to published full)
- empty digest value, encoding → test_empty_table_digest_is_spec_value, test_digest_exact_encoding
- blob rows, bare key, manifest fields, referenced set → same chain test, test_manifest_*, test_referenced_set_*

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- `build_release` only emits a diff when `s3` is given (needs the published blob); without S3 it is full-only.
- Default previous release = highest older id; its `artifacts` must already be stored in ref.dict_release by the caller (build_release does not write them).
