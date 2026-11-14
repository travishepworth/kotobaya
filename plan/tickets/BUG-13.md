# BUG-13 — "Open note" shows the note view briefly, then bounces back to the notes list (web)

- Phase: P10 (0.1.10) · Model: sonnet · Risk: low
- Deps: BUG-12 (merged 82a5b27 — struggle "Open in dictionary" linked to bare /dictionary; fixed via lib/state/dictionaryLink.ts)
- Branch: t/BUG-13-open-note-bounce
- Owned paths (only edit these): web/src/** (notes routes + their lib/state, any route/link that opens a note), android/app/src/main/** only if the same bug exists there
- Module(s) for CI: web

## Do
User report on live 0.1.9 web: "Open note" (from struggle, search, deck/cards browsing, review — find every place that opens a note) shows the note view briefly, then bounces back to the default notes page. Possibly the same class of bug as BUG-12 (UI-WEB rewrite, run 19, links/route params not wired): e.g. a notes page that reads selection from a query param it no longer receives, an onMount/$effect that resets selection or calls goto('/notes'), a load-error redirect, or a link to a path that doesn't exist.
1. Diagnose: root cause with file:line in HANDOFF. Audit every "open note" link/goto in web/src for the same issue, and fix all.
2. Fix. Use a small helper in lib/state (like dictionaryLink.ts) for note hrefs if useful.
3. Check: android has an equivalent? Fix only if clearly broken and small; else note it.

## Acceptance
- [ ] Opening a note from each entry point stays on that note (state how verified: vitest on the state/route helper + `make dev-ui` demo run if feasible).
- [ ] Regression vitest for the note href/selection logic.
- [ ] make check MODULE=web green.

## Out of scope
Review-loop bug (separate, critical, diagnosing in parallel — do not touch review state/routes). Dictionary-links-everywhere feature (FEAT-DICT).
