# /styleguide screenshots (UI-DS)

Curated captures of the `/styleguide` route for the design review. They come from the production build in
mock mode (`make dev-ui` serves the same page at http://localhost:3000/styleguide).

- `1600-<theme>-<section>.png`: desktop, one section each (colour, type, space, components, review, shell,
  header). The components section shows light and dark side by side, so it is captured once.
- `360-<theme>-<scale>-<section>.png`: phone width at text scale 1.0 and 2.0 (review mock, components).

The full matrix (360/768/1280/1920 × light/dark × 1.0/2.0, every route) comes from `make screenshots-web` and
goes to `web/screenshots/` (gitignored).
