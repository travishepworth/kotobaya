# HANDOFF C-11

STATUS: DONE
BRANCH: t/C-11-media-pipeline
CHECK: `make check MODULE=contracts` green (db incl. V2 + smoke_v2 + SQLDelight, sync, api, core, clients, no drift).

## Changed
- contracts/db: new SNAPSHOT.md (zstd JSON Lines archives) and MEDIA_PIPELINE.md (the `media-publish` hand-off, parse_status plus a server reconciler, jpdb corrections); media/ (record schemas, payload schema, examples, validate.py)
- V2__jpdb_vocab.sql and test/smoke_v2.sql (server-only `jpdb_vocab`, `jpdb_vocab_media`); SCHEMA.md §2.9, §4.3, §4.4 (`media-publish` kind); check.sh (tmpfs data dir, V2, validator)
- Attempt 2: normative lock order 7033001 before 7033011 (MEDIA_PIPELINE 3.4); 5.4 handlers take both first (fixes deadlock with publish step 9); child FOR UPDATE + `locked_by` guard; `parsing` op fields read under 7033001
- openapi: listJpdbVocab, put/clearJpdbVocabResolution; `remap_only`; x-emits-ops `media.content_published` on import/reparse; both SUMMARY files; clients regenerated

## Acceptance → tests
- Examples validate → `media/validate.py` self-test (examples plus 30 negative cases)
- Internal-kind row present, JobKind unchanged → SCHEMA.md 4.4, api check

## New dependencies
- none. T411c needs zstd-jni 1.5.6-4 on the server (Android already uses it).

## For the reviewer
- PROVISIONAL: ADR-014 refined (only the server writes media rows); ADR-015 refined (REST corrections, remap jobs clear their own dedupe key under advisory lock 7033011); the API emits `parsing` and a reconciler repairs it; deck_item.added_at = deck.created_at.
- server/build.gradle.kts copies only V1__init.sql. A V2 include is needed (outside my owned paths).
