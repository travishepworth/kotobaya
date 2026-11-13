# UI-FOUND-WEB — Web state layer, demo dataset, mock-server mode, Playwright screenshot harness (B1, web half)

- Phase: P9 · Model: opus (writer-senior) · Risk: med
- Deps: HOTFIX-0.1.7
- Branch: t/UI-FOUND-WEB-state-harness
- Owned paths: web/** (except web/src/lib/core/ wrapper semantics), fixtures/demo/ (new, shared with UI-FOUND-APP: you create it), Makefile (only a new `dev-ui` / `screenshots-web` target)
- Module(s) for CI: web

## Read
- Brief: plan/handoffs/INBOX-0.1.6.md › "Run 17 brief" › Track B › B1 and "UI rework notes"
- CLAUDE.md style rules (generated client only; domain logic from web/src/lib/core/)

## Do
Foundation so the later design agents only design. **No visual changes. Behaviour stays identical.**
1. One typed state module per screen under web/src/lib/state/<screen>.ts (Svelte 5 runes in .svelte.ts as needed). It owns
   all fetching, caching, core calls (renderCard/parseQuery/previewIntervals) and mutations, and exposes plain reactive
   state + actions. Views import only from state/ and ui/ (existing components may move to web/src/lib/ui/). No fetch,
   generated-client or core imports in any .svelte file. Add a small vitest/grep check that enforces this rule.
2. Demo/fixture dataset (no private data) under fixtures/demo/ as JSON (format usable by Android later): long multi-sense
   glosses, rare kanji, very long readings, 30+ decks, an empty account, a 900-due day, kanji notes, dictionary-backed
   vocab with empty fields, audio/no audio. README with licence (CC0, invented/JMdict-subset data already in fixtures/).
3. Mock-server mode: `make dev-ui` runs the SPA against the demo dataset (e.g. MSW or a Vite middleware serving
   generated-client-shaped responses). Selectable scenarios (default, empty account, 900-due).
4. Playwright screenshot harness: every route × widths 360/768/1280/1920 × light/dark (prefers-color-scheme) × font scale
   1.0/2.0, written to web/screenshots/ (gitignored except a README). `make screenshots-web`. This is a design feedback
   loop, not a CI gate; do not run it in make check. Note new dev deps in HANDOFF.
5. Effects rule from HOTFIX-0.1.7: never read inside an $effect the state it writes.

## Acceptance
- [ ] make check MODULE=web green; existing tests pass unchanged in meaning
- [ ] Import-rule check: no .svelte file imports fetch/client/core (test)
- [ ] `make dev-ui` boots against the demo data; `make screenshots-web` produces the matrix (report count in HANDOFF)

## Out of scope
Android (UI-FOUND-APP); any visual or layout change (UI-DS / UI-WEB).
