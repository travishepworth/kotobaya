# CARD-KIND-BADGE — Show the card type centred at the top of the review card (web, Android, CLI)

- Phase: P10 · Model: sonnet (writer) · Risk: low
- Split: CARD-KIND-BADGE-WEB (web/ + assets/card/ if the badge lives in shared card CSS), CARD-KIND-BADGE-CLI (cli/),
  CARD-KIND-BADGE-AND (android/; after AND-FLAKY-BACKNAV + REVIEW-UNLIMITED merge)

## User request (2026-11-16)
"Just like the new-card tag on the top left, but centered in the top middle, show the type
(i.e. kanji / vocab / grammar point, etc). For web, Android and CLI."

## Do
- Find how the existing top-left "new" tag is rendered on each client and add a matching, centred
  top badge with the card's type: Kanji, Vocab, Grammar, and any other kinds the data model has
  (use the existing kind/note-type field on the queue item; if the queue item doesn't carry it,
  STOP and return CONTRACT_CHANGE_REQUEST naming the field needed — don't infer it from HTML).
- Same wording on all clients, via i18n (web/Android). Same visual style as the "new" tag
  (shared card CSS in assets/card/ if that's where the tag lives — then web and Android WebView share it).
- CLI: include the type in the daemon state JSON (additive schema field, e.g. `card.kind_label`) and
  show it in the kby/terminal review output and the `-f` widget tooltip/text where the "new" marker shows.
- Must not shift card content or overlap the top-left tag on narrow screens.

## Acceptance
- [ ] make check for the module green. UI needs no tests; CLI state field has a test.
