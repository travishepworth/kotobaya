# HANDOFF UI-DS

STATUS: DONE
BRANCH: t/UI-DS-design-system
CHECK: `make check MODULE=web` → green (token tests 11, vitest 37, svelte-check 0); `core` → green; `android` → green

## Changed (≤ 6 lines)
- design/tokens/: tokens.json (single source), generate.mjs, tests; `make tokens`, `make tokens-check` (now run first by check-web)
- Generated: web/src/lib/tokens.css (legacy POC block kept verbatim, so screens are unchanged), card.css token block + dark, android ui/theme/Tokens.kt
- web/src/lib/ui/: 18 primitives (button … data table, grade bar, pitch, app shell), theme override, fonts, i18n
- /styleguide route (+ added to the Playwright harness); docs/DESIGN.md (both platforms, IA, every screen, Review first)
- Open: `make dev-ui`, then http://localhost:3000/styleguide · Screenshots: docs/design-screenshots/web/

## Acceptance → tests
- Generator drift → tokens.test.mjs "generated outputs are in sync…"
- Contrast AA → "every declared fg/bg pair meets WCAG AA…" + "every semantic … pairing is declared"

## New dependencies
- @fontsource-variable/noto-sans-jp, noto-serif-jp @5.3.1 (OFL): self-hosted JP fonts per fonts.txt

## For the reviewer (≤ 3 lines)
- card.css: colours are now `--kb-card-*` and sizes fluid, so the card looks a little different on web and Android.
- check-web now depends on tokens-check (a one-line change outside the `tokens` target). Breakpoints: sidebar ≥1024, rail 600–1023.
- Not done (outside my scope): `.kb-audio` is 32 px, below the touch target. Android theme not wired up yet (UI-APP).
