# HANDOFF C-18

STATUS: DONE
BRANCH: t/C-18-dict-headwords
CHECK: `make check MODULE=contracts` → green (exit 0); `make check MODULE=server` → green (exit 0)

## Changed (≤ 6 lines)
- contracts/api/openapi.yaml: `listDictHeadwords` = `GET /dict/headwords?seq=1358280,1358300` (scope `read`; `seq` comma-separated, `explode: false`, 1..500 `EntrySeq`; 200/401/422).
- Response `DictHeadwordList {items: DictHeadword[]}`, `DictHeadword {seq, headword, headword_reading, glosses: string[]}`; by `seq` ascending; unknown seqs omitted (never 404); duplicates collapse; retired entries returned. Example `DictHeadwordList`.
- contracts/api/SUMMARY.md: checklist row + "C-18 changes".
- clients/: regenerated (kotlin, typescript, python).
- server/src/test/.../WiringAuditTest.kt: `listDictHeadwords` added to the 501 allowlist (auto-stubbed; BUG-7c removes it).

## Acceptance → tests
- Operation, limits, responses, example → `contracts/api/check.sh` (lint + check_contract, 122 operations)
- Clients regenerated → contracts drift gate; 501 stub → ConformanceTest, WiringAuditTest

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Names follow the spec, not the ticket: `/dict/...`, `headword_reading`, `glosses` (array; Meaning column uses `glosses[0]`, may be empty).
- BUG-7c must raise Netty `maxInitialLineLength` to >= 8192: 500 seqs with `%2C` commas (typescript client encodes them) is ~5 kB, over the 4096 default.
- Missing/empty/repeated `seq`, bad element, or >500 as sent → 422.
