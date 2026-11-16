# HANDOFF RC-SERVER

STATUS: DONE
BRANCH: t/RC-SERVER
CHECK: `make check MODULE=server` → green

## Changed (≤ 6 lines)
- Root cause (measured on prod with pg_stat_activity + EXPLAIN): jOOQ binds Kotlin arrays as one varchar, so `x = any(?::uuid[])` is an unfoldable I/O coercion; where the plan filters per row it re-parses the whole array per row. Queue/summary: deck-membership query, 45k deck_item rows x ~1,100 note ids = 5.9–8.1 s. Known-words: 10k cards x 7,200 ids = 44 s; heatmap/retention/calibration then queued behind it (`heavy` semaphore) → 504s.
- All 50 `any(?…)` in server/ → `any(array(select unnest(?::T[])))` (evaluated once). Prod EXPLAIN: membership 8,055 → 4 ms, known-words card query 44,183 → 17.5 ms. Local prod copy, prod plan forced: queue load 3,180 → 235 ms (normal plan ~95 ms).
- Stats on the local prod copy after: heatmap 0.16 s, retention/calibration replay 2.7 s, known-words (53 pts) 2.1 s (were 14–76 s).
- Stats hold at most 3 of the 10 pool connections (`StatsModule.statsTx`), so the dashboard can never starve queue/answer/sync.

## Acceptance → tests
- root cause + guard + identical output → `ArrayBindTest` (source scan forbids `any(?`; old vs new form equal on empty/dup/absent uuid, text, bigint; 40k-row per-row filter < 2 s)
- waiting queue → `ReviewApiTest.onlyNotYetDueLearningCardsGiveAWaitingQueueWithNextDueAndMoreAvailable` (+ existing `aLearningCardNotYetDueIsNotServedButReportedAndCounted`)

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Live `more_available=true` with items=0 is correct (C-26: new limit used up, keep going has new cards; waiting card never served early). Audit of answer/undo/rollover found no bugs.
- Prod `app.deck` was never analyzed (reltuples −1) — that is what made the bad plan; harmless now, a manual ANALYZE is optional.
- Follow-up: web fires retention/calibration up to 4× per page load (seen in logs).
