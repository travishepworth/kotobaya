# HANDOFF C-14

STATUS: DONE
BRANCH: t/C-14-rule-test-contract
CHECK: `make check MODULE=contracts` → green (no `clients/` drift; worker-parse has no generated client)

## Changed (≤ 6 lines)
- `contracts/api/worker-parse.yaml` 1.0.0 → 1.1.0 (additive): `POST /internal/grammar/rules/test` (`workerTestGrammarRules`), schemas `WorkerRuleTest{Request,Sentence,Token,Result}`, `WorkerRuleMatch`, `WorkerRuleError`, three worked examples.
- Validate-only = `sentences` absent/empty; never `busy`/`not_ready`. Invalid rules are `200` + `errors`.
- Normative server mapping in the operation description (422 pointers, fail-closed 503, sync ops never validated).
- `contracts/api/SUMMARY.md`: decision, rejected alternatives, second-look items.

## Acceptance → tests
- Endpoint, shapes, UTF-16 offsets, limits → redocly lint incl. example-vs-schema validation (`check.sh` step 3)
- Validate-only mode for 422 → `validate_only` request/response examples

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Beyond the ticket: sentences may be `{text, tokens}` (stored tokens used as given) as well as strings; plus `max_matches`, `truncated`, `parser_version` in the result.
- Grammar create/update with rules fails `503` when the worker is down (fail closed).
- Not edited (not owned): PARSER.md 5.1 still says "nothing else; any other path is 404"; openapi `DetectionRule.rule` says "grammar owned by the core" and allows 1000 code points while the worker language caps at 500 (reported as an `errors` entry). Follow-up candidates.
