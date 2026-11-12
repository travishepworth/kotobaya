# HANDOFF C-3

STATUS: DONE
BRANCH: t/C-3-dict-package
CHECK: `contracts/db/check.sh` → green (incl. SQLDelight); `contracts/api/check.sh` → green

## Changed (≤ 6 lines)
- New `contracts/db/DICT_PACKAGE.md`: artifacts, full package (zstd SQLite, user_version 1, scope contents, integrity), diff `kotobaya-dict-diff` v1 (envelope, pk per table, order, apply/verify), hashes/object key/app.blob row, device update algorithm, versioning.
- `contracts/db/SCHEMA.md`: file table + 5.4 pointer (DICT_PACKAGE.md authoritative; FK-off exception for the diff-apply connection only).
- `openapi.yaml`: DictRelease and `/dict/release` descriptions point to it; artifact items gain required `format_version` and `uncompressed_size_bytes` (additive); example updated.

## Acceptance → tests
- check scripts green; referenced from SCHEMA.md and DictRelease → contracts/*/check.sh

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Worker changes now required (T103 mismatches): (1) object key = bare `<hash>`, not `blobs/<hash>` (app.blob.hash "Also the object key", 302 example); (2) insert `app.blob` row (kind `snapshot`, origin `build`, `application/zstd`) before publishing artifacts; (3) emit `format_version` + `uncompressed_size_bytes` in manifest entries; (4) `apply_diff` must also check `to_release`, the 11-table key set and `foreign_key_check` before COMMIT.
- top60k "referenced" defined as at least live note + word_status seqs; the job must pass these.
