# HANDOFF SEC-1

STATUS: DONE
BRANCH: t/SEC-1-blob-xss
CHECK: `make check MODULE=server` → green

## Changed (≤ 6 lines)
- `server/.../blobs/BlobsModule.kt`: `getBlob` sets `X-Content-Type-Options: nosniff` and `Content-Security-Policy: sandbox; default-src 'none'` on every response (200/206/304/404/416). The headers are set before any branch.
- `Content-Disposition: inline` only for `audio/*` and `image/{png,jpeg,gif,webp,avif}`, matched case-insensitively and ignoring parameters. Everything else gets `attachment; filename="<hash>"`.
- Tests in `server/src/test/kotlin/kotobaya/blobs/BlobsApiTest.kt`. `AnkiMedia.kt` is unchanged because it serves no bytes over HTTP. Its blobs are served through `getBlob`.

## Acceptance → tests
- SVG/HTML → attachment + nosniff + CSP → `svgAndHtmlAndOtherActiveTypesAreServedAsAttachmentWithNosniffAndCsp`, `ankiConnectSvgMediaIsServedAsAttachment`
- audio/png → inline + nosniff + CSP → `audioAndRasterImagesAreServedInlineWithNosniffAndCsp`
- 206/304 hardened too → `rangeAndNotModifiedResponsesAreHardenedToo`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Stored SVG still keeps `image/svg+xml`. `<img>` ignores Content-Disposition, and SVG loaded through `<img>` cannot run script, so card images keep working.
- The `getBlob` contract could add a note on these headers. I did not touch it (documentation only).
- `image/bmp` is now served as attachment, per the ticket's raster list.
