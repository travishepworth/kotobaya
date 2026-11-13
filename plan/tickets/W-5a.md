# W-5a — URGENT: wire blob upload + implement dictionary refresh/release endpoints

- Phase: P4 (human-reported, highest priority; human builds/pushes 0.1.1 after merge) · Model: sonnet · Risk: med
- Deps: none (T411a merged; worker dict-refresh job exists since W-1)
- Branch: t/W-5a-blobs-dict-wiring
- Owned paths (only edit these): `server/src/main/kotlin/kotobaya/server/Main.kt` (composeApp: install BlobsModule + new dict handlers only), `server/src/main/kotlin/kotobaya/server/Config.kt` (only if S3 config needs a fix), `server/src/main/kotlin/kotobaya/dict/` (DictApi.kt + new release/refresh code), `server/src/test/kotlin/kotobaya/dict/`, `server/src/test/kotlin/kotobaya/server/` (one wiring test file), `deploy/helm/` ONLY if the server Deployment lacks the S3_* env vars (check first; say in HANDOFF)
- Module(s) for CI: server (+ deploy if touched)

## Context (scout audit 2026-11-13, live cluster test)
- Live jpdb import fails: web import page calls `putBlob` (web/src/routes/import/+page.svelte:67) → 501. BlobsModule
  (server/src/main/kotlin/kotobaya/blobs/BlobsModule.kt:39-47, putBlob/getBlob/queryBlobs) is implemented (T411a) but
  NOT installed in Main.kt composeApp (Main.kt:92-94). Needs S3BlobStore (blobs/BlobStore.kt:32) built from
  S3_ENDPOINT, S3_BUCKET, S3_ACCESS_KEY, S3_SECRET_KEY, optional S3_REGION (Config.kt:23-27).
- `refreshDictionary` and `getDictRelease` were never implemented (only a comment at dict/DictApi.kt:20-22 attributing
  them to T109a, which covered jobs only). dictHandlers registers only search/entry/kanji.

## Read
- Contracts: contracts/api/openapi.yaml `putBlob`, `getBlob`, `queryBlobs`, `getDictRelease`, `refreshDictionary`
  (incl. scopes, dedupe, 202/409 rules), `DictRelease`; contracts/db/DICT_PACKAGE.md §1 (release row, artifacts);
  contracts/db/SCHEMA.md `ref.dict_release`, Jobs (dict-refresh kind + dedupe key).
- Code seams: worker dict-refresh job kind (W-1), kotobaya.jobs.JobRepo (enqueue with dedupe, mirror ImportsModule.importJpdbHistory).

## Do
1. Install BlobsModule in composeApp with an S3BlobStore from config. Fail fast at startup with a clear message if
   S3 config is missing (match how other required config is handled), unless there's an existing dev/in-memory mode.
2. Implement `getDictRelease` (read current `ref.dict_release` → DictRelease incl. artifacts) and `refreshDictionary`
   (enqueue the existing dict-refresh job with its dedupe key; contract status codes) with the contract's auth scopes.
3. Wiring test: through composeApp (like the existing WiringTest), putBlob → getBlob round trip (in-memory or
   Testcontainers S3 per existing test patterns), getDictRelease returns the seeded release, refreshDictionary enqueues
   exactly one job (second call deduped). Assert none of these 5 operations return 501.

## Acceptance
- [ ] Wiring test above green; scope enforcement test for refreshDictionary (admin/settings scope per contract).
- [ ] `make check MODULE=server` green. NOTE: if the full suite OOMs in the test JVM, that is known infra (F-11, in progress) — report targeted results and say so; don't fix it here.

## Out of scope
Media/import/coverage handlers (T411b etc.); remaining W-5 wiring.
