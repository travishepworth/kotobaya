# HANDOFF T506a

STATUS: CONTRACT_CHANGE_REQUEST (all code is done and tested; only the job registration is held back)
BRANCH: t/T506a-grammar-dsl
CHECK: `make check MODULE=worker` → green (740 passed, mypy, ruff)

## Changed (≤ 6 lines)
- `worker/src/kotobaya_worker/grammar/`: `DSL.md`, `dsl.py` (parser, column errors), `matcher.py`, `tester.py` (`test_rules`), `detect.py`, `job.py` (`grammar-detect`)
- The job republishes the media through `media-publish` with stored tokens and new `grammar_spans`. It skips the publish when the spans haven't changed. With no `media_id` it queues one job per media, deduped on a hash of the rules.
- `fixtures/grammar-rules/rules.json`: 61 self-written rules with their examples
- Registry line NOT added: `Registry.register` refuses any kind outside the API `JobKind` list.

## Acceptance → tests
- 50 rules, positives covered, negatives clean, real Sudachi (2 tokenisations) → `test_rule_matches_positives_and_no_negatives`, `test_fixture_shape`
- Parser valid/invalid + column → `test_dsl.py::test_invalid_rules_report_column` and the others in that file
- Idempotent / stale removed / manual untouched → `test_second_run_is_a_no_op_with_the_same_spans`, `test_rule_edit_removes_stale_detected_spans`, `test_manual_spans_are_never_read_published_or_touched`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- `media-parse` and `media-import` publish no `grammar_spans`, so a re-parse clears detected spans. Follow-up: call `detect_spans` in `plan_request` (one line).
- Proposed parse-service endpoint for T506b: `POST /grammar/rules/test {rules:[str], sentences:[str]}` → `test_rules(...).to_json()`, which returns `{matches:[{rule_index,sentence_index,start_offset,end_offset,text}], errors:[{rule_index,message,column}]}`.

## If CONTRACT_CHANGE_REQUEST
CONTRACT: contracts/api/openapi.yaml (`JobKind`, `Job`), contracts/db/SCHEMA.md (C-9 table)
CHANGE: Add `grammar-detect` to `JobKind`. Payload: `{media_id?: uuid}`. Result: `{media_id, changed, content_version, spans, invalid_rules}` or `{media, enqueued, rules_digest}`. Add `grammar-detect` to `media-publish`'s parent kinds. After that: add it to the runner's `API_JOB_KINDS`, add the registry line `reg.register("grammar-detect", make_grammar_detect(database_url))`, and update the wiring test.
WHY: Without it the worker can't register the handler, so a `detection_rules` edit (OPS.md: "server enqueues re-detection") has no job to run.
ADDITIVE: yes
