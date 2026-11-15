
# KanjiExample

C-25. One example word of a kanji (`QueueItem.kanji_examples`). `seq`, `headword` and `reading` equal `DictEntrySummary.seq` / `headword` / `headword_reading` of the entry. Fields added later will be optional, so clients must ignore unknown members.

## Properties

Name | Type
------------ | -------------
`gloss` | string
`headword` | string
`reading` | string
`readingType` | string
`seq` | number

## Example

```typescript
import type { KanjiExample } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "gloss": null,
  "headword": null,
  "reading": null,
  "readingType": null,
  "seq": null,
} satisfies KanjiExample

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as KanjiExample
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


