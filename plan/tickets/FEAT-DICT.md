# FEAT-DICT — Jump to the dictionary from anywhere a word or kanji appears (web)

- Phase: P10/P11 · Model: sonnet · Risk: low
- Deps: BUG-12 (dictionaryHref helper), BUG-13
- Branch: t/FEAT-DICT-links
- Owned paths: web/src/**
- Module(s) for CI: web

## Do
A consistent "open in dictionary" affordance anywhere a word/kanji appears on web: review card after reveal, deck browsing (deck items/cards), notes browsing, struggle list rows, search results, kanji. Use lib/state/dictionaryLink.ts (`/dictionary?seq=`); add a kanji deep link if the dictionary page supports kanji (else a dictionary search for the literal). Strings via i18n. Must not interfere with review keyboard shortcuts/grading.
Android equivalent → follow-up ticket FEAT-DICT-APP.

## Acceptance
- [ ] Link present in each listed place where an entry seq (or kanji) is known; vitest for any new href helper.
- [ ] make check MODULE=web green.
