# SCROLL-TOP — Review card scrolls to the absolute top when the answer is shown

- Phase: P10 · Model: sonnet (writer) · Risk: low · Deps: —
- Branch: t/SCROLL-TOP · Owned: web/src/** (review screen), android/** (review screen / review WebView), assets/card/** only if needed
- CI: web, android (Gradle flock)

## Do
User: "since the cards can be taller now, on show card, it needs to always be scrolled to the absolute top (so you can see the kanji always, scroll down for more info if necessary)."
On reveal (show answer) and on every new card (front), the card's scroll container (web page/card container; Android review WebView/Compose scroller) must be at scrollTop 0 — no preserved scroll offset, no auto-scroll to the answer divider/anchor (if the card template or renderer scrolls to an `#answer` anchor, remove that on both platforms). User scrolls down manually for more info. Applies to all card kinds (vocab, kanji, grammar, keyword).
Find the review screen yourself (web: review route; android: review screen + WebView). Keep it small.

## Acceptance
- [ ] Web and Android: after Show, scroll position is top (manual check; a small unit/Robolectric test only if trivial).
- [ ] make check web and android green.

## Out of scope
Card layout/CSS redesign; kanji rule changes (C-28f).
