# HANDOFF T107a

STATUS: DONE
BRANCH: t/T107a-portable-math
CHECK: `make check MODULE=core` → green (jvmTest + jsNodeTest); `make check MODULE=web` → green

## Changed (≤ 6 lines)
- `core/src/commonMain/kotlin/kotobaya/fsrs/PortableMath.kt`: `internal object FdlibmMath : PortableMath` (fdlibm 5.3 pow/exp/log/scalbn; constants from hex words), `internal fun roundHalfEven`
- `core/src/commonTest/kotlin/kotobaya/fsrs/`: `Fnv1a64` helper (reusable by T107b), seeded `PortableMathCorpus`, `PortableMathTest`
- `core/build.gradle.kts`: `nodejs()` with mocha (300 s timeout); `browser()` kept, its Karma test disabled. `Makefile` check-core runs `:core:jvmTest :core:jsTest`. `kotlin-js-store/yarn.lock` regenerated.

## Acceptance → tests
- bit-equal to StrictMath on ≥1e6 args + special cases, same digest on jvm/js → `pow|exp|lnIsBitEqualToStrictMathOnCorpus` (1.21M args), `corpusHasAtLeastOneMillionArgumentsPlusSpecials`, `powSpecialCasesFromFdlibmHeader`, `expAndLnSpecialCases`
- jsTest on node + web green → `make check` runs (above)
- roundHalfEven → `roundHalfEvenTiesGoToEven`

## New dependencies
- none (the Kotlin plugin adds mocha to yarn.lock for the node test runner)

## For the reviewer (≤ 3 lines)
- The StrictMath comparison runs as hard-coded digests in commonTest, because jvmTest is outside my paths. During development a temporary jvmTest compared each argument: 0 mismatches. It also confirmed both outcomes of the z==1024 and z==-1075 edge branches in e_pow.
- Fnv1a64 folds every NaN as 0x7FF8000000000000, because NaN sign and payload bits differ between platforms.
