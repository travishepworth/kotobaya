# BUG-7b — Notes page: readable list, preview-first, dictionary values (BUG-7 addendum)

- Phase: P7 · Model: sonnet · Risk: low
- Deps: BUG-7 (merged)
- Branch: t/BUG-7b-notes-view
- Owned paths (only edit these): web/src/lib/notes/, web/src/routes/notes/, web/src/lib/cards/model.ts, web/src/lib/cards/model.test.ts, web/src/lib/cards/messages.ts (only if a shared string belongs there; notes strings go in the notes i18n messages)
- Module(s) for CI: web

## Read
- plan/tickets/BUG-7.md (incl. the Addendum section — this ticket is that addendum) and plan/handoffs/BUG-7.md
- web/src/lib/cards/{model.ts,api.ts,CardPreview.svelte} (rowLabel, cached DictEntry/Kanji fetch, toDictionary/toKanji usage from BUG-7 — reuse, do not duplicate; you may import from lib/cards/api.ts without editing it)
- web/src/lib/notes/*, web/src/routes/notes/*, web/src/lib/review/model.ts (toDictionary/toKanji), KanjiPanel for the Kanji fetch
- contracts/api/openapi.yaml: Note, DictEntry, Kanji (read-only)

## User report
"there needs to be a way to actually view my cards/notes, i.e. what I will review. All it states is 'leave empty to render from the dictionary', but I want to see everything."

## Do
- NoteTable.svelte:61 shows `noteSummary(fields) || n.id`, so the ~8,776 jpdb notes (fields `{}`) list as UUIDs. Use the same headword fallback as the card browser (shared helper rowLabel in lib/cards/model.ts).
- NoteEditor: for referenced notes, each empty field shows the dictionary-supplied value as a read-only "from dictionary" display or as the textarea placeholder, so the user sees the content and knows typing overrides it. Keep the refFieldHint.
- NoteEditor.svelte:72 passes `kanji: null`; fetch Kanji for kanjiLiteral notes (as in KanjiPanel / BUG-7's cached api) and pass toKanji(...). Fetch the DictEntry for entrySeq notes too. Same fix as CardPreview.
- Move the Preview above the edit form (or tabs Preview | Edit, default Preview) so selecting a note shows the rendered card first. The preview lets the user switch between the note's live card kinds (Meaning / Reading / ...), not just one.
- Table: add Reading and Meaning (first gloss) columns for referenced notes. Fill them from a page-level headword map (seq -> {headword, reading, gloss}) that is populated lazily from entries fetched when a note is opened. NO per-row requests. A batch endpoint is coming (C-18, then BUG-7c wires it), so structure the map so BUG-7c can fill it in one call; do not raise a CONTRACT_CHANGE_REQUEST (already filed as C-18).
- All new strings via i18n messages. Loading state and small error on fetch failure (render with null).

## Acceptance
- [ ] Selecting a jpdb vocab note on /notes shows the rendered card first, with dictionary content.
- [ ] Empty override fields visibly show the dictionary value.
- [ ] Kanji note preview shows keyword/readings.
- [ ] Notes table shows word/reading/meaning instead of UUIDs (for opened notes now; all rows once BUG-7c lands; id fallback otherwise).
- [ ] Extend the rowLabel vitest only if you change it. No other UI tests required.
- [ ] make check MODULE=web green.

## Out of scope
Server, contracts, /cards behaviour (beyond the shared helper), Android.

## Addendum 2 (user, run 16): kanji preview (user-reported, verified)

Symptom: on /notes, a jpdb kanji note's preview shows an empty front and a back with only the kanji.
Cause: jpdb kanji notes store only `{"literal": "緊"}`; keyword/readings render from ref.kanji (CardRenderer C-2). NoteEditor.svelte:72 passes `kanji: null`, and it previews `note.cards[0]`, which for these notes is often `writing` (front = {{keyword}} → empty, back = literal + empty readings). Live counts: Kanji keyword 2185, writing 754.
Fix (in addition to Addendum 1's kanji fetch): fetch GET /kanji/{literal} and pass toKanji(...) in both NoteEditor and CardPreview; add a kind switcher over the note's live cards (default: the card the user clicked on /cards, else the first default-template kind).
Acceptance:
- [ ] jpdb kanji note, writing card: front shows the keyword; back shows kanji + on/kun readings.
- [ ] keyword card: front shows the kanji; back shows keyword + readings.
- [ ] Check reviews too: confirm /review renders the same two cards correctly (QueueItem.kanji); if not, fix it here.

(Orchestrator: arrived while BUG-7b's writer was running and overlapping NoteEditor; sent to that writer instead of a parallel ticket. BUG-7b paths widened to lib/cards/CardPreview.svelte, lib/cards/api.ts, routes/cards/, routes/review/, lib/review/.)
