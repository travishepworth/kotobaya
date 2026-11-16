# HANDOFF SHELL-W4

STATUS: DONE
BRANCH: t/SHELL-W4-mobile-gaps
CHECK: `make check MODULE=web` → green (Playwright run separately: shell-mobile, chip-overlap, shell-device all pass)

## Changed (≤ 6 lines)
- Bug: review `$effect` re-ran `start()` when `keepGoing` flipped, so tapping "Keep going" reset itself (aborted fetch, no cards). Fixed with `untrack` in review/+page.svelte.
- Touch (`pointer: coarse`) 44 px boxes: Button sm, button Chip, SegmentedControl, Browse card buttons; Browse card column truncates instead of overlapping; `overscroll-behavior: none` on html. 100dvh and safe-area were already in place; text stays selectable.
- New `web/playwright/shell-mobile.spec.ts` (+ `.gitignore` un-ignores screenshots/shell).

## Acceptance → tests
- No overflow / actions in viewport / no chip overlap / 44 px / above bottom bar → shell-mobile "<route> fits", "primary actions inside the viewport, chips clear of card text", "grade bar ... keyboard open"
- 60-card keep-going stream, waiting only with next_learning_due_at → "60 cards with keep going never end early", "waiting screen only with next_learning_due_at"
- Screenshots → web/screenshots/shell/ (360x800, 412x915): home, search, dictionary, dictionary-entry, browse, decks, struggle, settings, review front/back/keyboard/waiting/done (all viewed; browse fixed after looking)

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- The stream test runs in plain mode (`/__mock/shell/off`): the fake shell answers queue fetches itself, invisible to `page.route`.
- Follow-ups: Browse table is still dense on phones; the settings mock-shell shows not_implemented toasts (mock gap, not UI).
