# HANDOFF WRK-29

STATUS: DONE
BRANCH: t/WRK-29
CHECK: `make check MODULE=worker` green

## Changed
- `worker/src/kotobaya_worker/tts/job.py`: cache is now `app.tts_audio` keyed (voice, text) (replaces job-history lookup); blob row, then upsert `tts_audio` (created_at = now()); voice `^[0-9]{1,9}$` and text 1-500 validated before any VOICEVOX call.
- `worker/tests/dict/conftest.py`: V4 added to migration list.
- `worker/tests/tts/test_tts_job.py`: run-twice idempotency, voice-as-key, validation tests.

## New dependencies
- none

## For the reviewer
- Cache hit requires the `app.blob` row; a stale tts_audio row is overwritten by resynthesis.
- `dedupe_key` kept (server still enqueues with it).
