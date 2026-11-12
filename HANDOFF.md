# HANDOFF T102

STATUS: DONE
BRANCH: t/T102-dict-extra
CHECK: `make check MODULE=worker` → green

## Changed (≤ 6 lines)
- `worker/src/kotobaya_worker/dict/extra/`: kanjium.py, freq.py (Yomitan zip), tatoeba.py (pairs + linking), index.py, load.py, pipeline.py (`refresh_extra`).
- Loads `entry_pitch`, `freq_list`, `entry_freq`, recomputes `dict_entry.freq_rank` (lowest priority number wins), `example_sentence(_entry)`; records sources in `dict_release.sources`; prints coverage report.
- Tests and CC0 inputs: `worker/tests/dict/extra/` (data/README.md states origin and licence).

## Acceptance → tests
- Coverage report printed and asserted over floors → `test_extra_load_coverage_and_idempotent` (floors: pitch 9, rank 8, examples 8)
- Idempotent re-run (rows, checksums, no re-download) → same test
- Mapping counts, nothing dropped (unmatched counted) → same test, `test_parsers_see_every_row`, `test_freq_zip_parse`; checksum failure writes nothing → `test_checksum_mismatch_writes_nothing`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- DECISIONS names no frequency lists yet: the list set is `ExtraSources.frequency` config (explicit id/priority/pinned download). Values are taken as ranks. No default Tatoeba URL; pairs TSV is `jpn_id, jpn_text, eng_id, eng_text` (.bz2 ok), caller pins the URL.
- Tatoeba linking has no tokeniser: substring match on normalised forms (kana forms >= 2 chars, ichidan/i-adj stems), so it over-links (41 entries linked from 6 test sentences). Parser-based linking would be more precise.
- T101 needed no changes. Loaders replace their tables wholesale; requires the release to exist.
