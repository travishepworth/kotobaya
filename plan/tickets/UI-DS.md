# UI-DS — Design system, information architecture and visual plan (B2) — HUMAN GATE after merge

- Phase: P9 · Model: opus (writer-senior) · Risk: med
- Deps: UI-FOUND-WEB, UI-FOUND-APP
- Branch: t/UI-DS-design-system
- Owned paths: docs/DESIGN.md (new), design/tokens/ (new: token JSON + generator), web/src/lib/tokens.css (generated), web/src/lib/ui/ (primitives only), web/src/routes/styleguide/ (new), assets/card/card.css (tokens/dark mode only; keep the core drift test green), android theme package + a debug styleguide screen, Makefile (`tokens` target)
- Module(s) for CI: web, android (flock), core if card.css drift test lives there

## Read
- Brief: plan/handoffs/INBOX-0.1.6.md › "Run 17 brief" › Track B › B2 (authoritative; follow it in full), I-5, I-6, "UI rework notes"
- assets/card/fonts.txt (font licences)

## Do
Follow B2 in the brief exactly. Summary:
1. One token source (JSON) generating CSS custom properties (web + assets/card/card.css) and the Compose theme
   (colour, type, shape, spacing, motion). Light + dark from day one, following the system theme with manual override.
2. Japanese-first typography: JP/Latin pairing (respect fonts.txt licences), furigana/ruby sizing, kana line-height,
   fluid clamp() scale on web and sp sizes on Android that survive 200% font scale.
3. Colour: restrained, high contrast, WCAG AA in both themes; semantic tokens for grading (again/hard/good/easy), card
   states, pitch accent, deck colours. Colour never the only signal.
4. Component spec: button, card surface, list row, tabs/segmented control, chip, input, sheet/dialog, toast, empty and
   loading states, data table (web).
5. Information architecture per platform (default: drop the Android Media tab (I-5); web gets a real app shell: sidebar
   on desktop, compact/bottom nav on mobile widths).
6. docs/DESIGN.md visual plan: a section per screen (Review first) — layout at each width, hierarchy, motion,
   empty/loading/error states, with the B1 screenshot matrix as the acceptance check.
7. /styleguide web route and an Android debug styleguide screen rendering every token and component in both themes, plus
   screenshots of both (commit a curated set under docs/design-screenshots/; full matrix stays gitignored).
Do not restyle the existing screens (that is UI-WEB / UI-APP after human approval).

## Acceptance
- [ ] Token generator test: JSON → CSS and Compose outputs in sync (drift check in make check)
- [ ] Contrast test: every fg/bg semantic pair ≥ WCAG AA in light and dark
- [ ] make check web + android (+ core if card.css touched) green
- [ ] docs/DESIGN.md complete per item 6; screenshots in docs/design-screenshots/

## Out of scope
Rebuilding screens (UI-WEB, UI-APP — HUMAN-GATED).
