# HANDOFF T008

STATUS: DONE
BRANCH: t/T008-fixtures
CHECK: `uv run --with pytest pytest fixtures/tools` → green (3 passed). No make module covers fixtures/.

## Changed (≤ 6 lines)
- fixtures/fsrs/vectors.json: 240 sequences from py-fsrs 6.3.2 (defaults, no fuzz); generator in fixtures/tools/
- fixtures/dict/: 2000-entry JMdict + 612-char KANJIDIC2 subsets (CC BY-SA 4.0), script make_dict_subset.py
- fixtures/parse/sentences.json: 20 hand-tokenised sentences; fixtures/media/: SRT, EPUB, Mokuro
- fixtures/jpdb/reviews-sample.json: invented, marked unverified; READMEs in every dir

## Acceptance → tests
- Each fixture dir has README → present in fsrs, dict, parse, media, jpdb
- ≥200 FSRS sequences incl. lapses/long gaps → fixtures/tools/test_fixtures.py::test_fsrs_vectors_count_and_coverage

## New dependencies
- fsrs==6.3.2 (py-fsrs, PEP 723 inline in generator only — pinned per DECISIONS)

## For the reviewer (≤ 3 lines)
- Segmentations are hand-written, not Sudachi output; mokuro and jpdb shapes are from memory.
- Float vectors are full precision: compare with tolerance. FSRS-6 default params used.
