# HANDOFF AND-REVIEW-SNAPPY

STATUS: DONE
BRANCH: t/AND-REVIEW-SNAPPY-instant
CHECK: `make check MODULE=android` → green

## Changed (≤ 6 lines)
- ReviewSession: an in-memory queue kept current between builds. The next card is pre-rendered and shown at the tap, then the write runs on the IO thread in tap order. A failed write rolls back to that card and withdraws the later taps.
- Phone DB copy, JVM: an answer took ~11 ms (full rebuild); the advance is now 0–0.3 ms, the ~2 ms commit runs after it.
- ReviewTiming: `KotobayaReview` lines for answer, reveal, rebuild and WebView paint. CardWebView caches card.css.
- Reads go through ReviewReads (SqlDelightDeviceStore.read). VM: markStale on sync, inline error on a failed write.

## Acceptance → tests
- Timing log → ReviewAsyncCommitTest.theTimingLineCoversEveryPhase
- Async commit exactly once, in order, rapid/double taps, undo, failure → ReviewAsyncCommitTest (3), ReviewDurabilityTest.aKillBetweenTheTapAndTheCommit…
- <20 ms, no queue build per answer (9.5k cards/80k logs) → ReviewLatencyTest

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Riskiest: lock order commitLock > lock > storeLock in ReviewSession. A kill before commit loses only the uncommitted tap; the card comes back after restart.
- The 200 ms card fade starts before the WebView paints; check that on the phone. PhoneDbBenchTest is opt-in (KOTOBAYA_BENCH_DB).
