# HANDOFF WEB-29

STATUS: DONE
BRANCH: t/WEB-29
CHECK: `make check MODULE=web` → green

## Changed
- core facade (`web/src/lib/core/index.ts`): `wordAudio` in; `wordAudio`/`autoplayAudio` out. Passed via `preview.svelte.ts`.
- `state/review.svelte.ts`: `autoplay` setting, `showSeq`/`showKey`, `resolveAudio()` (A4: urgent resolveSpeech, wait doubling, max 5 calls, drop if moved on). `review/api.ts`: `speechApi` on the quiet config (no error toast, A5).
- `review/+page.svelte`: two effects (resolve once per showing; autoplay once per showing+side, also when the hash arrives later). `CardFrame`: `play()` exported; `P` plays `wordAudio ?? audio[0]`.
- Settings toggle `review.autoplay_audio` (i18n). `assets/card/card.css`: speaker inline-flex, in the expression line, beside the word.

## Acceptance → tests
- Layout/no-overlap + behaviour → `web/playwright/word-audio.spec.ts` (5 pass; API mocked via page.route). Screenshots viewed:
  /tmp/claude-1000/-home-travmonkey-repos-kotobaya/f871c8d0-e7b3-4d58-ba92-fd5b8044cb8f/scratchpad/audio-shots/answer-phone-412x915.png and answer-desktop-1280x800.png

## New dependencies
- none

## Attempt 2: review-back failure
- Not a regression from my change: it is parallel-worker flakiness. The mock API keeps per-scenario state in memory, shared by all workers, so parallel answer/undo tests interfere.
- Evidence: with web/src and assets/card reset to main (no WEB-29 code), `playwright test review-back` at default workers failed in 4 of 5 runs (different tests each time); it passed once.
- Serialised, 3 of 3 runs green with my changes: `SCREENSHOT_WORKERS=1 playwright test review-back word-audio` → `10 passed (15.9s)`.
- Coordinator: run with `SCREENSHOT_WORKERS=1`.
- `make check MODULE=web` green.
- `web/mock` (not mine) has no `word_audio`, so word-audio.spec injects it with `page.route`.
