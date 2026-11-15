# AND-KEEPGOING — Android: Keep going after the queue is done + tiny "New" badge (offline)

- Phase: P9 · Model: sonnet · Risk: med
- Deps: C-26
- Branch: t/AND-KEEPGOING
- Owned paths: android/src/**/review/, android/src/**/home/ (Today), android i18n, android/src/test/
- Module(s) for CI: android

## Do (contract from C-26: core QueueLimits keepGoing, core queue item is_new)
1. The review Empty state keeps "you're done" and adds a "Keep going" button when the core builder with keepGoing=true would return cards (one cheap check). Tapping it rebuilds the queue in keep-going mode and keeps refilling until the user leaves. With nothing left even in keep going: "all caught up", no button.
2. Today screen: the Review button turns into "Keep going" (with a count if cheap) when the due queue is empty and more is available; it opens review in keep-going mode.
3. A small "New" badge on first-time cards (is_new) in the review UI (Compose/WebView overlay; design-system tokens).
4. Keep-going mode is session-scoped: it resets when leaving review, on day rollover, and on app restart. Works offline. Off the tap path (compute with prefetch).
5. i18n for all strings.

## Acceptance
- [ ] Test: Empty state offers Keep going iff the keepGoing queue is non-empty; keep-going mode serves new cards past the daily limit.
- [ ] make check MODULE=android green.

## Notes from C-26 review (binding)
- Keep going is UI state for ONE visit to the review screen: clear it when leaving review, when the study day changes from the day it started, and on app restart. The normal Review entry point and the Today counts always use keep_going=false.
- In keep-going mode counts.new is the batch, not the remaining total: don't show it as "remaining".
- more_available can be true when only the session cap is hit: word the empty state "done for now", not "done for today".
- Web Today: use more_available from the stats/Today summary (added in C-26); don't make an extra /review/queue call.
