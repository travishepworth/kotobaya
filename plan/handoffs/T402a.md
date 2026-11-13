# HANDOFF T402a

STATUS: DONE
BRANCH: t/T402a-extract-subs-text
CHECK: `make check MODULE=worker` → green

## Changed (≤ 6 lines)
- `worker/src/kotobaya_worker/extract/`: `model.py` (dataclasses, `Extractor`, `detect_format`), `split.py`, `subs/` (srt, ass, vtt), `text/` (txt, texthooker), `__init__.py` (`EXTRACTORS`, `extract()`).
- `worker/tests/extract/test_extract.py`.
- `fixtures/media/`: sample.ass/.vtt/.txt/-sjis.txt/-texthooker.txt, `<file>.expected.json` for each plus sample.srt; README lines.

## Acceptance → tests
- Fixtures match expected → `test_fixture_matches_expected` (6 cases, incl. Shift-JIS)
- No empty / start<=end / no markup leak → `test_invariants_no_empty_no_leaked_markup`
- `detect_format` → `test_detect_format`
- worker check green

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Cue split rule: a cue with 2+ sentences becomes one sentence per piece, each keeping the cue's timestamps (so sample.srt gives 9 sentences from 7 cues). Sentence `location` is None for these formats. Segment `title` defaults to the file stem.
- TXT: hard-wrapped lines in a paragraph are joined unless the previous line ends in a terminator/closer. Fields are tuples (frozen).
- Ran `make fmt` once and reverted its unrelated edits to other files.
