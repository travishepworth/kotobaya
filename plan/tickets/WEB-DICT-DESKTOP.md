# WEB-DICT-DESKTOP — Redesign the /dictionary page for desktop widths

- Phase: P10 · Model: sonnet (writer) · Risk: low · Deps: —
- Branch: t/WEB-DICT-DESKTOP · Owned: web/src/routes/dictionary/**, web/src/lib/** (only dictionary-specific components/styles), web/playwright/** (screenshot spec)
- CI: web

## Read
- docs/DESIGN.md or design/ (whatever the web design system / tokens are; follow existing styleguide at /styleguide)
- web/src/routes/dictionary/{+page.svelte,EntryView.svelte,KanjiDetail.svelte}

## Do
User (2026-11-19): "dictionary page for web mode sucks a ton. probably just needs an optimized redesign, phone mode is decent."
1. First screenshot the current page with the existing Playwright mock harness (pattern: web/playwright review-loop / chip-overlap specs) at 1280x800 and 1920x1080 with a search that has many results, an entry open, and a kanji detail open; and at 412x915 for reference. Write in HANDOFF what is bad at desktop width (stretched single column, wasted space, tiny/huge text, etc.).
2. Redesign for ≥ 900px wide: a proper desktop layout — e.g. search bar on top, results list in a left pane (scrollable, dense rows: headword, reading, short gloss, common/JLPT badges), selected entry detail in the right pane (headword large, readings, pitch, senses, forms, kanji breakdown with clickable kanji, examples, add-to-deck action), kanji detail in the same pane or a side panel; keyboard: ↑/↓ moves through results, Enter opens; URL keeps query + selected entry so back/forward works. Cap line length; use the existing design tokens, dark mode included.
3. Phone (< 900px) must stay as it is now (visually unchanged).
4. All strings via i18n (messages.ts). No new deps. Data via the generated client only; no domain logic re-implemented.

## Acceptance
- [ ] Playwright screenshots before/after at 1280x800, 1920x1080, 412x915 saved and VIEWED by the writer; phone after == before (layout); no overlap/clipping (bounds check in the spec).
- [ ] make check MODULE=web green.

## Out of scope
Android native screens; dictionary API changes (return CONTRACT_CHANGE_REQUEST if data is missing).
