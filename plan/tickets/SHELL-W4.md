# SHELL-W4 — SPA mobile gaps audit and fixes

- Phase: SHELL · Model: sonnet · Risk: low
- Deps: SHELL-W1
- Branch: t/SHELL-W4-web-mobile-gaps
- Owned paths (only edit these): web/src/lib/ui/**, web/src/routes/**/+page.svelte (CSS and markup only), web/playwright/**, web/screenshots/shell/**
- Module(s) for CI: web
- Gradle-heavy: no · First phone test: no

## Read
- Spec: Android app; Web app; Sync and offline
- Design: docs/ANDROID-SHELL.md (ADR-036) — sections 4
- Contracts: none
- Decisions: ADR-036; memory rules: verify UI visually; keep going = unlimited; no learn-ahead (waiting screen)

## Do
Screenshot every route the phone keeps (Home, Review incl. waiting and done/keep-going states, Search, Dictionary entry, Browse, Decks, Struggle,
Settings) at 360x800 and 412x915 with the shell mock. Fix only: horizontal overflow, tap targets under 44 px, content under the bottom bar,
hover-only affordances, review grade bar reachability with the IME closed/open, text selection on card content (must stay selectable), pull-to-refresh
/overscroll (`overscroll-behavior: none` on the shell root), `100vh` → `100dvh`. List anything larger in HANDOFF.md as follow-ups; do not redesign.

## Acceptance (each bullet → at least one named test)
- [ ] Playwright layout test per listed route and size: no element wider than the viewport; primary actions inside the viewport; tag row does not overlap card text (0.1.27 regression)
- [ ] Long review stream test with the mock: 60 cards with keep going never shows an end screen while items remain; waiting screen appears only with `next_learning_due_at`
- [ ] Screenshots committed under web/screenshots/shell/

## Out of scope
New features, device UI (W2), navigation (W3).

## Notes from previous attempts
