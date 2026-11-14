# FEAT-DICT-APP — Android: jump to the dictionary from anywhere a word/kanji appears

- Phase: P10 · Model: sonnet · Risk: low
- Deps: FEAT-DICT (web) for parity of places; AND-INSETS (serialize android UI writers)
- Branch: t/FEAT-DICT-APP
- Owned paths: android/src/**/ui/**, android/src/**/nav/** (deep link to dictionary/word detail)
- Module(s) for CI: android

## Do
Android equivalent of FEAT-DICT: from the review card (after reveal), deck items, notes/browse, struggle rows, search results, and kanji, open the in-app word/kanji detail (offline dict) by seq or kanji literal. Must not interfere with review gestures/grading. Strings via i18n.

## Acceptance
- [ ] Affordance present in each listed place where seq/kanji is known (Roborazzi shot or stated manual check).
- [ ] make check MODULE=android green.
