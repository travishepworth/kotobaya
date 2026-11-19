# WEB-30 — Web: Home/header four-part counts, refetch at next_due_at in every phase, waiting screen Keep going (ADR-049)

- Phase: P10 · Model: sonnet (writer) · Risk: med · Deps: C-30, CORE-30, audio chain merged to main
- Branch: t/WEB-30 · Owned: web/src/**, web/playwright/**
- CI: web

## Read
- docs/DECISIONS.md ADR-033, ADR-037, ADR-038, ADR-039, ADR-049; plan/handoffs/C-30.md
- contracts/api/SUMMARY.md "C-30 changes"; contracts/core/README.md "ADR-049"; core/src/commonMain/kotlin/kotobaya/contracts/queue/ReviewLoop.kt; docs/REVIEW-LOOP.md §14 (C3, C4, C6); parity scenario queue-loop-wait-before-stop

## Do
C3: review header + Home show learning / later / review / new from until_stop_parts (fallback per SUMMARY), summing to the bar total; C4: waiting screen countdown, auto-resume, Keep going when keep_going_available; C6: Home and review refetch once at later_today.next_due_at in every phase (fix today.svelte.ts #schedule which only refetches in waiting). i18n. Keep ADR-048 back/undo and word audio behaviour intact.

## Acceptance
- [ ] Playwright: reported case — after the 40th new card, review shows waiting (not done) with countdown and resumes; Home count matches header; header parts sum to bar total; screenshots viewed. make check web green.
