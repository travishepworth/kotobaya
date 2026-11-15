# AND-REVIEW-INFO — Android review back: kanji example words + vocab deck chips + "Open in dictionary" button

- Phase: P9 · Model: sonnet · Risk: med
- Deps: C-25 (core picker), AND-BACK (both edit review/; rebase on it)
- Branch: t/AND-REVIEW-INFO
- Owned paths (only edit these): android/src/**/review/, android/src/**/ui/components (if needed), android i18n, android/src/test/
- Module(s) for CI: android

## Do
Offline: use the core KanjiExamplePicker over the on-device dictionary (words containing the kanji, freq ranks, user's notes/word status/deck_item) and local deck_item + deck priority for vocab deck chips (top 3 by priority, +N more). Show them on the back side like web (WEB-REVIEW-INFO). Add a visible "Open in dictionary" button on the back side (it exists in the overflow menu today; make it a first-class button) that opens the word/kanji detail and returns to the same review state on back. i18n. Must not slow the tap path (compute with the prefetch, off main thread).
## Acceptance
- [ ] Test: picker inputs built from a fixture DB give the expected 3 words incl. ON+KUN; deck chips in priority order.
- [ ] Test: open-in-dictionary → back returns to the same card, still revealed, no review change.
- [ ] make check MODULE=android green.
