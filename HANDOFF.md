# HANDOFF T404a

STATUS: DONE
BRANCH: t/T404a-media-publish-kit
CHECK: `make check MODULE=worker` → green (430 passed, mypy, ruff)

## Changed (≤ 6 lines)
- `worker/src/kotobaya_worker/media/publish/`: `snapshot.py` (writers/readers, deterministic bytes, `media_deck_items`), `ids.py` (`assign_ids`, read-only `PgBaseReader` in REPEATABLE READ), `blobs.py` (`upload_snapshot`, S3/Pg adapters), `handoff.py` (`publish`, `publish_replanning`, `PgPublishStore`, `JobResultMedia`).
- Child protocol copied (not wrapped) into `handoff.py`: jpdb's `ChildStore`/`adopt_or_create_child` hard-code kind and the `plan` payload. Reused `uuid7`, `ChildRow`, `Context`.
- Added empty `worker/src/kotobaya_worker/media/__init__.py` (needed to make the package).

## Acceptance → tests (`worker/tests/media/publish/test_publish_kit.py`)
- Round-trip/schema/hash → `test_snapshot_round_trip_validates_and_is_deterministic` (uses contracts `validate.py`)
- Id stability → `test_id_stability_*` (three tests)
- Retry/idempotent upload → `test_blob_upload_is_idempotent…`, `test_publish_retry_adopts…`, `test_pg_base_reader_blobs_and_retry_against_postgres`, plus `test_stale_base_replans…`

## New dependencies
- `jsonschema` (dev only): `contracts/db/media/validate.py` imports it. `worker/pyproject.toml` and `uv.lock` changed.

## For the reviewer (≤ 3 lines)
- A new media's ids are minted per plan, so re-planning on retry gives different bytes/hash (an orphan `app.blob` row); an adopted child keeps its earlier payload. Same-request retries upload nothing new.
- `StaleBase` is raised by `publish`; `publish_replanning` allows 4 publishes (3 re-plans), my reading of "3 publishes... 4th fails".
