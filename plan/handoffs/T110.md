# HANDOFF T110 (attempt 2)

STATUS: DONE
BRANCH: t/T110-clients
CHECK: `make check MODULE=contracts` green (4 contract scripts, regeneration, drift check, compile of all 3 clients)

## Changed
- `clients/{kotlin,typescript,python}` generated; `clients/generate.sh` (bundle, normalise, generate, compile), `normalize_spec.py`, `openapitools.json`, `README.md`.
- Makefile `check-contracts`: db, sync, api, core checks, `generate.sh`, `git diff --exit-code -- clients/` plus untracked-file check.

## Acceptance -> tests
- Drift: renamed an operationId, regenerated, `git diff` on clients/ non-empty; reverted.
- Compile: `tsc --strict --noEmit`; Python compileall + import of all modules; Kotlin `gradle compileKotlin`.
- Generation is deterministic (second run: no diff).

## New dependencies (pinned)
- openapi-generator 7.27.0 via `@openapitools/openapi-generator-cli@2.42.2`; `@redocly/cli@2.77.0`; `typescript@5.9.3` (npx only). Needs java, gradle, uv.
- Kotlin: `kotlin` generator, jvm-ktor + gson (kotlinx_serialization crashes on `Map<String, Any>`; `enumPropertyNaming=UPPERCASE` for enum value `name`). Python: `-g python` (pydantic v2) works after normalisation; `openapi-python-client` rejects boolean schemas.
- Patches (spec-level, in temp copy only, each rule must match or the script fails): drop string param defaults; drop `if/then/else` allOf; drop `false` property schemas; string `const` -> `enum`. Documented in clients/README.md.

## For the reviewer
- Python mypy has 1 generator-side error, so only compileall + import are gated. Defaults for string query params are absent in clients.
- Contract check scripts need docker (postgres:17) as before.
