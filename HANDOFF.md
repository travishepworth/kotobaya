# HANDOFF C-11

STATUS: BLOCKED (host disk full; contract work is done, the full check was not run)
BRANCH: t/C-11-media-pipeline
CHECK: `make check MODULE=contracts` not run as one command. These parts passed: `SKIP_GRADLE=1 contracts/db/check.sh` (snapshot validator, V1+V2, smoke_v2, parity), `contracts/api/check.sh`, `clients/generate.sh` (rc 0, clients regenerated). Not run: the SQLDelight Gradle step, sync/core checks (contracts/sync untouched), and the `git diff` drift check.

## Changed
- contracts/db: new SNAPSHOT.md (zstd JSON Lines archives) and MEDIA_PIPELINE.md (the `media-publish` hand-off, parse_status plus a server reconciler, jpdb corrections); media/ (record schemas, payload schema, examples, validate.py)
- V2__jpdb_vocab.sql and test/smoke_v2.sql (server-only `jpdb_vocab`, `jpdb_vocab_media`); SCHEMA.md §2.9, §4.3, §4.4 (`media-publish` kind); check.sh (tmpfs data dir, V2, validator)
- openapi: listJpdbVocab, put/clearJpdbVocabResolution; `remap_only`; x-emits-ops `media.content_published` on import/reparse; both SUMMARY files; clients regenerated

## Acceptance → tests
- Examples validate → `media/validate.py` self-test (examples plus 30 negative cases)
- Internal-kind row present, JobKind unchanged → SCHEMA.md 4.4, api check

## New dependencies
- none. T411c needs zstd-jni 1.5.6-4 on the server (Android already uses it).

## For the reviewer
- PROVISIONAL: ADR-014 refined (only the server writes media rows); ADR-015 refined (REST corrections, remap jobs clear their own dedupe key under advisory lock 7033011); the API emits `parsing` and a reconciler repairs it; deck_item.added_at = deck.created_at.
- server/build.gradle.kts copies only V1__init.sql. A V2 include is needed (outside my owned paths).

## If BLOCKED
QUESTION: The host root disk is 100% full (Docker). After the human prunes, should I re-run `make check MODULE=contracts` (Gradle step plus drift check) to confirm it is green?
