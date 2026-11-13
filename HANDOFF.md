# HANDOFF F-14

STATUS: DONE
BRANCH: t/F-14-seaweedfs-blob-put
CHECK: `make check MODULE=server` → green

## Changed (≤ 6 lines)
- server/.../blobs/BlobStore.kt: `S3BlobStore.put` spools the stream to a temp file (deleted in `finally`) and uploads with a known length, so minio-java no longer uses unknown-length streaming with per-part Content-MD5.
- Same file: `copy` now goes get + put instead of server-side CopyObject. A second SeaweedFS 3.80 failure was found: CopyObject returns 500 (also Content-MD5). The upload flow (tmp key to hash key) depends on `copy`.
- New test S3BlobStoreSeaweedFsTest.kt.

## Acceptance → tests
- Small and >16 MiB (multipart) PUT/GET byte-identical against chrislusf/seaweedfs:3.80, plus copy/size/delete → S3BlobStoreSeaweedFsTest.smallAndMultipartRoundTrip
- Hash mismatch still rejected → unchanged module logic, covered by existing BlobsApiTest

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Temp-file spooling doubles disk IO per upload, bounded by the blob size cap. Copy content-type is not preserved on the object (the metadata row holds it).
- The hash-mismatch case is not re-tested against SeaweedFS; it is decided in the module before the store is touched.
