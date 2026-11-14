# JLPT-1b — Server: deck-publish runner + /deck-lists counts from vocab lists

- Phase: P10 (0.1.10) · Model: opus · Risk: high
- Deps: C-21 (merged 1578783)
- Branch: t/JLPT-1b-server-deck-publish
- Owned paths (only edit these): server/** , acceptance/** only if a shared test helper needs it
- Module(s) for CI: server

## Read
- Contracts: contracts/db/VOCAB_LISTS.md §1, §4.1–4.2, §5 (normative), §6 worked example, §7 edge cases; contracts/db/V3__vocab_list.sql; contracts/db/SNAPSHOT.md; contracts/db/media/deck-publish.v1.schema.json; openapi `/deck-lists`, createDeck from a list, JobResultDeckBuild; contracts/sync/SYNC.md sections on deck.content_published
- Existing code to mirror: the media-publish runner (MEDIA_PIPELINE.md 3) — DeckPublishRunner should reuse its blob/OpApply path.
- docs/DECISIONS.md ADR-022

## Live context
server/src/main/kotlin/kotobaya/decks/DeckLists.kt:18 hard-codes JLPT item_count 0 ("no JLPT vocabulary source yet"); DeckApi.kt:124 enqueues deck-build (keep).

## Do
- Make sure the server's migrations pick up V3__vocab_list.sql (however contracts/db migrations reach the server today).
- DeckLists: counts per §4.1 (JLPT from vocab lists, frequency bands from freq lists), resolution identical to §4.2; hide/422 rules per the openapi.
- DeckPublishRunner (§5.2): sole claimer of `deck-publish`; validate payload; install deck_item rows ONLY via OpApply emitting deck.content_published (CLAUDE.md rule 8); idempotent on retry; fail cleanly per §5.2 checks.
- Wire it in Main.kt next to the media-publish runner.

## Acceptance (each → named test)
- [ ] DecksApiTest: with vocab-list fixture rows loaded, GET /deck-lists returns JLPT item_count > 0 matching the fixture; createDeck from a JLPT key enqueues deck-build.
- [ ] DeckPublishRunner: §6 worked example end to end → deck_item rows equal archive, one deck.content_published op; re-running the same job is idempotent (no duplicate op/rows); out-of-order/stale seq handled per §5.2.
- [ ] make check MODULE=server green (Gradle under `flock /tmp/kotobaya-gradle.lock`).

## Out of scope
Worker loader/deck-build (JLPT-1a). Web UI changes.
