# HANDOFF WRK-FREQ-MATCH (attempt 3)

STATUS: DONE
BRANCH: t/WRK-FREQ-MATCH
CHECK: `make check MODULE=worker` → green (899 passed)

## Changed (≤ 6 lines)
- `freqmatch.py` kana rows: a hiragana term no longer matches katakana-only readings. A kanji row at the same rank wins (jpdb ㋕ pairs). Kanji words the source ranks far away are dropped (`is_near`: claimed ≤ 2r+50).
- Ranking: a near kanji-row rank now comes right after the common-kana-word tier. The reading-ord check uses the term's own script (ず aux, not ズ). Tied kana words take the lowest seq; only tied kanji words count as ambiguous.
- `FreqMapping.row_status` records each row's outcome. `index.exact_readings` now holds the ord of each exact reading.
- Rebuild, lock and deck requeue are unchanged from attempt 2. Run: `kubectl -n kotobaya exec deploy/kotobaya-worker -- python -m kotobaya_worker.jobs.freq_rebuild`

## Acceptance → tests
- `test_freqmatch.py` (56 real JMdict entries): one test each for same-rank pairs, near kanji rows, far kanji rows, the hiragana filter, ああ and ず
- `test_freqmatch_jmdict.py::test_real_list[jpdb|bccwj|cejc]`: real lists checked against their pins. Covers いくら, げ, つく, とる, ている, た, ます and ああ. Top-1000 deferred+ambiguous must not grow (jpdb 34→3, BCCWJ 19→5, CEJC 18→9). The earlier 19-term tests still pass.

## New dependencies
- none. The real lists are cached in `~/.cache/kotobaya-fixtures/freq/`; the test is skipped if they are missing.

## For the reviewer (≤ 3 lines)
- jpdb ない(29) maps to 無い, not the auxiliary, because 無い/ない is paired at 29 (your rule 1).
- た→2654250 and ます→〼 2210290, both untagged in JMdict. ている is unmatched because JMdict has no ている entry.
- `is_near` is a heuristic: いや (CEJC 52) maps to 否 because 嫌 (202) is just past 2r+50 = 154.
