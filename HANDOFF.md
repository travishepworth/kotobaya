# HANDOFF T107b

STATUS: DONE
BRANCH: t/T107b-fsrs-scheduler
CHECK: `make check MODULE=core` → green (jvm + jsNode, 26 scheduler tests each); `make check MODULE=web` → green

## Changed (≤ 6 lines)
- `core/.../fsrs/FsrsScheduler.kt`: public `object FsrsScheduler : Scheduler`, a port of py-fsrs 6.3.2 using only FdlibmMath. Fuzz is SplitMix64 + Easy Days; a KDoc line says load balancing is not implemented (ADR-005).
- `core/.../core/KotobayaCore.kt`: `previewIntervals` implemented.
- commonTest: `FsrsSchedulerTest`, plus `FsrsVectors.kt` (vectors.json generated into Kotlin so js runs the vectors too).
- jvmTest: `FsrsVectorsDriftTest` (generated file == JSON) and `StrictMathDigestTest` (PortableMathTest constants == StrictMath digests).

## Acceptance → tests
- Golden vectors → goldenVectorsMatchPyFsrs, lapsesCountAgainOnlyFromReview
- Bit identity (no NaN asserted) → crossPlatformDigestOfAllOutputs (0xcd4b5100896f9768)
- Manual rows → setDueThenRatedReviewMeasuresElapsedFromLastRatedReview, resetClearsLastReviewAtAndCounters
- Fuzz → sameReviewIdGivesFixedIntervalOnEveryPlatform, allOnesEasyDaysGiveUniformFormula, zeroWeightEasyDayIsNeverChosenUnlessAllAreZero
- Preview / paramsOf / rounding → previewEqualsReviewForAllFourRatings, paramsOfFallsBackToAllDefaultsOnAnyBadWeight, paramsOfFallbackValuesArePyFsrsDefaults, roundHalfEvenEdgeCases

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Checked py-fsrs 6.3.2: it uses only `**` and `math.e ** x`, with no math.exp or log. roundHalfEven(-0.5) returns +0.0 while Python gives -0.0. That is harmless because every rounded value is clamped afterwards.
- If the cumulative weight never exceeds u·Σw (rounding), fuzz picks the last positive-weight day.
