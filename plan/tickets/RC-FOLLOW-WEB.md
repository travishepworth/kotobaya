# RC-FOLLOW-WEB — RC-WEB review minor

- Phase: P10 · Model: sonnet (writer) · Risk: low
- Branch: t/RC-FOLLOW-WEB · Owned paths: web/src/lib/state/review.svelte.ts, web/tests/review/ · Module: web

## Do
1. review.svelte.ts:788 — after the first clock-skew 422 (`/reviewed_at` in_future), stop sending reviewed_at for the rest of the page visit so a device with a persistently wrong clock doesn't need a Retry tap on every card. Test it.

## Acceptance
- [ ] vitest: two answers after a clock 422 → second answer sent without reviewed_at, no notice.
- [ ] make check MODULE=web green.
