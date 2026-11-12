
# DeckListCatalogueEntry

A generated list that can be enabled.

## Properties

Name | Type
------------ | -------------
`deckId` | string
`itemCount` | number
`listKey` | string
`title` | string

## Example

```typescript
import type { DeckListCatalogueEntry } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "deckId": null,
  "itemCount": null,
  "listKey": null,
  "title": null,
} satisfies DeckListCatalogueEntry

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as DeckListCatalogueEntry
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


