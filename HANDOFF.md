# HANDOFF WEB-REVIEW-INFO
Status: DONE
- New `web/src/routes/review/BackExtras.svelte`, rendered in `+page.svelte` after reveal.
  - Kanji cards (note.kanjiLiteral set): up to 3 `kanjiExamples` (headword, reading, ON/KUN chip, gloss), each linking to the dictionary entry (new tab, so the session is kept).
  - Other cards: up to 3 `decks` chips linking to `/decks/{id}`, plus "+N more" from `deckCount`.
  - Absent fields render nothing (older servers).
- i18n: `rv.examples`, `rv.tag.on/kun`, `rv.decks`, `rv.decks.more` in review/messages.ts.
- Test: `web/tests/review/BackExtras.test.ts` (kanji block, deck chips + more, absent fields).
- `make check MODULE=web` green. No new deps. review.svelte.ts untouched.
