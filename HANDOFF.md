# HANDOFF KIND-CHIP-OVERLAP (web)
Status: DONE. `make check MODULE=web` green.

Did web actually overlap? Yes, in some cases. The three chips (.tag, .kind-badge, .new-badge) were position:absolute at top 12px inside the scrolling `.card-body`. Top padding is clamp(24px, 12dvh, 112px), and 8cqh in `.compact`, so on short windows or compact (revealed, tall) cards the padding was smaller than the chip row (about 32px). Scrolled long content also slid under the chips. At normal heights, 12dvh cleared them.

Fix: `web/src/routes/review/+page.svelte`. The chips now sit in a normal-flow grid row (`.chip-row`, columns 1fr auto 1fr) as the first child of `.card-body`, rendered only when at least one chip exists. Its negative top margin cancels the body's top padding via a `--body-pt` var (also set for `.compact`), so the chips stay at the top edge and the card text starts below the row. Empty slots are visibility:hidden to keep the kind chip centred.

Guard: no DOM or CSS test. A jsdom test cannot measure layout, and the ticket allows a manual check. The guard is structural, since the chips no longer overlay and are in flow.
Manual check: not run in a browser, only the build.
No new dependencies.
