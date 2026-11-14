# AND-REVIEW-SNAPPY — Make answering a review feel instant on the phone

- Phase: P9 hotfix · Model: opus · Risk: high (review durability)
- Deps: none (main @ 9144bd69)
- Branch: t/AND-REVIEW-SNAPPY-instant
- Owned paths (only edit these): android/src/**/review/ (ReviewSession, ReviewViewModel, ReviewScreen, card WebView host), android/src/**/queue/ only if the next-card cost lives there (say so), android/src/test/ for these
- Module(s) for CI: android

## Symptom
User: reviews aren't instant. Tapping a grade → next card has visible lag on the phone (0.1.13 debuggable build, ~90k review_log, ~6.6k notes, 10.7k deck items). No timing logs exist, so the cost split is unknown. Expect roughly 40x JVM cost on device (debuggable ART). Phone DB copy (PRIVATE, read-only): /tmp/claude-1000/-home-travmonkey-repos-kotobaya/c6911de0-217a-4b52-b8c2-af6c5b745fd0/scratchpad/dbcopy/kotobaya-app.db.

## Do
1. Instrument: one log line per answer (tag KotobayaReview) with ms for mint+commit op tx, card cache/replay update, next-card selection, render (core renderer → HTML), WebView load/first paint. Same for reveal (front → back).
2. Measure on the JVM with the DB copy. Fix whatever scales with collection size (full queue rebuild per answer, todayReviews re-query, word-status recompute, etc.): next card should come from the in-memory session queue, with incremental updates.
3. Perceived latency: advance the UI to the next card right after the grade tap, and commit the op off the main thread. Durability must hold: the review is committed (DurableDriver, synchronous=FULL) before the session forgets it; undo still works; if the commit fails, surface it and roll the UI back. Pre-render the next card's HTML (and the back side) while the current card is showing; reuse one WebView and swap content (no recreation); no main-thread DB.
4. Keep the existing durability test green and extend it for the async commit (kill between tap and commit → no lost or duplicated review).

## Acceptance
- [ ] Timing log line exists (KotobayaReview, per phase).
- [ ] Test: async commit — the review is persisted exactly once, with the same ordering, under rapid successive answers and undo.
- [ ] Test/bench: answer → next card state on the JVM < 20 ms with the real-DB-sized collection, and no per-answer query that scales with review_log size.
- [ ] make check MODULE=android green.

## Out of scope
Takoboto provider (AND-TAKO-PERF2), release build type, server.
