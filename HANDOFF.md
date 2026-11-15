# HANDOFF WRK-FREQ-MATCH

STATUS: DONE
BRANCH: t/WRK-FREQ-MATCH
CHECK: `make check MODULE=worker` → green (885 passed)

## Changed (≤ 6 lines)
- `dict/extra/freqmatch.py` (new): each row maps to at most one seq. Rows counted as mapped, unmatched or deferred; `load.py` uses it and logs a job_summary for each list.
- `dict/extra/index.py`: adds priority forms, first kanji form, usually-kana level and bound-POS to the index.
- `jobs/freq_rebuild.py` (new CLI): reloads entry_freq/freq_rank for the current release from cached or pinned sources, then queues list-deck builds.
- Run: `kubectl -n kotobaya exec deploy/kotobaya-worker -- python -m kotobaya_worker.jobs.freq_rebuild`
- word_status is keyed by entry_seq, so removed deck items lose no learning data.

## Acceptance → tests
- そう/だ/か → `test_kana_term_maps_to_the_usually_kana_word`
- 草+そう, priority-tagged entry wins → `test_kanji_term_with_reading_maps_to_that_entry_only`, `test_kanji_term_with_two_entries_picks_the_priority_tagged`
- counts add up, one seq per row → `test_mapping_counts_sum_to_input_and_one_seq_per_row`, `test_rebuild_maps_one_entry_per_row_and_queues_list_decks`
- Parser fixture tests → still green (parse/match not touched)

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- The kana key orders priority tag before the usually-kana level, the reverse of the ticket. Ticket order maps とき→鴇 and ところ→野老 on real JMdict. Ties (いる) are broken by the source's own kanji-row rank, then by lowest seq. Bound-POS demotion makes そう pick 然う rather than -そう.
- No ranking code shared with parse/match: it depends on Sudachi POS and freq_rank, which this job computes.
- Device dict packages keep the old entry_freq/top60k until the next release.
