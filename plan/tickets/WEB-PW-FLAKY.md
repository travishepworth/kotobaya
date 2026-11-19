# WEB-PW-FLAKY — Playwright mock API state shared across workers makes review specs flaky

- Model: haiku (writer-light) · Risk: low · Deps: WEB-29 merged
- Branch: t/WEB-PW-FLAKY · Owned: web/playwright/**, web/mock/**
- CI: web
## Do
The Playwright mock API keeps per-scenario state in memory shared by all workers; parallel answer/undo tests interfere (review-back.spec.ts failed 3/4 parallel runs on clean main 2026-11-19, passes with 1 worker). Isolate state per worker/test (e.g. scenario key per test or per-worker mock server port), or mark stateful specs serial. Also add `word_audio` to web/mock so word-audio.spec.ts needn't inject it via page.route.
## Acceptance
- [ ] review-back + word-audio specs pass 5/5 consecutive runs at default workers; make check web green.
