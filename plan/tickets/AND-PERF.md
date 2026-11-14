# AND-PERF — todayReviews query starves SQLite pool on device (app loads nothing)

- Phase: P9 hotfix 0.1.11 · Model: opus · Risk: high
- Deps: none
- Branch: t/AND-PERF-todayreviews
- Owned paths (only edit these): android/ (sqldelight queries LocalQueue.sq + their Kotlin callers/loaders, tests), core/ only if the query lives there (say so in HANDOFF)
- Module(s) for CI: android

## Evidence (live phone, 0.1.10, Android user 10 = Private Space; 9533 cards, 8778 notes, 78698 review_log)
- Logcat: SQLiteConnectionPool exhausted — all 4 connections running LocalQueue.sq `todayReviews` for 30–116+ s, repeatedly (41 pool-starvation warnings, /tmp/claude-1000/-home-travmonkey-repos-kotobaya/14e7df7d-ac8c-49b3-99e3-adc26fe8ab51/scratchpad/phone-logs/logcat-app.txt ~line 793). Every screen (home, review, widget) waits for a connection → "syncing" forever, nothing loads, offline review impossible.
- Real DB copy (with WAL): /tmp/claude-1000/-home-travmonkey-repos-kotobaya/14e7df7d-ac8c-49b3-99e3-adc26fe8ab51/scratchpad/phone-logs/u10/kotobaya-app.db (work copy /tmp/claude-1000/-home-travmonkey-repos-kotobaya/14e7df7d-ac8c-49b3-99e3-adc26fe8ab51/scratchpad/phone-logs/u10work/ — use only the work copy, read-only on u10/). Query text: /tmp/claude-1000/-home-travmonkey-repos-kotobaya/14e7df7d-ac8c-49b3-99e3-adc26fe8ab51/scratchpad/phone-logs/todayReviews.sql.
- Desktop sqlite3: 163 rows in 3.5 ms (r via review_log_reviewed_idx; correlated prev_id subquery via review_log_reviewed_idx reviewed_at<? + temp B-tree). Android's SQLite (Robolectric uses a native sqlite too — check its version vs device API 35) evidently picks a far worse plan → O(today_reviews × 78k) per call.

## Do
1. Rewrite todayReviews so the prev_id lookup is index-driven per card (e.g. review_log_replay_idx (card_id, reviewed_at, hlc, id); handle merged cards with UNION/IN over the card + cards merged into it), no row-value comparisons, no COALESCE join defeating indexes. Same result set as today (prove with a test comparing old vs new on generated data incl. merged cards and same-timestamp ties).
2. Loaders must not hog the pool: conflate/cancel superseded loads for home, widget, notification and review so concurrent triggers cannot occupy all connections (AND-POSTSYNC conflation may not cover all of them).
3. Offline-first: home/review render from local DB regardless of pairing/sync state; sync status must never gate local data display.

## Acceptance
- [ ] Test with ~80k review_log rows (+ merged cards): todayReviews correct and completes under a generous time bound (e.g. < 500 ms) under Robolectric; EXPLAIN QUERY PLAN shows no full scan of review_log in the per-row subquery (assert on plan text).
- [ ] Test: N concurrent load triggers do not run more than one todayReviews at a time per consumer (or are cancelled).
- [ ] Old-vs-new equivalence test.
- [ ] make check MODULE=android green.

## Out of scope
Widget Row>10 crash (AND-WIDGET). Server/worker. VERSION_CODE bump (release step).
