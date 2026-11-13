# BUG-1 — Stats endpoints too slow; stats page fails as a whole

- Phase: P7 (live bugfix, 0.1.4) · Model: opus · Risk: high (perf + correctness of stats numbers)
- Deps: none
- Branch: t/BUG-1-stats-perf
- Owned paths (only edit these): `server/src/main/kotlin/kotobaya/stats/`, `server/src/test/kotlin/kotobaya/stats/`, `web/src/routes/stats/`
- Module(s) for CI: server, web

## Read
- Spec: Stats section of docs/SPEC.md
- Contracts: `contracts/api/openapi.yaml` (stats operations only), `contracts/db/V1__init.sql` (review_log, cards incl. merged_into_id, existing indexes ~line 620)

## Live report (0.1.4, 78.5k review_log rows)
summary 6.7s, known-words 30s, calibration 42s, retention 42s, heatmap never finishes. One query in
StatsQueries.kt ran 8+ min: LEFT JOIN LATERAL per review_log row to find the card's previous review,
matching on coalesce(merged_into_id, id) — unindexable, quadratic. The web page loads all 6 endpoints
and shows "Could not load stats." if any one fails.

## Do
- Rewrite every stats endpoint's SQL so each returns in ~1s or less at 100k reviews: window functions
  (LAG over PARTITION BY the resolved card id ORDER BY replay order) instead of per-row LATERAL; sargable
  joins (resolve merged_into_id once, e.g. a CTE mapping card → root, not coalesce() in a join predicate);
  bounded date ranges using review_log_reviewed_idx. Results must be identical to the current semantics.
- Existing indexes: review_log (card_id,hlc,id), (card_id,reviewed_at,hlc,id), (reviewed_at), (device_id,hlc).
  If a new index is truly needed, do NOT edit contracts/: finish the query work and put the exact
  `CREATE INDEX` + why under CONTRACT_CHANGE_REQUEST in HANDOFF (status still DONE if the 1s target holds without it).
- Web: each of the 6 panels loads independently, shows its own loading state and its own error message
  (i18n via messages.ts); one slow/failed endpoint never blanks the others.

## Acceptance
- [ ] Perf test (Testcontainers Postgres): seed ~100k review_log rows over ~2k cards (some merged), assert
      each stats endpoint/query completes < 2s (target ~1s), log timings in HANDOFF.
- [ ] Equivalence test: on a small fixture with merged cards, new queries give the same numbers as the
      previous implementation's semantics (hand-computed expected values).
- [ ] `EXPLAIN` of each query has no nested loop over review_log × review_log.
- [ ] make check MODULE=server and MODULE=web green.

## Out of scope
Other server modules; contracts; new endpoints.
