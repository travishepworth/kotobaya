# HANDOFF HOTFIX-0.1.7

STATUS: BLOCKED
BRANCH: t/HOTFIX-0.1.7-preview-loop-heap
CHECK: `make check MODULE=web` → green; `make check MODULE=deploy` → green (both on HEAD)

## Changed (≤ 6 lines)
- web/src/lib/cards/CardPreview.svelte, web/src/lib/notes/NoteEditor.svelte: effect tests a local `busy`, not the `loading`/`dictLoading` it writes (loop fix).
- deploy/helm/kotobaya/values.yaml: `api.env` defaults `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=70` (map key, so override replaces, no duplicate).
- deploy/helm/check.sh: asserts default rendered once, and user override wins.

## Acceptance → tests
- Chart default present, override wins, no duplicate → check.sh assertions (green)
- Preview effects bounded → NOT DONE, no vitest files (see question)

## New dependencies
- none committed. `jsdom` (devDependency) needed for component tests; `pnpm add -D jsdom` was denied by the auto-mode permission classifier, so it was not added.

## For the reviewer (≤ 3 lines)
- Other `$effect`s in cards/ and notes/ checked: none read what they write.

## If BLOCKED
QUESTION: May I add `jsdom` (or `happy-dom`) as a web devDependency (`pnpm add -D jsdom`, lockfile update) so I can add `web/tests/cards/CardPreview.test.ts` and `web/tests/notes/NoteEditor.test.ts`? Alternative: ship the fix alone and file the tests as a follow-up.
