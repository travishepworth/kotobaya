# FEAT-1 — Clickable kanji: words using a kanji, by frequency and in your decks

- Phase: P8 · Model: sonnet · Risk: med
- Deps: C-17
- Branch: t/FEAT-1-kanji-words
- Owned paths (only edit these): `server/src/main/kotlin/kotobaya/dict/`, `server/src/test/kotlin/kotobaya/dict/`,
  `server/src/main/kotlin/kotobaya/server/` (routing/wiring only), `web/src/routes/dictionary/`, `web/src/lib/dictionary/`
- Module(s) for CI: server, web

## Read
- Contracts: `contracts/api/openapi.yaml` operation `listKanjiWords` (from C-17), `getKanji`, `DictEntrySummary`
- Existing: `server/.../dict/DictApi.kt` (`/kanji/{literal}` handler, entry-summary overlay query — reuse it),
  `web/src/routes/dictionary/+page.svelte` (kanji line ~L246)

## Do
1. Server: implement `listKanjiWords` per contract. One query: `ref.entry_kanji` (by literal) → `ref.dict_entry`
   ordered per contract, keyset cursor; overlay status/note_id the same way `searchDict` does; `decks` via
   `app.deck_item.entry_seq` join `app.deck` (live rows, caller's decks). Remove from the 501 allowlist if listed.
2. Web: in the dictionary entry view, each kanji in the "Kanji" line becomes a button. Clicking opens a kanji
   panel: `getKanji` details (meanings, on/kun, strokes, JLPT, grade) + two tabs — **Most frequent**
   (`listKanjiWords`) and **In my decks** (`mine=true`), with a deck filter dropdown (`deck_id`). Each word shows
   headword, reading, gloss, freq rank, status, and deck chips (with in-deck occurrence count). Clicking a word
   opens that entry in the dictionary view; "load more" uses `next_cursor`. Strings via i18n.

## Acceptance
- [ ] Server test: seeded entries containing 績 come back in `freq_rank` order; `mine=true` and `deck_id`
      filter correctly and `deck_id` orders by `occurrence_count`; `decks[]` excludes deleted items/decks and other users' decks;
      cursor paging returns every entry exactly once; 422 on two code points; 401 without auth.
- [ ] Web: kanji on the dictionary entry view are clickable and show the panel (no tests required).
- [ ] `make check MODULE=server` and `make check MODULE=web` green.

## Out of scope
Clickable kanji inside rendered review cards (core renderer + Android WebView) → follow-up. Android kanji screen.
