
# DeckItem

An item of a deck (`app.deck_item`) with dictionary summary and status.

## Properties

Name | Type
------------ | -------------
`addedAt` | Date
`deckId` | string
`entry` | [DictEntrySummary](DictEntrySummary.md)
`entrySeq` | number
`itemKey` | string
`noteId` | string
`occurrenceCount` | number
`position` | number
`status` | [WordStatusValue](WordStatusValue.md)

## Example

```typescript
import type { DeckItem } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "addedAt": null,
  "deckId": null,
  "entry": null,
  "entrySeq": null,
  "itemKey": null,
  "noteId": null,
  "occurrenceCount": null,
  "position": null,
  "status": null,
} satisfies DeckItem

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as DeckItem
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


