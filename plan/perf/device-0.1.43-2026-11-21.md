# Device timing 0.1.43 2026-11-21

Runs: 5 cold, 5 warm. Times in ms. gate = readiness gate, lane = background lane queue, mutex = same-request lock, compute = the handler, total = whole handle, encode = JSON encode + post.

- gate open after, cold: median 68, p90 107 (n=5)

## Per handler and phase (first call per run; median / p90)

| kind | handler | n | gate | lane | mutex | compute | total | encode |
|---|---|---|---|---|---|---|---|---|
| cold | getPrincipal | 5 | 1 / 4 | 0 / 0 | 0 / 0 | 1 / 2 | 34 / 51 | 10 / 35 |
| cold | getReviewQueue | 4 | 0 / 1 | 0 / 0 | 0 / 0 | 6446 / 6710 | 6526 / 6757 | 26 / 36 |
| cold | getShellInfo | 5 | 0 / 0 | 0 / 0 | 0 / 0 | 52 / 91 | 75 / 103 | 3 / 18 |
| cold | getStatsHeatmap | 1 | 0 / 0 | 4007 / 4007 | 0 / 0 | 38 / 38 | 4059 / 4059 | 4 / 4 |
| cold | getStatsRetention | 1 | 0 / 0 | 3916 / 3916 | 0 / 0 | 92 / 92 | 4022 / 4022 | 1 / 1 |
| cold | getStatsSummary | 1 | 0 / 0 | 2073 / 2073 | 0 / 0 | 1844 / 1844 | 3929 / 3929 | 1 / 1 |
| cold | getSyncStatus | 5 | 0 / 0 | 0 / 0 | 0 / 0 | 78 / 115 | 122 / 165 | 2 / 6 |
| cold | listDecks | 1 | 0 / 0 | 588 / 588 | 0 / 0 | 1488 / 1488 | 2086 / 2086 | 1 / 1 |
| cold | listHotKanji | 1 | 0 / 0 | 0 / 0 | 1 / 1 | 589 / 589 | 598 / 598 | 1 / 1 |
| cold | listHotWords | 3 | 0 / 0 | 1 / 4044 | 0 / 2 | 3528 / 4002 | 4014 / 4356 | 4 / 33 |
| warm | getStatsHeatmap | 1 | 0 / 0 | 24552 / 24552 | 0 / 0 | 850 / 850 | 25487 / 25487 | 14 / 14 |
| warm | getStatsRetention | 1 | 0 / 0 | 23521 / 23521 | 1 / 1 | 1049 / 1049 | 24636 / 24636 | 10 / 10 |
| warm | getStatsSummary | 1 | 0 / 0 | 1 / 1 | 4 / 4 | 17368 / 17368 | 17434 / 17434 | 8 / 8 |
| warm | listDecks | 1 | 1 / 1 | 17369 / 17369 | 4 / 4 | 6154 / 6154 | 23600 / 23600 | 4 / 4 |
| warm | listHotKanji | 1 | 4 / 4 | 25400 / 25400 | 0 / 0 | 2271 / 2271 | 27760 / 27760 | 12 / 12 |
| warm | listHotWords | 1 | 0 / 0 | 27665 / 27665 | 0 / 0 | 1905 / 1905 | 29675 / 29675 | 8 / 8 |

## Targets (medians of total)

| target | measured | limit | result |
|---|---|---|---|
| cold: first getReviewQueue | 6526 | 400 | FAIL |
| warm: getReviewQueue | n/a | 150 | n/a (no data) |
| warm: getStatsHeatmap | 25487 | 300 | FAIL |
| warm: getStatsRetention | 24636 | 300 | FAIL |
| warm: getStatsSummary | 17434 | 300 | FAIL |
| warm: listDecks | 23600 | 300 | FAIL |
| warm: listHotKanji | 27760 | 300 | FAIL |
| warm: listHotWords | 29675 | 300 | FAIL |
| answerReview (answer to next card) | n/a | 50 | n/a (no data) |
