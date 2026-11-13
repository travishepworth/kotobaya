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

## Addendum (user, run 16)

User addendum to BUG-7: widen it to /notes as well. Add web/src/lib/notes/ and web/src/routes/notes/ to its paths.

User report: "there needs to be a way to actually view my cards/notes, i.e. what I will review. All it states is 'leave empty to render from the dictionary', but I want to see everything."

Verified on /notes:
- NoteTable.svelte:61 shows `noteSummary(fields) || n.id`, so the ~8,776 jpdb notes (fields `{}`) list as UUIDs. Use the same headword fallback as the card browser (shared helper in lib/cards/model.ts).
- NoteEditor: for referenced notes, each empty field should show the dictionary-supplied value as a read-only "from dictionary" display or the textarea placeholder, so the user sees the content and knows typing overrides it. Keep the refFieldHint.
- NoteEditor.svelte:72 passes `kanji: null`; fetch Kanji for kanjiLiteral notes (as in KanjiPanel) and pass toKanji(...). Same fix as CardPreview.
- Move the Preview above the edit form (or make it a tab: Preview | Edit, default Preview) so selecting a note shows the rendered card first. Preview should let the user switch between the note's live card kinds (Meaning / Reading / …), not just one.
- Table: add Reading and Meaning (first gloss) columns for referenced notes, filled from the same headword batch lookup, so the list is readable without opening each note. If a batch lookup needs a new endpoint, return CONTRACT_CHANGE_REQUEST (headword/reading/gloss per entry_seq list) and ship the per-row fallback meanwhile.

Extra acceptance:
- [ ] Selecting a jpdb vocab note on /notes shows the rendered card first, with dictionary content.
- [ ] Empty override fields visibly show the dictionary value.
- [ ] Kanji note preview shows keyword/readings.
- [ ] Notes table shows word/reading/meaning instead of UUIDs.

Note: the batch lookup is now needed by both pages (headword + reading + first gloss per entry_seq). If no existing endpoint serves a list of entry_seqs, the contract ticket for it is in scope this run: architect, additive. Then wire both tables to it.

(Orchestrator: BUG-7 had already merged when this arrived; the addendum ships as BUG-7b. Batch wiring for both tables is BUG-7c after C-18.)

## Addendum 2 (user, run 16): kanji preview (user-reported, verified)

Symptom: on /notes, a jpdb kanji note's preview shows an empty front and a back with only the kanji.
Cause: jpdb kanji notes store only `{"literal": "緊"}`; keyword/readings render from ref.kanji (CardRenderer C-2). NoteEditor.svelte:72 passes `kanji: null`, and it previews `note.cards[0]`, which for these notes is often `writing` (front = {{keyword}} → empty, back = literal + empty readings). Live counts: Kanji keyword 2185, writing 754.
Fix (in addition to Addendum 1's kanji fetch): fetch GET /kanji/{literal} and pass toKanji(...) in both NoteEditor and CardPreview; add a kind switcher over the note's live cards (default: the card the user clicked on /cards, else the first default-template kind).
Acceptance:
- [ ] jpdb kanji note, writing card: front shows the keyword; back shows kanji + on/kun readings.
- [ ] keyword card: front shows the kanji; back shows keyword + readings.
- [ ] Check reviews too: confirm /review renders the same two cards correctly (QueueItem.kanji); if not, fix it here.

(Orchestrator: arrived while BUG-7b's writer was running and overlapping NoteEditor; sent to that writer instead of a parallel ticket. BUG-7b paths widened to lib/cards/CardPreview.svelte, lib/cards/api.ts, routes/cards/, routes/review/, lib/review/.)
