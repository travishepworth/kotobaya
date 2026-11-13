# HANDOFF T405b

STATUS: DONE
BRANCH: t/T405b-media-dashboard
CHECK: `make check MODULE=web` → green

## Changed (≤ 6 lines)
- `web/src/routes/media/[id]/+page.svelte`: header, filters, summary, per-segment table, difficulty, curve, unknown words.
- `web/src/routes/media/[id]/+layout.svelte`: Coverage / Read / Sentences tab shell.
- `web/src/lib/coverage/`: `filters.ts` (localStorage persistence), `CurveChart.svelte` (inline SVG), `UnknownWords.svelte` (sort by count or rank, paged, known / ignore / never-forget), `messages.ts`.
- `web/tests/coverage-filters.test.ts`

## Acceptance → tests
- Filter persistence round-trips and survives a throwing `localStorage` → `tests/coverage-filters.test.ts`
- `make check MODULE=web` green → yes

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Entry link goes to `/dictionary?q=<headword>`. The dictionary page has no deep link by seq, and I did not touch it.
- The segment filter changes only the coverage numbers. `listDeckItems` has no segment parameter, so the unknown-words table always covers the whole media.
- No `+page.ts`, because `ssr=false` is already set globally and nothing needs a load function.
