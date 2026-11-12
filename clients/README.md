# clients/ - generated API clients

Generated from `contracts/api/openapi.yaml`. **Never hand-edit** anything under `kotlin/`,
`typescript/` or `python/` (each has a `GENERATED.txt`). One command regenerates and compile-checks all three:

    clients/generate.sh            # or: make check MODULE=contracts (also runs the four contract checks and the drift check)

| Dir | Generator | Use |
|---|---|---|
| `kotlin/` | `kotlin`, library `jvm-ktor`, gson, `enumPropertyNaming=UPPERCASE`, package `app.kotobaya.client` | android |
| `typescript/` | `typescript-fetch`, package `@kotobaya/api-client` | web |
| `python/` | `python` (pydantic v2, urllib3), package `kotobaya_client` | CLI, worker |

Pinned tools: openapi-generator 7.27.0 (`openapitools.json`) run through `@openapitools/openapi-generator-cli@2.42.2`;
spec bundled by `@redocly/cli@2.77.0` (same as `contracts/api/check.sh`); `typescript@5.9.3` for `tsc`.
Needs node/npx, java 17+, gradle, uv, python3. Compile checks (run in a scratch copy): `tsc --strict --noEmit`,
`python -m compileall` plus an import of every module, `gradle compileKotlin`.

## Spec normalisation (`normalize_spec.py`)

The generators mishandle some OpenAPI 3.1 constructs the frozen contract uses. `contracts/` is not touched; the
bundled spec is rewritten in a temp copy before generation. Four rules; each must match at least once or the script
fails, so a stale workaround is never silent:

1. String `default` on parameter schemas is dropped (Kotlin emits `Sort.MinusCreated_at` references to a missing enum).
   Defaults stay documented in the contract; servers apply them.
2. `allOf` entries using `if/then/else` are dropped (validation-only; the Python generator crashes on them).
3. `properties: {x: false}` boolean schemas are dropped (same crash; PushResult).
4. String `const: x` becomes `enum: [x]` (TypeScript emits undefined `FromJSON`/`ToJSON` calls for a nullable const).

Generator options also avoid bugs: Kotlin `kotlinx_serialization` fails on `Map<String, Any>`, and with it the
`additionalProperties: true` + `properties` models emit a broken supertype (`HashMap<..>()()`), so Kotlin uses gson;
`enumPropertyNaming=UPPERCASE` avoids an enum value `name` clashing with `Enum.name`.

Known gap: `mypy` on the Python client reports one generator-side error (`note_type_updated_schema_set.py`), so it is not gated.
