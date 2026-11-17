# HANDOFF LOOP-C2

STATUS: DONE
BRANCH: t/LOOP-C2
CHECK: `make check MODULE=contracts` → green · `MODULE=server` → green · `MODULE=android` → green (shell parity: 24 match, 0 differ, 0 pending)

## Changed (≤ 6 lines)
- `parity/queue-loop-cap-keep-going-backlog.json`: seed reviews now 10 days apart (100…60 d before clock), so due_at order = card order whatever the fuzz; `expect` unchanged; golden re-recorded.
- `ParityGoldenTest.kt`: `EXPECT_DISAGREES` removed (Android had no equivalent).
- New scenarios + server goldens: `bulk-cards-local`, `word-status-set-clear`, `note-update-delete`, `card-get-reviews`.
- `SHELL.md` §4.3 `createNote` row: mints only `note.created`, `card.created`, `deck.item_put`; kanji notes come from C-27 only.

## Acceptance → tests
- No allowlist, green → `ParityGoldenTest`, `LocalParityTest`
- Four scenarios recorded, run on Android → same tests

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Server `updateNote` accepts an `entry_seq` that is in no dictionary (200); SHELL.md §4.3 says the shell checks it like `createNote` (422). Left out of parity; needs a decision.
- Server answers `auto_status: null` for an entry whose only card is new (schema: null = no live vocab note). Recorded as is; Android agrees.
- `/next_cursor` ignored in `card-get-reviews` step 7 (opaque cursor); new coverage tags are not in §8.4 (outside owned paths).
