# HANDOFF F-25b

STATUS: DONE
BRANCH: t/F-25b-grammar-sentence-android
CHECK: `make check MODULE=android` → green

## Changed
- New `grammar/GrammarSentenceSource` (android): builds candidates from the local replica (live spans of the point, `origin=media` sentences of live media, unknown_count from local word_status), lastShown = latest live non-manual review by hlc (incl. merged cards), calls core `GrammarCardSentences.pick` with rotation = card reps.
- `ReviewSession.load`: grammar notes, non-production kinds, get the cloze; `ShownCard.sentenceId` is sent as `sentence_id` in `review.logged`.
- `ReviewScreen`: null pick shows i18n notice and the point's pattern (`strings_grammar_review.xml`).

## Acceptance → tests
- same pick as server for a fixture → GrammarSentenceSourceTest

## New dependencies
- none

## For the reviewer
- A device without installed media snapshots has no candidates and shows the notice (no sync changes).
- Gradle daemon stopped.
