# WEB-KEEPGOING — Web: Keep going after the queue is done + tiny "New" badge on first-time cards

- Phase: P9 · Model: sonnet · Risk: low
- Deps: C-26
- Branch: t/WEB-KEEPGOING
- Owned paths: web/src/routes/review/, web/src/routes/+page.svelte (Today), web/src/lib/state/ (review/queue state), web i18n, web tests
- Module(s) for CI: web

## Do (contract from C-26: GET /review/queue keep_going, the "more available" signal, QueueItem.is_new)
1. Review "done" state stays as now ("you're done"), plus a "Keep going" button when more is available. It switches the session to keep_going=true and keeps fetching batches until the user leaves. When nothing is left even with keep going, show "all caught up" with no button.
2. Today page: when the due queue is empty but more is available, the review CTA becomes "Keep going" and opens review in keep-going mode (e.g. /review?more=1).
3. The review card shows a small, unobtrusive "New" badge when item.is_new (front and back, design-system tokens).
4. The session's keep-going mode resets on leaving review / a new day.
5. i18n for all strings.

## Acceptance
- [ ] make check MODULE=web green.
