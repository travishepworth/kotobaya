# AND-LABELS — Android shows raw keys/placeholders instead of words (deck view, search); merge search

- Phase: P9 hotfix · Model: sonnet · Risk: med
- Deps: none (AND-DECKCONTENT runs in parallel; don't touch its data layer except reading)
- Branch: t/AND-LABELS-words
- Owned paths (only edit these): android/src/**/search/, android/src/**/decks/ (UI + view models; not the content installer), android/src/**/ui/components (only if needed), android i18n strings, android/src/test/ for these
- Module(s) for CI: android

## Symptoms (live 0.1.12, phone user 10)
1. Deck view for non-media decks ("Test") shows the item key, e.g. "e:1358280", instead of the word. deck_item rows are entry_seq items (item_key "e:<seq>", note_id null). The label must come from the on-device dictionary (files/dict/dictionary.db, installed) → headword + reading + first gloss; fall back to note fields, then key only as last resort (mirror web rowLabel order: fields → headword → kanji → id, see BUG-7).
2. Search → "My cards" results display the literal words "reading" / "meaning" (placeholder labels) instead of the card's reading and meaning.
3. Search is split into Dictionary and My cards; the user expects ONE search. Provisional decision (ADR, see below): one query box, one result list: dictionary entries in dictionary order, each marked "In my cards" (with status/deck) when the user has a card/note for it; then the user's notes that match but aren't dictionary-linked. Tapping opens the existing detail. Keep the core search parser for card-query syntax (if the query parses as a card search with operators, show card results first). Offline must still work.
4. Settings screen: client reports "GET /meta failed: network" x6 (route=settings) while sync works on the same device. Find why (different client/base URL/timeout/called before pairing loaded?) and fix if in owned paths; else report in HANDOFF.

Phone DB copy (PRIVATE, read-only): /tmp/claude-1000/-home-travmonkey-repos-kotobaya/c6911de0-217a-4b52-b8c2-af6c5b745fd0/scratchpad/dbcopy/kotobaya-app.db. Dictionary: pull with adb exec-out run-as com.ichi2.anki --user 10 cat files/dict/dictionary.db if needed (keep in scratchpad).

## Read
- docs/SPEC.md line ~249 (search across dictionary, notes, sentences), plan/handoffs/AND-SEARCH.md, plan/handoffs/AND-DECKS.md, plan/handoffs/AND-CARDHTML.md (how ReviewBackend got Dictionary lookups — reuse that).
- docs/DESIGN.md for list rows.

## Acceptance
- [ ] Test: deck item label for an entry_seq item resolves to headword (dictionary present) and never shows "e:" when the dictionary has the entry.
- [ ] Test: search result row shows the real reading/meaning values (regression for the placeholder bug).
- [ ] Merged search: view-model test that one query yields dictionary + my-card results in the specified order with the in-my-cards flag.
- [ ] Every new string via i18n. make check MODULE=android green.

## Out of scope
Deck content download (AND-DECKCONTENT), Takoboto provider, web, server.
