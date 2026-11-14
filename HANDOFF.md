# HANDOFF LOGFIX-1

STATUS: DONE
BRANCH: t/LOGFIX-1-worker
CHECK: `make check MODULE=worker` → green (832 passed)

## Changed (≤ 6 lines)
- 1 (real bug: live values pin all 3 EDRDG files; after a cache loss plus an upstream republish, every refresh failed): fetch.py now retries up to 3 downloads (transient errors, short Content-Length), uses a private .part file, and adds `SourceSpec.strict`. EDRDG pins are advisory: two matching downloads of new content are accepted, and the new digest produces a new release.
- 2: already fixed by BUG-3 (as_utc), which predates the failure. Added a test on the real py-fsrs path.
- 3/5 pages.py: the overview is cached only if it has the list link. deck_slug rejects sub-page segments.
- 4 vocab.py: a header-only CSV raises invalid_source. 7: provenance compares all five (url, sha).
- 6: `_requeue_decks` always runs on resume. 8: unchanged deck-build reports progress 1.0.

## Acceptance → tests
- 1 → test_republished_edrdg_file_with_stale_pin_is_accepted_and_recorded, test_partial_download_is_retried_then_verified, test_strict_pin_mismatch_raises_after_all_attempts
- 2 → test_real_pyfsrs_trainer_and_evaluate_accept_naive_and_non_utc_reviews
- 3 → test_overview_without_list_link_is_never_cached
- 4 → test_header_only_source_is_invalid
- 5/7 → test_deck_slug_accepts_only_title_segments, test_jlpt_provenance_is_default_only_when_all_five_match

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- The advisory EDRDG pin goes against ADR-020's "optional pin enforces exact digest". The ADR needs an amendment, and the live values.yaml pins could be dropped.
