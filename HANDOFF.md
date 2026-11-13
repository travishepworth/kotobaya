# HANDOFF W-5

STATUS: DONE
BRANCH: t/W-5-wiring
CHECK: `make check MODULE=server|worker|deploy` → all green

## Changed
- server Main.kt/Config.kt: ParseModule, CoverageModule, SentencesModule wired; MediaPublishRunner built (null without blob store) and started in `main`; `PARSER_URL`/`PARSER_TIMEOUT_MS` (absent → 503). `listEntryExamples` registered once (dict/F-17; removed from the Sentences map).
- WiringAuditTest: 9 now-wired ops removed from the 501 allowlist.
- worker: `media-import`, `media-parse`, `jpdb-deck-import` registered; parse service started in `main()`. Dockerfile: ffmpeg, `SUDACHI_DICT` build arg (full default), jpdb cache dir.
- Helm/compose: worker Service :8082, `PARSER_URL` on api, allow-parser NetworkPolicy (api→worker), jpdb-cache PVC + `JPDB_CACHE_DIR`, optional `JPDB_API_KEY` Secret (`worker.jpdbApiKey.enabled`); check.sh asserts.

## Acceptance → tests
- Wiring test → `MediaWiringTest` (runner claims `media-publish`; PARSER_URL optional) + `WiringAuditTest`
- Worker kinds registered → `test_registry_has_all_registered_kinds` (edited tests/jobs/test_dict_refresh_wiring.py, outside owned paths, needed)
- Helm → `deploy/helm/check.sh`

## New dependencies
- none

## For the reviewer
- Both listEntryExamples impls look equivalent; kept dict's. Dictionary is already pinned full in pyproject; core swap is a Docker build arg.
- Parse service stops in `finally`; Dockerfile/compose not built here.
