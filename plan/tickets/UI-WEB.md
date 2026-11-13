# UI-WEB — Rebuild every web screen on the approved design system (B3)

- Phase: P9 · Model: opus (writer-senior) · Risk: high (reviewer gets screenshot set + quality bar)
- Deps: UI-DS + human approval of docs/DESIGN.md and /styleguide; C-19 (for FEAT-x part)
- Owned paths: web/src/routes/**, web/src/lib/ui/** only. May NOT change state modules, core, server, contracts.
- Spec: plan/handoffs/INBOX-0.1.6.md › Run 17 brief › B3/B4 (priorities and non-negotiable quality bar), plus FEAT-x (BACKLOG) folded in.
Status: B2 APPROVED by the human 2026-11-14, no changes requested. Ready.
- Branch: t/UI-WEB-rebuild · Module(s) for CI: web (`make screenshots-web` for the matrix)
- Read: docs/DESIGN.md, /styleguide (web/src/routes/styleguide), plan/handoffs/UI-FOUND-WEB.md, plan/handoffs/UI-DS.md, plan/handoffs/C-19.md, BACKLOG FEAT-x, INBOX-0.1.6.md lines 161-262.
- Deliverable: every route rebuilt; old POC view code deleted; curated screenshots in docs/design-screenshots/web/screens/ (owned); state-layer gaps -> CONTRACT_CHANGE_REQUEST, never hacked.
## Acceptance
- [ ] make check MODULE=web green; make screenshots-web runs clean across the full matrix
- [ ] Every screen passes the quality bar (self-reviewed against the screenshots), FEAT-x WYSIWYG override on /notes and the card preview
