# AND-28 — Android local API: until_stop, kanji minting/backfill, Deck.progress, kanji_parts

- Phase: P10 · Model: sonnet (writer) · Risk: med · Gradle-heavy
- Deps: CORE-28 (SRV-28 for parity check)
- Branch: t/AND-28-android
- Owned paths: android/**
- Module(s) for CI: android (Gradle lock; `rm -rf /tmp/parity*` after)

## Do
Same as SRV-28 on the device local API (LocalQueueLoader, materialize path, DeckViews/DeckRoutes, kanji_parts); getDeckCoverage local if C-28 chose so.
## Acceptance
- [ ] Shell parity scenarios match server; materialize race tests still green.
