# Device timing 0.1.43 2026-11-21 (compiled -m speed)

Runs: 5 cold, 5 warm. Times in ms. gate = readiness gate, lane = background lane queue, mutex = same-request lock, compute = the handler, total = whole handle, encode = JSON encode + post.

- gate open after, cold: median 68, p90 77 (n=5)

## Per handler and phase (first call per run; median / p90)

| kind | handler | n | gate | lane | mutex | compute | total | encode |
|---|---|---|---|---|---|---|---|---|
| cold | getPrincipal | 5 | 1 / 1 | 0 / 0 | 0 / 0 | 1 / 1 | 22 / 40 | 27 / 28 |
| cold | getReviewQueue | 5 | 0 / 0 | 0 / 0 | 0 / 0 | 1267 / 3312 | 1274 / 3338 | 3 / 4 |
| cold | getShellInfo | 5 | 0 / 0 | 0 / 0 | 0 / 0 | 19 / 23 | 32 / 34 | 15 / 20 |
| cold | getStatsHeatmap | 5 | 0 / 1 | 2825 / 5114 | 0 / 0 | 37 / 127 | 2876 / 5221 | 2 / 3 |
| cold | getStatsRetention | 5 | 0 / 0 | 2835 / 5160 | 0 / 1 | 70 / 871 | 2919 / 5281 | 1 / 4 |
| cold | getStatsSummary | 5 | 0 / 1 | 1663 / 3925 | 0 / 0 | 1178 / 1212 | 2739 / 5166 | 1 / 2 |
| cold | getSyncStatus | 5 | 0 / 0 | 0 / 0 | 0 / 0 | 16 / 65 | 25 / 80 | 1 / 5 |
| cold | listDecks | 5 | 0 / 0 | 1 / 3308 | 0 / 0 | 1096 / 1293 | 1301 / 3966 | 1 / 2 |
| cold | listHotKanji | 5 | 0 / 0 | 1511 / 2672 | 0 / 1 | 168 / 2572 | 2598 / 2849 | 2 / 405 |
| cold | listHotWords | 5 | 0 / 0 | 1178 / 2567 | 0 / 0 | 333 / 744 | 1469 / 3342 | 2 / 3 |

## Targets (medians of total)

| target | measured | limit | result |
|---|---|---|---|
| cold: first getReviewQueue | 1274 | 400 | FAIL |
| warm: getReviewQueue | n/a | 150 | n/a (no data) |
| warm: Home handlers | n/a | 300 | n/a (no data) |
| answerReview (answer to next card) | n/a | 50 | n/a (no data) |
