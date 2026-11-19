# WEB-28 — Web: progress bar on until_stop, deck progress UI, kanji parts tiles

- Phase: P10 · Model: sonnet (writer) · Risk: low-med
- Deps: C-28 (generated client), WEB-REVIEW-FLOW (same files; rebase after); SRV-28/AND-28 for live check
- Branch: t/WEB-28-web
- Owned paths: web/** , assets/card/**
- Module(s) for CI: web

## Read
ADR-038, ADR-041; C-28 handoff; docs/DESIGN.md.

## Do
1. Review progress bar (web/src/routes/review/+page.svelte ~L136, L295): total = done + `loop.until_stop`; label e.g. "12 / 77". Grows on Again.
2. Deck progress on Home deck panel (routes/+page.svelte ~L102,160), deck list (routes/decks/+page.svelte) and deck page (routes/decks/[id]): "400 of 512 words learned · 78%" + thin bar from `Deck.progress`. Reading/media decks additionally: raw coverage % + the stacked words bar reused from media/[id]/+page.svelte:63-80 (extract a shared component). Readable at 360px. i18n strings.
3. Vocab back: kanji tiles from `QueueItem.kanji_parts` — character large, keyword/meanings, the used reading highlighted, other ON/KUN dimmed; horizontal row that wraps; hidden when empty. Tap opens dictionary kanji page.
4. ADR-042 furigana front: render `QueueItem.front_furigana` (see C-28 openapi + plan/handoffs/C-28.md) on the review front for exempt words (ruby over the word); unchanged when absent.
5. Read field shapes from the regenerated client (C-28 merged); use web/mock data for screenshots since the server fills them only after SRV-28.
## Acceptance
- [ ] Screenshots 360x800 + desktop of Home, deck page (list deck + reading deck), review back with 2-kanji word, viewed before handoff.
- [ ] make check MODULE=web green.
