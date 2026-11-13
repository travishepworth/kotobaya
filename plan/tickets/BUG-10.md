# BUG-10 — /words/hot, /kanji/hot and /kanji/{literal}/words are slow (I-2)

- Phase: P7 · Model: sonnet (writer) · Risk: med
- Deps: HOTFIX-0.1.7
- Branch: t/BUG-10-hot-queries
- Owned paths: server hot words/kanji handlers + queries (server/src/main/kotlin/kotobaya/** files for listHotWords, listHotKanji, listKanjiWords), server Flyway migrations for new indexes ONLY if contracts/db allows server-side index migrations (check how earlier perf fixes such as BUG-1 added indexes; if an index requires a contracts/db change return CONTRACT_CHANGE_REQUEST), their tests
- Module(s) for CI: server (flock /tmp/kotobaya-gradle.lock)

## Read
- Evidence: plan/handoffs/INBOX-0.1.6.md › I-2
- Contracts: contracts/api (responses unchanged), contracts/db

## Do
GET /words/hot and /kanji/hot take ~1.5 s on every dashboard load (10 s under load); GET /kanji/{literal}/words takes
1.4 s. Run EXPLAIN (ANALYZE) on a seeded volume (~80k reviews, ~10k cards, full JMdict-sized ref data where relevant),
then add indexes, rewrite the queries, or add a cached rollup (invalidated on relevant ops). Record the before/after
plans briefly in HANDOFF.

## Acceptance
- [ ] Each endpoint < 200 ms on the seeded Testcontainers volume (test asserts a generous bound, HANDOFF reports real numbers)
- [ ] Outputs unchanged on the fixture set (golden/equality test)
- [ ] make check MODULE=server green

## Out of scope
Stats endpoints (BUG-9); web dashboard.
