# HANDOFF CLI-REVIEW

STATUS: DONE (attempt 2)
BRANCH: t/CLI-REVIEW
CHECK: `make check MODULE=cli` → green (83 pytest, mypy --strict, ruff)

## Changed (≤ 6 lines)
- `daemon/journal.py`: an entry exists in memory only once its rename to disk succeeded. A failed append or remove changes nothing, so a full disk can't create a second id for the same answer.
- `backend.classify_review`: only 409/410/422, or a 404 with the server's `not_found` code, drop an entry. Any other 4xx ("rejected") is retried with one notice. 403 is "forbidden".
- `daemon/review.py`: 403 refuses further answers and undos until restart and keeps the journal. Dropping an answer makes it not undoable and quietly removes its queued undo. Acked cards stay filtered until a fetch that started after the ack has been applied.
- `server.py`: new `begin_fetch`/`end_fetch` on the seam (CLI-WATCH files untouched).

## Acceptance → tests (cli/tests/test_review.py)
- Unchanged from attempt 1, all still passing.
- Major 1 → `test_review_post_classification_…` (real HttpBackend, 12 statuses), `test_unexpected_4xx_keeps_the_entry_…`
- Major 2 → `test_failed_journal_write_leaves_no_entry_and_no_second_id`, `test_failed_journal_remove_…`
- Minors 3/4/5 → `test_snapshot_fetched_before_the_answer_…` (fails if acked filtering is removed), `test_forbidden_is_sticky_…`, `test_terminal_drop_of_last_answer_…`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- The forbidden state clears only on restart, since the daemon loads its config at start.
- A failed directory fsync after the rename still counts as committed.
