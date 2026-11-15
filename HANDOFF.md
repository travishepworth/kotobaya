# HANDOFF WRK-FREQ-MATCH (attempt 2)

STATUS: DONE
BRANCH: t/WRK-FREQ-MATCH
CHECK: `make check MODULE=worker` → green (890 passed)

## Changed (≤ 6 lines)
- `freqmatch.py` kana key, in order: exact script, common kana word, common, kana level, the source's kanji-row rank, reading ord 0, not bound. If a kanji row owns the winner and it is level >= 2, the row is deferred; there is no fallback. A full tie counts as `ambiguous`.
- `index.py`: kanji forms tagged rK/sK/oK count as no kanji (居る, 然う). Also stores reading ord and exact reading text.
- `freq_rebuild.py`: takes the extras lock before reading is_current. Queues a fresh deck-build (no dedupe key) when a running one holds the key.
- Run: `kubectl -n kotobaya exec deploy/kotobaya-worker -- python -m kotobaya_worker.jobs.freq_rebuild`. word_status is keyed by entry_seq, so no data is lost.

## Acceptance → tests
- `test_freqmatch.py` (fixture: 42 real JMdict entries): とき, もの/ます, いる, かた, and the counts test
- `test_freqmatch_jmdict.py`: the 19 terms, run with and without kanji rows (skipped if the cache is absent)
- `test_freq_rebuild.py`: lock, running-build requeue

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Not mapped, counted as ambiguous: かた, ます (升/増す tie, 〼 untagged), ある without kanji rows. もの→particle 2780660; とこ→床. Tag counts don't help: they pick 要る and 肩.
- ㋕ not used: it only says which spelling was counted, and term + reading already fix the entry.
- Device packages update at the next release.
