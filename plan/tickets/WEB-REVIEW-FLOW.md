# WEB-REVIEW-FLOW — seamless due-card slot-in, keep going per visit, kanji example rows

- Phase: P10 · Model: sonnet (writer) · Risk: med
- Deps: none (no contract change)
- Branch: t/WEB-REVIEW-FLOW-web
- Owned paths: web/src/lib/state/review.svelte.ts, web/src/lib/review/**, web/src/routes/review/**, web/src/lib/ui/** (BackExtras / kanji example rows only), assets/card/**, web/tests/**, web/playwright/**, web/mock/**
- Module(s) for CI: web

## Read
ADR-033, ADR-037, ADR-039, ADR-041(3); docs/REVIEW-LOOP.md client rules.

## Do
1. Seamless (user: "a screen pops up … it should just give you the card again without any stopping"): when a learning card comes due mid-session it must appear as the next card with no loading/settling screen. Today advance() (review.svelte.ts ~L802) clears the list when dueFirstPassed() and refresh() fetches while nothing is on screen (model.ts ~L161). Fix: never blank the screen while any card can be shown — prefetch the queue in the background shortly before `later_today.next_due_at` (and after each answer), then splice the server's order into the local list at the next advance; show the next local card immediately if a fetch is still in flight. Keep no-learn-ahead (ADR-033): never show a learning card before its due_at. Loading state only on first load / truly empty list.
2. ADR-039: keep going lasts one review visit. Remove study-day persistence (web/src/lib/review/keepGoingDay.ts, localStorage `kotobaya.keepGoingDay`); flag resets on entering Review and app restart; stays on for the whole visit.
3. ADR-041(3): kanji-card example word rows: expression + reading on one line (no wrap/stack), meaning ellipsizes first (min-width 0, text-overflow). Check 360px width.
## Acceptance
- [ ] vitest: list with 3 cards + a learning card due during card 2 → no loading state emitted, due card shown next after it is due, never before.
- [ ] vitest: keep going cleared on new visit; persists across answers within a visit.
- [ ] Screenshot 360x800 of kanji back with long meanings viewed (memory: verify UI visually).
