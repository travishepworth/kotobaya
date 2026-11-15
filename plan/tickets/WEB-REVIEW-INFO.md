# WEB-REVIEW-INFO — Web review back: kanji example words + vocab deck chips

- Phase: P9 · Model: sonnet · Risk: low
- Deps: C-25
- Branch: t/WEB-REVIEW-INFO
- Owned paths (only edit these): web/src/routes/review, web/src/lib/state/review.svelte.ts (display only, don't touch the Outbox/answer logic), shared CardView/review components, web i18n, web tests
- Module(s) for CI: web

## Do
On the review back side (after reveal): for kanji cards, under the readings, the 3 `kanji_examples` (headword, reading, short gloss, small ON/KUN tag), each linking to the dictionary entry. For vocab cards, up to 3 `decks` chips in priority order (+N more if deck_count > 3), each linking to the deck. Keep it compact; DESIGN.md tokens. i18n.
## Acceptance
- [ ] Component test renders both blocks from a QueueItem fixture. make check MODULE=web green.
