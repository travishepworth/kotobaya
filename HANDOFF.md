# HANDOFF T506c

STATUS: DONE
BRANCH: t/T506c-rule-tester
CHECK: `make check MODULE=worker` → green; `make check MODULE=server` → green

## Changed (≤ 6 lines)
- worker: `POST /internal/grammar/rules/test` (parse/service/rules.py + __init__.py); backends gained `tokenise()`; validate-only skips the concurrency slot and needs no dictionary.
- server: `RuleTesterClient`/`HttpRuleTesterClient` (PARSER_URL), `GrammarRuleTestApi` (testGrammarRules scan, `RuleValidator`); create/update validate rules (422 pointer `/detection_rules/i/rule`, 503 fail-closed); existing `id` returns 200 before the worker call.
- `Main.kt`: one `ruleTester` param passed to `GrammarModule` (outside owned paths, needed for wiring). WiringAuditTest allowlist entry removed.

## Acceptance → tests
- endpoint both sides → worker `tests/parse/test_rules_endpoint.py`; server `GrammarRuleTestApiTest`
- invalid rule → 422 with column → `invalidRuleIs422WithPointerAndColumnAndWritesNothing`
- off 501 allowlist → WiringAuditTest

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Existing grammar tests now pass a `FakeRuleTester` (the default client is unconfigured, so rules → 503).
- Scan re-sends the tail of a batch when the worker reports `truncated`; `truncated` on limit stop is exact (peeks next page).
