# BUG-7 — Card browser: preview unreachable/blank for dictionary-backed notes

- Phase: P7 · Model: sonnet · Risk: low
- Deps: —
- Branch: t/BUG-7-cards-preview
- Owned paths (only edit these): web/src/lib/cards/ (CardTable.svelte, CardPreview.svelte, model.ts, messages.ts, api.ts, plus a model test file there), web/src/routes/cards/
- Module(s) for CI: web

## Read
- Contracts: contracts/api/openapi.yaml (Note, DictEntry, Kanji schemas; dictionary operations) — read only, never edit
- Code: web/src/lib/cards/*, web/src/routes/cards/*, web/src/routes/review/+page.svelte (~L87, toDictionary/toKanji usage), web/src/lib/review/model.ts (toDictionary/toKanji), the dictionary page and KanjiPanel for the DictionaryApi calls

## Symptom (prod 0.1.5)
On /cards, selecting a row only shows the bulk bar; the preview pane stays on "select a card to preview it". Affects ~6,591 of the user's notes (all jpdb-imported vocab).

## Root cause (verified)
1. Preview is opened only by clicking the note label button in the Note column (CardTable.svelte ~L94). Its text is noteSummary(note.fields) (model.ts:39), which returns '' for referenced notes, whose fields are `{}` by design (openapi: "for referenced notes an empty or missing field means render from the dictionary"). The result is a zero-width button with nothing to click. The checkbox only toggles bulk selection.
2. Even when opened, CardPreview.svelte:31-32 passes `dictionary: null, kanji: null` to renderCard, so referenced notes render blank. The review screen does this correctly: routes/review/+page.svelte:87 uses toDictionary(item.entry) / toKanji(item.kanji).

## Do
- Row label: when noteSummary is empty, fall back to the headword: the dictionary entry's first kanji form/reading for note.entrySeq, note.kanjiLiteral for kanji notes, else the short note id. Fetch headwords for the visible page in one call if the existing DictionaryApi allows it; if that needs a new endpoint, return CONTRACT_CHANGE_REQUEST and ship the kanjiLiteral/id fallback only.
- Clicking anywhere on the row (except the checkbox) activates the preview; keep the checkbox for bulk selection only. Keyboard: Enter on a focused row activates it.
- CardPreview: for notes with entrySeq / kanjiLiteral, fetch the DictEntry / Kanji through the generated DictionaryApi (same calls as the dictionary page / KanjiPanel). Pass them through toDictionary / toKanji from lib/review/model.ts into renderCard. Show a loading state; on fetch failure, render with null and show a small error. Cache per entrySeq/literal for the page's life.
- All new strings via the cards i18n messages.

Orchestrator note: "return CONTRACT_CHANGE_REQUEST and ship the fallback" means: implement and commit the kanjiLiteral/id fallback plus the preview fix (status DONE for the shippable part), and put the batch-headword request in HANDOFF.md under a CONTRACT_CHANGE_REQUEST section (what endpoint/params/response you need and why). Do not block the ticket on it. Note: the preview fetch (one entry per opened card) is fine; also fine to populate a row's label lazily from the preview cache once an entry has been fetched, but do not issue one request per row.

## Acceptance
- [ ] A jpdb-imported vocab card (fields `{}`, entrySeq set) shows a non-empty label in the table and renders front/back with the dictionary content in the preview.
- [ ] Kanji notes and custom notes with their own fields still preview as before.
- [ ] Checkbox still only toggles bulk selection; row click only changes the preview.
- [ ] One small vitest for the label fallback (noteSummary empty → headword → id), since that logic lives in model.ts. No other UI tests required.
- [ ] make check MODULE=web green.

## Out of scope
Server /cards response shape changes (no contract edits in this ticket), bulk bar, search language, Android.

## Notes from previous attempts
(none)
