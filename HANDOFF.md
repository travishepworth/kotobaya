# SCROLL-TOP handoff
Status: DONE (web only; android needs no change)

- web/src/routes/review/+page.svelte: removed `bringAnswerIntoView` (auto-scroll to the divider) and the `extraBottom` padding hack. On every card change/flip the `.card-body` scroller is now set to `scrollTop = 0` (again after the compact re-layout). Applies to all card kinds.
- Android: the review screen is the shared web SPA in the shell WebView (no native scroller, no `loadDataWithBaseURL` card page scrolling, no `#answer` anchor in assets/card), so the web fix covers it. No android/assets edits.
- `make check MODULE=web` green. Android check not run (no android changes). Manual check of scroll-after-Show still needed (HUMAN).
- No new deps.
