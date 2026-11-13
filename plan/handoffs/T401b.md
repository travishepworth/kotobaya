# HANDOFF T401b

STATUS: DONE
BRANCH: t/T401b-jpdb-deck-job
CHECK: `make check MODULE=worker` → green (443 passed)

## Changed (≤ 6 lines)
- `importers/jpdb_deck/plan.py`: pure mapping (corrections first, then `map_vocab`), merge by seq, row accounting, `pos_class` (PARSER.md 2.1, P=[]).
- `repo.py`: corrections / dict POS / media state / R1 remap claim (Pg). `handler.py`: `make_jpdb_deck_import`, `run_import` (publish via T404a `publish_replanning`; cancel check per HTTP request; remap uses cache at any age).

## Acceptance → tests
- Mapping counts → `test_every_fetched_row_is_mapped_unmapped_or_skipped_and_duplicates_merge`
- Corrections → `test_stored_corrections_map_or_ignore_a_previously_unmapped_vid`
- Snapshot validity/token sum → `test_snapshots_validate_against_c11_and_token_counts_add_up`
- Retry/adoption → `test_retry_gives_identical_snapshots_and_adopts_the_existing_child`
- Postgres path → `test_postgres_corrections_remap_claim_and_publish`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Decisions: row without occurrence count counts as 1; new-media segment id = uuid5(media_id) so retries are byte-identical; `jpdb.vocab` includes corrected vids (MEDIA_PIPELINE 5.3) though the schema text says "auto-unmapped"; a stale `mapped` correction (seq not in dict) falls back to the mapper.
- Branch name is t/T401b-jpdb-deck-job (as instructed), not the ticket's.
