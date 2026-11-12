# HANDOFF C-3 (attempt 2)

STATUS: DONE
BRANCH: t/C-3-dict-package (rebased on main)
CHECK: `contracts/db/check.sh` → green (incl. SQLDelight); `contracts/api/check.sh` → green

## Changed (≤ 6 lines)
- DICT_PACKAGE.md 1.5: diff base MUST be the hash-checked published `full` of `from_release`; never rebuilt from ref.*; otherwise no diff.
- New required diff key `table_digests` + 3.5 digest definition (typed binary row encoding in pk order); verified in 3.4 step 6.
- Referenced set: deck_item SHOULD; post-build misses heal at next release.
- Device: file's `dict_release` row authoritative (sync_state cache refreshed at startup); staging + sidecars deleted before/after/at startup; fsync parent dir after rename.
- V1__init.sql artifacts COMMENT now shows the new shape; SCHEMA.md pointer updated. Attempt-1 content unchanged.

## Acceptance → tests
- check scripts green; referenced from SCHEMA.md and DictRelease → contracts/*/check.sh

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Worker changes required: (1) object key bare `<hash>`, not `blobs/<hash>`; (2) insert `app.blob` (snapshot/build/`application/zstd`) before publishing; (3) manifest entries add `format_version`, `uncompressed_size_bytes`; (4) `apply_diff` checks `to_release`, 11-table set, digests, `foreign_key_check`; (5) diff base = downloaded/hash-checked published full, else skip diff; (6) emit `table_digests`; (7) pass note + word_status (+deck_item) seqs as referenced.
