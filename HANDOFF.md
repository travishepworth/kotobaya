# HANDOFF C-12

STATUS: DONE
BRANCH: t/C-12-parser-contract
CHECK: `make check MODULE=contracts` → green

## Changed (≤ 6 lines)
- `contracts/api/PARSER.md`: token format 1, class table, pos_class tie-break (jpdb: table with P=[]), override algorithm, worker service, versioning
- Attempt 2: readiness = Sudachi loaded only (`dict_release` null + per-request `503 not_ready`); 4.4 sanitises sync-path override tokens (fold katakana; invalid lemma/reading/class/seq = absent)
- `openapi.yaml` OverrideToken.reading rejects katakana; `worker-parse.yaml` health body +`dict_release`; no host exposure of 8082
- New vector "sync-path override with invalid members"; check_parser.py checks the reading pattern; clients regenerated

## Acceptance → tests
- contracts green, clients regenerated → `make check MODULE=contracts`
- fixture s03 token example validates → `check_parser.py` `check_token_example`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Provisional: no auth on 8082 (NetworkPolicy only); cform verbatim; ties favour `content`; overrides align to token boundaries.
- HUMAN_CHECKS (orchestrator): SentenceToken all-required is a tightening (no consumer merged yet).
- Follow-up (C-11 owner): optional `parser_version` in content archive header; T403b: real Sudachi may gap OOV 𠮷.
