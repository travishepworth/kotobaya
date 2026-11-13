# UI-APP — Rebuild every Android screen on the approved design system (B4)

- Phase: P9 · Model: opus (writer-senior) · Risk: high (reviewer gets screenshot set + quality bar)
- Deps: UI-DS, UI-DS-APP, BUG-8 (B2 approved by the human 2026-11-14, no changes)
- Owned paths: android/**/ui/**, nav/, theme only. May NOT change ViewModels, core, server, contracts.
- Spec: plan/handoffs/INBOX-0.1.6.md › Run 17 brief › B3/B4 (priorities and quality bar); I-5 (Media tab), I-6 (dark mode).
Status: ready once UI-DS-APP merges.
- Branch: t/UI-APP-rebuild · Module(s) for CI: android (flock /tmp/kotobaya-gradle.lock); `make screenshots-app` for the matrix
- Read: docs/DESIGN.md, plan/handoffs/UI-FOUND-APP.md, plan/handoffs/UI-DS.md, plan/handoffs/UI-DS-APP.md, INBOX-0.1.6.md lines 99-115 and 161-262.
- Deliverable: every screen rebuilt; old POC composables deleted; Media tab dropped per DESIGN.md IA; curated screenshots in docs/design-screenshots/app/screens/ (owned); ViewModel gaps -> state-layer request, never hacked.
## Acceptance
- [ ] make check MODULE=android green; make screenshots-app runs clean across the full matrix
- [ ] Every screen passes the quality bar (self-reviewed against the screenshots)
- Note (BUG-8 architect review): word_status.updated_at is not replica-stable after batched pulls; never order or display by it.
