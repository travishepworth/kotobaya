# WEB-DICT-DESKTOP — HANDOFF

Status: DONE. `make check MODULE=web` green.

## What was bad at desktop (before)
- 1920x1080: headword squeezed into a one-glyph-wide column and overlapping the Add-to-deck button (actions sat beside the headword).
- Whole page scrolled; list pane was sticky with auto height, so its divider ended mid-screen (1280x800 start state).
- Rows tall (3-line gloss + marks stacked); entry wasted the right half at 1920; no URL state (no back/forward), no arrow-key navigation.

## Changes (web/src/routes/dictionary/{+page,EntryView}.svelte, lib/state/dictionary.svelte.ts, playwright/dictionary.spec.ts)
- >=900px: fixed-height two panes; search stays on top of the list, rows scroll; entry pane scrolls separately. Dense rows (headword, reading, one-line gloss, check + Common badges). Entry: 4rem headword, actions on their own row, senses held to measure.
- Up/Down walk results from the search box, Enter opens. URL keeps `q` and `seq` (pushState per opened entry, replace while typing); popstate restores. Desktop only.
- Phone (<900px): pixel-identical (cmp on all 4 shots).
- No new strings, no new deps. JLPT badge on rows omitted: summary has no JLPT field (would need API change).
- Spec has bounds checks (overflow, row/pane overlap) and a keyboard/URL test.

## Screenshots (before/ and after/, names `<size>-{0-start,1-results,2-entry,3-kanji}.png`)
/tmp/claude-1000/-home-travmonkey-repos-kotobaya/f871c8d0-e7b3-4d58-ba92-fd5b8044cb8f/scratchpad/dict-shots/before/
/tmp/claude-1000/-home-travmonkey-repos-kotobaya/f871c8d0-e7b3-4d58-ba92-fd5b8044cb8f/scratchpad/dict-shots/after/
Viewed: 1280 and 1920 before/after, 412 before. Dark mode not screenshotted (tokens only).
Note: spec needs core/build/npm (copied from main checkout, gitignored).
