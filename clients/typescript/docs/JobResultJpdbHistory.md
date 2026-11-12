
# JobResultJpdbHistory

Result of a jpdb history import. Every input record is counted in exactly one bucket (C-2; mapping rules on `importJpdbHistory`): - rows of the vocab arrays: `vocabulary_total = vocabulary_carded + vocabulary_status_only + len(unmapped)`; - rows of the kanji arrays: `kanji_total = kanji_carded + len(kanji_unmapped)`; - review events of all four arrays: `reviews_total = reviews_imported + reviews_already_imported   + reviews_skipped + reviews_unknown_grade + status_events_unknown + status_events_known`. `notes_*`, `cards_created`, `kanji_notes_*`, `kanji_cards_created` and `word_statuses_set` count what was written, not input records. An export without kanji arrays reports the kanji counts as 0.

## Properties

Name | Type
------------ | -------------
`cardsCreated` | number
`kanjiCarded` | number
`kanjiCardsCreated` | number
`kanjiNotesCreated` | number
`kanjiNotesExisting` | number
`kanjiTotal` | number
`kanjiUnmapped` | [Array&lt;JobResultJpdbHistoryKanjiUnmappedInner&gt;](JobResultJpdbHistoryKanjiUnmappedInner.md)
`notesCreated` | number
`notesExisting` | number
`reviewsAlreadyImported` | number
`reviewsImported` | number
`reviewsSkipped` | number
`reviewsTotal` | number
`reviewsUnknownGrade` | number
`statusEventsKnown` | number
`statusEventsUnknown` | number
`unmapped` | [Array&lt;JobResultJpdbHistoryUnmappedInner&gt;](JobResultJpdbHistoryUnmappedInner.md)
`vocabularyCarded` | number
`vocabularyStatusOnly` | number
`vocabularyTotal` | number
`wordStatusesSet` | number

## Example

```typescript
import type { JobResultJpdbHistory } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "cardsCreated": null,
  "kanjiCarded": null,
  "kanjiCardsCreated": null,
  "kanjiNotesCreated": null,
  "kanjiNotesExisting": null,
  "kanjiTotal": null,
  "kanjiUnmapped": null,
  "notesCreated": null,
  "notesExisting": null,
  "reviewsAlreadyImported": null,
  "reviewsImported": null,
  "reviewsSkipped": null,
  "reviewsTotal": null,
  "reviewsUnknownGrade": null,
  "statusEventsKnown": null,
  "statusEventsUnknown": null,
  "unmapped": null,
  "vocabularyCarded": null,
  "vocabularyStatusOnly": null,
  "vocabularyTotal": null,
  "wordStatusesSet": null,
} satisfies JobResultJpdbHistory

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as JobResultJpdbHistory
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


