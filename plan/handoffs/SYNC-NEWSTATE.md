# HANDOFF SYNC-NEWSTATE

STATUS: DONE
BRANCH: t/SYNC-NEWSTATE
CHECK: no code changed, so no `make check` run

## Changed (≤ 6 lines)
- Nothing. There is no sync bug. The "New" cards are the never-reviewed **reading** siblings of words whose **meaning** card was reviewed yesterday.

## Acceptance → tests
- Root cause, from the live op log (fetched with GET /sync/pull, seq 1–115668; kubectl OIDC auth failed):
  - Device `...0001` is `SERVER_DEVICE_ID`. REST answers mint `review.logged`, and the phone pulls and applies them. 320 times the phone answered a card more than 6 h after a server review, and every one was `review_type=scheduled` (from queue kind REVIEW). The phone also answered cards the server created at seq 114259, so its pull cursor is past every review from yesterday.
  - Each ticket card is a reviewed `meaning` card, and its `reading` sibling has 0 reviews (e.g. 01a1debb-343f…). All 6 `is_new=true` items in today's live /review/queue are reading cards like this.
- Failing test: none, because nothing failed.

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- UX follow-up: the review badge shows the note type ("Vocab") but not the card kind, so a reading sibling looks like the same card as yesterday's meaning card. Suggested fix: add a Meaning/Reading label (web and Android).
- The phone needs no repair.
