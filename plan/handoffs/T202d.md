# HANDOFF T202d

STATUS: DONE
BRANCH: t/T202d-core-no-nul
CHECK: `make check MODULE=core` → green (jvmTest + jsTest); `make check MODULE=server` → green (191 tests, 0 failures)

## Changed (≤ 6 lines)
- `DefaultOpCodec.nulPointer`: iterative walk (explicit stack, no recursion) over strings and member names, RFC 6901 pointer built only on a hit
- decode/decodeTree: NO_NUL runs after depth, lenient shape and Unsupported, and before the pushed seq check and op.schema.json. encode/encodeTree/sizeBytes throw. rawSizeBytes does not check NO_NUL.
- Drift test embeds `examples/invalid-codec` as `SyncExamples.invalidCodec` (regenerated)

## Acceptance → tests
- invalid-codec on jvm+js → `NoNulTest.everyInvalidCodecExampleIsRejectedByNoNulOnEveryPath` (pushed, pulled, text, encode throws, push body per-op)
- 5.3 order → `unknownTypeWithNulStaysUnsupported`; depth → `nulAtMaxOpDepthIsFoundAndDeeperIsRefusedForDepth` (also 100k-deep walk)
- push → `pushBodyWithOneNulOpKeepsTheOthers`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- PushBodyParser is unchanged: NUL elements are already `Parsed`, and decodeTree rejects them per op. NUL in `op_id` stays a whole-body 400 because of PushRequestLenient's uuid pattern.
- Server: T202b's interim step-9 invalid_value for U+0000 is now unreachable on the push path, and server/ is untouched.
