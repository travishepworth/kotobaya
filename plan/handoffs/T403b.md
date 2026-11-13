# HANDOFF T403b

STATUS: DONE
BRANCH: t/T403b-parse-matching
CHECK: `make check MODULE=worker` → green (519 passed, mypy, ruff)

## Changed (≤ 6 lines)
- `worker/src/kotobaya_worker/parse/match/`: Token, class table, ranked JMdict matching; in-memory + Postgres lookups
- `parse/overrides/`: PARSER.md 4 apply (scope, alignment, selection key, sanitising) + `load_overrides`
- `parse/pipeline.py`: `parse_sentences`, `PARSER_VERSION="kp1"`, `parser_version()`
- `parse/service/`: worker parse HTTP service; `start_parse_service()` for W-5
- Tests: `worker/tests/parse/test_match*.py`, `test_overrides.py`, `test_parse_service.py`, `test_parse_pg.py`

## Acceptance → tests
- Fixture agreement → `test_parser_fixture_agreement_with_seq` (full 0.9777, core 0.9754)
- Overrides survive re-parse → `test_overrides_survive_reparse`, `test_sentence_beats_media_beats_global`, `test_longest_match_wins_within_scope`, `test_deleted_overrides_are_ignored`, `test_override_golden_vectors`
- Deterministic ranking / homographs → `test_ranking_is_deterministic_regardless_of_insertion_order`, `test_same_input_same_seqs`, `test_ranking_on_fixture_homograph_sentences`
- Service → `test_valid_request_returns_c12_shape`, `test_text_over_20000_code_points_rejected`, malformed-JSON tests in `test_parse_service.py`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Resumed after a crash: kept the previous writer's code; fixed one test that contradicted the PARSER.md 3 splitter rule, plus mypy/ruff errors. Added the Postgres integration test.
- Ranking adds two tie-breaks the ticket doesn't list (kana-written fit, JMdict `common`), placed before lowest seq.
