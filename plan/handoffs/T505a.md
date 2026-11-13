# HANDOFF T505a

STATUS: DONE
BRANCH: t/T505a-grammar-seed
CHECK: `python3 fixtures/grammar-seed/validate.py` → green (no module; data only). No Gradle run, `make fmt` not run (no formatter for data files).

## Changed
- `fixtures/grammar-seed/grammar-seed.json`: 280 stubs (N3 109, N2 96, N1 75) as `{title, pattern, jlpt}`. Hand-written titles and patterns only.
- `fixtures/grammar-seed/validate.py`: stdlib checker (count ≥ 250, keys, jlpt ∈ {1,2,3}, non-empty strings, no U+0000, unique (pattern, jlpt)).
- `fixtures/grammar-seed/README.md`: provenance, CC0 licence, field mapping to `app.grammar_point`.

## Acceptance → tests
- validate.py exits 0 → `validate.py` run (OK: 280 grammar point stubs)

## New dependencies
- none

## For the reviewer
- Count is 280, under the "around 300" target; N1 is the thinnest. Accepted since the minimum is 250.
- Levels are approximate author judgement, not checked against a JLPT list.
