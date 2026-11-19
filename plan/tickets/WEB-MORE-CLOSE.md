# WEB-MORE-CLOSE — mobile "More" page sheet closes after picking a page

- Phase: P10 · Model: haiku (writer-light) · Risk: low · Deps: —
- Branch: t/WEB-MORE-CLOSE-web
- Owned paths: web/src/lib/ui/AppShell.svelte (+ web/src/lib/ui/Dialog.svelte only if needed)
- Module(s) for CI: web
## Do
User: on mobile/small layout the page navigator popup (AppShell `moreOpen` sheet, ~L38/83/92) stays open after tapping a page. Close it when a page link is chosen (also when the route changes, e.g. afterNavigate), including tapping the current page.
## Acceptance
- [ ] make check MODULE=web green; screenshot not required.
