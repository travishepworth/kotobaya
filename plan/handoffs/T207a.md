# HANDOFF T207a

STATUS: DONE
BRANCH: t/T207a-jpdb-worker-r
CHECK: `make check MODULE=worker` → green (299 passed, mypy, ruff)

## Changed (≤ 6 lines)
- `worker/src/kotobaya_worker/importers/jpdb_history/plan.py`: `plan_import`, `ImportPlan` (JSON schema v1 in the module docstring, for T207b), `job_result(plan, ExistingState)` gives the full `JobResultJpdbHistory`
- `mapping.py` (S02 `map_word` port), `review_id.py` (the contract's UUIDv7), `ref.py` (`RefLookup`: in-memory and Postgres `ref.dict_entry*`), `handler.py` (runner adapter)
- Tests in `worker/tests/importers/jpdb_history/`

## Acceptance → tests
- Sample counts/sums, nothing dropped → `test_sample_bucket_counts_match_hand_computed_table`, `test_property_every_row_and_event_lands_in_exactly_one_bucket`
- Repeated vid merge, stable unique ids → `test_repeated_vid_interleaves_and_drops_exact_duplicates`, `test_review_ids_are_stable_across_runs_and_unique`, `test_review_id_golden_vector`
- ADR-009 → `test_known_only_vocab_gets_status_only`, `test_known_then_reviews_is_carded_without_status_override`, `test_known_only_kanji_gets_a_suspended_card`, `test_writing_cards_are_suspended_keyword_cards_are_not`, `test_no_vocab_production_or_reading_cards`
- Unmapped list → `test_unmapped_vids_are_listed_with_spelling_reading_review_count`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Registry wiring is still needed in `runner/__main__.py` (not owned): `reg.register("jpdb-history-import", make_jpdb_history_import(DATABASE_URL, fetch_blob, apply_plan))`. `fetch_blob` and `apply_plan` come from T207b, so the line is not added yet.
- Decisions the contract doesn't cover: ambiguous text matches pick the smallest seq; timestamps outside the instant_ms range count as malformed; the export keyword is trimmed; `importers/` has no `__init__.py` (namespace package, since that path is not owned). `PostgresRefLookup` has no DB test.
- The contract's ids can collide when one vid maps to two seqs through text fallback and both rows have the same timestamp+grade. `job_result` counts the second as already imported. This never happens on real data.
