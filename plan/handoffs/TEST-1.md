# HANDOFF TEST-1

STATUS: DONE
BRANCH: t/TEST-1-preview-loop-vitest
CHECK: `make check MODULE=web` → green (8 files, 34 tests; svelte-check 0 errors, 0 warnings)

## Changed (≤ 6 lines)
- web/tests/cards/CardPreview.test.ts, web/tests/notes/NoteEditor.test.ts: jsdom component tests, dictionary-backed (entrySeq) and kanji (kanjiLiteral) notes.
- web/tests/support/previews.ts: fixtures and fetch stub (wire-form JSON).
- web/vite.config.ts: under vitest only, `resolve.conditions: ['browser']` so Svelte's browser build is used.
- web/package.json, web/pnpm-lock.yaml: jsdom devDependency (approved).

## Acceptance → tests
- CardPreview bounded effect → `CardPreview bounded effect` (entry and kanji cases: effect ≤ 2, loading settles)
- NoteEditor bounded effect → `NoteEditor bounded effect` (entry: effect and onentry ≤ 2; kanji: effect ≤ 2, loading settles)
- Fails on pre-fix code: all 4 failed with pre-HOTFIX components (loading never settles); fixed code restored, not committed.

## New dependencies
- jsdom@31.0.0 — component tests (human-approved 2026-11-13)

## For the reviewer (≤ 3 lines)
- Probe wraps `cards/api` fetchEntry/fetchKanji; past 20 calls it never settles, so a looping effect fails instead of hanging the runner.
- Worktree branch was renamed to the ticket branch name.
