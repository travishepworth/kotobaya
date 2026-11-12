# HANDOFF W-1

STATUS: DONE
BRANCH: t/W-1-wiring
CHECK: `make check MODULE=server` and `MODULE=worker` → green

## Changed (≤ 6 lines)
- server `Main.kt`: `composeApp(...)` = `Auth(db, AuthConfig.fromEnv())` authenticator + `auth.handlers + dictHandlers(db) + jobs.handlers`; `jobs.start(scope)`. Missing AUTH_PASSWORD_HASH exits 2 with a message (no AllowAll fallback, no test flag).
- worker `jobs/dict_refresh.py`: `dict-refresh` handler: refresh → refresh_extra → build_release (both scopes, S3 base) → writes `ref.dict_release.artifacts`. Registered in `build_registry()`. Dockerfile CMD runs `kotobaya_worker.runner`.
- Pinned sources/checksums come from env `DICT_SOURCES_JSON` (format in the module docstring); payload `freq_lists` selects/orders lists. No source change and no `force` → result `release_id: null`. Needs S3_* env on the worker.

## Acceptance → tests
- login ok/401, protected 401, /dict/search, /jobs, /events hello → `wiring/WiringTest`
- registry + sources parsing → `tests/jobs/test_dict_refresh_wiring.py`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- The handler's end-to-end path (network, S3) is untested; only wiring and config parsing are. Helm (T111) must supply DICT_SOURCES_JSON, S3_*, AUTH_* env.
- Release version is `YYYY.MM.DD.<id>`; id = max(id)+1. Packaging runs after load, so `is_current` flips before artifacts exist.
