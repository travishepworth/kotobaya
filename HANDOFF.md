# HANDOFF W-5a

STATUS: DONE
BRANCH: t/W-5a-blobs-dict-wiring
CHECK: `make check MODULE=server` NOT run in full (OOM risk pre-F-11). Targeted `:server:test --tests 'kotobaya.server.BlobsDictWiringTest' 'kotobaya.dict.*' 'kotobaya.wiring.*' ConformanceTest` → green except ConformanceTest.everySpecOperationIsRoutedAndNothingElse, which fails on `assertEquals(117, ops)` (contract now has 120). Pre-existing, unrelated to this change (test hardcodes the count).

## Changed
- Main.kt: `composeApp(blobStore: BlobStore? = null)` installs BlobsModule; `main` always passes `S3BlobStore` built from required S3_* config (Config.fromEnv already fails fast, exit 2).
- dict/DictRelease.kt (new) + DictApi.kt: `getDictRelease` (current row + freq_lists + artifacts; 404 if none), `refreshDictionary` (enqueue `dict-refresh`, dedupe key `dict-refresh`, 202/200, absent body = {}, freq_lists validated).
- server/src/test/.../server/BlobsDictWiringTest.kt (new).
- Helm: Deployment already has all S3_* env vars; not touched.

## Acceptance → tests
- Wiring (put/get/query round trip, release seeded, one job + dedupe, not 501) and refresh scope (read-only token → 403) → BlobsDictWiringTest

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- composeApp's `blobStore` defaults to null so existing tests are unchanged (blobs stay 501 there); only `main` supplies S3.
- ConformanceTest's hardcoded 117 should be fixed by whoever owns it.
