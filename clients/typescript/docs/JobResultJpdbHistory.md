
# JobResultJpdbHistory

Result of a jpdb history import. Every input record is counted in exactly one bucket (C-2, C-4; mapping rules on `importJpdbHistory`): - rows of the vocab arrays: `vocabulary_total = vocabulary_carded + vocabulary_status_only   + vocabulary_en_jp_ignored + len(unmapped)`; - rows of the kanji arrays: `kanji_total = kanji_carded + len(kanji_unmapped)`; - review events of all four arrays: `reviews_total = reviews_imported + reviews_already_imported   + reviews_skipped + reviews_unknown_grade + status_events_unknown + status_events_known   + reviews_duplicate_dropped`. An event\'s bucket is the first that applies: (1) its row is a `cards_vocabulary_en_jp` row or is unmapped → `reviews_skipped`; (2) malformed → `reviews_unknown_grade`; (3) exact duplicate within its group → `reviews_duplicate_dropped`; (4) grade `unknown` → `status_events_unknown`; (5) grade `known` → `status_events_known` (whether or not it set a word status); (6) any other non-rating grade → `reviews_unknown_grade`; (7) rating with `import_reviews = false` → `reviews_skipped`; (8) rating whose review id already exists → `reviews_already_imported`; (9) else `reviews_imported`. `reviews_id_collisions` (C-9) is not a bucket: it counts the subset of bucket (8) whose id is held by a review of a different live card (see \"Review id collisions\" on `importJpdbHistory`), so `reviews_id_collisions <= reviews_already_imported` and the sums above are unchanged. Rows count rows, not distinct words: rows that repeat a vid are each counted in `vocabulary_total` and in their group\'s bucket (`unmapped` lists each row). `notes_*`, `cards_created`, `kanji_notes_*`, `kanji_cards_*` and `word_statuses_set` count what was written, not input records. With `import_reviews = true`: `notes_created + notes_existing` = distinct seqs of carded vocab groups; `cards_created <= notes_created + notes_existing`; `word_statuses_set <=` distinct seqs of status-only groups; `kanji_notes_created + kanji_notes_existing` = distinct characters of mapped kanji rows; `kanji_cards_suspended <= kanji_cards_created`. An export without kanji arrays reports the kanji counts as 0.

## Properties

Name | Type
------------ | -------------
`cardsCreated` | number
`kanjiCarded` | number
`kanjiCardsCreated` | number
`kanjiCardsSuspended` | number
`kanjiNotesCreated` | number
`kanjiNotesExisting` | number
`kanjiTotal` | number
`kanjiUnmapped` | [Array&lt;JobResultJpdbHistoryKanjiUnmappedInner&gt;](JobResultJpdbHistoryKanjiUnmappedInner.md)
`notesCreated` | number
`notesExisting` | number
`reviewsAlreadyImported` | number
`reviewsDuplicateDropped` | number
`reviewsIdCollisions` | number
`reviewsImported` | number
`reviewsSkipped` | number
`reviewsTotal` | number
`reviewsUnknownGrade` | number
`statusEventsKnown` | number
`statusEventsUnknown` | number
`unmapped` | [Array&lt;JobResultJpdbHistoryUnmappedInner&gt;](JobResultJpdbHistoryUnmappedInner.md)
`vocabularyCarded` | number
`vocabularyEnJpIgnored` | number
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
  "kanjiCardsSuspended": null,
  "kanjiNotesCreated": null,
  "kanjiNotesExisting": null,
  "kanjiTotal": null,
  "kanjiUnmapped": null,
  "notesCreated": null,
  "notesExisting": null,
  "reviewsAlreadyImported": null,
  "reviewsDuplicateDropped": null,
  "reviewsIdCollisions": null,
  "reviewsImported": null,
  "reviewsSkipped": null,
  "reviewsTotal": null,
  "reviewsUnknownGrade": null,
  "statusEventsKnown": null,
  "statusEventsUnknown": null,
  "unmapped": null,
  "vocabularyCarded": null,
  "vocabularyEnJpIgnored": null,
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


