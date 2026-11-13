# HANDOFF C-15

STATUS: DONE
BRANCH: t/C-15-grammar-card-sentence
CHECK: `make check MODULE=contracts` → green (no drift)

## Changed (≤ 6 lines)
- openapi: optional `QueueItem.grammar_sentence` (`GrammarCardSentence | null`), used by queue and drill items. It holds the sentence, the chosen span, translation (null), the picker inputs and `ExampleSentenceSource`. It maps 1:1 to `ClozeSentence`/`JsCloze`.
- Rules: the server calls core `GrammarCardSentences.pick`, with rotation = `card.reps` and lastShown = latest live non-manual review's `sentence_id` by hlc. Refetch and undo give the same sentence.
- Android picks locally over its replica. With no candidate it renders with `cloze = null` plus a notice.
- Example grammar item in ReviewQueue; SUMMARY.md C-15 section; clients regenerated.

## Acceptance → tests
- Additive only → diff changes only descriptions; check green

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Summary lives in contracts/api/SUMMARY.md (contracts/SUMMARY.md does not exist).
- Grammar notes have no sentence field, so there is no fixed-sentence fallback.
- Choosing rotation = reps means drills rotate only through lastShown.
