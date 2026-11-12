# HANDOFF T202a

STATUS: DONE
BRANCH: t/T202a-sync-core-r
CHECK: `make check MODULE=core` → green (jvmTest + jsTest)

## Changed (≤ 6 lines)
- `core/.../sync/`: `DefaultHlcRules`, `DefaultRemintRules`, `DefaultCanonicalJson` (JS number printing), `DefaultOpCodec`, `SyncSchemas` + `PortableRegex` (ECMA `$`/`\s`/`\S`; refuses `\-` outside a class and `[`/`&&` inside one)
- `PushBodyParser`: per-op `DuplicateKey` / `TooDeep`; whole-body 400 otherwise (SYNC 5.2)
- Attempt 4: depth counts containers (C-5). `MAX_OP_DEPTH` 500 applies in decodeTree, encode, encodeTree, sizeBytes and per push element; `MAX_DOCUMENT_DEPTH` 512 applies to bodies. The iterative deep scan is gone.
- Embeddings regenerated after C-5 (non-ASCII now written as `\uXXXX`); 4 new invalid examples and 2 raw-push examples pass

## Acceptance → tests
- examples → `OpCodecTest.everyExampleOpDecodesKnownAndReencodesCanonically`, `everyInvalidExampleIsRejectedWithTheExpectedReason`, `PushBodyParserTest.everyRawPushExampleGetsItsVerdict` (8)
- HLC → `HlcTest.uniquenessUnderSkewProperty`, `guardBoundaries`, `scenarioClockBehindRemint`
- jvm = js → `CanonicalJsonTest.numbersMatchJavaScriptVectors`, `PortableRegexTest.patternsBehaveAsEcmaScriptOnEveryPlatform`
- Depth/dup per-op → `OpCodecTest.opDepthLimitLeavesRoomForTheBody`, `PushBodyParserTest.tooDeepElementIsAPerOpErrorNotAWholeBodyFailure`, `duplicateKeyInOneOpIsAPerOpErrorNotAWholeBodyFailure`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- An element of depth 511 or more makes the body deeper than 512, so the whole body is a 400 (per C-5).
- Constants are renamed to `MAX_OP_DEPTH` / `MAX_DOCUMENT_DEPTH` to match SYNC.md 2.1.
