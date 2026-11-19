# HANDOFF WEB-MORE-CLOSE

STATUS: DONE
BRANCH: t/WEB-MORE-CLOSE-web
CHECK: `make check MODULE=web` → green (299 tests pass; svelte-check 0 errors, 1 pre-existing warning in AddToDeck.svelte)

## Changed (≤ 6 lines)
- web/src/lib/ui/AppShell.svelte: `afterNavigate` closes the "More" sheet on every completed navigation.
- AppShell.svelte: click handler on the sheet's `.more` wrapper closes it when any `<a>` inside is clicked, including the current page.
- Dialog.svelte untouched.

## Acceptance → tests
- make check MODULE=web green → existing web suite (no new test; ticket says none required)

## New dependencies
- none ($app/navigation is already used in the web app)

## For the reviewer (≤ 3 lines)
- Close-on-click is a delegated click on the sheet body, keyed on `closest('a')`; ListRow with href renders an anchor and ignores onclick, so this avoids changing ListRow.
- Not verified in a browser (no screenshot required by ticket).
