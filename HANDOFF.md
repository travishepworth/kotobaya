# HANDOFF T202a

STATUS: DONE
BRANCH: t/T202a-sync-core-r
CHECK: `make check MODULE=core` → green (jvmTest + jsTest)

## Changed (≤ 6 lines)
- `core/.../sync/`: `DefaultHlcRules`, `DefaultRemintRules`, `DefaultCanonicalJson` (+ pure-Kotlin JS `Number::toString`), `DefaultOpCodec`
- `SyncSchemas` (2020-12 subset validator, schemas in generated `SyncSchemaTexts.kt`); `PortableRegex` rewrites `$`, `\s`, `\S` to ECMA semantics and refuses constructs that don't port
- `PushBodyParser`: per-op `DuplicateKey` / `TooDeep` (SYNC 5.2, C-2); `MAX_DEPTH` 512 now applies to decode, encode/sizeOf (IAE) and `decodeTree` (Invalid)
- Attempt 3: pushed unknown op carrying `seq` → Unsupported; `\u` escapes accept ASCII hex only; KDoc says `opVersion` is capped at `Int.MAX_VALUE`

## Acceptance → tests
- examples → `OpCodecTest.everyExampleOpDecodesKnownAndReencodesCanonically`, `everyInvalidExampleIsRejectedWithTheExpectedReason`
- HLC → `HlcTest.uniquenessUnderSkewProperty`, `guardBoundaries`, `scenarioClockBehindRemint`, `remintShiftEdgeCases`
- jvm = js → `CanonicalJsonTest.numbersMatchJavaScriptVectors`, `OpCodecTest.canonicalGoldensFromJavaScript`, `PortableRegexTest.patternsBehaveAsEcmaScriptOnEveryPlatform` (28 rows; node-verified with and without the `u` flag), `opsWithNonEcmaValuesAreRejectedEverywhere`
- Per-op errors → `PushBodyParserTest.duplicateKeyInOneOpIsAPerOpErrorNotAWholeBodyFailure`, `tooDeepElementIsAPerOpErrorNotAWholeBodyFailure`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Depth is counted from each push element's own root, the same way `encode` counts it. A too-deep body member other than `ops` is still a 400; a correct client never sends one.
- New public variant `PushElement.TooDeep`; T202b must handle it the way it handles `DuplicateKey`.
