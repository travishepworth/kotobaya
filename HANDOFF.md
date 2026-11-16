# HANDOFF SHELL-L6

STATUS: DONE
BRANCH: t/SHELL-L6-local-browse (from t/SHELL-L5-local-mining)
CHECK: `make check MODULE=android` → green (one unrelated flake seen once: DuplicateCheckExpressionTest "No suitable driver", passed on rerun)

## Changed (≤ 6 lines)
- shell/api/browse/: Routes.kt (listCards, listNotes), ListCardsRoute.kt, ListNotesRoute.kt; RouteTableTest PENDING line removed.
- listCards: reuses `CardQuery`/`LocalCardQuery` (L4), sorts in Kotlin, keyset cursor (sort value|id|hash); Forward → Fallthrough.
- listNotes: replica filters of server NoteApi.list, keyset (created_at, id); notes embed cards via ActionViews.note.

## Acceptance → tests
- SearchExamplesTest → SearchExamplesTest.everyExampleCompilesAndReturnsTheSharedSelection (+ hand-pinned ids)
- CursorTest (250 cards, insert between pages) → CursorTest
- Parse error 422 pointer/position → SearchExamplesTest (every `-` row)

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- No listCards golden parity scenario exists in contracts/api/shell/parity, so "same ids" is checked against the shared LocalCardQuery selection plus hand-pinned ids.
- Cursors are 3-part (`value|id|hash`); a server-issued cursor is `422 /cursor`. SHELL.md has no documented forward list for listCards (only the L4 dictionary-form guard); contract not edited.
