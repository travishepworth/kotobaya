# HANDOFF BUG-7b

STATUS: DONE
BRANCH: t/BUG-7b-notes-view
CHECK: `make check MODULE=web` → green (exit 0)

## Changed (≤ 6 lines)
- NoteTable: label via shared `rowLabel` (no UUIDs), new Reading/Meaning columns read from the headword map.
- NoteEditor: Preview | Edit tabs (default Preview); card-kind switcher over live `note.cards` kinds; fetches DictEntry + Kanji via cached `lib/cards/api` and passes `toKanji`; loading/error line; empty override fields show "From dictionary: …" (placeholder + read-only line), refFieldHint kept.
- Kanji fix: NoteEditor and CardPreview fetch /kanji/{literal} and pass `toKanji`; both have a kind switcher over the note's live cards (CardPreview defaults to the clicked card, fetching kinds via new cached `fetchNoteKinds` in cards/api.ts; NoteEditor defaults to first kind in template order, not cards[0]).
- New `lib/notes/dict.ts` (pure: `headwordInfo`, `dictFieldValues`), `lib/notes/headwords.svelte.ts`; messages added; `+page.svelte` owns the map.

## Acceptance → tests
- /review check: already correct (renders `toKanji(item.kanji)` from QueueItem.kanji); no change. Kanji writing/keyword acceptance covered by the switcher + kanji fetch.
- All bullets → manual/UI only (rowLabel unchanged, so no vitest change).

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- BUG-7c: page-level map is `const headwords = new HeadwordMap()` in `web/src/routes/notes/+page.svelte`, class in `web/src/lib/notes/headwords.svelte.ts` (`byseq: SvelteMap<number, {headword, reading, gloss}>`). Fill it in one call with `headwords.putMany([[seq, DictEntry], ...])` (or add a `putInfo` for a lighter batch shape); the table is reactive and never fetches per row.
- Field-key -> dictionary-value mapping is hardcoded for built-in keys (expression/reading/meaning/literal/keyword/readings); custom keys show no dictionary hint.
