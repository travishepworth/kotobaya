
# DeckItemRemovedSchema

Removes an item from a user deck (reversible tombstone). OPS.md#deckitem_removed

## Properties

Name | Type
------------ | -------------
`deckId` | string
`itemKey` | string

## Example

```typescript
import type { DeckItemRemovedSchema } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "deckId": null,
  "itemKey": null,
} satisfies DeckItemRemovedSchema

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as DeckItemRemovedSchema
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


