# HANDOFF T404b

STATUS: DONE
BRANCH: t/T404b-media-pipeline
CHECK: `make check MODULE=worker` → green (554 passed, mypy and ruff clean)

## Changed (≤ 6 lines)
- `coverage/aggregate.py` (pure): occurrences, `pos_class` (C-12 tie order), `sentence_ids` ≤ 20, segment counts, deck items, `JobResultMedia` numbers.
- `media/pipeline/{base,parser,plan}.py`: whole-row base reader (REPEATABLE READ), media-scoped segment parser, drafts → `PublishRequest`. Ids are assigned before parsing; progress and a cancel check run between segments.
- `media/pipeline/import_job.py` (`make_media_import`): file/url/paste, extract, clips, append, publish. `parse_job.py` (`make_media_parse`).

## Acceptance → tests
- Coverage fixture agreement → `test_coverage_fixture_agreement_srt`, `..._epub_one_segment_per_chapter`, `test_sentence_ids_capped_at_20_in_ordinal_order_through_the_pipeline`, `tests/coverage/test_aggregate.py`
- Real parser → `test_real_parser_occurrences_match_fixture_seqs` (skipped without sudachidict_full; recall 0.990, gate 0.98)
- Append / re-parse → `test_append_keeps_existing_ids_and_adds_segments_after_the_last_ordinal`, `test_reparse_after_override_changes_only_affected_tokens_and_keeps_ids`
- Cancellation → `test_cancellation_mid_import_publishes_nothing`, `test_cancelled_append_or_failing_parse_leaves_previous_version`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Contract wording: one segment per non-EPUB file, so a TXT with chapter headings is merged into one `volume`. URL and paste become one `whole`. A source with no text fails the job.
- Append keeps existing tokens as they are; only new segments are parsed. A stale-base re-plan parses again.
- `ruby: hint` reading hints are not used: `parse_sentences` has no hint input (follow-up).
