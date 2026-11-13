# SEC-1 — Blob responses: stop stored XSS (from T609 S-01)

- Phase: P6 · Model: opus · Risk: high
- Deps: T609 (MERGED)
- Branch: t/SEC-1-blob-xss
- Owned paths (only edit these): `server/src/main/kotlin/kotobaya/blobs/`, `server/src/main/kotlin/kotobaya/ankiconnect/AnkiMedia.kt`, `server/src/test/kotlin/kotobaya/blobs/`, `server/src/test/kotlin/kotobaya/ankiconnect/`
- Module(s) for CI: server (Gradle: wrap in `flock /tmp/kotobaya-gradle.lock`)

## Read
- docs/SECURITY.md finding S-01 (row and proposed fix SEC-1)
- Contracts: contracts/api/openapi.yaml `getBlob`, `putBlob`; `ankiConnect` media actions (retrieveMediaFile/storeMediaFile)

## Do
- Every blob response (getBlob, and any other path serving blob bytes, incl. AnkiConnect media if it serves bytes over HTTP)
  sends `X-Content-Type-Options: nosniff` and `Content-Security-Policy: sandbox; default-src 'none'`.
- Serve inline only for `audio/*` and raster `image/*` (png, jpeg, gif, webp, avif); everything else (incl. `image/svg+xml`, `text/*`,
  `*html*`, `application/*`) gets `Content-Disposition: attachment`.
- Do not change contract shapes (headers are additive). If you believe the contract must say this, note it in HANDOFF
  "For the reviewer"; do not return CONTRACT_CHANGE_REQUEST for documentation-only wording.
- Ensure the web app and Android review WebView usage (audio/image tags) keep working: inline for audio/raster images.

## Acceptance
- [ ] Tests: SVG/HTML blob → attachment + nosniff + CSP; audio/png → inline + nosniff + CSP.
- [ ] `make check MODULE=server` green.
