
# KanjiWordDeck

One of your live decks holding the entry as a live item (C-17).

## Properties

Name | Type
------------ | -------------
`deckId` | string
`name` | string
`occurrenceCount` | number
`type` | [DeckType](DeckType.md)

## Example

```typescript
import type { KanjiWordDeck } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "deckId": null,
  "name": null,
  "occurrenceCount": null,
  "type": null,
} satisfies KanjiWordDeck

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as KanjiWordDeck
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


