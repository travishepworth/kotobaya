
# DeckItemPutSchema

Adds an item to a user deck or moves it. Whole-row last-writer-wins on hlc together with deck.item_removed. OPS.md#deckitem_put

## Properties

Name | Type
------------ | -------------
`deckId` | string
`itemKey` | string
`position` | number

## Example

```typescript
import type { DeckItemPutSchema } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "deckId": null,
  "itemKey": null,
  "position": null,
} satisfies DeckItemPutSchema

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as DeckItemPutSchema
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


