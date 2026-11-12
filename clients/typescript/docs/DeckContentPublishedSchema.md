
# DeckContentPublishedSchema

Announces a new server-built deck_item snapshot of a media or list deck. OPS.md#deckcontent_published

## Properties

Name | Type
------------ | -------------
`contentBlobHash` | string
`contentVersion` | number
`deckId` | string

## Example

```typescript
import type { DeckContentPublishedSchema } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "contentBlobHash": null,
  "contentVersion": null,
  "deckId": null,
} satisfies DeckContentPublishedSchema

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as DeckContentPublishedSchema
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


