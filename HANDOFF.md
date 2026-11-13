# HANDOFF T411a

STATUS: DONE
BRANCH: t/T411a-blobs-api
CHECK: `make check MODULE=server` → green

## Changed (≤ 6 lines)
- server/src/main/kotlin/kotobaya/blobs/BlobStore.kt: `BlobStore` interface, `S3BlobStore` (MinIO client, any S3 endpoint), `InMemoryBlobStore`.
- server/src/main/kotlin/kotobaya/blobs/BlobsModule.kt: `BlobsModule(db, store, maxBlobBytes)` with `putBlob`, `getBlob`, `queryBlobs`.
- server/src/test/kotlin/kotobaya/blobs/BlobsApiTest.kt.

## Acceptance → tests
- Round-trip, mismatch → 422 and no row, idempotent repeat → `uploadThenGetRoundTripsAndRepeatUploadIsIdempotent`, `hashMismatchIs422AndLeavesNoRowAndNoObject`
- Range, 304, 404, scopes → `rangeAndConditionalGetAndUnknownHash`, `aReadOnlyTokenCannotPutAndAnImportTokenCan`
- Also 413 and query → `tooLargeBodyIs413AndNothingIsKept`, `queryPartitionsDistinctHashesSorted`
- Tests use the in-memory fake, not SeaweedFS; `S3BlobStore` is untested.

## New dependencies
- none (io.minio already present)

## For the reviewer (≤ 3 lines)
- PUT streams to `tmp/<uuid>` while hashing, then server-side copies to key=hash and deletes tmp, then inserts the row. Copy is capped at 5 GB by S3; the limit is 2 GB.
- Wiring for W-5: `BlobsModule(db, S3BlobStore(MinioClient..., config.s3Bucket)).handlers`. `BucketInit` already creates the bucket (verified, not duplicated). No 302 pre-signed redirects; GET streams. Multi-range requests get 200 full.
