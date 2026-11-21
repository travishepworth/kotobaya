# PROPOSAL (not a ticket) — Warm the local caches before they are needed

> **Status: UNVALIDATED.** Becomes a ticket only if PERF-GATE's phone measurements show that cache fills (not JIT or startup replay) are a large share of the time. Checked 2026-11-21: the "queue first on the lane" part was FALSE. getReviewQueue is not on the background lane (LocalApi.kt:251-254), so that item is removed.

- Phase: P11 perf · Model: opus (writer-senior) · Risk: med
- Deps: PERF-LF (merged), PERF-GATE
- Branch: t/PERF-WARM-cache-warmup
- Owned paths (only edit these): android/src/main/kotlin/kotobaya/android/shell/api/LocalApi.kt, android/src/main/kotlin/kotobaya/android/sync/SyncFeature.kt, android/src/main/kotlin/kotobaya/android/queue/QueueReadCache.kt, android/src/main/kotlin/kotobaya/android/shell/api/stats/StatsHistory.kt, tests under android/src/test/
- Module(s) for CI: android

## Why
On the phone the first queue took 1830 ms. Home stats then piled up into a 3.7–4.5 s staircase, because they run one at a time on the 1-wide background lane and summary alone spends about 2.4 s filling the stats history cache. The caches are filled when Home first asks for them. Independent review (plan/handoffs/PERF-REVIEW-2026-11-21.md) rejected keeping derived tables in SQLite: that creates a second source of truth with silent-divergence risk under undo, merges, resets and UNKNOWN fallback. It recommends warming the existing in-memory caches instead. It also rejected "Home asks for counts only": ADR-051(b) requires the counts from the full evaluation, and the 30 items are what make Start review instant.

## Do
1. **Warm-up.** Once the readiness gate opens at process start, and again after each sync apply that changed replica versions, put a low-priority warm-up job on the lane. It fills QueueReadCache, for today's day and the default queue request, and StatsHistoryCache. It must never run in the sync's write transaction, and must never hold the write lock. It is skipped if the caches' versions are already current. Split the warm-up into small slices (one cache at a time) so that a real request waits for at most one slice, and a real request always runs before warm-up slices that are still pending.
2. Keep every correctness property from PERF-LF: version checks before use, TouchLog UNKNOWN goes to a full refill, and results are identical to a cold computation.

## Acceptance (each bullet → at least one named test)
- [ ] Test: after a warm-up, getReviewQueue makes no candidate query; the cache was hit.
- [ ] Test: a sync apply that changes a card's state makes the warm-up refill, and the next queue equals a cold computation, compared field by field.
- [ ] Test: warm-up never runs while the sync write transaction is open. Use a fake store that asserts this.
- [ ] On the phone via `make perf-device`: cold first getReviewQueue median is 400 ms or less. Warm Home handlers are 300 ms or less each. The table goes in the handoff.
- [ ] `make check MODULE=android` green.

## Out of scope
New SQLite tables. Baseline profiles and R8 (a separate idea). Web changes.
