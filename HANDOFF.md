# HANDOFF BUG-ADD2DECK-500

STATUS: DONE
BRANCH: t/BUG-ADD2DECK-500-web
CHECK: `make check MODULE=web` → green (40 files, 302 tests)

## Changed (≤ 6 lines)
- Root cause: `registerOverlay` (web/src/lib/ui/backStack.svelte.ts), called from Dialog's `$effect`, ran `overlays.push`. That reads and writes the reactive `length`, so every Dialog that opened looped. Push and splice now run in `untrack`.
- This one cause covers every reported route: AddToDeck sheet (/dictionary, /notes), the BrowseLayout note sheet ("Open note" at phone width), AppShell "More" (all routes), and the settings dialogs.
- New web/src/routes/+error.svelte (i18n `app.error.*`): "Something went wrong" + Reload/Today instead of "500"; 404 shows "Page not found".

## Acceptance → tests
- add-to-deck toggle → tests/ui/AddToDeck.svelte.test.ts (fails before the fix, passes after)
- note view sheet / any Dialog → tests/ui/BrowseSheet.svelte.test.ts, "Dialog overlay registration" (both fail before)
- 360x800 phone check → playwright/add-to-deck.spec.ts (both cases fail before, pass after). I viewed the screenshots in web/screenshots/add-to-deck/.

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- "Add to deck" already opens a picker: a bottom-sheet checklist, one checkbox per deck. It adds nothing until you tick a deck. I kept the sheet rather than turning it into a dropdown.
- I could not run prettier (not installed); the files are hand-formatted.
