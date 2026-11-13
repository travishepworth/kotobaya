# Web screens (UI-WEB, curated)

Every rebuilt screen from the `make screenshots-web` matrix, demo dataset (`fixtures/demo`), reduced motion.
Names: `<screen>[--<scenario>]-<width>-<theme>[-x2].png`.

- All screens: 360 and 1920 px, light and dark, text 1.0 (`default` scenario).
- Review (front and back): also at 200 % text (`-x2`).
- Empty account (`--empty`): Today, Review, Cards, Notes, Decks, Struggle, Stats, Reading.
- 900 due (`--due900`): Review, Stats.
- Load error (`--error`): Review, Cards, Decks, 360 and 1920 px. Not part of the matrix: captured once with the
  API answering 500 for the screen's list call (the toast is the API client's).

Images are palette-quantised to keep the repository small. The full matrix (688 images: 360/768/1280/1920 ×
light/dark × 1.0/2.0, every route and scenario) is written by `make screenshots-web` to `web/screenshots/`
(gitignored).
