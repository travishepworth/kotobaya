# LOOP-C2 — Fix parity scenario queue-loop-cap-keep-going-backlog expectations

- Model: opus (architect) · Risk: low · Deps: LOOP-SRV, LOOP-AND · Gradle: yes (server + android parity runs)
- Owned: contracts/api/shell/parity/queue-loop-cap-keep-going-backlog.json, fixtures/shell-parity/golden/queue-loop-cap-keep-going-backlog.json, server parity test allowlist (EXPECT_DISAGREES), android parity equivalents
## Why
LOOP-SRV found the hand-derived `expect` orders review cards by answer order, but Easy-graduated seed cards get fuzzed intervals (6 vs 7 days) so dueAt order differs (server is correct: most overdue first by dueAt). Fix the scenario (seed explicit due dates or expect by dueAt), re-record, remove the EXPECT_DISAGREES allowlist entry on both server and android.
## Acceptance
- [ ] contracts, server, android checks green with no allowlist entry.
- Also (from SHELL-L4 CONTRACT_CHANGE_REQUEST, additive): add parity scenarios bulk-cards-local, word-status-set-clear, note-update-delete, card-get-reviews; record goldens (server) and run them on android.
- Also: clarify SHELL.md §4.3 createNote row: createNote creates NO add_kanji kanji notes (server NoteApi emits note.created, card.created, deck.item_put only; kanji notes come from C-27 materialization).
- Also: deck parity scenarios (list/get/items, create user/smart, update, delete, add/put/remove items) + listCards scenario; record goldens on server (SHELL-P1 runner) and run on android (DeckParityTest currently hand-written).
- Also: struggle/drill parity scenarios (StruggleParityTest is hand-written).
