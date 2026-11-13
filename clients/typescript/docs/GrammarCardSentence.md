
# GrammarCardSentence

C-15. The example sentence for one showing of a grammar card (SPEC \"Grammar review\": the sentence rotates on each review; i+0 sentences first). It is exactly the `GrammarCandidate` returned by the core\'s `GrammarCardSentences.pick(candidates, lastShown, rotation)`, plus its inputs and source. Servers MUST call that core function; they never re-implement the choice.  **Candidates** (one per distinct (`sentence_id`, `start_offset`, `end_offset`)): every live span (`deleted_at` null; origin `detected` or `manual`) of the note\'s `grammar_point_id` in an `app.sentence` row with `origin = media` whose media is live. Generated sentences are never candidates (SPEC: kept separate); Tatoeba sentences have no spans and are not candidates. A span whose grammar point is deleted, whose sentence is missing, or that is not a valid UTF-16 range of the text (`GrammarCardSentences.isValidSpan`: empty, out of range, or cutting a surrogate pair) is dropped. `unknownCount` = the sentence\'s `Sentence.unknown_count` (the `filter` parameter\'s definition, now).  **Inputs**: - `rotation` = `card.reps` of the card as returned in the same item (after replay). - `lastShown` = the `sentence_id` of the card\'s latest review (highest `hlc`) among   `review_log` rows with `undone_at` null and `review_type` not `manual`, counting rows   logged on cards merged into it; null if there is none or its `sentence_id` is null   (earlier rows are not consulted).  **Determinism**: the result is a pure function of the replica\'s state. Fetching the queue again without answering returns the same sentence; answering moves both inputs (the answered sentence becomes `lastShown`; a schedule answer also increments `reps`), so the next showing differs whenever two or more candidate sentences exist; undoing that answer restores both inputs and therefore the earlier sentence. The pick can change between fetches only when the candidates change (new detection, a span removed, a word becoming known); clients must not swap the sentence of an item already shown. Drill and cram answers log `sentence_id` but leave `reps` unchanged; `lastShown` still advances, so drills rotate too.  **Offline / Android**: a device building its queue locally runs the same pick over its replica: candidates from installed media snapshots (sentences, detected spans) plus manual spans from `grammar.span_put`, `unknownCount` from local word statuses, and `card.reps` / `review_log` from local state. Media whose snapshot is not installed contribute nothing; with no candidate the device renders with `cloze = null`, as for a null `QueueItem.grammar_sentence`. A device and the server may pick differently when their replicas differ; this only affects display, and the review logs whatever was shown. 

## Properties

Name | Type
------------ | -------------
`endOffset` | number
`lastShownSentenceId` | string
`rotation` | number
`sentenceId` | string
`source` | [ExampleSentenceSource](ExampleSentenceSource.md)
`startOffset` | number
`text` | string
`translation` | string
`unknownCount` | number

## Example

```typescript
import type { GrammarCardSentence } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "endOffset": null,
  "lastShownSentenceId": null,
  "rotation": null,
  "sentenceId": null,
  "source": null,
  "startOffset": null,
  "text": null,
  "translation": null,
  "unknownCount": null,
} satisfies GrammarCardSentence

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as GrammarCardSentence
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


