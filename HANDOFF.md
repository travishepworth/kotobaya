# HANDOFF BUG-13
Status: DONE (make check MODULE=web green)

**Root cause:** `web/src/lib/state/notes.svelte.ts` `start()` (~L41-57) fetched the linked note async and then called `search()` (L58 original), whose reset load (`loadPage`, `this.notes = res.items`, ~L85) replaced the list. `search()` also zeroes `activeId`/`notes`. The linked note was activated, then the first-page response overwrote the list without it (or it was not on page 1), so `active` became null and the editor closed, showing the default notes page.

**Fix:** `NotesPage` keeps the deep-linked note as `pinned`. Every reset load merges it via `withPinned`. A user search clears it. New helper `web/src/lib/state/noteLink.ts` has `noteHref` and `withPinned`.

**Audit:** all open-note links now use `noteHref` (CardDetail edit button and field click, dictionary EntryView "Open note", struggle leeches). Struggle words go to `/struggle/word/<id>` (not /notes), so they were unaffected. Review has no open-note link, and I did not touch it.

**Tests:** `noteLink.test.ts` covers hrefs and pinning. I did not run a `make dev-ui` demo.

**Android:** not checked; web-only fix.

No new deps.
