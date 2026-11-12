# HANDOFF T110

STATUS: BLOCKED
BRANCH: t/T110-clients
CHECK: `make check MODULE=contracts` → red (not wired; clients not generated)

## Changed (≤ 6 lines)
- No changes under `clients/` or `Makefile` (blocked before wiring).
- Verified on this tree: `contracts/{db,sync,api,core}/check.sh` all exit 0.
- Trial generation (openapi-generator 7.27.0 via `@openapitools/openapi-generator-cli` 2.42.2, spec bundled with `@redocly/cli` 2.77.0): TypeScript `typescript-fetch` generates and typechecks under `tsc --strict`.

## Acceptance → tests
- Four check scripts pass individually → not yet wired into Makefile.
- Clients compile → TypeScript ok; Kotlin and Python fail (see below).

## New dependencies
- `@openapitools/openapi-generator-cli@2.42.2` with generator jar 7.27.0 (not added yet; pending the decision below).

## For the reviewer (≤ 3 lines)
- Both failing generators choke on OpenAPI 3.1 shapes the frozen contract uses.

## If BLOCKED
QUESTION: How should generator failures on the frozen spec be resolved (contracts/ is read-only)?
1. Kotlin (`jvm-ktor` and `jvm-okhttp4`, generator 7.21.0 and 7.27.0): models with `additionalProperties: true` plus `properties` (SentenceToken, AnkiConnectRequest, DeckSettings) emit `: kotlin.collections.HashMap<String, kotlin.Any>()()`, which does not compile.
2. Python (`-g python`, 7.27.0): throws "Codegen Property not yet supported in getPydanticType" on an inline any-of with `{}` (PushResult_error, `any_of_1`).
Options: (a) post-generation patch step in clients/generate.sh; (b) a different generator per language; (c) drop Kotlin and Python from T110 and ship TypeScript only; (d) CONTRACT_CHANGE_REQUEST to simplify those schemas.
