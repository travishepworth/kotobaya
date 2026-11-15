# HANDOFF CLI-REVIEW

STATUS: DONE
BRANCH: t/CLI-REVIEW
CHECK: `make check MODULE=cli` → green (65 pytest, mypy --strict, ruff)

## Changed (≤ 6 lines)
- `cli/src/kotobaya_cli/daemon/journal.py`: JSONL journal (answer/undo), atomic rewrite with file and directory fsync. Corrupt lines are copied to `journal.corrupt.<ms>` and reported in a notice.
- `daemon/review.py`: `ReviewHandler`. Journals first, then advances the card. One FIFO sender resends the same entry, with backoff for offline errors, waits for reconnect after auth errors, and drops 409/410/422 entries with a notice. Replays on start.
- `backend.py`: `post_answer` and `post_undo`. `session.apply_queue(pending=…)`: cards with an unsent answer are filtered out, and a card with any unsent entry is never replaced as "gone".
- `server.py`: the seam gains `pending_cards()` and `close()`. `run_daemon` wires up `ReviewHandler`.

## Acceptance → tests (cli/tests/test_review.py)
- Journal fsynced before the POST → `test_journal_written_and_fsynced_before_post_answer`
- Retries reuse the same id → `test_retry_reuses_same_id_no_double_review_logged`
- Crash replay → `test_daemon_crash_between_journal_and_post_replays_exactly_once` and `test_crash_after_post_before_journal_removal_…`
- Terminal 4xx → `test_terminal_4xx_drops_the_entry_with_a_notice[409/410/422]`
- Card replacement → `test_current_card_replaced_only_when_gone_from_the_queue` and `test_stale_snapshot_…`
- Undo → `test_undo_targets_the_last_answered_id` and `test_undo_is_journaled_and_sent_after_its_pending_answer`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Decisions: every other 4xx (e.g. 404) also counts as terminal. Undo is refused while the connection isn't ok. Last-undo state is lost on restart. `last.rating` is the button actually sent.
- A token without the `review` scope gets 403 → `conn:"auth"`, then flips back to ok on the 60 s refetch (flap). Documentation should require the `review` scope.
