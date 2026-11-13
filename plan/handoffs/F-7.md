# HANDOFF F-7

STATUS: DONE
BRANCH: t/F-7-parse-fixture-200
CHECK: `make check MODULE=worker` → green (also `uv run --with pytest pytest fixtures/tools` → 6 passed)

## Changed (≤ 6 lines)
- `fixtures/parse/sentences.json`: 200 sentences (s21–s200 new), every token has `seq` (null for punct/name/number/symbol/ambiguous); s01–s20 tokenisation untouched.
- `fixtures/dict/JMdict_parse_extra.xml` (296 entries, disjoint from subset); `JMdict_subset.xml` and kanjidic byte-identical.
- `fixtures/tools/`: `gen_parse_sentences.py` + `parse_sentences_src.py` (hand-authored source), `make_dict_subset.py` extended, tests extended. READMEs updated.

## Acceptance → tests
- tools tests green, 200 sentences + invariants → `test_sentences`, `test_parse_extra_is_disjoint_from_subset`, `test_sentence_lemmas_resolve_in_subset_or_extra`, `test_sentence_seqs_exist`
- worker check green → `make check MODULE=worker`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Sudachi was not run; segmentation and readings are hand-authored (please spot-check). seq is null for ~11% of content tokens (genuinely ambiguous: する, いる, ない, 私...). New pos values: prefix, interjection, name, number, symbol.
- `make_dict_subset.py` now forces subset lemmas from the first 20 sentences only, to keep the 2000-entry subset stable.
