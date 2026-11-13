# web/screenshots

Output of `make screenshots-web` (Playwright, `web/playwright/screenshots.spec.ts`). Everything here except this
README is gitignored. It is a design feedback loop, not a CI gate, and never runs in `make check`.

Layout: `<scenario>/<screen>/<width>-<theme>-<font scale>.png`

- scenario: `default` for every screen; `empty` (new account) and `due900` (900 cards due) for the screens they change
- screen: one folder per route, plus interaction states such as `review--back`, `cards--preview`, `dictionary--entry`
- width: 360, 768, 1280, 1920 (full-page captures, so height varies)
- theme: `light` / `dark` (`prefers-color-scheme`)
- font scale: `1.0` / `2.0` (root font size doubled, like a 200 % browser/OS text size)

The SPA is built and served by `vite preview` in mock-server mode against `fixtures/demo` with the clock fixed to
the dataset's "now" (2026-11-13T09:00Z, Asia/Tokyo), so runs are comparable. Knobs: `CHROMIUM_PATH`,
`SCREENSHOT_WORKERS`, `SCREENSHOT_PORT`, `SCREENSHOT_TIMEOUT`. Add a screen by adding an entry to `SHOTS`.
