# UI-RUBY — Furigana (ruby rt) too small on web

- Phase: P10 (0.1.10) · Model: haiku (writer-light) · Risk: low
- Deps: none
- Branch: t/UI-RUBY-furigana-size
- Owned paths (only edit these): assets/card/**, design token source + generated outputs (tokens.json and its generated tokens.css/card.css/Tokens.kt, per docs/DESIGN.md / UI-DS) only if the rt size is a token, web/src/** CSS only if web overrides rt size, docs/design-screenshots/** (refresh affected curated shots if cheap)
- Module(s) for CI: web (and android if Tokens.kt changes)

## Read
- docs/DESIGN.md (type scale, ruby), assets/card/ CSS

## Do
User report on live 0.1.9: furigana (ruby `rt`) on web is too small. Make it larger: target rt ≈ 0.6em of the base (from whatever it is now, likely ≤ 0.5em) with a sensible absolute minimum (e.g. ≥ 11–12px) so it stays legible on small card text. Find the single source of truth (token or card.css rule) and change it there; do not hand-edit generated files. The shared card CSS also feeds the Android review WebView — applying there too is fine.
Check that the UI-WEB revealed-card compaction ("compact the revealed card only when it overflows", commit 2a10964) still behaves: larger rt raises line height; make sure the overflow detection measures after the change and nothing clips (ruby line-height / padding-top for rt).

## Acceptance
- [ ] rt visibly larger on review card, note preview and any word/search view that shows furigana.
- [ ] make check MODULE=web green (and android if Tokens.kt changed); token drift/WCAG tests still pass.
- [ ] No rt clipped at 360px width or 200% zoom on the revealed card (state how you checked; a screenshot run of the affected review shots is enough).

## Out of scope
Any other typography change.
