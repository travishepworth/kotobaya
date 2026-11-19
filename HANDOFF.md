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

## For the reviewer
- `review-back.spec.ts` "undo button and U key" fails (timeout waiting for grade after U) with and without my changes in this env; the other 4 pass. Likely pre-existing flake or env. Please check on main.
- The Playwright mock harness (`web/mock`, not mine) has no `word_audio`, so my spec injects it with `page.route`.
