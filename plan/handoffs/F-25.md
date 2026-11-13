# HANDOFF F-25

STATUS: DONE
BRANCH: t/F-25-grammar-sentence-review
CHECK: `make check MODULE=core|server|web` → green

## Changed
- core GrammarCardSentences.pick: candidates ordered (sentence_id,start,end); if lastShown is in the best tier, step to the next sentence after it (wraps); else index tier by rotation. Full coverage for reviews and drills. Test added.
- server: GrammarCardSentenceSource.itemFor + ReviewViews.items fill `sentence` and `grammar_sentence` (queue and drill; not production); lastShown = latest live non-manual review by hlc, incl. merged cards.
- web review page: passes grammarSentence as cloze, echoes sentenceId on answer, i18n notice when null.

## Acceptance → tests
- refetch/undo same sentence → GrammarQueueSentenceTest; rotation → GrammarCardSentencesTest

## New dependencies
- none

## For the reviewer
- Deviation: when lastShown is in the tier I step "next after it" (not rotation index) so drills (reps constant) also cycle all sentences.
