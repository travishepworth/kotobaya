# HANDOFF SHELL-W3

STATUS: DONE
BRANCH: t/SHELL-W3-spa-nav
CHECK: `make check MODULE=web` → green (270 tests)

## Changed (≤ 6 lines)
- `api/shell/nav.ts`: navigate (path rule re-applied), back order 1-5, `back.state`, haptic, intake helpers; `reviewBackPolicy.ts` (port); `ui/backStack.svelte.ts` (LIFO overlays, review undo slot).
- Dialog registers while open; layout installs nav and sends `back.state`; theme sends `theme` at start and on change.
- review.svelte.ts: `backUndo/canBackUndo` (undo path + toast), haptics on flip/grade (Again = reject).
- search/dictionary: `?q&sentence` intake; sentence goes into `createNote.fields.sentence` if the vocab type has that field.

## Acceptance → tests
- reviewBackPolicy cases → `tests/shell/reviewBackPolicy.test.ts`
- back order → `tests/shell/backOrder.test.ts`
- intake encoding → `tests/shell/intake.test.ts`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Edited unowned `lib/state/dictionary.svelte.ts` (`sentence` field, addNote) — the Add action lives there. Views reach nav through re-exports in `ui/backStack.svelte.ts` (view-import rule).
- Ticket branch name said `-web-nav`; used `-spa-nav` per prompt. Step 3 uses `history.length > 1`.
