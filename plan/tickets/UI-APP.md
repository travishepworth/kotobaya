# UI-APP — Rebuild every Android screen on the approved design system (B4) — HUMAN-GATED

- Phase: P9 · Model: opus (writer-senior) · Risk: high (reviewer gets screenshot set + quality bar)
- Deps: UI-DS + human approval; BUG-8
- Owned paths: android/**/ui/**, nav/, theme only. May NOT change ViewModels, core, server, contracts.
- Spec: plan/handoffs/INBOX-0.1.6.md › Run 17 brief › B3/B4 (priorities and quality bar); I-5 (Media tab), I-6 (dark mode).
Status: HUMAN-GATED — waiting for human approval of DESIGN.md + styleguide. Ticket to be finalised with the human's changes.
- Note (BUG-8 architect review): word_status.updated_at is not replica-stable after batched pulls; never order or display by it.
