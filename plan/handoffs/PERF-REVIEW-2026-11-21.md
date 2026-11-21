# Independent review of the four speed-up ideas (2026-11-21)

> Correction (2026-11-21, checked by the orchestrator against the code): PERF-SNAP2 was withdrawn because its premise was false. PERF-WARM's "queue first on the lane" item was false. WARM and STATS-CACHED are unvalidated proposals in plan/proposals/. Only PERF-GATE is a ticket.

Two read-only reviewers (Plan agents) were told to try to break each idea, using the PERF-LF worktree and the timings from the phone running 0.1.42.

## Idea 1: Home numbers from a server endpoint when online. Doesn't hold for counts.
- Home's hero numbers come from getReviewQueue's loop (web/src/lib/state/today.svelte.ts:1-3). All of Home is routed LOCAL (ApiOperations.kt:138-140).
- It breaks REVIEW-LOOP.md:14 (every client shows the same numbers at the same instant) and ADR-051(b) (counts come from one evaluation that includes unsynced answers).
  - Server counts lack the phone's unpushed ops and include ops the phone hasn't pulled. Home would say 12 while the review header says 10.
- "Server minus pending answers" can't be exact:
  - Again moves a card into learning.
  - newToday changes the goal headroom, deck caps and whether a kanji unit fits.
  - Sibling burying changes eligibility.
- "Push first" brings back the push before each call (up to 5 s) that ADR-050 removed. An ETag gains little, because "ready" depends on the current time. The device and server clocks can disagree at rollover.
- What survives:
  - Keep the snapshot fresh in the background → PERF-SNAP2.
  - Move heatmap, retention and the hot lists to the existing `cached` class → C-33.

## Idea 2: Keep derived results in SQLite plus background precompute. Persisting doesn't hold; warm-up does, with changes.
- Process death isn't the main cost:
  - With the process still alive and caches warm, the phone took 497–1137 ms against 89–158 ms on the JVM.
  - Even the desktop's first call is 774 ms against about 55 ms warm.
  - That points at JIT and code not compiled ahead of time on a slow CPU, plus one-time cache fills.
- A persisted candidate table reads back about as fast as one query. Stats rollups diverge under undo, merges, resets and changes to day boundaries.
- ReplicaVersions and TouchLog live in memory on one connection. An UNKNOWN fallback would force a full rebuild inside the sync write transaction, which risks the 5 s write-wait.
- What survives: warm the in-memory caches at process start and after sync, on the lane → PERF-WARM.

## Idea 3: Home asks for counts only. Doesn't hold.
- ADR-051(b) needs the full evaluation anyway. No counts-only handler exists; `limit` must be 1..200.
- The 30 items make Start review instant. Rendering is only 13–81 ms of each call.
- What survives: queue first on the lane, with Home handlers waiting behind the first getReviewQueue → PERF-WARM. WAL and parallel readers already exist. The lane stays 1 wide because the connection pool is small.

## Idea 4: Gate on device timings. Holds, with changes.
- No macrobenchmark, Perfetto setup or benchmark module exists.
- The slow-handler clock (BridgeCore.kt:98-112) includes gate, lane and mutex waits.
- → PERF-GATE: timing split, an adb script with cold and warm runs, a `--speed` JIT experiment, and targets.

## Ideas the reviewers added (no tickets yet)
- Baseline profile plus R8. Release has no minify and no profileinstaller (android/build.gradle.kts:68-72, 268-297). The reviewers think this is probably the biggest single win for cold first use. PERF-GATE's `--speed` run will show how big.
- Per-card check so a snapshot card can be graded without waiting for a full queue build (review.svelte.ts:727-728). Needs an ADR-051 decision.
