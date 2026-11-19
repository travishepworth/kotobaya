# SRV-28 — Server: until_stop, kanji minting/backfill, Deck.progress, kanji_parts

- Phase: P10 · Model: sonnet (writer) · Risk: med
- Deps: CORE-28
- Branch: t/SRV-28-server
- Owned paths: server/**
- Module(s) for CI: server

## Do
Wire C-28/CORE-28 into server: `loop.until_stop`; materialize path mints missing kanji notes (op transaction, as C-27 auto_kanji, idempotent, respects user-deleted kanji notes); `Deck.progress` on listDecks/getDeck (batched SQL, no N+1); `QueueItem.kanji_parts` batched with QueueExtras.
## Acceptance
- [ ] Parity goldens green; test: drawn deckless vocab mints kanji notes once (idempotent on re-fetch); Deck.progress counts on fixture (retired reading cards ignored, known words counted).
