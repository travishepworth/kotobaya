# KIND-CHIP-OVERLAP — Card text overlaps the top-centre type chip (Android; check web)

- Model: sonnet (writer) · Risk: low · Owned: android/ (AND), web/ (WEB) — separate branches
## User report (2026-11-16, angry): on Android the card text must move DOWN by the height of the new
Kanji/Vocab chip — the chip currently overlaps the card's text. This shipped in APK 0.1.27 unnoticed.
## Do
- Android: reserve vertical space for the chip row (chip height + its spacing) above the card content
  whenever a top chip/tag row is shown (type chip, New tag, review-type tag), so nothing overlaps on any
  screen width, font scale (1.0 and 1.3) or card type (vocab front/back, kanji, long text). Prefer laying
  the chip row out in normal flow (Column/Row) over absolute overlay + magic padding.
- Web: check the same (badge is position:absolute top-centre in .card-body) and fix the same way if card
  text can sit under it (narrow widths too).
- Add a guard: Android Compose/Robolectric test asserting the chip's bounds don't intersect the card
  content's bounds; web: a vitest/DOM or CSS-level check if feasible, else describe the manual check done.
## Acceptance
- [ ] No overlap; test guards it. make check green.
