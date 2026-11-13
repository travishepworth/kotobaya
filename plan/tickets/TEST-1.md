# TEST-1 — Regression vitest for the preview $effect loop (HOTFIX-0.1.7 follow-up)

- Phase: P7 · Model: haiku (writer-light) · Risk: low
- Deps: HOTFIX-0.1.7 (merged)
- Branch: t/TEST-1-preview-loop-vitest
- Owned paths: web/package.json (devDependencies only), web/pnpm-lock.yaml, web vitest config (test environment only), web/tests/ (new test files)
- Module(s) for CI: web

## Read
- plan/handoffs/HOTFIX-0.1.7.md; plan/handoffs/INBOX-0.1.6.md › I-3

## Do
The human approved adding `jsdom` as a web devDependency on 2026-11-13 (`pnpm add -D jsdom`). Add it, use it as the
environment for these tests only (per-file `// @vitest-environment jsdom` or a config glob), and write vitests that mount
web/src/lib/cards/CardPreview.svelte and web/src/lib/notes/NoteEditor.svelte with a dictionary-backed note (entrySeq set)
and a kanji note (kanjiLiteral), mock the fetch, and assert the fetch/effect runs a bounded number of times (≤ 2) and
loading settles; NoteEditor also asserts `onentry` is called a bounded number of times. Verify the test FAILS against the
pre-hotfix code (temporarily revert locally, don't commit) and passes now. No product code changes.

## Acceptance
- [ ] CardPreview and NoteEditor bounded-effect tests (named) pass; shown to fail on the pre-fix code
- [ ] make check MODULE=web green
