# LOOP-C2b — Parity scenarios for decks, listCards, struggle/drill

- Model: opus (architect) · Risk: low · Deps: LOOP-C2 · Gradle: yes (server record + android run)
- Owned: contracts/api/shell/parity/** (new files), fixtures/shell-parity/golden/** (new), contracts/api/shell/check_shell.py (tags only), android parity tests that are hand-written today (DeckParityTest, StruggleParityTest, listCards checks) — convert to golden-driven where they duplicate
## Do
Add parity scenarios: decks (list/get/items, create user/smart, update, delete, add/put/remove items), listCards (incl. smart deck + negated smart deck), struggle (hot words, hot kanji, leeches) and a hot_words drill. Record goldens with the server runner (RECORD=1), run them on android (LocalParityTest), retire the hand-written duplicates.
## Acceptance
- [ ] contracts, server, android green; no EXPECT_DISAGREES entries.
