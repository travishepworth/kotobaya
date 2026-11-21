# Test APK 0.1.42 (from t/PERF-LF-local-handlers @ 60ee56fa) on the real phone, real account
Only handlers over the slow-log threshold are logged.
First open (cold process): getReviewQueue 1830 ms, listDecks 1339, getStatsSummary 3762,
  listHotKanji 4096, getStatsRetention 4187, getStatsHeatmap 4228, listHotWords 4488
  (staircase: background lane is 1 wide, so Home handlers serialize).
Later opens: getReviewQueue 497 / 801 / 516 / 1137 ms; Home handlers 552-1680 ms,
  re-run after a sync that pushed/pulled 1 op.
Same replica on desktop JVM (PERF-LF harness): queue p95 153-158 ms, summary 107, heatmap 10,
  retention 35, decks 266, answer->next 2 ms. Server answers getReviewQueue in ~130 ms on real data.
User feedback: PERF-SNAP (IndexedDB Home/queue snapshot shown instantly) "helps a ton".
Requirement: near-zero waits in the phone app regardless of connection.
