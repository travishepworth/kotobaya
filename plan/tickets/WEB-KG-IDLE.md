# WEB-KG-IDLE — keep going also ends after 10 min idle (ADR-039 amendment)

- Phase: P10 · Model: haiku (writer-light) · Risk: low · Deps: WEB-REVIEW-FLOW (merged)
- Branch: t/WEB-KG-IDLE-web
- Owned paths: web/src/lib/review/**, web/src/lib/state/review.svelte.ts, web/tests/review/**
- Module(s) for CI: web

## Do
Keep going (in-memory since WEB-REVIEW-FLOW, web/src/lib/review/keepGoingDay.ts) is cleared when no answer has been given for 10 minutes (constant), checked on each queue fetch and on app/page resume (visibilitychange). On Android the WebView can stay on Review for hours, so dispose() alone never fires.
## Acceptance
- [ ] vitest with fake timers: 11 min idle → next fetch keep_going=false; 9 min → still true.
