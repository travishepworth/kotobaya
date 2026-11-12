# HANDOFF T202a

STATUS: DONE
BRANCH: t/T202a-sync-core-r
CHECK: `make check MODULE=core` → green (jvmTest + jsTest)

## Changed (≤ 6 lines)
- `core/.../sync/`: `DefaultHlcRules`, `DefaultRemintRules`, `DefaultCanonicalJson` (+ pure-Kotlin JS `Number::toString`), `DefaultOpCodec`
- `SyncSchemas`: JSON Schema 2020-12 subset validator; schemas embedded in generated `SyncSchemaTexts.kt`
- `PushBodyParser`: duplicate-tolerant push-body parse, per-op `DuplicateKey` (SYNC 5.2, C-2)
- Tests in commonTest (run on jvm and js); examples embedded in generated `SyncExamples.kt`

## Acceptance → tests
- examples ops/invalid → `OpCodecTest.everyExampleOpDecodesKnownAndReencodesCanonically`, `everyInvalidExampleIsRejectedWithTheExpectedReason`
- HLC → `HlcTest.uniquenessUnderSkewProperty`, `guardBoundaries`, `mintRefusesToOverflowTheWallField`, `scenarioClockBehindRemint`, `remintShiftEdgeCases`
- Canonical goldens jvm=js, raw UTF-8 size → `CanonicalJsonTest.numbersMatchJavaScriptVectors` (440 node-verified), `OpCodecTest.canonicalGoldensFromJavaScript`, `CanonicalJsonTest.sizesAreRawUtf8OfCompactCanonicalJson`
- Duplicate key per-op → `PushBodyParserTest.duplicateKeyInOneOpIsAPerOpErrorNotAWholeBodyFailure`, `everyRawPushExampleGetsItsVerdict`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- OUTSIDE OWNED PATHS: added `core/src/jvmTest/kotlin/kotobaya/sync/SyncContractDriftTest.kt`. The ticket asks for a jvm drift test, and common code can't read files. Keep it or move it.
- Error pointer = the first failure in a fixed keyword order (documented in `SyncSchemas`); an `anyOf` failure reports its deepest branch error.
