# HANDOFF KIND-CHIP-OVERLAP (web)
Status: DONE. `make check MODULE=web` green. Branch t/KIND-CHIP-OVERLAP-WEB.

Did web actually overlap? Yes. The chips were position:absolute at top 12px inside the scrolling `.card-body`. The body's top padding is clamp(24px, 12dvh, 112px), or 8cqh in `.compact`. On short or compact cards that was smaller than the chip row (about 32px). Scrolled text also slid under the pinned chips.

Fix, in `web/src/routes/review/+page.svelte`: the chips are now a normal-flow grid row (`.chip-row`, columns 1fr auto 1fr) above `.card-body`, outside the scroll area. Text can never sit under it, and it stays visible when the revealed card scrolls. The card text moves down by the row height. Empty slots are hidden to keep the kind chip centred.

Guard: `web/playwright/chip-overlap.spec.ts` (Playwright, same config as `make screenshots-web`). It asserts every non-empty chip's bottom is at or above the top of the scroll container, which is the visible content area. Run:
`CHROMIUM_PATH=/usr/bin/chromium pnpm exec playwright test --config playwright.config.ts chip-overlap`
Result: 6 passed.

PNGs, all in `web/screenshots/chip-overlap/` (gitignored):
- phone-390x844.png
- short-desktop-1280x600.png
- compact-revealed-390x500.png
- compact-revealed-1280x600.png
- new-card-340x600.png (3 chips)
- new-card-revealed-340x600.png (3 chips)

Caveat: the demo queue has no New card, because they unlock later. The "new-card" cases fill the real row's tag and New slots with text ("Relearning", "New") to get all three chips at once.

Existing behaviour, unchanged: on a revealed card that does not fit, the body auto-scrolls to the divider. The headword is then clipped at the body's top edge, directly under the chip row. It is no longer under a chip.
No new dependencies.
