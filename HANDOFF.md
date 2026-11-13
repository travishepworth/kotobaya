# HANDOFF C-12

STATUS: DONE
BRANCH: t/C-12-parser-contract
CHECK: `make check MODULE=contracts` → green

## Changed (≤ 6 lines)
- New `contracts/api/PARSER.md`: token format 1, class table, pos_class tie-break, override algorithm, worker service, versioning
- New `token.schema.json`, `worker-parse.yaml`, `examples/{tokens,parser-cases}.json`, `check_parser.py` (wired into `check.sh`)
- `openapi.yaml`: SentenceToken all 10 required; OverrideToken +`reading`/`class`; optional `parser_version` on ParseResult/JobResultMedia; examples hiragana + verbatim cform
- `SCHEMA.md` 4.3 token note; `api/SUMMARY.md` C-12 section; clients regenerated

## Acceptance → tests
- contracts green, clients regenerated → `make check MODULE=contracts`
- fixture s03 token example validates → `check_parser.py` `check_token_example`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Provisional: no auth on worker port 8082 (NetworkPolicy only); cform verbatim (`連用形-一般`); ties favour `content`; overrides must align to token boundaries.
- New files beyond the listed owned paths: PARSER.md, token.schema.json, examples/, check_parser.py, check.sh edit.
- Follow-up (C-11 owner): optional `parser_version` in content archive header → JobResultMedia.
