# HANDOFF T506a

STATUS: DONE
BRANCH: t/T506a-grammar-dsl
CHECK: `make check MODULE=worker` → green (745 passed, exit=0)

## Changed (≤ 6 lines)
- `grammar/`: rule language, matcher, `test_rules`, `detect`, and the `grammar-detect` job (follows C-13)
- `PgMediaReader` reads the base, the rules and the detected spans in one snapshot; a stale-base re-plan reads them again
- `plan_request` runs detection, so media-parse and media-import keep the spans (F-22.1)
- Job kind registered (`API_JOB_KINDS`, registry, wiring test); `fixtures/grammar-rules/` has 61 rules

## Acceptance → tests
- Fixture → `test_rule_matches_positives_and_no_negatives`
- Parser → `test_dsl.py`
- Idempotent, stale, manual, re-parse → `test_detect_job.py`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- The result schema forbids extra fields, so budget give-ups are logged and not returned.
- Proposed T506b endpoint: `POST /grammar/rules/test` → `test_rules(...).to_json()`.
