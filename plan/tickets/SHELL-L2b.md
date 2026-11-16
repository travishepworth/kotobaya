# SHELL-L2b — Local queue follow-ups: keep-going batch = limit, kanji_examples batching, headword cache

- Model: sonnet (writer) · Risk: med · Deps: SHELL-L2 · Gradle-heavy: yes
- Owned: android/src/main/kotlin/kotobaya/android/queue/LocalQueueLoader.kt, android/src/main/kotlin/kotobaya/android/shell/api/review/**, their tests
## From the SHELL-L2 review (APPROVE, minors)
1. Add `keepGoingNewBatch` to `LocalQueueLoader.load` and pass the request `limit` (server uses batch = limit). Today: phone keep-going page ≤10 new vs server 20; limit<10 creates 10 notes but serves `limit`; pending window 36 vs 56, so `counts.new` with keep_going can be lower than without when new_per_day was raised mid-visit (violates "same counts on all clients", ADR-033). Native callers keep today's value.
3. kanji_examples: load word_status / live note / deck items for all candidate seqs in one `IN (...)` read per table (chunk ≤ 500) instead of 3 lookups per word.
4. `headwordOf`: per-request cache map.
## Acceptance
- [ ] Parity/test: keep_going with limit=20 serves up to 20 new (golden-equivalent to server); counts equal with and without keep_going after raising new_per_day mid-visit.
- [ ] make check MODULE=android green.
- Also: the android parity tests leak /tmp/parity<random> dirs (2.7 MB each, 60 found) — delete them in test teardown. /tmp is a usrquota tmpfs; leaks caused a red L3 check (Disk quota exceeded).
