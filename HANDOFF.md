# HANDOFF T211

STATUS: DONE
BRANCH: t/T211-web-decks
CHECK: `make check MODULE=web` steps (core npmPackage, pnpm test, svelte-check, pnpm build) → green

## Changed (≤ 6 lines)
- web/src/lib/decks/ + routes/decks/ (list, /new, /[id]): create/edit/delete, limits, priority, enabled; smart query field uses core `parseQuery`/`completeQuery`; server 422 `invalid_query` shown inline; item list/add/remove (user decks only); coverage shows "not available yet".
- web/src/lib/note-types/ + routes/note-types/ (list, /new, /[id]): fields, card kinds (enable toggle, custom kinds), front/back templates, CSS, live preview via core `renderCard` with editable samples; renderer warnings listed.
- Own messages files (`decks/messages.ts`, `note-types/messages.ts`).

## Acceptance → tests
- Live preview, errors-not-crash, round trips → manual (no UI tests per CLAUDE.md)
- `pnpm build` (production) bundles the core wrapper correctly: verified, build succeeds with core imported only via `lib/core/index.ts`.

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- T209 `CardView` does not exist on main (web/src/lib/review/ absent; T209 not a dep). I added `note-types/CardView.svelte` (RenderedCard to DOM, extraCss via textContent in `@scope`); swap to T209's component when it merges (one import in Editor.svelte).
- Core renderer gave no warning for `{{nope}}` (renders empty), so template errors show only if core reports warnings. No nav link to /note-types (layout not owned). Deck `settings` (order, add_kanji, card_kinds) not editable. eslint no-navigation-without-resolve flags hrefs, as existing code does.
