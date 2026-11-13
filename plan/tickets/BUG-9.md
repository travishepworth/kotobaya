# BUG-9 — Stats memory and speed: one bounded pass instead of three full replays (I-1 real fix)

- Phase: P7 · Model: sonnet (writer) · Risk: med
- Deps: HOTFIX-0.1.7
- Branch: t/BUG-9-stats-memory
- Owned paths: server/src/main/kotlin/kotobaya/stats/, server stats tests, server/Dockerfile (JAVA_TOOL_OPTIONS default only)
- Module(s) for CI: server (flock /tmp/kotobaya-gradle.lock)

## Read
- Evidence: plan/handoffs/INBOX-0.1.6.md › I-1
- Contracts: contracts/api (stats operations; responses must not change), contracts/db (if you add a column, STOP: CONTRACT_CHANGE_REQUEST)

## Do
`scheduledReviews` (StatsQueries.kt:135) loads and replays the full history of every card reviewed in the window,
and retention, calibration and heatmap do this in parallel (~3 × 78,597 reviews / 9,531 cards → OOM at 256 MB heap).
Compute retention and calibration in one bounded pass: stream card by card (keyset/cursor, fetchSize) and replay per
card, sharing the result between panels (short-lived cache keyed by user+window, or a single computation both use).
Bound concurrency of heavy stats computations (soft memory guard: e.g. a semaphore of 1). Heatmap and known-words
must not materialise full history either. Set a sane default heap in server/Dockerfile
(`ENV JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=70`), consistent with the chart default from HOTFIX-0.1.7.

## Acceptance
- [ ] Golden equality: new outputs == current outputs for every /stats/* endpoint on the fixture set (test)
- [ ] Testcontainers test with seeded volume (~80k reviews, ~10k cards): each /stats/* < 1 s, run with a constrained heap (e.g. -Xmx256m for the test JVM or measured allocation) without OOM; report numbers
- [ ] make check MODULE=server green

## Out of scope
Hot words/kanji (BUG-10); web stats UI.
